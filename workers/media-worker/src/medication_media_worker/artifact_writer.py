"""Write the V1.19 canonical synchronized-frame artifact.

This module deliberately accepts only data that has already been synchronized.  It
does not know how anchors, drift, a reference stream, a timeline rate, or a
tolerance are selected.  Its job is to validate the frozen input, canonicalize
the row order, and write a local Parquet artifact plus a small manifest.
"""

from __future__ import annotations

import hashlib
import json
import platform
import re
from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Final, cast
from uuid import UUID

import pyarrow as pa
import pyarrow.parquet as pq

SCHEMA_VERSION: Final = "synchronized-frame.v1"
MANIFEST_SCHEMA_VERSION: Final = "synchronized-frame-manifest.v1"
WRITER_NAME: Final = "medication-media-worker-canonical-artifact-writer"
WRITER_VERSION: Final = "1.0.0"
NON_PRODUCTION_LAYOUT_STATUS: Final = "PENDING_PRODUCTION_APPROVAL"
SORT_ORDER: Final = (
    "capture_id ASC",
    "sync_revision_id ASC",
    "sync_frame_index ASC",
    "camera_number ASC",
)
LOGICAL_KEY_FIELDS: Final = (
    "capture_id",
    "sync_revision_id",
    "sync_frame_index",
    "camera_number",
)
LOGICAL_FIELDS: Final = (
    "schema_version",
    "capture_id",
    "capture_group_key",
    "sync_revision_id",
    "sync_frame_index",
    "capture_timestamp_ns",
    "camera_number",
    "camera_stream_id",
    "video_id",
    "source_frame_index",
    "source_pts_ns",
    "source_pts_ordinal",
    "frame_state",
    "frame_reason_code",
    "sync_error_ns",
    "hand_detection_state",
    "ai_job_id",
    "candidate_count",
    "candidate_artifact_ref",
    "annotation_revision_id",
    "model_sha256",
    "profile_sha256",
    "artifact_created_at",
)

_UUID_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_DIGEST_PATTERN = re.compile(r"^[a-f0-9]{64}$")
_PROFILE_KEY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
_PROFILE_VERSION_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_REASON_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")


class ArtifactValidationError(ValueError):
    """Raised when frozen synchronization input cannot satisfy the row contract."""


@dataclass(frozen=True, slots=True)
class SyncProfileReference:
    """The already selected and hashed synchronization profile."""

    key: str
    version: str
    digest: str


@dataclass(frozen=True, slots=True)
class CaptureProvenance:
    """References for the Capture and its frozen camera snapshot."""

    capture_session_id: str
    camera_set_snapshot_id: str | None = None
    camera_set_snapshot_sha256: str | None = None


@dataclass(frozen=True, slots=True)
class SourceFrameProvenance:
    """Raw and normalized facts for one source frame."""

    source_frame_index: int
    raw_pts: int
    source_pts_ns: int
    source_pts_ordinal: int


@dataclass(frozen=True, slots=True)
class TimeMappingProvenance:
    """The frozen stream-to-Capture mapping selected by the sync producer."""

    anchor_source_ns: int
    anchor_capture_ns: int
    drift_ppm: str | None
    method: str
    source: str
    anchor_count: int


@dataclass(frozen=True, slots=True)
class CameraProvenance:
    """Frozen CameraStream/ClipAsset and source-time provenance for a camera."""

    camera_number: int
    camera_index: int
    camera_set_snapshot_member_id: str
    camera_stream_id: str | None
    video_id: str | None
    original_mp4_sha256: str | None
    time_base_num: int | None
    time_base_den: int | None
    pts_origin: int | None
    source_frames: tuple[SourceFrameProvenance, ...] = ()
    source_input_size_bytes: int | None = None
    source_media_report_id: str | None = None
    source_time_base_digest: str | None = None
    time_mapping: TimeMappingProvenance | None = None


@dataclass(frozen=True, slots=True)
class AIProvenance:
    """References proving an AI attempt and the exact model/profile used."""

    ai_job_id: str
    attempt_id: str
    attempt_number: int
    model_sha256: str
    profile_sha256: str
    sampling_rule: str | None = None


@dataclass(frozen=True, slots=True)
class AnnotationProvenance:
    """References for an optional formal AnnotationRevision projection."""

    annotation_revision_id: str
    annotation_task_id: str | None = None
    review_id: str | None = None
    cleaning_run_id: str | None = None
    label_schema_digest: str | None = None


@dataclass(frozen=True, slots=True)
class FrameSlot:
    """One logical row in synchronized-frame-v1."""

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
    frame_state: str
    frame_reason_code: str | None
    sync_error_ns: int | None
    hand_detection_state: str
    ai_job_id: str | None
    candidate_count: int
    candidate_artifact_ref: str | None
    annotation_revision_id: str | None
    model_sha256: str | None
    profile_sha256: str | None
    artifact_created_at: datetime

    @classmethod
    def from_mapping(cls, value: Mapping[str, object]) -> FrameSlot:
        """Parse one JSON-like row and reject unknown or missing fields."""

        if set(value) != set(LOGICAL_FIELDS):
            raise ArtifactValidationError("Frame Slot fields do not match synchronized-frame.v1")
        try:
            created_at = _parse_datetime(value["artifact_created_at"], "artifact_created_at")
            return cls(
                schema_version=_string(value["schema_version"], "schema_version"),
                capture_id=_string(value["capture_id"], "capture_id"),
                capture_group_key=_optional_string(value["capture_group_key"], "capture_group_key"),
                sync_revision_id=_string(value["sync_revision_id"], "sync_revision_id"),
                sync_frame_index=_integer(value["sync_frame_index"], "sync_frame_index"),
                capture_timestamp_ns=_integer(
                    value["capture_timestamp_ns"], "capture_timestamp_ns"
                ),
                camera_number=_integer(value["camera_number"], "camera_number"),
                camera_stream_id=_optional_string(value["camera_stream_id"], "camera_stream_id"),
                video_id=_optional_string(value["video_id"], "video_id"),
                source_frame_index=_optional_integer(
                    value["source_frame_index"], "source_frame_index"
                ),
                source_pts_ns=_optional_integer(value["source_pts_ns"], "source_pts_ns"),
                source_pts_ordinal=_optional_integer(
                    value["source_pts_ordinal"], "source_pts_ordinal"
                ),
                frame_state=_string(value["frame_state"], "frame_state"),
                frame_reason_code=_optional_string(value["frame_reason_code"], "frame_reason_code"),
                sync_error_ns=_optional_integer(value["sync_error_ns"], "sync_error_ns"),
                hand_detection_state=_string(value["hand_detection_state"], "hand_detection_state"),
                ai_job_id=_optional_string(value["ai_job_id"], "ai_job_id"),
                candidate_count=_integer(value["candidate_count"], "candidate_count"),
                candidate_artifact_ref=_optional_string(
                    value["candidate_artifact_ref"], "candidate_artifact_ref"
                ),
                annotation_revision_id=_optional_string(
                    value["annotation_revision_id"], "annotation_revision_id"
                ),
                model_sha256=_optional_digest(value["model_sha256"], "model_sha256"),
                profile_sha256=_optional_digest(value["profile_sha256"], "profile_sha256"),
                artifact_created_at=created_at,
            )
        except KeyError as exc:
            raise ArtifactValidationError("Frame Slot is missing a required field") from exc


