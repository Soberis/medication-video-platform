from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import asdict, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast

import pyarrow.parquet as pq
import pytest
from jsonschema import Draft202012Validator, FormatChecker

import medication_media_worker.artifact_writer as artifact_writer_module
from medication_media_worker.artifact_writer import (
    LOGICAL_FIELDS,
    SCHEMA_VERSION,
    AIProvenance,
    AnnotationProvenance,
    ArtifactValidationError,
    CameraProvenance,
    CanonicalArtifactInput,
    CanonicalArtifactWriter,
    CaptureProvenance,
    FrameSlot,
    ParquetOptions,
    SourceFrameProvenance,
    SyncProfileReference,
    TimeMappingProvenance,
    canonical_schema,
    inspect_artifact,
    logical_content_sha256,
)

CAPTURE_ID = "10000000-0000-4000-8000-000000000001"
SYNC_REVISION_ID = "20000000-0000-4000-8000-000000000002"
FROZEN_AT = datetime(2026, 8, 12, 12, 34, 56, 123456, tzinfo=UTC)
EXPECTED_CAMERAS = (1, 2, 3, 4, 5)
MODEL_SHA256 = hashlib.sha256(b"synthetic-model").hexdigest()
AI_PROFILE_SHA256 = hashlib.sha256(b"synthetic-ai-profile").hexdigest()


def _uuid(suffix: int) -> str:
    return f"00000000-0000-4000-8000-{suffix:012d}"


def _digest(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


def _source_frame(index: int, *, step_ns: int = 33_333_333) -> SourceFrameProvenance:
    return SourceFrameProvenance(
        source_frame_index=index,
        raw_pts=270000 + index * 3000,
        source_pts_ns=index * step_ns,
        source_pts_ordinal=0,
    )


def _camera(camera_number: int) -> CameraProvenance:
    member_id = _uuid(100 + camera_number)
    if camera_number in {2, 4}:
        return CameraProvenance(
            camera_number=camera_number,
            camera_index=camera_number - 1,
            camera_set_snapshot_member_id=member_id,
            camera_stream_id=None,
            video_id=None,
            original_mp4_sha256=None,
            time_base_num=None,
            time_base_den=None,
            pts_origin=None,
        )
    source_step_ns = 40_000_000 if camera_number == 3 else 33_333_333
    return CameraProvenance(
        camera_number=camera_number,
        camera_index=camera_number - 1,
        camera_set_snapshot_member_id=member_id,
        camera_stream_id=_uuid(200 + camera_number),
        video_id=_uuid(300 + camera_number),
        original_mp4_sha256=_digest(f"camera-{camera_number}-original-mp4"),
        time_base_num=1,
        time_base_den=90000,
        pts_origin=270000,
        source_frames=(
            _source_frame(0, step_ns=source_step_ns),
            _source_frame(1, step_ns=source_step_ns),
        ),
        source_input_size_bytes=12345 + camera_number,
        source_media_report_id=_uuid(400 + camera_number),
        source_time_base_digest=_digest(f"camera-{camera_number}-time-base"),
        time_mapping=TimeMappingProvenance(
            anchor_source_ns=0,
            anchor_capture_ns=0,
            drift_ppm=None if camera_number != 3 else "1000",
            method="synthetic-explicit-anchor",
            source="synthetic-fixture",
            anchor_count=1 if camera_number != 3 else 2,
        ),
    )


def _ai(job_number: int) -> AIProvenance:
    return AIProvenance(
        ai_job_id=_uuid(500 + job_number),
        attempt_id=_uuid(600 + job_number),
        attempt_number=1,
        model_sha256=MODEL_SHA256,
        profile_sha256=AI_PROFILE_SHA256,
        sampling_rule="synthetic-source-frame-sample-v1",
    )


def _row(
    camera_number: int,
    sync_frame_index: int,
    *,
    frame_state: str,
    hand_state: str,
    source_frame_index: int | None,
    reason: str | None,
    ai_job_id: str | None = None,
    candidate_count: int = 0,
    candidate_ref: str | None = None,
    annotation_revision_id: str | None = None,
) -> FrameSlot:
    camera = _camera(camera_number)
    source = (
        next(
            frame
            for frame in camera.source_frames
            if frame.source_frame_index == source_frame_index
        )
        if source_frame_index is not None
        else None
    )
    return FrameSlot(
        schema_version=SCHEMA_VERSION,
        capture_id=CAPTURE_ID,
        capture_group_key="synthetic-capture-group",
        sync_revision_id=SYNC_REVISION_ID,
        sync_frame_index=sync_frame_index,
        capture_timestamp_ns=sync_frame_index * 100_000_000,
        camera_number=camera_number,
        camera_stream_id=camera.camera_stream_id,
        video_id=camera.video_id,
        source_frame_index=None if source is None else source.source_frame_index,
        source_pts_ns=None if source is None else source.source_pts_ns,
        source_pts_ordinal=None if source is None else source.source_pts_ordinal,
        frame_state=frame_state,
        frame_reason_code=reason,
        sync_error_ns=0 if frame_state == "PRESENT" else None,
        hand_detection_state=hand_state,
        ai_job_id=ai_job_id,
        candidate_count=candidate_count,
        candidate_artifact_ref=candidate_ref,
        annotation_revision_id=annotation_revision_id,
        model_sha256=MODEL_SHA256 if ai_job_id is not None else None,
        profile_sha256=AI_PROFILE_SHA256 if ai_job_id is not None else None,
        artifact_created_at=FROZEN_AT,
    )


def _input(rows: tuple[FrameSlot, ...] | None = None) -> CanonicalArtifactInput:
    cameras = tuple(_camera(number) for number in EXPECTED_CAMERAS)
    ai = tuple(_ai(number) for number in (1, 2, 3))
    annotation_revision_id = _uuid(700)
    input_rows = rows or (
        _row(
            1,
            0,
            frame_state="PRESENT",
            hand_state="HANDS_DETECTED",
            source_frame_index=0,
            reason=None,
            ai_job_id=ai[0].ai_job_id,
            candidate_count=1,
            candidate_ref="candidate:synthetic-1",
            annotation_revision_id=annotation_revision_id,
        ),
        _row(
            2,
            0,
            frame_state="MISSING",
            hand_state="NOT_RUN",
            source_frame_index=None,
            reason="CAMERA_STREAM_MISSING",
        ),
        _row(
            3,
            0,
            frame_state="PRESENT",
            hand_state="NO_DETECTION",
            source_frame_index=0,
            reason=None,
            ai_job_id=ai[1].ai_job_id,
        ),
        _row(
            4,
            0,
            frame_state="MISSING",
            hand_state="NOT_RUN",
            source_frame_index=None,
            reason="CAMERA_STREAM_MISSING",
        ),
        _row(
            5,
            0,
            frame_state="PRESENT",
            hand_state="FAILED",
            source_frame_index=0,
            reason=None,
            ai_job_id=ai[2].ai_job_id,
        ),
        _row(
            1,
            1,
            frame_state="PRESENT",
            hand_state="NOT_RUN",
            source_frame_index=1,
            reason=None,
        ),
        _row(
            2,
            1,
            frame_state="MISSING",
            hand_state="NOT_RUN",
            source_frame_index=None,
            reason="CAMERA_STREAM_MISSING",
        ),
        _row(
            3,
            1,
            frame_state="INVALID",
            hand_state="NOT_RUN",
            source_frame_index=1,
            reason="SYNTHETIC_DECODE_VALIDATION_FAILED",
        ),
        _row(
            4,
            1,
            frame_state="MISSING",
            hand_state="NOT_RUN",
            source_frame_index=None,
            reason="CAMERA_STREAM_MISSING",
        ),
        _row(
            5,
            1,
            frame_state="PRESENT",
            hand_state="NOT_RUN",
            source_frame_index=1,
            reason=None,
        ),
    )
    return CanonicalArtifactInput.from_rows(
        capture_id=CAPTURE_ID,
        capture_group_key="synthetic-capture-group",
        sync_revision_id=SYNC_REVISION_ID,
        sync_profile=SyncProfileReference(
            key="synthetic-sync-profile",
            version="test-1",
            digest=_digest("synthetic-sync-profile"),
        ),
        expected_camera_numbers=EXPECTED_CAMERAS,
        rows=input_rows,
        frozen_at=FROZEN_AT,
        capture_provenance=CaptureProvenance(
            capture_session_id=CAPTURE_ID,
            camera_set_snapshot_id=_uuid(800),
            camera_set_snapshot_sha256=_digest("synthetic-camera-snapshot"),
        ),
        camera_provenance=cameras,
        input_manifest_sha256=_digest("synthetic-input-manifest"),
        ai_provenance=ai,
        annotation_provenance=(
            AnnotationProvenance(
                annotation_revision_id=annotation_revision_id,
                annotation_task_id=_uuid(701),
                review_id=_uuid(702),
                cleaning_run_id=_uuid(703),
                label_schema_digest=_digest("synthetic-label-schema"),
            ),
        ),
    )


def _logical_json(row: FrameSlot) -> dict[str, object]:
    value = asdict(row)
    for field_name in ("model_sha256", "profile_sha256"):
        digest = value[field_name]
        value[field_name] = digest
    created_at = value["artifact_created_at"]
    assert isinstance(created_at, datetime)
    value["artifact_created_at"] = created_at.isoformat().replace("+00:00", "Z")
    return value


def _schema_validator() -> Draft202012Validator:
    contract_path = (
        Path(__file__).resolve().parents[3]
        / "contracts"
        / "schemas"
        / "synchronized-frame-v1.schema.json"
    )
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(contract)
    return Draft202012Validator(contract, format_checker=FormatChecker())


def _replace_row(
    value: CanonicalArtifactInput,
    predicate: Callable[[FrameSlot], bool],
    **changes: object,
) -> CanonicalArtifactInput:
    rows = list(value.rows)
    index = next(index for index, row in enumerate(rows) if predicate(row))
    rows[index] = replace(rows[index], **cast(dict[str, Any], changes))
    return replace(value, rows=tuple(rows))


def test_writer_canonicalizes_rows_and_writes_stable_artifact_and_manifest(
    tmp_path: Path,
) -> None:
    value = _input()
    first = CanonicalArtifactWriter().write(
        replace(value, rows=tuple(reversed(value.rows))),
        tmp_path / "first.parquet",
    )
    second = CanonicalArtifactWriter().write(value, tmp_path / "second.parquet")

    assert first.logical_content_sha256 == second.logical_content_sha256
    assert first.artifact_sha256 == second.artifact_sha256
    assert first.artifact_manifest_sha256 == second.artifact_manifest_sha256
    assert first.parquet_path.read_bytes() == second.parquet_path.read_bytes()
    assert first.manifest_path.read_bytes() == second.manifest_path.read_bytes()

    rows = pq.read_table(first.parquet_path).to_pylist()
    keys = [
        (
            row["capture_id"],
            row["sync_revision_id"],
            row["sync_frame_index"],
            row["camera_number"],
        )
        for row in rows
    ]
    assert keys == sorted(keys)
    assert len(keys) == len(set(keys)) == 10
    assert {row["frame_state"] for row in rows} == {"PRESENT", "MISSING", "INVALID"}
    assert {row["hand_detection_state"] for row in rows} == {
        "HANDS_DETECTED",
        "NO_DETECTION",
        "FAILED",
        "NOT_RUN",
    }
    assert [row["camera_number"] for row in rows if row["sync_frame_index"] == 0] == [1, 2, 3, 4, 5]

    manifest = json.loads(first.manifest_path.read_text(encoding="utf-8"))
    assert manifest == first.manifest
    assert manifest["schema_version"] == SCHEMA_VERSION
    assert manifest["row_count"] == 10
    assert manifest["sync_frame_count"] == 2
    assert manifest["expected_camera_count"] == 5
    assert manifest["state_summary"]["frame_states"] == {
        "INVALID": 1,
        "MISSING": 4,
        "PRESENT": 5,
    }
    assert manifest["logical_content_sha256"] == first.logical_content_sha256
    assert manifest["artifact_sha256"] == first.artifact_sha256
    assert manifest["artifact_manifest_sha256"] == first.artifact_manifest_sha256
    assert "rows" not in manifest
    assert "frame_slots" not in manifest

    provenance = cast(dict[str, Any], manifest["provenance"])
    assert len(provenance["cameras"]) == 5
    assert len(provenance["ai"]) == 3
    assert len(provenance["annotations"]) == 1
    camera_two = next(item for item in provenance["cameras"] if item["camera_number"] == 2)
    assert camera_two["camera_stream_id"] is None
    assert camera_two["original_mp4_sha256"] is None
    camera_one = next(item for item in provenance["cameras"] if item["camera_number"] == 1)
    assert camera_one["time_base"] == {"numerator": 1, "denominator": 90000}
    assert camera_one["source_frames"][0]["raw_pts"] == 270000
    assert camera_one["time_mapping"] == {
        "anchor_source_ns": 0,
        "anchor_capture_ns": 0,
        "drift_ppm": None,
        "method": "synthetic-explicit-anchor",
        "source": "synthetic-fixture",
        "anchor_count": 1,
    }

    inspection = inspect_artifact(first.parquet_path)
    inspection_schema = cast(list[dict[str, object]], inspection["arrow_schema"])
    inspection_metadata = cast(dict[str, str], inspection["metadata"])
    assert [item["name"] for item in inspection_schema] == list(LOGICAL_FIELDS)
    assert inspection_metadata["logical_content_sha256"] == first.logical_content_sha256


def test_schema_and_physical_types_are_explicit_and_compatible(tmp_path: Path) -> None:
    value = _input()
    validator = _schema_validator()
    for row in value.rows:
        validator.validate(_logical_json(row))

    artifact = CanonicalArtifactWriter().write(value, tmp_path / "canonical.parquet")
    schema = pq.ParquetFile(artifact.parquet_path).schema_arrow
    assert schema.names == list(LOGICAL_FIELDS)
    assert str(schema.field("sync_frame_index").type) == "int64"
    assert str(schema.field("capture_timestamp_ns").type) == "int64"
    assert str(schema.field("camera_number").type) == "int32"
    assert str(schema.field("model_sha256").type) == "fixed_size_binary[32]"
    assert str(schema.field("profile_sha256").type) == "fixed_size_binary[32]"
    assert str(schema.field("artifact_created_at").type) == "timestamp[us, tz=UTC]"
    assert canonical_schema().names == list(LOGICAL_FIELDS)


def test_schema_accepts_failed_with_ai_job_and_attempt_provenance() -> None:
    failed = next(row for row in _input().rows if row.hand_detection_state == "FAILED")

    _schema_validator().validate(_logical_json(failed))


@pytest.mark.parametrize(
    ("field_name", "replacement"),
    [
        ("ai_job_id", None),
        ("model_sha256", None),
        ("profile_sha256", None),
        ("candidate_count", 1),
        ("candidate_artifact_ref", "candidate:unexpected-failure-output"),
    ],
)
def test_schema_rejects_failed_without_required_provenance_or_empty_output(
    field_name: str, replacement: object
) -> None:
    failed = next(row for row in _input().rows if row.hand_detection_state == "FAILED")
    value = _logical_json(failed)
    value[field_name] = replacement

    assert list(_schema_validator().iter_errors(value))


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: replace(value, rows=()),
        lambda value: replace(value, rows=(*value.rows, value.rows[0])),
        lambda value: _replace_row(
            value,
            lambda row: row.camera_number == 1 and row.sync_frame_index == 0,
            source_pts_ns=None,
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.camera_number == 2 and row.sync_frame_index == 0,
            source_frame_index=0,
            source_pts_ns=0,
            source_pts_ordinal=0,
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.frame_state == "INVALID",
            frame_reason_code=None,
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.hand_detection_state == "NOT_RUN",
            ai_job_id=_uuid(501),
            model_sha256=MODEL_SHA256,
            profile_sha256=AI_PROFILE_SHA256,
        ),
        lambda value: replace(value, ai_provenance=()),
        lambda value: _replace_row(
            value,
            lambda row: row.hand_detection_state == "HANDS_DETECTED",
            candidate_count=0,
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.camera_number == 1 and row.sync_frame_index == 0,
            capture_id="not-a-uuid",
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.camera_number == 1 and row.sync_frame_index == 0,
            camera_number=65,
        ),
        lambda value: replace(
            value,
            sync_profile=replace(value.sync_profile, digest="g" * 64),
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.camera_number == 1 and row.sync_frame_index == 0,
            sync_revision_id=_uuid(999),
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.camera_number == 1 and row.sync_frame_index == 0,
            capture_id=_uuid(999),
        ),
    ],
)
def test_writer_rejects_empty_or_invalid_input(
    tmp_path: Path,
    mutate: Callable[[CanonicalArtifactInput], CanonicalArtifactInput],
) -> None:
    with pytest.raises(ArtifactValidationError):
        CanonicalArtifactWriter().write(mutate(_input()), tmp_path / "invalid.parquet")


def test_writer_refuses_to_overwrite_existing_artifact(tmp_path: Path) -> None:
    output = tmp_path / "immutable.parquet"
    CanonicalArtifactWriter().write(_input(), output)
    with pytest.raises(FileExistsError):
        CanonicalArtifactWriter().write(_input(), output)


def test_writer_accepts_failed_only_with_matching_ai_attempt_provenance(tmp_path: Path) -> None:
    value = _input()

    artifact = CanonicalArtifactWriter().write(value, tmp_path / "failed-with-provenance.parquet")

    assert artifact.row_count == len(value.rows)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: _replace_row(
            value,
            lambda row: row.hand_detection_state == "FAILED",
            ai_job_id=None,
            model_sha256=None,
            profile_sha256=None,
        ),
        lambda value: replace(
            value,
            ai_provenance=tuple(
                replace(item, attempt_id="") if item.ai_job_id == _uuid(503) else item
                for item in value.ai_provenance
            ),
        ),
        lambda value: _replace_row(
            value,
            lambda row: row.hand_detection_state == "FAILED",
            ai_job_id=_uuid(999),
        ),
    ],
)
def test_writer_rejects_failed_without_matching_ai_attempt_provenance(
    tmp_path: Path, mutate: Callable[[CanonicalArtifactInput], CanonicalArtifactInput]
) -> None:
    with pytest.raises(ArtifactValidationError):
        CanonicalArtifactWriter().write(mutate(_input()), tmp_path / "invalid-failed.parquet")


