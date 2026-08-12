from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from fractions import Fraction
from pathlib import Path
from typing import Any, Literal, cast

FrameState = Literal["PRESENT", "MISSING", "INVALID"]
HandDetectionState = Literal["HANDS_DETECTED", "NO_DETECTION", "FAILED", "NOT_RUN"]
Pattern = Literal["CFR", "GAP", "VFR"]


@dataclass(frozen=True, slots=True)
class StreamSpec:
    camera_number: int
    camera_stream_id: str
    video_id: str
    file_name: str
    source_rate: Fraction
    pattern: Pattern
    pts_offset_seconds: Fraction
    invalid_source_frame_indices: frozenset[int] = field(default_factory=frozenset)
    no_detection_source_frame_indices: frozenset[int] = field(default_factory=frozenset)


@dataclass(frozen=True, slots=True)
class CaptureSpec:
    name: str
    capture_id: str
    sync_revision_id: str
    capture_group_key: str | None
    duration_seconds: Fraction
    expected_camera_numbers: tuple[int, ...]
    streams: tuple[StreamSpec, ...]
    artifact_created_at: datetime


@dataclass(frozen=True, slots=True)
class SourceFrame:
    source_frame_index: int
    raw_pts: int
    source_pts_ns: int
    source_pts_ordinal: int
    invalid_reason_code: str | None


@dataclass(frozen=True, slots=True)
class ProbedStream:
    spec: StreamSpec
    path: Path
    size_bytes: int
    sha256: str
    time_base: Fraction
    pts_origin: int
    nominal_rate: Fraction | None
    average_rate: Fraction | None
    frames: tuple[SourceFrame, ...]


@dataclass(frozen=True, slots=True)
class SyncProfile:
    timeline_rate: Fraction
    allowed_tolerance_ns: int
    reference_rule: str = "SYNTHETIC_EXPLICIT_CAPTURE_CLOCK"
    rounding_rule: str = "ROUND_HALF_EVEN"
    tie_breaker: str = "EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL"


@dataclass(frozen=True, slots=True)
class FrameSlot:
    schema_version: str
    capture_id: str
    capture_group_key: str | None
    sync_revision_id: str
    sync_frame_index: int
    capture_timestamp_ns: int
    camera_number: int
    camera_stream_id: str | None
    video_id: str | None
    source_frame_index: int | None
    source_pts_ns: int | None
    source_pts_ordinal: int | None
    frame_state: FrameState
    frame_reason_code: str | None
    sync_error_ns: int | None
    hand_detection_state: HandDetectionState
    ai_job_id: str | None
    candidate_count: int
    candidate_artifact_ref: str | None
    annotation_revision_id: str | None
    model_sha256: bytes | None
    profile_sha256: bytes | None
    artifact_created_at: datetime


def load_capture_specs(path: Path) -> tuple[CaptureSpec, ...]:
    raw = cast(dict[str, Any], json.loads(path.read_text(encoding="utf-8")))
    created_at = _parse_utc(str(raw["artifact_created_at"]))
    cases = cast(list[dict[str, Any]], raw["cases"])
    return tuple(_capture_spec(value, created_at) for value in cases)


def _capture_spec(value: dict[str, Any], created_at: datetime) -> CaptureSpec:
    stream_values = cast(list[dict[str, Any]], value["streams"])
    streams = tuple(_stream_spec(stream) for stream in stream_values)
    expected = tuple(int(number) for number in cast(list[int], value["expected_camera_numbers"]))
    if expected != tuple(sorted(set(expected))):
        raise ValueError("expected_camera_numbers must be unique and sorted")
    actual = [stream.camera_number for stream in streams]
    if len(actual) != len(set(actual)) or not set(actual).issubset(expected):
        raise ValueError("stream camera numbers must be unique members of the expected matrix")
    return CaptureSpec(
        name=str(value["name"]),
        capture_id=str(value["capture_id"]),
        sync_revision_id=str(value["sync_revision_id"]),
        capture_group_key=_optional_string(value.get("capture_group_key")),
        duration_seconds=Fraction(str(value["duration_seconds"])),
        expected_camera_numbers=expected,
        streams=streams,
        artifact_created_at=created_at,
    )


def _stream_spec(value: dict[str, Any]) -> StreamSpec:
    pattern = str(value["pattern"])
    if pattern not in {"CFR", "GAP", "VFR"}:
        raise ValueError(f"unsupported pattern: {pattern}")
    return StreamSpec(
        camera_number=int(value["camera_number"]),
        camera_stream_id=str(value["camera_stream_id"]),
        video_id=str(value["video_id"]),
        file_name=str(value["file_name"]),
        source_rate=Fraction(str(value["source_rate"])),
        pattern=cast(Pattern, pattern),
        pts_offset_seconds=Fraction(str(value["pts_offset_seconds"])),
        invalid_source_frame_indices=frozenset(
            int(index) for index in cast(list[int], value.get("invalid_source_frame_indices", []))
        ),
        no_detection_source_frame_indices=frozenset(
            int(index)
            for index in cast(list[int], value.get("no_detection_source_frame_indices", []))
        ),
    )


def _parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("artifact_created_at must include a UTC offset")
    return parsed.astimezone(UTC)


def _optional_string(value: object) -> str | None:
    return None if value is None else str(value)
