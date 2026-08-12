from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import subprocess
import time
from dataclasses import asdict, replace
from fractions import Fraction
from pathlib import Path
from typing import Any

import pyarrow

from golden_capture_g0.media import (
    generate_capture_media,
    probe_capture_media,
    sha256_file,
    verify_full_decode,
)
from golden_capture_g0.model import (
    EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL,
    REQUIRE_TRUSTED_STREAM_TO_CAPTURE_MAPPING,
    ROUND_HALF_EVEN,
    CaptureSpec,
    ProbedStream,
    SyncProfile,
    load_capture_specs,
)
from golden_capture_g0.parquet_artifact import (
    WriterOptions,
    bounded_read,
    canonical_schema,
    inspect_artifact,
    write_artifact,
)
from golden_capture_g0.rational import round_half_even
from golden_capture_g0.sync import (
    SynchronizationUnavailableError,
    build_frame_slots,
    input_manifest_sha256,
    logical_content_sha256,
    profile_sha256,
    summarize_slots,
)

MEASUREMENT_PROFILE = SyncProfile(
    Fraction(30, 1),
    20_000_000,
    REQUIRE_TRUSTED_STREAM_TO_CAPTURE_MAPPING,
    ROUND_HALF_EVEN,
    EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL,
)
CANDIDATE_RATES = (Fraction(10, 1), Fraction(25, 1), Fraction(30, 1))
CANDIDATE_TOLERANCES_NS = (10_000_000, 15_000_000, 20_000_000)


def run_experiment(
    fixture_path: Path,
    work_dir: Path,
    *,
    worker_trials: int = 3,
) -> dict[str, Any]:
    captures = load_capture_specs(fixture_path)
    media_dir = work_dir / "media"
    artifact_dir = work_dir / "artifacts"
    paths_by_capture: dict[str, dict[int, Path]] = {}
    streams_by_capture: dict[str, dict[int, ProbedStream]] = {}
    initial_hashes: dict[str, str] = {}
    for capture in captures:
        paths = generate_capture_media(capture, media_dir)
        paths_by_capture[capture.name] = paths
        initial_hashes.update({str(path): sha256_file(path) for path in paths.values()})
        streams_by_capture[capture.name] = probe_capture_media(capture, paths)

    candidate_measurements = _candidate_measurements(captures, streams_by_capture)
    canonical_measurements = _canonical_measurements(captures, streams_by_capture, artifact_dir)
    layout_measurements = _layout_measurements(
        _capture(captures, "case_b_missing_routes"),
        streams_by_capture["case_b_missing_routes"],
        artifact_dir / "layout-candidates",
    )
    capacity = _capacity_measurement(
        captures,
        paths_by_capture,
        work_dir / "capacity-trials",
        worker_trials,
    )
    final_hashes = {path: sha256_file(Path(path)) for path in initial_hashes}
    immutable = initial_hashes == final_hashes
    return {
        "evidence_version": "v1.19-g0-evidence.v2",
        "scope": "SYNTHETIC_ONLY_NON_PRODUCTION",
        "environment": _environment(),
        "fixture_path": fixture_path.as_posix(),
        "measurement_profile": _profile_payload(MEASUREMENT_PROFILE),
        "media_evidence": _media_evidence(captures, streams_by_capture),
        "candidate_measurements": candidate_measurements,
        "canonical_parquet": canonical_measurements,
        "physical_layout_candidates": layout_measurements,
        "rounding_evidence": _rounding_evidence(),
        "time_mapping_evidence": _time_mapping_evidence(captures, streams_by_capture),
        "reference_candidates": _reference_candidates(captures),
        "worker_capacity_measurement": capacity,
        "source_media_immutable": immutable,
        "source_media_sha256_before": initial_hashes,
        "source_media_sha256_after": final_hashes,
        "freeze_assessment": {
            "timeline_rule": "YES_PARAMETERIZED_EXACT_RATIONAL",
            "reference_rule": "YES_REQUIRE_TRUSTED_STREAM_TO_CAPTURE_MAPPING",
            "tolerance": "YES_PARAMETERIZED_INCLUSIVE_CAPTURE_TIME_ERROR",
            "rounding": "YES",
            "parquet_schema": "YES",
            "parquet_physical_layout": "NO",
            "worker_capacity": "NO",
        },
    }