def test_writer_requires_distinct_parquet_and_manifest_paths(tmp_path: Path) -> None:
    output = tmp_path / "same-output"

    with pytest.raises(ArtifactValidationError):
        CanonicalArtifactWriter().write(_input(), output, manifest_path=output)

    assert not output.exists()


def test_writer_removes_partial_outputs_when_manifest_write_fails(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    parquet_path = tmp_path / "partial.parquet"
    manifest_path = tmp_path / "partial.manifest.json"

    def fail_manifest_write(path: Path, value: object) -> None:
        raise OSError("synthetic manifest failure")

    monkeypatch.setattr(artifact_writer_module, "_write_json", fail_manifest_write)
    with pytest.raises(OSError, match="synthetic manifest failure"):
        CanonicalArtifactWriter().write(_input(), parquet_path, manifest_path=manifest_path)

    assert not parquet_path.exists()
    assert not manifest_path.exists()


@pytest.mark.parametrize(
    ("drift_ppm", "anchor_count"),
    [("1", 1), ("not-a-decimal", 2), ("-1000000", 2), ("NaN", 2)],
)
def test_writer_rejects_untraceable_time_mapping_drift(
    tmp_path: Path, drift_ppm: str, anchor_count: int
) -> None:
    value = _input()
    cameras = list(value.camera_provenance)
    camera = cameras[0]
    assert camera.time_mapping is not None
    cameras[0] = replace(
        camera,
        time_mapping=replace(
            camera.time_mapping,
            drift_ppm=drift_ppm,
            anchor_count=anchor_count,
        ),
    )

    with pytest.raises(ArtifactValidationError):
        CanonicalArtifactWriter().write(
            replace(value, camera_provenance=tuple(cameras)),
            tmp_path / "invalid-drift.parquet",
        )


def test_logical_digest_is_independent_of_input_order() -> None:
    value = _input()
    assert logical_content_sha256(value.rows) == logical_content_sha256(reversed(value.rows))


def test_writer_preserves_frame_slots_generated_from_different_source_fps(
    tmp_path: Path,
) -> None:
    value = _input()
    artifact = CanonicalArtifactWriter().write(value, tmp_path / "different-fps.parquet")
    rows = pq.read_table(artifact.parquet_path).to_pylist()
    camera_three = [row for row in rows if row["camera_number"] == 3]
    assert [
        row["source_pts_ns"] for row in camera_three if row["source_frame_index"] is not None
    ] == [0, 40_000_000]


def test_non_production_physical_layout_is_explicit() -> None:
    options = ParquetOptions()
    assert options.production_default is False
    assert options.compression == "zstd"
    assert options.row_group_sync_frames == 1