@dataclass(frozen=True, slots=True)
class CanonicalArtifactInput:
    """All frozen inputs needed by the artifact writer.

    ``rows`` are allowed to arrive in any order.  The writer sorts them by the
    frozen logical key; it never computes or changes synchronization values.
    """

    capture_id: str
    capture_group_key: str | None
    sync_revision_id: str
    sync_profile: SyncProfileReference
    expected_camera_numbers: tuple[int, ...]
    rows: tuple[FrameSlot, ...]
    frozen_at: datetime
    capture_provenance: CaptureProvenance
    camera_provenance: tuple[CameraProvenance, ...]
    input_manifest_sha256: str | None = None
    ai_provenance: tuple[AIProvenance, ...] = ()
    annotation_provenance: tuple[AnnotationProvenance, ...] = ()

    @classmethod
    def from_rows(
        cls,
        *,
        capture_id: str,
        capture_group_key: str | None,
        sync_revision_id: str,
        sync_profile: SyncProfileReference,
        expected_camera_numbers: Sequence[int],
        rows: Iterable[FrameSlot | Mapping[str, object]],
        frozen_at: datetime,
        capture_provenance: CaptureProvenance,
        camera_provenance: Sequence[CameraProvenance],
        input_manifest_sha256: str | None = None,
        ai_provenance: Sequence[AIProvenance] = (),
        annotation_provenance: Sequence[AnnotationProvenance] = (),
    ) -> CanonicalArtifactInput:
        parsed_rows = tuple(
            row if isinstance(row, FrameSlot) else FrameSlot.from_mapping(row) for row in rows
        )
        return cls(
            capture_id=capture_id,
            capture_group_key=capture_group_key,
            sync_revision_id=sync_revision_id,
            sync_profile=sync_profile,
            expected_camera_numbers=tuple(expected_camera_numbers),
            rows=parsed_rows,
            frozen_at=frozen_at,
            capture_provenance=capture_provenance,
            camera_provenance=tuple(camera_provenance),
            input_manifest_sha256=input_manifest_sha256,
            ai_provenance=tuple(ai_provenance),
            annotation_provenance=tuple(annotation_provenance),
        )


@dataclass(frozen=True, slots=True)
class ParquetOptions:
    """Explicit local writer settings; production layout is not frozen yet."""

    compression: str | None = "zstd"
    compression_level: int | None = 3
    row_group_sync_frames: int = 1
    data_page_version: str = "1.0"
    use_dictionary: tuple[str, ...] = (
        "schema_version",
        "frame_state",
        "frame_reason_code",
        "hand_detection_state",
    )
    production_default: bool = False


@dataclass(frozen=True, slots=True)
class ArtifactResult:
    """Checksums and locations produced by one local write."""

    parquet_path: Path
    manifest_path: Path
    row_count: int
    sync_frame_count: int
    logical_content_sha256: str
    artifact_sha256: str
    artifact_manifest_sha256: str
    manifest: dict[str, object]

    @property
    def path(self) -> Path:
        """Compatibility alias for callers that use ``path`` for the Parquet file."""

        return self.parquet_path

    @property
    def manifest_sha256(self) -> str:
        """Short alias for the artifact manifest digest."""

        return self.artifact_manifest_sha256


def canonical_schema() -> pa.Schema:
    """Return the explicit physical Arrow schema for the logical row contract."""

    return pa.schema(
        [
            pa.field("schema_version", pa.string(), nullable=False),
            pa.field("capture_id", pa.string(), nullable=False),
            pa.field("capture_group_key", pa.string()),
            pa.field("sync_revision_id", pa.string(), nullable=False),
            pa.field("sync_frame_index", pa.int64(), nullable=False),
            pa.field("capture_timestamp_ns", pa.int64(), nullable=False),
            pa.field("camera_number", pa.int32(), nullable=False),
            pa.field("camera_stream_id", pa.string()),
            pa.field("video_id", pa.string()),
            pa.field("source_frame_index", pa.int64()),
            pa.field("source_pts_ns", pa.int64()),
            pa.field("source_pts_ordinal", pa.int32()),
            pa.field("frame_state", pa.string(), nullable=False),
            pa.field("frame_reason_code", pa.string()),
            pa.field("sync_error_ns", pa.int64()),
            pa.field("hand_detection_state", pa.string(), nullable=False),
            pa.field("ai_job_id", pa.string()),
            pa.field("candidate_count", pa.int32(), nullable=False),
            pa.field("candidate_artifact_ref", pa.string()),
            pa.field("annotation_revision_id", pa.string()),
            pa.field("model_sha256", pa.binary(32)),
            pa.field("profile_sha256", pa.binary(32)),
            pa.field("artifact_created_at", pa.timestamp("us", tz="UTC"), nullable=False),
        ]
    )