def _candidate_measurements(
    captures: tuple[CaptureSpec, ...],
    streams_by_capture: dict[str, dict[int, ProbedStream]],
) -> list[dict[str, Any]]:
    measurements: list[dict[str, Any]] = []
    for rate in CANDIDATE_RATES:
        for tolerance_ns in CANDIDATE_TOLERANCES_NS:
            profile = SyncProfile(
                rate,
                tolerance_ns,
                REQUIRE_TRUSTED_STREAM_TO_CAPTURE_MAPPING,
                ROUND_HALF_EVEN,
                EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL,
            )
            for capture in captures:
                slots = build_frame_slots(capture, streams_by_capture[capture.name], profile)
                summary = summarize_slots(slots)
                measurements.append(
                    {
                        "case": capture.name,
                        "timeline_rate": _fraction_payload(rate),
                        "tolerance_ns": tolerance_ns,
                        "frame_slot_density_rows_per_capture_second": float(
                            rate * len(capture.expected_camera_numbers)
                        ),
                        **summary,
                    }
                )
    return measurements


def _canonical_measurements(
    captures: tuple[CaptureSpec, ...],
    streams_by_capture: dict[str, dict[int, ProbedStream]],
    artifact_dir: Path,
) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    for capture in captures:
        streams = streams_by_capture[capture.name]
        slots = build_frame_slots(capture, streams, MEASUREMENT_PROFILE)
        reversed_slots = build_frame_slots(
            capture, dict(reversed(list(streams.items()))), MEASUREMENT_PROFILE
        )
        manifest_digest = input_manifest_sha256(capture, streams)
        reversed_manifest_digest = input_manifest_sha256(
            capture, dict(reversed(list(streams.items())))
        )
        profile_digest = profile_sha256(MEASUREMENT_PROFILE)
        options = WriterOptions("zstd", 15, 3)
        first = write_artifact(
            slots,
            artifact_dir / f"{capture.name}.parquet",
            input_manifest_sha256=manifest_digest,
            sync_profile_sha256=profile_digest,
            options=options,
        )
        second = write_artifact(
            reversed_slots,
            artifact_dir / f"{capture.name}.repeat.parquet",
            input_manifest_sha256=reversed_manifest_digest,
            sync_profile_sha256=profile_digest,
            options=options,
        )
        max_frame = max(slot.sync_frame_index for slot in slots)
        query_from = min(12, max_frame)
        query_to = min(query_from + 5, max_frame + 1)
        bounded = bounded_read(first.path, query_from, query_to)
        cases.append(
            {
                "case": capture.name,
                "input_manifest_sha256": manifest_digest,
                "sync_profile_sha256": profile_digest,
                "artifact_sha256": first.artifact_sha256,
                "logical_content_sha256": first.logical_content_sha256,
                "size_bytes": first.size_bytes,
                "row_group_count": first.row_group_count,
                "summary": summarize_slots(slots),
                "order_independent_input_manifest": manifest_digest == reversed_manifest_digest,
                "order_independent_logical_content": logical_content_sha256(slots)
                == logical_content_sha256(reversed_slots),
                "repeat_artifact_sha256_equal": first.artifact_sha256 == second.artifact_sha256,
                "bounded_read": asdict(bounded),
            }
        )
    sample_path = artifact_dir / f"{captures[0].name}.parquet"
    return {
        "logical_grain": [
            "capture_id",
            "sync_revision_id",
            "sync_frame_index",
            "camera_number",
        ],
        "schema": inspect_artifact(sample_path)["arrow_schema"],
        "cases": cases,
    }


def _layout_measurements(
    capture: CaptureSpec,
    streams: dict[int, ProbedStream],
    output_dir: Path,
) -> list[dict[str, Any]]:
    slots = build_frame_slots(capture, streams, MEASUREMENT_PROFILE)
    manifest_digest = input_manifest_sha256(capture, streams)
    sync_profile_digest = profile_sha256(MEASUREMENT_PROFILE)
    results: list[dict[str, Any]] = []
    for compression, level in ((None, None), ("snappy", None), ("zstd", 3)):
        for row_group_frames in (1, 5, 15, 30):
            label = compression or "none"
            options = WriterOptions(compression, row_group_frames, level)
            artifact = write_artifact(
                slots,
                output_dir / f"{label}-rg{row_group_frames}.parquet",
                input_manifest_sha256=manifest_digest,
                sync_profile_sha256=sync_profile_digest,
                options=options,
            )
            query = bounded_read(artifact.path, 12, 17)
            results.append(
                {
                    "compression": label,
                    "compression_level": level,
                    "row_group_sync_frames": row_group_frames,
                    "size_bytes": artifact.size_bytes,
                    "artifact_sha256": artifact.artifact_sha256,
                    "row_group_count": artifact.row_group_count,
                    "bounded_window_sync_frames": 5,
                    "bounded_rows_read": query.rows_read,
                    "bounded_rows_returned": query.rows_returned,
                    "bounded_read_amplification": query.read_amplification,
                }
            )
    return results


