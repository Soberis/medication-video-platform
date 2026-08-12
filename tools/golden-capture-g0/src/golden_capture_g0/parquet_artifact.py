from __future__ import annotations

import hashlib
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, cast

import pyarrow as pa
import pyarrow.parquet as pq

from golden_capture_g0.model import FrameSlot
from golden_capture_g0.sync import logical_content_sha256


@dataclass(frozen=True, slots=True)
class WriterOptions:
    compression: str | None
    row_group_sync_frames: int
    compression_level: int | None = None


@dataclass(frozen=True, slots=True)
class ArtifactResult:
    path: Path
    size_bytes: int
    artifact_sha256: str
    logical_content_sha256: str
    row_group_count: int
    writer_options: WriterOptions


@dataclass(frozen=True, slots=True)
class BoundedReadResult:
    selected_row_groups: tuple[int, ...]
    rows_read: int
    rows_returned: int
    read_amplification: float
    keys: tuple[tuple[int, int], ...]


def canonical_schema() -> pa.Schema:
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


def write_artifact(
    slots: tuple[FrameSlot, ...],
    output_path: Path,
    *,
    input_manifest_sha256: str,
    sync_profile_sha256: str,
    options: WriterOptions,
) -> ArtifactResult:
    if not slots:
        raise ValueError("cannot write an empty canonical artifact")
    expected_camera_count = len({slot.camera_number for slot in slots})
    row_group_size = options.row_group_sync_frames * expected_camera_count
    if row_group_size <= 0:
        raise ValueError("row group size must be positive")
    logical_digest = logical_content_sha256(slots)
    metadata = {
        b"schema_version": b"synchronized-frame.v1",
        b"sort_order": (b"capture_id,sync_revision_id,sync_frame_index,camera_number"),
        b"input_manifest_sha256": input_manifest_sha256.encode("ascii"),
        b"sync_profile_sha256": sync_profile_sha256.encode("ascii"),
        b"logical_content_sha256": logical_digest.encode("ascii"),
        b"writer_scope": b"V1.19_G0_MEASUREMENT_ONLY",
    }
    schema = canonical_schema().with_metadata(metadata)
    table = pa.Table.from_pylist([asdict(slot) for slot in slots], schema=schema)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(
        table,
        output_path,
        compression=options.compression,
        compression_level=options.compression_level,
        use_dictionary=[
            "schema_version",
            "frame_state",
            "frame_reason_code",
            "hand_detection_state",
        ],
        row_group_size=row_group_size,
        write_statistics=True,
        data_page_version="1.0",
    )
    artifact_digest = _sha256_file(output_path)
    parquet_file = pq.ParquetFile(output_path)
    return ArtifactResult(
        path=output_path,
        size_bytes=output_path.stat().st_size,
        artifact_sha256=artifact_digest,
        logical_content_sha256=logical_digest,
        row_group_count=parquet_file.metadata.num_row_groups,
        writer_options=options,
    )


def bounded_read(
    path: Path,
    from_sync_frame_index: int,
    to_sync_frame_index_exclusive: int,
    camera_numbers: set[int] | None = None,
) -> BoundedReadResult:
    parquet_file = pq.ParquetFile(path)
    column_index = parquet_file.schema_arrow.get_field_index("sync_frame_index")
    selected: list[int] = []
    rows_read = 0
    for row_group_index in range(parquet_file.metadata.num_row_groups):
        row_group = parquet_file.metadata.row_group(row_group_index)
        statistics = row_group.column(column_index).statistics
        if statistics is None or not statistics.has_min_max:
            raise RuntimeError(
                "sync_frame_index row-group statistics are required for bounded reads"
            )
        minimum = int(statistics.min)
        maximum = int(statistics.max)
        if maximum >= from_sync_frame_index and minimum < to_sync_frame_index_exclusive:
            selected.append(row_group_index)
            rows_read += row_group.num_rows
    table = parquet_file.read_row_groups(selected)
    rows = cast(list[dict[str, Any]], table.to_pylist())
    filtered = [
        row
        for row in rows
        if from_sync_frame_index <= int(row["sync_frame_index"]) < to_sync_frame_index_exclusive
        and (camera_numbers is None or int(row["camera_number"]) in camera_numbers)
    ]
    keys = tuple((int(row["sync_frame_index"]), int(row["camera_number"])) for row in filtered)
    if keys != tuple(sorted(keys)):
        raise RuntimeError("bounded Parquet result is not stably sorted")
    rows_returned = len(filtered)
    amplification = rows_read / rows_returned if rows_returned else 0.0
    return BoundedReadResult(tuple(selected), rows_read, rows_returned, amplification, keys)


def inspect_artifact(path: Path) -> dict[str, Any]:
    parquet_file = pq.ParquetFile(path)
    schema = parquet_file.schema_arrow
    metadata = schema.metadata or {}
    return {
        "arrow_schema": [
            {
                "name": field.name,
                "type": str(field.type),
                "nullable": field.nullable,
            }
            for field in schema
        ],
        "metadata": {
            key.decode("utf-8"): value.decode("utf-8") for key, value in sorted(metadata.items())
        },
        "row_group_count": parquet_file.metadata.num_row_groups,
        "row_count": parquet_file.metadata.num_rows,
        "created_by": parquet_file.metadata.created_by,
    }


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