def validate_artifact_input(value: CanonicalArtifactInput) -> tuple[FrameSlot, ...]:
    """Validate and return rows in canonical logical order."""

    if not isinstance(value, CanonicalArtifactInput):
        raise ArtifactValidationError("artifact input must be CanonicalArtifactInput")
    capture_id = _uuid(value.capture_id, "capture_id")
    sync_revision_id = _uuid(value.sync_revision_id, "sync_revision_id")
    if value.capture_group_key is not None:
        _bounded_non_empty(value.capture_group_key, "capture_group_key", 128)
    _validate_profile(value.sync_profile)
    _validate_digest(value.input_manifest_sha256, "input_manifest_sha256", optional=True)
    frozen_at = _utc_datetime(value.frozen_at, "frozen_at")
    _validate_expected_cameras(value.expected_camera_numbers)
    _validate_capture_provenance(value.capture_provenance, capture_id)
    cameras = _validate_camera_provenance(value.camera_provenance, value.expected_camera_numbers)
    ai = _validate_ai_provenance(value.ai_provenance)
    annotations = _validate_annotation_provenance(value.annotation_provenance)
    if not value.rows:
        raise ArtifactValidationError("canonical artifact input rows must not be empty")

    normalized_rows = tuple(_normalize_slot(row) for row in value.rows)
    sorted_rows = tuple(sorted(normalized_rows, key=_logical_key))
    _validate_rows(
        sorted_rows,
        capture_id,
        sync_revision_id,
        value.capture_group_key,
        frozen_at,
        value.expected_camera_numbers,
        cameras,
        ai,
        annotations,
    )
    return sorted_rows


def logical_content_sha256(rows: Iterable[FrameSlot | Mapping[str, object]]) -> str:
    """Hash canonical logical rows, independent of input order or Parquet layout."""

    parsed = tuple(
        _normalize_slot(row if isinstance(row, FrameSlot) else FrameSlot.from_mapping(row))
        for row in rows
    )
    if not parsed:
        raise ArtifactValidationError("cannot hash an empty canonical artifact")
    ordered = tuple(sorted(parsed, key=_logical_key))
    keys = tuple(_logical_key(row) for row in ordered)
    if len(keys) != len(set(keys)):
        raise ArtifactValidationError("duplicate canonical logical key")
    digest = hashlib.sha256()
    for row in ordered:
        digest.update(_canonical_json(_logical_payload(row)))
        digest.update(b"\n")
    return digest.hexdigest()


class CanonicalArtifactWriter:
    """Validate and write one local canonical synchronized-frame artifact."""

    def __init__(self, options: ParquetOptions | None = None) -> None:
        self.options = options or ParquetOptions()
        _validate_options(self.options)

    def write(
        self,
        value: CanonicalArtifactInput,
        output_path: str | Path,
        *,
        manifest_path: str | Path | None = None,
    ) -> ArtifactResult:
        rows = validate_artifact_input(value)
        parquet_path = Path(output_path)
        sidecar_path = (
            Path(manifest_path)
            if manifest_path is not None
            else parquet_path.with_suffix(".manifest.json")
        )
        if parquet_path.resolve() == sidecar_path.resolve():
            raise ArtifactValidationError("Parquet and manifest outputs must use distinct paths")
        _ensure_new_output(parquet_path)
        _ensure_new_output(sidecar_path)
        parquet_path.parent.mkdir(parents=True, exist_ok=True)
        sidecar_path.parent.mkdir(parents=True, exist_ok=True)

        try:
            logical_digest = logical_content_sha256(rows)
            schema = _schema_with_metadata(self.options, value, logical_digest)
            table = pa.Table.from_pylist([_arrow_payload(row) for row in rows], schema=schema)
            row_group_size = self.options.row_group_sync_frames * len(value.expected_camera_numbers)
            pq.write_table(
                table,
                parquet_path,
                compression=self.options.compression,
                compression_level=self.options.compression_level,
                use_dictionary=list(self.options.use_dictionary),
                row_group_size=row_group_size,
                write_statistics=True,
                data_page_version=self.options.data_page_version,
            )

            artifact_digest = _sha256_file(parquet_path)
            parquet_file = pq.ParquetFile(parquet_path)
            try:
                row_group_count = parquet_file.metadata.num_row_groups
            finally:
                parquet_file.close()
            manifest = _build_manifest(
                value,
                rows,
                self.options,
                logical_digest,
                artifact_digest,
                parquet_path.stat().st_size,
                row_group_count,
            )
            manifest_digest = _sha256_json(
                {key: item for key, item in manifest.items() if key != "artifact_manifest_sha256"}
            )
            manifest["artifact_manifest_sha256"] = manifest_digest
            _write_json(sidecar_path, manifest)
        except Exception:
            sidecar_path.unlink(missing_ok=True)
            parquet_path.unlink(missing_ok=True)
            raise
        return ArtifactResult(
            parquet_path=parquet_path,
            manifest_path=sidecar_path,
            row_count=len(rows),
            sync_frame_count=len({row.sync_frame_index for row in rows}),
            logical_content_sha256=logical_digest,
            artifact_sha256=artifact_digest,
            artifact_manifest_sha256=manifest_digest,
            manifest=manifest,
        )


def write_artifact(
    value: CanonicalArtifactInput,
    output_path: str | Path,
    *,
    options: ParquetOptions | None = None,
    manifest_path: str | Path | None = None,
) -> ArtifactResult:
    """Functional convenience wrapper around :class:`CanonicalArtifactWriter`."""

    return CanonicalArtifactWriter(options).write(value, output_path, manifest_path=manifest_path)


def inspect_artifact(path: str | Path) -> dict[str, object]:
    """Return non-business inspection facts useful to tests and local diagnostics."""

    parquet_file = pq.ParquetFile(path)
    schema = parquet_file.schema_arrow
    metadata = schema.metadata or {}
    return {
        "arrow_schema": [
            {"name": field.name, "type": str(field.type), "nullable": field.nullable}
            for field in schema
        ],
        "metadata": {
            key.decode("utf-8"): value.decode("utf-8") for key, value in sorted(metadata.items())
        },
        "row_group_count": parquet_file.metadata.num_row_groups,
        "row_count": parquet_file.metadata.num_rows,
        "created_by": parquet_file.metadata.created_by,
    }