def _capacity_measurement(
    captures: tuple[CaptureSpec, ...],
    paths_by_capture: dict[str, dict[int, Path]],
    output_dir: Path,
    trials: int,
) -> dict[str, Any]:
    if trials < 1:
        raise ValueError("worker_trials must be positive")
    durations: list[float] = []
    total_capture_seconds = float(
        sum((capture.duration_seconds for capture in captures), Fraction(0))
    )
    total_source_video_seconds = float(
        sum(
            (capture.duration_seconds * len(capture.streams) for capture in captures),
            Fraction(0),
        )
    )
    for trial in range(trials):
        started = time.perf_counter()
        for capture in captures:
            paths = paths_by_capture[capture.name]
            streams = probe_capture_media(capture, paths)
            for path in paths.values():
                verify_full_decode(path)
            slots = build_frame_slots(capture, streams, MEASUREMENT_PROFILE)
            write_artifact(
                slots,
                output_dir / f"trial-{trial + 1}" / f"{capture.name}.parquet",
                input_manifest_sha256=input_manifest_sha256(capture, streams),
                sync_profile_sha256=profile_sha256(MEASUREMENT_PROFILE),
                options=WriterOptions("zstd", 15, 3),
            )
        durations.append(time.perf_counter() - started)
    return {
        "trial_count": trials,
        "wall_seconds": durations,
        "wall_seconds_p50": statistics.median(durations),
        "wall_seconds_p95_nearest_rank": _nearest_rank(durations, 0.95),
        "total_capture_seconds_per_trial": total_capture_seconds,
        "total_source_video_seconds_per_trial": total_source_video_seconds,
        "realtime_factor_per_capture_duration": [
            duration / total_capture_seconds for duration in durations
        ],
        "realtime_factor_per_source_video_duration": [
            duration / total_source_video_seconds for duration in durations
        ],
        "includes": ["ffprobe_per_frame_pts", "full_decode", "nearest_mapping", "parquet_write"],
        "excludes": [
            "network",
            "object_storage",
            "core_api",
            "rabbitmq",
            "lease",
            "result_acceptance",
        ],
        "capacity_freeze": "PENDING_TARGET_SCALE_AND_TARGET_RUNTIME",
    }


