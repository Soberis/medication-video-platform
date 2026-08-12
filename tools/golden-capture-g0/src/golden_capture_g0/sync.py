from __future__ import annotations

import bisect
import hashlib
import json
import uuid
from collections import Counter
from dataclasses import asdict
from fractions import Fraction
from typing import Any, cast

from golden_capture_g0.model import (
    CaptureSpec,
    FrameSlot,
    FrameState,
    HandDetectionState,
    ProbedStream,
    SourceFrame,
    SyncProfile,
)
from golden_capture_g0.rational import round_half_even

NANOSECONDS_PER_SECOND = 1_000_000_000
SCHEMA_VERSION = "synchronized-frame.v1"
SYNTHETIC_MODEL_SHA256 = hashlib.sha256(b"v1.19-g0-synthetic-no-detection-model").digest()
SYNTHETIC_AI_PROFILE_SHA256 = hashlib.sha256(b"v1.19-g0-synthetic-no-detection-profile").digest()


def build_frame_slots(
    capture: CaptureSpec,
    streams: dict[int, ProbedStream],
    profile: SyncProfile,
) -> tuple[FrameSlot, ...]:
    unexpected = set(streams) - set(capture.expected_camera_numbers)
    if unexpected:
        raise ValueError(f"unexpected camera numbers: {sorted(unexpected)}")
    slots: list[FrameSlot] = []
    for sync_frame_index, capture_timestamp_ns in enumerate(
        build_timeline(capture.duration_seconds, profile.timeline_rate)
    ):
        for camera_number in capture.expected_camera_numbers:
            stream = streams.get(camera_number)
            if stream is None:
                slots.append(
                    _missing_stream_slot(
                        capture, sync_frame_index, capture_timestamp_ns, camera_number
                    )
                )
                continue
            slots.append(
                _map_stream_slot(
                    capture,
                    sync_frame_index,
                    capture_timestamp_ns,
                    stream,
                    profile.allowed_tolerance_ns,
                )
            )
    result = tuple(slots)
    validate_frame_slots(capture, result)
    return result


def build_timeline(duration_seconds: Fraction, rate: Fraction) -> tuple[int, ...]:
    if duration_seconds <= 0 or rate <= 0:
        raise ValueError("duration and timeline rate must be positive")
    frame_count = _ceil_fraction(duration_seconds * rate)
    timestamps = tuple(
        round_half_even(Fraction(index, 1) / rate * NANOSECONDS_PER_SECOND)
        for index in range(frame_count)
    )
    if timestamps and timestamps[-1] >= round_half_even(duration_seconds * NANOSECONDS_PER_SECOND):
        raise AssertionError("timeline must use a half-open capture duration")
    return timestamps


def profile_sha256(profile: SyncProfile) -> str:
    return _sha256_json(
        {
            "schema_version": "capture-sync-g0-measurement-profile.v1",
            "approval_status": "NOT_APPROVED_FOR_PRODUCTION",
            "timeline_rate_numerator": profile.timeline_rate.numerator,
            "timeline_rate_denominator": profile.timeline_rate.denominator,
            "allowed_tolerance_ns": profile.allowed_tolerance_ns,
            "reference_rule": profile.reference_rule,
            "rounding_rule": profile.rounding_rule,
            "tie_breaker": profile.tie_breaker,
            "tolerance_boundary": "INCLUSIVE",
        }
    )


def input_manifest_sha256(capture: CaptureSpec, streams: dict[int, ProbedStream]) -> str:
    payload = {
        "fixture": capture.name,
        "capture_id": capture.capture_id,
        "sync_revision_id": capture.sync_revision_id,
        "expected_camera_numbers": list(capture.expected_camera_numbers),
        "duration_seconds": _fraction_payload(capture.duration_seconds),
        "streams": [
            {
                "camera_number": camera_number,
                "camera_stream_id": stream.spec.camera_stream_id,
                "video_id": stream.spec.video_id,
                "sha256": stream.sha256,
                "size_bytes": stream.size_bytes,
                "time_base": _fraction_payload(stream.time_base),
                "pts_origin": stream.pts_origin,
                "frames": [
                    {
                        "source_frame_index": frame.source_frame_index,
                        "raw_pts": frame.raw_pts,
                        "source_pts_ns": frame.source_pts_ns,
                        "source_pts_ordinal": frame.source_pts_ordinal,
                        "invalid_reason_code": frame.invalid_reason_code,
                    }
                    for frame in stream.frames
                ],
            }
            for camera_number, stream in sorted(streams.items())
        ],
    }
    return _sha256_json(payload)