def _validate_rows(
    rows: tuple[FrameSlot, ...],
    capture_id: str,
    sync_revision_id: str,
    capture_group_key: str | None,
    frozen_at: datetime,
    expected_camera_numbers: tuple[int, ...],
    cameras: dict[int, CameraProvenance],
    ai: dict[str, AIProvenance],
    annotations: dict[str, AnnotationProvenance],
) -> None:
    camera_count = len(expected_camera_numbers)
    if len(rows) % camera_count != 0:
        raise ArtifactValidationError(
            "each sync frame must contain exactly one row for every expected camera"
        )
    sync_frame_count = len(rows) // camera_count
    source_frames = {
        camera_number: {
            (frame.source_frame_index, frame.source_pts_ordinal): frame
            for frame in camera.source_frames
        }
        for camera_number, camera in cameras.items()
    }
    frame_timestamps: dict[int, int] = {}
    for position, row in enumerate(rows):
        _validate_slot_basics(row)
        if row.capture_id != capture_id or row.sync_revision_id != sync_revision_id:
            raise ArtifactValidationError("capture_id and sync_revision_id must not be mixed")
        if row.capture_group_key != capture_group_key:
            raise ArtifactValidationError("capture_group_key must be stable across rows")
        if _utc_datetime(row.artifact_created_at, "artifact_created_at") != frozen_at:
            raise ArtifactValidationError(
                "artifact_created_at must equal the frozen artifact timestamp on every row"
            )
        expected_frame_index = position // camera_count
        expected_camera_number = expected_camera_numbers[position % camera_count]
        if (
            row.sync_frame_index != expected_frame_index
            or row.camera_number != expected_camera_number
            or expected_frame_index >= sync_frame_count
        ):
            raise ArtifactValidationError(
                "each sync frame must contain exactly one row for every expected camera"
            )
        prior_timestamp = frame_timestamps.setdefault(
            row.sync_frame_index, row.capture_timestamp_ns
        )
        if prior_timestamp != row.capture_timestamp_ns:
            raise ArtifactValidationError(
                "all camera rows in a sync frame must share capture_timestamp_ns"
            )
        camera = cameras[row.camera_number]
        _validate_camera_identity(row, camera)
        _validate_frame_state(row, camera, source_frames[row.camera_number])
        _validate_ai_state(row, ai)
        if row.annotation_revision_id is not None and row.annotation_revision_id not in annotations:
            raise ArtifactValidationError(
                "annotation_revision_id has no matching annotation provenance"
            )


def _validate_slot_basics(row: FrameSlot) -> None:
    if row.schema_version != SCHEMA_VERSION:
        raise ArtifactValidationError("unsupported synchronized frame schema version")
    _uuid(row.capture_id, "capture_id")
    _uuid(row.sync_revision_id, "sync_revision_id")
    if row.sync_frame_index < 0:
        raise ArtifactValidationError("sync_frame_index must be non-negative")
    if row.capture_timestamp_ns < 0:
        raise ArtifactValidationError("capture_timestamp_ns must be non-negative")
    if not 1 <= row.camera_number <= 64:
        raise ArtifactValidationError("camera_number must be between 1 and 64")
    if row.camera_stream_id is not None:
        _uuid(row.camera_stream_id, "camera_stream_id")
    if row.video_id is not None:
        _uuid(row.video_id, "video_id")
    if row.source_frame_index is not None and row.source_frame_index < 0:
        raise ArtifactValidationError("source_frame_index must be non-negative")
    if row.source_pts_ordinal is not None and row.source_pts_ordinal < 0:
        raise ArtifactValidationError("source_pts_ordinal must be non-negative")
    if row.frame_state not in {"PRESENT", "MISSING", "INVALID"}:
        raise ArtifactValidationError("frame_state is invalid")
    if row.hand_detection_state not in {
        "HANDS_DETECTED",
        "NO_DETECTION",
        "FAILED",
        "NOT_RUN",
    }:
        raise ArtifactValidationError("hand_detection_state is invalid")
    if not 0 <= row.candidate_count <= 2:
        raise ArtifactValidationError("candidate_count must be between zero and two")
    if (
        row.frame_reason_code is not None
        and _REASON_PATTERN.fullmatch(row.frame_reason_code) is None
    ):
        raise ArtifactValidationError("frame_reason_code is invalid")
    if row.candidate_artifact_ref is not None:
        _validate_logical_reference(row.candidate_artifact_ref, "candidate_artifact_ref")
    _utc_datetime(row.artifact_created_at, "artifact_created_at")
    for digest, field_name in (
        (row.model_sha256, "model_sha256"),
        (row.profile_sha256, "profile_sha256"),
    ):
        _validate_digest(digest, field_name, optional=True)


def _validate_frame_state(
    row: FrameSlot,
    camera: CameraProvenance,
    source_frames: Mapping[tuple[int, int], SourceFrameProvenance],
) -> None:
    source_values = (row.source_frame_index, row.source_pts_ns, row.source_pts_ordinal)
    has_source = any(value is not None for value in source_values)
    all_source = all(value is not None for value in source_values)
    if has_source and not all_source:
        raise ArtifactValidationError("source frame provenance fields must be all null or all set")
    if row.frame_state == "PRESENT":
        if camera.camera_stream_id is None or not all_source:
            raise ArtifactValidationError("PRESENT must trace camera, source frame and PTS")
        if row.frame_reason_code is not None or row.sync_error_ns is None:
            raise ArtifactValidationError("PRESENT requires null reason and non-null sync error")
        frame = _source_frame(
            source_frames,
            cast(int, row.source_frame_index),
            cast(int, row.source_pts_ordinal),
        )
        if frame.source_pts_ns != row.source_pts_ns:
            raise ArtifactValidationError("PRESENT source_pts_ns disagrees with source provenance")
    elif row.frame_state == "MISSING":
        if has_source or row.sync_error_ns is not None:
            raise ArtifactValidationError("MISSING must not carry source frame or sync error")
        if row.frame_reason_code is None or row.hand_detection_state != "NOT_RUN":
            raise ArtifactValidationError("MISSING requires reason and NOT_RUN")
    else:
        if camera.camera_stream_id is None or row.frame_reason_code is None:
            raise ArtifactValidationError("INVALID requires a camera source and stable reason")
        if row.sync_error_ns is not None or row.hand_detection_state != "NOT_RUN":
            raise ArtifactValidationError("INVALID requires null sync error and NOT_RUN")
        if has_source:
            _source_frame(
                source_frames,
                cast(int, row.source_frame_index),
                cast(int, row.source_pts_ordinal),
            )


def _validate_ai_state(row: FrameSlot, ai: dict[str, AIProvenance]) -> None:
    state = row.hand_detection_state
    if state == "NOT_RUN":
        if (
            any(
                value is not None
                for value in (
                    row.ai_job_id,
                    row.candidate_artifact_ref,
                    row.model_sha256,
                    row.profile_sha256,
                )
            )
            or row.candidate_count != 0
        ):
            raise ArtifactValidationError("NOT_RUN must not carry AI provenance or candidates")
        return
    if state == "FAILED" and row.ai_job_id is None:
        if (
            any(
                value is not None
                for value in (row.candidate_artifact_ref, row.model_sha256, row.profile_sha256)
            )
            or row.candidate_count != 0
        ):
            raise ArtifactValidationError("FAILED without AI provenance must not carry AI output")
        return
    if row.ai_job_id is None or row.ai_job_id not in ai:
        raise ArtifactValidationError(f"{state} requires AI job and attempt provenance")
    provenance = ai[row.ai_job_id]
    if (
        row.model_sha256 != provenance.model_sha256
        or row.profile_sha256 != provenance.profile_sha256
    ):
        raise ArtifactValidationError("row AI digests must match AI provenance")
    if state == "NO_DETECTION":
        if row.candidate_count != 0 or row.candidate_artifact_ref is not None:
            raise ArtifactValidationError("NO_DETECTION must not carry candidates")
    elif state == "HANDS_DETECTED":
        if row.candidate_count < 1 or row.candidate_artifact_ref is None:
            raise ArtifactValidationError(
                "HANDS_DETECTED requires at least one candidate reference"
            )
    elif state == "FAILED":
        if row.candidate_count != 0 or row.candidate_artifact_ref is not None:
            raise ArtifactValidationError("FAILED must not carry accepted candidates")


def _validate_camera_identity(row: FrameSlot, camera: CameraProvenance) -> None:
    if row.camera_number != camera.camera_number:
        raise ArtifactValidationError("camera provenance key mismatch")
    if camera.camera_stream_id is None or camera.video_id is None:
        if row.camera_stream_id is not None or row.video_id is not None:
            raise ArtifactValidationError("a missing camera cannot acquire a new identity")
    elif row.camera_stream_id != camera.camera_stream_id or row.video_id != camera.video_id:
        raise ArtifactValidationError("row camera/video identity disagrees with provenance")
    if row.frame_state == "INVALID" and (row.camera_stream_id is None or row.video_id is None):
        raise ArtifactValidationError("INVALID requires camera stream and video identity")


def _source_frame(
    source_frames: Mapping[tuple[int, int], SourceFrameProvenance],
    source_frame_index: int,
    source_pts_ordinal: int,
) -> SourceFrameProvenance:
    frame = source_frames.get((source_frame_index, source_pts_ordinal))
    if frame is None:
        raise ArtifactValidationError("row source frame is absent or ambiguous in provenance")
    return frame


def _validate_expected_cameras(numbers: Sequence[int]) -> None:
    if not numbers:
        raise ArtifactValidationError("expected camera set must not be empty")
    if tuple(numbers) != tuple(sorted(set(numbers))):
        raise ArtifactValidationError("expected camera numbers must be sorted and unique")
    for number in numbers:
        if isinstance(number, bool) or not isinstance(number, int) or not 1 <= number <= 64:
            raise ArtifactValidationError("expected camera number must be between 1 and 64")


def _validate_capture_provenance(value: CaptureProvenance, capture_id: str) -> None:
    if _uuid(value.capture_session_id, "capture_session_id") != capture_id:
        raise ArtifactValidationError("capture provenance does not match capture_id")
    if (value.camera_set_snapshot_id is None) != (value.camera_set_snapshot_sha256 is None):
        raise ArtifactValidationError("camera snapshot ID and digest must be supplied together")
    if value.camera_set_snapshot_id is not None:
        _uuid(value.camera_set_snapshot_id, "camera_set_snapshot_id")
        _validate_digest(value.camera_set_snapshot_sha256, "camera_set_snapshot_sha256")


def _validate_camera_provenance(
    values: Sequence[CameraProvenance], expected: Sequence[int]
) -> dict[int, CameraProvenance]:
    cameras: dict[int, CameraProvenance] = {}
    for value in values:
        if value.camera_number in cameras:
            raise ArtifactValidationError("duplicate camera provenance")
        if value.camera_number not in expected or value.camera_index + 1 != value.camera_number:
            raise ArtifactValidationError("camera_number must equal camera_index + 1")
        if not 0 <= value.camera_index <= 63:
            raise ArtifactValidationError("camera_index must be between zero and 63")
        _uuid(value.camera_set_snapshot_member_id, "camera_set_snapshot_member_id")
        actual = (value.camera_stream_id is not None, value.video_id is not None)
        if actual[0] != actual[1]:
            raise ArtifactValidationError("camera stream and video identity must be paired")
        _optional_uuid(value.camera_stream_id, "camera_stream_id")
        _optional_uuid(value.video_id, "video_id")
        _validate_digest(value.original_mp4_sha256, "original_mp4_sha256", optional=True)
        _validate_digest(value.source_time_base_digest, "source_time_base_digest", optional=True)
        if value.source_input_size_bytes is not None and value.source_input_size_bytes < 0:
            raise ArtifactValidationError("source_input_size_bytes must be non-negative")
        _optional_uuid(value.source_media_report_id, "source_media_report_id")
        _validate_time_mapping(value.time_mapping)
        if value.camera_stream_id is None:
            if (
                any(
                    item is not None
                    for item in (
                        value.original_mp4_sha256,
                        value.time_base_num,
                        value.time_base_den,
                        value.pts_origin,
                        value.source_input_size_bytes,
                        value.source_media_report_id,
                        value.source_time_base_digest,
                        value.time_mapping,
                    )
                )
                or value.source_frames
            ):
                raise ArtifactValidationError(
                    "a missing camera cannot carry source media provenance"
                )
        else:
            if value.original_mp4_sha256 is None:
                raise ArtifactValidationError(
                    "CameraStream provenance requires original MP4 SHA-256"
                )
            if (
                value.time_base_num is None
                or value.time_base_den is None
                or value.time_base_num <= 0
                or value.time_base_den <= 0
                or value.pts_origin is None
            ):
                raise ArtifactValidationError("CameraStream provenance requires source time basis")
            if value.time_mapping is None:
                raise ArtifactValidationError(
                    "CameraStream provenance requires stream-to-Capture mapping"
                )
        _validate_source_frames(value.source_frames)
        cameras[value.camera_number] = value
    if set(cameras) != set(expected):
        raise ArtifactValidationError("camera provenance must cover every expected camera")
    return cameras


def _validate_source_frames(values: Sequence[SourceFrameProvenance]) -> None:
    keys = [(item.source_frame_index, item.source_pts_ordinal) for item in values]
    if len(keys) != len(set(keys)):
        raise ArtifactValidationError("duplicate source frame provenance")
    indices = [item.source_frame_index for item in values]
    if any(index < 0 for index in indices):
        raise ArtifactValidationError("source frame index must be non-negative")
    if any(item.source_pts_ordinal < 0 for item in values):
        raise ArtifactValidationError("source PTS ordinal must be non-negative")
    if len(indices) != len(set(indices)):
        raise ArtifactValidationError("source frame index must identify one source frame")


def _validate_ai_provenance(values: Sequence[AIProvenance]) -> dict[str, AIProvenance]:
    result: dict[str, AIProvenance] = {}
    for value in values:
        _uuid(value.ai_job_id, "ai_job_id")
        _uuid(value.attempt_id, "ai_attempt_id")
        if value.attempt_number < 1:
            raise ArtifactValidationError("AI attempt_number must be positive")
        _validate_digest(value.model_sha256, "model_sha256")
        _validate_digest(value.profile_sha256, "profile_sha256")
        if value.ai_job_id in result:
            raise ArtifactValidationError("duplicate AI job provenance")
        result[value.ai_job_id] = value
    return result


def _validate_annotation_provenance(
    values: Sequence[AnnotationProvenance],
) -> dict[str, AnnotationProvenance]:
    result: dict[str, AnnotationProvenance] = {}
    for value in values:
        _uuid(value.annotation_revision_id, "annotation_revision_id")
        _optional_uuid(value.annotation_task_id, "annotation_task_id")
        _optional_uuid(value.review_id, "review_id")
        _optional_uuid(value.cleaning_run_id, "cleaning_run_id")
        _validate_digest(value.label_schema_digest, "label_schema_digest", optional=True)
        if value.annotation_revision_id in result:
            raise ArtifactValidationError("duplicate annotation provenance")
        result[value.annotation_revision_id] = value
    return result


def _validate_profile(value: SyncProfileReference) -> None:
    if _PROFILE_KEY_PATTERN.fullmatch(value.key) is None:
        raise ArtifactValidationError("sync profile key is invalid")
    if _PROFILE_VERSION_PATTERN.fullmatch(value.version) is None:
        raise ArtifactValidationError("sync profile version is invalid")
    _validate_digest(value.digest, "sync_profile.digest")


def _validate_time_mapping(value: TimeMappingProvenance | None) -> None:
    if value is None:
        return
    if value.anchor_source_ns < 0 or value.anchor_capture_ns < 0:
        raise ArtifactValidationError("time mapping anchors must be non-negative")
    if value.drift_ppm is not None:
        _bounded_non_empty(value.drift_ppm, "drift_ppm", 128)
        if value.anchor_count < 2:
            raise ArtifactValidationError("drift_ppm requires at least two anchors")
        try:
            drift = Decimal(value.drift_ppm)
        except InvalidOperation as exc:
            raise ArtifactValidationError("drift_ppm must be a finite decimal") from exc
        if not drift.is_finite() or drift <= Decimal("-1000000"):
            raise ArtifactValidationError("time mapping scale must be positive")
    _bounded_non_empty(value.method, "time_mapping.method", 128)
    _bounded_non_empty(value.source, "time_mapping.source", 128)
    if value.anchor_count < 1:
        raise ArtifactValidationError("time mapping anchor_count must be positive")


def _validate_options(value: ParquetOptions) -> None:
    if value.compression not in {None, "snappy", "gzip", "brotli", "lz4", "zstd"}:
        raise ArtifactValidationError("unsupported Parquet compression")
    if value.compression_level is not None and value.compression_level < 0:
        raise ArtifactValidationError("compression_level must be non-negative")
    if value.compression is None and value.compression_level is not None:
        raise ArtifactValidationError("compression_level must be null when compression is disabled")
    if value.row_group_sync_frames < 1:
        raise ArtifactValidationError("row_group_sync_frames must be positive")
    if value.data_page_version not in {"1.0", "2.0"}:
        raise ArtifactValidationError("data_page_version must be 1.0 or 2.0")
    unknown = set(value.use_dictionary) - set(LOGICAL_FIELDS)
    if unknown:
        raise ArtifactValidationError("dictionary fields must belong to the logical schema")
    if value.production_default:
        raise ArtifactValidationError(
            "production Parquet layout is not approved; production_default must remain false"
        )


def _schema_with_metadata(
    options: ParquetOptions,
    value: CanonicalArtifactInput,
    logical_digest: str,
) -> pa.Schema:
    metadata = {
        b"schema_version": SCHEMA_VERSION.encode("ascii"),
        b"sort_order": ",".join(SORT_ORDER).encode("ascii"),
        b"sync_profile_key": value.sync_profile.key.encode("utf-8"),
        b"sync_profile_version": value.sync_profile.version.encode("utf-8"),
        b"sync_profile_sha256": value.sync_profile.digest.encode("ascii"),
        b"input_manifest_sha256": (value.input_manifest_sha256 or "").encode("ascii"),
        b"logical_content_sha256": logical_digest.encode("ascii"),
        b"writer_name": WRITER_NAME.encode("utf-8"),
        b"writer_version": WRITER_VERSION.encode("ascii"),
        b"physical_layout_status": NON_PRODUCTION_LAYOUT_STATUS.encode("ascii"),
        b"production_default": str(options.production_default).lower().encode("ascii"),
    }
    return canonical_schema().with_metadata(metadata)


def _build_manifest(
    value: CanonicalArtifactInput,
    rows: tuple[FrameSlot, ...],
    options: ParquetOptions,
    logical_digest: str,
    artifact_digest: str,
    size_bytes: int,
    row_group_count: int,
) -> dict[str, object]:
    frame_states = Counter(row.frame_state for row in rows)
    hand_states = Counter(row.hand_detection_state for row in rows)
    per_camera: dict[str, object] = {}
    for camera_number in value.expected_camera_numbers:
        camera_rows = [row for row in rows if row.camera_number == camera_number]
        per_camera[str(camera_number)] = {
            "frame_states": dict(sorted(Counter(row.frame_state for row in camera_rows).items())),
            "hand_detection_states": dict(
                sorted(Counter(row.hand_detection_state for row in camera_rows).items())
            ),
            "row_count": len(camera_rows),
        }
    errors = sorted(abs(row.sync_error_ns) for row in rows if row.sync_error_ns is not None)
    manifest: dict[str, object] = {
        "manifest_schema_version": MANIFEST_SCHEMA_VERSION,
        "schema_version": SCHEMA_VERSION,
        "artifact_role": "SYNCHRONIZED_FRAME_PARQUET",
        "capture_id": value.capture_id,
        "capture_group_key": value.capture_group_key,
        "sync_revision_id": value.sync_revision_id,
        "sync_profile": {
            "key": value.sync_profile.key,
            "version": value.sync_profile.version,
            "digest": value.sync_profile.digest,
        },
        "input_manifest_sha256": value.input_manifest_sha256,
        "row_count": len(rows),
        "sync_frame_count": len({row.sync_frame_index for row in rows}),
        "expected_camera_count": len(value.expected_camera_numbers),
        "expected_camera_numbers": list(value.expected_camera_numbers),
        "state_summary": {
            "frame_states": dict(sorted(frame_states.items())),
            "hand_detection_states": dict(sorted(hand_states.items())),
            "per_camera": per_camera,
            "sync_error_abs_ns": {
                "sample_count": len(errors),
                "p50": _nearest_rank(errors, 0.50),
                "p95": _nearest_rank(errors, 0.95),
                "max": errors[-1] if errors else None,
            },
        },
        "sort_order": list(SORT_ORDER),
        "writer": {
            "name": WRITER_NAME,
            "version": WRITER_VERSION,
            "runtime": {
                "python": platform.python_version(),
                "pyarrow": pa.__version__,
            },
        },
        "parquet_physical_properties": {
            "format": "parquet",
            "compression": options.compression,
            "compression_level": options.compression_level,
            "row_group_sync_frames": options.row_group_sync_frames,
            "row_group_size_rows": options.row_group_sync_frames
            * len(value.expected_camera_numbers),
            "row_group_count": row_group_count,
            "data_page_version": options.data_page_version,
            "dictionary_fields": list(options.use_dictionary),
            "partitioning": "none",
            "production_default": options.production_default,
            "layout_status": NON_PRODUCTION_LAYOUT_STATUS,
        },
        "logical_content_sha256": logical_digest,
        "artifact_sha256": artifact_digest,
        "artifact_size_bytes": size_bytes,
        "frozen_at": _format_datetime(value.frozen_at),
        "created_at": _format_datetime(value.frozen_at),
        "provenance": _manifest_provenance(value),
        "artifact_manifest_sha256": None,
    }
    return manifest


def _manifest_provenance(value: CanonicalArtifactInput) -> dict[str, object]:
    cameras: list[dict[str, object]] = []
    for camera in sorted(value.camera_provenance, key=lambda item: item.camera_number):
        cameras.append(
            {
                "camera_number": camera.camera_number,
                "camera_index": camera.camera_index,
                "camera_set_snapshot_member_id": camera.camera_set_snapshot_member_id,
                "camera_stream_id": camera.camera_stream_id,
                "video_id": camera.video_id,
                "original_mp4_sha256": camera.original_mp4_sha256,
                "source_input_size_bytes": camera.source_input_size_bytes,
                "source_media_report_id": camera.source_media_report_id,
                "source_time_base_digest": camera.source_time_base_digest,
                "time_base": {
                    "numerator": camera.time_base_num,
                    "denominator": camera.time_base_den,
                },
                "pts_origin": camera.pts_origin,
                "time_mapping": (
                    None
                    if camera.time_mapping is None
                    else {
                        "anchor_source_ns": camera.time_mapping.anchor_source_ns,
                        "anchor_capture_ns": camera.time_mapping.anchor_capture_ns,
                        "drift_ppm": camera.time_mapping.drift_ppm,
                        "method": camera.time_mapping.method,
                        "source": camera.time_mapping.source,
                        "anchor_count": camera.time_mapping.anchor_count,
                    }
                ),
                "source_frames": [
                    {
                        "source_frame_index": frame.source_frame_index,
                        "raw_pts": frame.raw_pts,
                        "source_pts_ns": frame.source_pts_ns,
                        "source_pts_ordinal": frame.source_pts_ordinal,
                    }
                    for frame in sorted(
                        camera.source_frames,
                        key=lambda item: (item.source_frame_index, item.source_pts_ordinal),
                    )
                ],
            }
        )
    return {
        "capture": {
            "capture_session_id": value.capture_provenance.capture_session_id,
            "camera_set_snapshot_id": value.capture_provenance.camera_set_snapshot_id,
            "camera_set_snapshot_sha256": value.capture_provenance.camera_set_snapshot_sha256,
        },
        "cameras": cameras,
        "ai": [
            {
                "ai_job_id": item.ai_job_id,
                "attempt_id": item.attempt_id,
                "attempt_number": item.attempt_number,
                "model_sha256": item.model_sha256,
                "profile_sha256": item.profile_sha256,
                "sampling_rule": item.sampling_rule,
            }
            for item in sorted(value.ai_provenance, key=lambda item: item.ai_job_id)
        ],
        "annotations": [
            {
                "annotation_revision_id": item.annotation_revision_id,
                "annotation_task_id": item.annotation_task_id,
                "review_id": item.review_id,
                "cleaning_run_id": item.cleaning_run_id,
                "label_schema_digest": item.label_schema_digest,
            }
            for item in sorted(
                value.annotation_provenance,
                key=lambda item: item.annotation_revision_id,
            )
        ],
    }


def _arrow_payload(row: FrameSlot) -> dict[str, object]:
    return {
        "schema_version": row.schema_version,
        "capture_id": row.capture_id,
        "capture_group_key": row.capture_group_key,
        "sync_revision_id": row.sync_revision_id,
        "sync_frame_index": row.sync_frame_index,
        "capture_timestamp_ns": row.capture_timestamp_ns,
        "camera_number": row.camera_number,
        "camera_stream_id": row.camera_stream_id,
        "video_id": row.video_id,
        "source_frame_index": row.source_frame_index,
        "source_pts_ns": row.source_pts_ns,
        "source_pts_ordinal": row.source_pts_ordinal,
        "frame_state": row.frame_state,
        "frame_reason_code": row.frame_reason_code,
        "sync_error_ns": row.sync_error_ns,
        "hand_detection_state": row.hand_detection_state,
        "ai_job_id": row.ai_job_id,
        "candidate_count": row.candidate_count,
        "candidate_artifact_ref": row.candidate_artifact_ref,
        "annotation_revision_id": row.annotation_revision_id,
        "model_sha256": bytes.fromhex(row.model_sha256) if row.model_sha256 else None,
        "profile_sha256": bytes.fromhex(row.profile_sha256) if row.profile_sha256 else None,
        "artifact_created_at": _utc_datetime(row.artifact_created_at, "artifact_created_at"),
    }