def _media_evidence(
    captures: tuple[CaptureSpec, ...],
    streams_by_capture: dict[str, dict[int, ProbedStream]],
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for capture in captures:
        streams: list[dict[str, Any]] = []
        for camera_number, stream in sorted(streams_by_capture[capture.name].items()):
            source_pts_deltas = [
                current.source_pts_ns - previous.source_pts_ns
                for previous, current in zip(stream.frames, stream.frames[1:], strict=False)
            ]
            raw_pts_deltas = [
                current.raw_pts - previous.raw_pts
                for previous, current in zip(stream.frames, stream.frames[1:], strict=False)
            ]
            streams.append(
                {
                    "camera_number": camera_number,
                    "explicit_video_id": stream.spec.video_id,
                    "file_name": stream.spec.file_name,
                    "sha256": stream.sha256,
                    "size_bytes": stream.size_bytes,
                    "time_base": _fraction_payload(stream.time_base),
                    "raw_pts_origin": stream.pts_origin,
                    "raw_first_pts": stream.frames[0].raw_pts,
                    "raw_last_pts": stream.frames[-1].raw_pts,
                    "frame_count": len(stream.frames),
                    "unique_raw_pts_delta": sorted(set(raw_pts_deltas)),
                    "unique_source_pts_delta_ns": sorted(set(source_pts_deltas)),
                    "vfr_observed": len(set(raw_pts_deltas)) > 1,
                    "nonzero_raw_pts_origin": stream.pts_origin != 0,
                    "synthetic_invalid_frame_indices": sorted(
                        stream.spec.invalid_source_frame_indices
                    ),
                    "time_mapping": _time_mapping_payload(stream),
                }
            )
        result.append(
            {
                "case": capture.name,
                "expected_camera_numbers": list(capture.expected_camera_numbers),
                "actual_camera_numbers": sorted(streams_by_capture[capture.name]),
                "streams": streams,
            }
        )
    return result


def _rounding_evidence() -> dict[str, Any]:
    rate = Fraction(30, 1)
    sample_count = 60 * 60 * 30
    exact_last = Fraction(sample_count - 1, 1) / rate * 1_000_000_000
    absolute_rounded = round_half_even(exact_last)
    floor_interval = 1_000_000_000 // 30
    ceil_interval = floor_interval + 1
    absolute_errors = [
        Fraction(round_half_even(Fraction(index, 1) / rate * 1_000_000_000), 1)
        - Fraction(index, 1) / rate * 1_000_000_000
        for index in range(300)
    ]
    positive_half_ties = [Fraction(value, 2) for value in range(1, 20, 2)]
    return {
        "candidate": "ROUND_HALF_EVEN_PER_ABSOLUTE_RATIONAL_TIMESTAMP",
        "one_hour_30hz_last_index": sample_count - 1,
        "one_hour_exact_last_timestamp_ns": _fraction_payload(exact_last),
        "one_hour_half_even_last_timestamp_ns": absolute_rounded,
        "repeated_floor_interval_last_timestamp_error_ns": float(
            Fraction((sample_count - 1) * floor_interval, 1) - exact_last
        ),
        "repeated_ceil_interval_last_timestamp_error_ns": float(
            Fraction((sample_count - 1) * ceil_interval, 1) - exact_last
        ),
        "absolute_half_even_max_error_ns_first_300_frames": float(
            max(abs(error) for error in absolute_errors)
        ),
        "positive_half_tie_error_sum_half_even_ns": float(
            sum(
                (Fraction(round_half_even(value), 1) - value for value in positive_half_ties),
                Fraction(0),
            )
        ),
        "rule": (
            "round origin + exact rational index/rate independently; "
            "never add a rounded interval repeatedly"
        ),
    }


def _time_mapping_evidence(
    captures: tuple[CaptureSpec, ...],
    streams_by_capture: dict[str, dict[int, ProbedStream]],
) -> dict[str, Any]:
    offset_capture = _capture(captures, "case_f_cross_camera_offset")
    offset_slots = build_frame_slots(
        offset_capture,
        streams_by_capture[offset_capture.name],
        MEASUREMENT_PROFILE,
    )
    drift_capture = _capture(captures, "case_g_affine_drift")
    drift_slots = build_frame_slots(
        drift_capture,
        streams_by_capture[drift_capture.name],
        MEASUREMENT_PROFILE,
    )
    camera_two_at_zero = next(
        slot for slot in offset_slots if slot.camera_number == 2 and slot.capture_timestamp_ns == 0
    )
    camera_two_at_one_second = next(
        slot
        for slot in drift_slots
        if slot.camera_number == 2 and slot.capture_timestamp_ns == 1_000_000_000
    )
    untrusted_streams = dict(streams_by_capture[offset_capture.name])
    untrusted = untrusted_streams[2]
    untrusted_streams[2] = replace(untrusted, spec=replace(untrusted.spec, time_mapping=None))
    rejection_code = None
    try:
        build_frame_slots(offset_capture, untrusted_streams, MEASUREMENT_PROFILE)
    except SynchronizationUnavailableError as error:
        rejection_code = error.code
    return {
        "algorithm": [
            "inverse_map_capture_target_to_source_time",
            "nearest_source_pts_with_deterministic_tie_break",
            "forward_map_selected_source_frame_to_capture_time",
            "sync_error_ns_equals_mapped_capture_time_minus_target_capture_time",
        ],
        "offset_case": {
            "camera_2_anchor_capture_ns": 80_000_000,
            "camera_2_capture_zero_state": camera_two_at_zero.frame_state,
            "camera_2_capture_zero_reason": camera_two_at_zero.frame_reason_code,
            "summary": summarize_slots(offset_slots),
        },
        "drift_case": {
            "camera_2_drift_ppm": 1_000,
            "camera_2_anchor_count": 2,
            "target_capture_timestamp_ns": camera_two_at_one_second.capture_timestamp_ns,
            "selected_source_pts_ns": camera_two_at_one_second.source_pts_ns,
            "sync_error_ns": camera_two_at_one_second.sync_error_ns,
            "summary": summarize_slots(drift_slots),
        },
        "untrusted_time_basis_rejection_code": rejection_code,
    }


def _reference_candidates(captures: tuple[CaptureSpec, ...]) -> list[dict[str, Any]]:
    return [
        {
            "candidate": "TRUSTED_STREAM_TO_CAPTURE_MAPPING",
            "available_cases": len(captures),
            "total_cases": len(captures),
            "contract": (
                "every present stream requires an explicit trusted common-clock or anchor mapping"
            ),
            "limitation": (
                "synthetic truth validates mapping semantics, not the production anchor source"
            ),
        },
        {
            "candidate": "LOWEST_EXPECTED_CAMERA_NUMBER_STREAM",
            "available_cases": sum(
                min(capture.expected_camera_numbers)
                in {stream.camera_number for stream in capture.streams}
                for capture in captures
            ),
            "total_cases": len(captures),
            "limitation": (
                "all G0 fixtures happen to contain Camera 1; "
                "reference selection remains a production profile parameter"
            ),
        },
        {
            "candidate": "UPLOAD_ORDER_OR_FILE_NAME",
            "available_cases": 0,
            "total_cases": len(captures),
            "limitation": (
                "prohibited identity inference; deliberately scrambled fixture names and order"
            ),
        },
    ]


def _time_mapping_payload(stream: ProbedStream) -> dict[str, Any] | None:
    mapping = stream.spec.time_mapping
    if mapping is None:
        return None
    return {
        "anchor_source_ns": mapping.anchor_source_ns,
        "anchor_capture_ns": mapping.anchor_capture_ns,
        "drift_ppm": (None if mapping.drift_ppm is None else _fraction_payload(mapping.drift_ppm)),
        "method": mapping.method,
        "source": mapping.source,
        "anchor_count": mapping.anchor_count,
        "trusted": mapping.trusted,
    }


def _environment() -> dict[str, Any]:
    return {
        "os": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "logical_cpu_count": os.cpu_count(),
        "python": platform.python_version(),
        "pyarrow": pyarrow.__version__,
        "ffmpeg": _tool_version("ffmpeg"),
        "ffprobe": _tool_version("ffprobe"),
        "parquet_schema": [
            {"name": field.name, "type": str(field.type), "nullable": field.nullable}
            for field in canonical_schema()
        ],
    }


def _tool_version(command: str) -> str:
    completed = subprocess.run(
        [command, "-version"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0 or not completed.stdout:
        return "UNAVAILABLE"
    return completed.stdout.splitlines()[0]


def _profile_payload(profile: SyncProfile) -> dict[str, Any]:
    return {
        "approval_status": "G0_MEASUREMENT_ONLY_NOT_APPROVED",
        "timeline_rate": _fraction_payload(profile.timeline_rate),
        "allowed_tolerance_ns": profile.allowed_tolerance_ns,
        "reference_rule": profile.reference_rule,
        "rounding_rule": profile.rounding_rule,
        "tie_breaker": profile.tie_breaker,
        "tolerance_boundary": "INCLUSIVE",
        "profile_sha256": profile_sha256(profile),
    }


def _fraction_payload(value: Fraction) -> dict[str, int]:
    return {"numerator": value.numerator, "denominator": value.denominator}


def _nearest_rank(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(len(ordered) * quantile + 0.999999) - 1))
    return ordered[index]


def _capture(captures: tuple[CaptureSpec, ...], name: str) -> CaptureSpec:
    return next(capture for capture in captures if capture.name == name)


def _parser() -> argparse.ArgumentParser:
    tool_dir = Path(__file__).resolve().parents[2]
    repository_root = tool_dir.parents[1]
    parser = argparse.ArgumentParser(description="Run the synthetic-only V1.19 G0 experiment")
    parser.add_argument(
        "--fixture",
        type=Path,
        default=tool_dir / "fixtures" / "golden-captures.json",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=repository_root / "tmp" / "v1.19-g0",
    )
    parser.add_argument("--evidence-out", type=Path)
    parser.add_argument("--worker-trials", type=int, default=3)
    return parser


def main() -> None:
    args = _parser().parse_args()
    work_dir = args.work_dir.resolve()
    evidence_out = (
        args.evidence_out.resolve()
        if args.evidence_out is not None
        else work_dir / "v1.19-g0-evidence.json"
    )
    evidence = run_experiment(
        args.fixture.resolve(),
        work_dir,
        worker_trials=args.worker_trials,
    )
    evidence_out.parent.mkdir(parents=True, exist_ok=True)
    evidence_out.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(evidence_out)


if __name__ == "__main__":
    main()