def logical_content_sha256(slots: tuple[FrameSlot, ...]) -> str:
    digest = hashlib.sha256()
    for slot in slots:
        payload = asdict(slot)
        payload["model_sha256"] = slot.model_sha256.hex() if slot.model_sha256 else None
        payload["profile_sha256"] = slot.profile_sha256.hex() if slot.profile_sha256 else None
        payload["artifact_created_at"] = slot.artifact_created_at.isoformat().replace("+00:00", "Z")
        digest.update(_canonical_json(payload))
        digest.update(b"\n")
    return digest.hexdigest()


def validate_frame_slots(capture: CaptureSpec, slots: tuple[FrameSlot, ...]) -> None:
    expected_key_order = sorted(
        (slot.capture_id, slot.sync_revision_id, slot.sync_frame_index, slot.camera_number)
        for slot in slots
    )
    actual_key_order = [
        (slot.capture_id, slot.sync_revision_id, slot.sync_frame_index, slot.camera_number)
        for slot in slots
    ]
    if actual_key_order != expected_key_order or len(actual_key_order) != len(
        set(actual_key_order)
    ):
        raise ValueError("frame slots must have a unique stable primary-key order")
    expected_camera_count = len(capture.expected_camera_numbers)
    if len(slots) % expected_camera_count != 0:
        raise ValueError("frame slot matrix is not rectangular")
    grouped = Counter(slot.sync_frame_index for slot in slots)
    if any(count != expected_camera_count for count in grouped.values()):
        raise ValueError("each sync frame must contain every expected camera")
    for slot in slots:
        if slot.frame_state == "PRESENT":
            if (
                slot.source_frame_index is None
                or slot.source_pts_ns is None
                or slot.sync_error_ns is None
            ):
                raise ValueError(
                    "PRESENT slot must trace a source frame, source PTS and sync error"
                )
        if slot.frame_state in {"MISSING", "INVALID"} and slot.hand_detection_state != "NOT_RUN":
            raise ValueError("MISSING/INVALID slots must not imply an AI inference conclusion")
        if slot.hand_detection_state == "NO_DETECTION" and (
            slot.ai_job_id is None or slot.model_sha256 is None or slot.profile_sha256 is None
        ):
            raise ValueError("NO_DETECTION requires accepted inference provenance")
        if slot.hand_detection_state == "NOT_RUN" and slot.ai_job_id is not None:
            raise ValueError("NOT_RUN must not carry an AI job")


def summarize_slots(slots: tuple[FrameSlot, ...]) -> dict[str, Any]:
    states = Counter(slot.frame_state for slot in slots)
    hand_states = Counter(slot.hand_detection_state for slot in slots)
    errors = sorted(abs(slot.sync_error_ns) for slot in slots if slot.sync_error_ns is not None)
    per_camera = {
        str(camera_number): dict(
            sorted(
                Counter(
                    slot.frame_state for slot in slots if slot.camera_number == camera_number
                ).items()
            )
        )
        for camera_number in sorted({slot.camera_number for slot in slots})
    }
    mapped = Counter(
        (slot.camera_number, slot.source_frame_index)
        for slot in slots
        if slot.frame_state == "PRESENT" and slot.source_frame_index is not None
    )
    return {
        "sync_frame_count": len({slot.sync_frame_index for slot in slots}),
        "frame_slot_count": len(slots),
        "frame_states": dict(sorted(states.items())),
        "hand_detection_states": dict(sorted(hand_states.items())),
        "per_camera_states": per_camera,
        "sync_error_abs_ns": {
            "sample_count": len(errors),
            "p50": _nearest_rank(errors, 0.50),
            "p95": _nearest_rank(errors, 0.95),
            "max": errors[-1] if errors else None,
        },
        "reused_present_source_frame_mappings": sum(
            count - 1 for count in mapped.values() if count > 1
        ),
    }