def _logical_payload(row: FrameSlot) -> dict[str, object]:
    return {
        "schema_version": row.schema_version,
        "capture_id": row.capture_id,
        "capture_group_key": row.capture_group_key,
        "sync_revision_id": row.sync_revision_id,
        "sync_frame_index": row.sync_frame_index,
        "capture_timestamp_ns": row.capture_timestamp_ns,
        "camera_number": row.camera_number,
        "camera_stream_id": row.camera_stream_id,
        "video_id": row.video_id,
        "source_frame_index": row.source_frame_index,
        "source_pts_ns": row.source_pts_ns,
        "source_pts_ordinal": row.source_pts_ordinal,
        "frame_state": row.frame_state,
        "frame_reason_code": row.frame_reason_code,
        "sync_error_ns": row.sync_error_ns,
        "hand_detection_state": row.hand_detection_state,
        "ai_job_id": row.ai_job_id,
        "candidate_count": row.candidate_count,
        "candidate_artifact_ref": row.candidate_artifact_ref,
        "annotation_revision_id": row.annotation_revision_id,
        "model_sha256": row.model_sha256,
        "profile_sha256": row.profile_sha256,
        "artifact_created_at": _format_datetime(row.artifact_created_at),
    }


def _normalize_slot(row: FrameSlot) -> FrameSlot:
    if not isinstance(row, FrameSlot):
        raise ArtifactValidationError("rows must be FrameSlot values or mappings")
    return FrameSlot(
        schema_version=row.schema_version,
        capture_id=row.capture_id,
        capture_group_key=row.capture_group_key,
        sync_revision_id=row.sync_revision_id,
        sync_frame_index=row.sync_frame_index,
        capture_timestamp_ns=row.capture_timestamp_ns,
        camera_number=row.camera_number,
        camera_stream_id=row.camera_stream_id,
        video_id=row.video_id,
        source_frame_index=row.source_frame_index,
        source_pts_ns=row.source_pts_ns,
        source_pts_ordinal=row.source_pts_ordinal,
        frame_state=row.frame_state,
        frame_reason_code=row.frame_reason_code,
        sync_error_ns=row.sync_error_ns,
        hand_detection_state=row.hand_detection_state,
        ai_job_id=row.ai_job_id,
        candidate_count=row.candidate_count,
        candidate_artifact_ref=row.candidate_artifact_ref,
        annotation_revision_id=row.annotation_revision_id,
        model_sha256=_optional_digest(row.model_sha256, "model_sha256"),
        profile_sha256=_optional_digest(row.profile_sha256, "profile_sha256"),
        artifact_created_at=_utc_datetime(row.artifact_created_at, "artifact_created_at"),
    )


def _logical_key(row: FrameSlot) -> tuple[str, str, int, int]:
    return (row.capture_id, row.sync_revision_id, row.sync_frame_index, row.camera_number)


def _uuid(value: object, field_name: str) -> str:
    if not isinstance(value, str) or _UUID_PATTERN.fullmatch(value) is None:
        raise ArtifactValidationError(f"{field_name} must be a canonical UUID")
    try:
        UUID(value)
    except ValueError as exc:  # pragma: no cover - regex already excludes malformed UUIDs
        raise ArtifactValidationError(f"{field_name} must be a canonical UUID") from exc
    return value


def _optional_uuid(value: str | None, field_name: str) -> None:
    if value is not None:
        _uuid(value, field_name)


def _validate_digest(value: str | None, field_name: str, *, optional: bool = False) -> None:
    if value is None and optional:
        return
    if value is None or _DIGEST_PATTERN.fullmatch(value) is None:
        raise ArtifactValidationError(f"{field_name} must be lowercase SHA-256 hex")


def _optional_digest(value: object, field_name: str) -> str | None:
    if value is None:
        return None
    if isinstance(value, bytes):
        if len(value) != 32:
            raise ArtifactValidationError(f"{field_name} must be a 32-byte SHA-256 digest")
        result = value.hex()
    elif isinstance(value, str):
        result = value
    else:
        raise ArtifactValidationError(f"{field_name} must be a SHA-256 digest")
    _validate_digest(result, field_name)
    return result


def _string(value: object, field_name: str) -> str:
    if not isinstance(value, str):
        raise ArtifactValidationError(f"{field_name} must be a string")
    return value


def _optional_string(value: object, field_name: str) -> str | None:
    if value is None:
        return None
    return _string(value, field_name)


def _integer(value: object, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ArtifactValidationError(f"{field_name} must be an integer")
    return value


def _optional_integer(value: object, field_name: str) -> int | None:
    if value is None:
        return None
    return _integer(value, field_name)


def _parse_datetime(value: object, field_name: str) -> datetime:
    if isinstance(value, datetime):
        return _utc_datetime(value, field_name)
    if not isinstance(value, str):
        raise ArtifactValidationError(f"{field_name} must be an RFC3339 timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ArtifactValidationError(f"{field_name} must be an RFC3339 timestamp") from exc
    return _utc_datetime(parsed, field_name)


def _utc_datetime(value: datetime, field_name: str) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ArtifactValidationError(f"{field_name} must include a UTC offset")
    return value.astimezone(UTC).replace(tzinfo=UTC)


def _format_datetime(value: datetime) -> str:
    return (
        _utc_datetime(value, "datetime").isoformat(timespec="microseconds").replace("+00:00", "Z")
    )


def _bounded_non_empty(value: str, field_name: str, maximum: int) -> None:
    if not value or len(value) > maximum:
        raise ArtifactValidationError(f"{field_name} must contain 1 to {maximum} characters")


def _validate_logical_reference(value: str, field_name: str) -> None:
    _bounded_non_empty(value, field_name, 512)
    lowered = value.lower()
    if (
        "\n" in value
        or "\r" in value
        or "\\" in value
        or value.startswith("/")
        or "?" in value
        or "#" in value
        or lowered.startswith(("http://", "https://", "s3://", "gs://", "file://"))
    ):
        raise ArtifactValidationError(f"{field_name} must be a logical reference, not a URL or key")


def _nearest_rank(values: list[int], quantile: float) -> int | None:
    if not values:
        return None
    index = max(0, min(len(values) - 1, int(quantile * len(values) + 0.999999999) - 1))
    return values[index]


def _canonical_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )


def _sha256_json(value: object) -> str:
    return hashlib.sha256(_canonical_json(value)).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _ensure_new_output(path: Path) -> None:
    if path.exists():
        raise FileExistsError(f"refusing to overwrite existing artifact output: {path}")


def _write_json(path: Path, value: Mapping[str, object]) -> None:
    encoded = _canonical_json(value) + b"\n"
    with path.open("xb") as target:
        target.write(encoded)