def _map_stream_slot(
    capture: CaptureSpec,
    sync_frame_index: int,
    capture_timestamp_ns: int,
    stream: ProbedStream,
    tolerance_ns: int,
) -> FrameSlot:
    frame = _nearest_frame(stream.frames, capture_timestamp_ns)
    error_ns = frame.source_pts_ns - capture_timestamp_ns
    if abs(error_ns) > tolerance_ns:
        return _slot(
            capture,
            sync_frame_index,
            capture_timestamp_ns,
            stream.spec.camera_number,
            stream.spec.camera_stream_id,
            stream.spec.video_id,
            None,
            "MISSING",
            "NO_FRAME_WITHIN_TOLERANCE",
            None,
        )
    if frame.invalid_reason_code is not None:
        return _slot(
            capture,
            sync_frame_index,
            capture_timestamp_ns,
            stream.spec.camera_number,
            stream.spec.camera_stream_id,
            stream.spec.video_id,
            frame,
            "INVALID",
            frame.invalid_reason_code,
            None,
        )
    hand_state = (
        "NO_DETECTION"
        if frame.source_frame_index in stream.spec.no_detection_source_frame_indices
        else "NOT_RUN"
    )
    return _slot(
        capture,
        sync_frame_index,
        capture_timestamp_ns,
        stream.spec.camera_number,
        stream.spec.camera_stream_id,
        stream.spec.video_id,
        frame,
        "PRESENT",
        None,
        error_ns,
        hand_state=hand_state,
    )


def _missing_stream_slot(
    capture: CaptureSpec,
    sync_frame_index: int,
    capture_timestamp_ns: int,
    camera_number: int,
) -> FrameSlot:
    return _slot(
        capture,
        sync_frame_index,
        capture_timestamp_ns,
        camera_number,
        None,
        None,
        None,
        "MISSING",
        "CAMERA_STREAM_MISSING",
        None,
    )


def _slot(
    capture: CaptureSpec,
    sync_frame_index: int,
    capture_timestamp_ns: int,
    camera_number: int,
    camera_stream_id: str | None,
    video_id: str | None,
    frame: SourceFrame | None,
    frame_state: str,
    reason_code: str | None,
    sync_error_ns: int | None,
    *,
    hand_state: str = "NOT_RUN",
) -> FrameSlot:
    ai_job_id = None
    model_sha256 = None
    ai_profile_sha256 = None
    if hand_state == "NO_DETECTION" and video_id is not None:
        ai_job_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"v1.19-g0-no-detection:{video_id}"))
        model_sha256 = SYNTHETIC_MODEL_SHA256
        ai_profile_sha256 = SYNTHETIC_AI_PROFILE_SHA256
    return FrameSlot(
        schema_version=SCHEMA_VERSION,
        capture_id=capture.capture_id,
        capture_group_key=capture.capture_group_key,
        sync_revision_id=capture.sync_revision_id,
        sync_frame_index=sync_frame_index,
        capture_timestamp_ns=capture_timestamp_ns,
        camera_number=camera_number,
        camera_stream_id=camera_stream_id,
        video_id=video_id,
        source_frame_index=frame.source_frame_index if frame else None,
        source_pts_ns=frame.source_pts_ns if frame else None,
        source_pts_ordinal=frame.source_pts_ordinal if frame else None,
        frame_state=cast(FrameState, frame_state),
        frame_reason_code=reason_code,
        sync_error_ns=sync_error_ns,
        hand_detection_state=cast(HandDetectionState, hand_state),
        ai_job_id=ai_job_id,
        candidate_count=0,
        candidate_artifact_ref=None,
        annotation_revision_id=None,
        model_sha256=model_sha256,
        profile_sha256=ai_profile_sha256,
        artifact_created_at=capture.artifact_created_at,
    )


def _nearest_frame(frames: tuple[SourceFrame, ...], target_ns: int) -> SourceFrame:
    timestamps = [frame.source_pts_ns for frame in frames]
    insertion = bisect.bisect_left(timestamps, target_ns)
    candidate_indices = {max(0, insertion - 1), min(len(frames) - 1, insertion)}
    candidates = (frames[index] for index in candidate_indices)
    return min(
        candidates,
        key=lambda frame: (
            abs(frame.source_pts_ns - target_ns),
            frame.source_pts_ns,
            frame.source_frame_index,
            frame.source_pts_ordinal,
        ),
    )


def _nearest_rank(values: list[int], quantile: float) -> int | None:
    if not values:
        return None
    index = max(0, min(len(values) - 1, _ceil_fraction(Fraction(str(quantile)) * len(values)) - 1))
    return values[index]


def _ceil_fraction(value: Fraction) -> int:
    return -(-value.numerator // value.denominator)


def _fraction_payload(value: Fraction) -> dict[str, int]:
    return {"numerator": value.numerator, "denominator": value.denominator}


def _sha256_json(value: object) -> str:
    return hashlib.sha256(_canonical_json(value)).hexdigest()


def _canonical_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )
