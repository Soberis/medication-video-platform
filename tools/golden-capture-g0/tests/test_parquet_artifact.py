from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pyarrow.parquet as pq
from conftest import GoldenData

from golden_capture_g0.model import (
    EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL,
    REQUIRE_TRUSTED_STREAM_TO_CAPTURE_MAPPING,
    ROUND_HALF_EVEN,
    SyncProfile,
)
from golden_capture_g0.parquet_artifact import (
    WriterOptions,
    bounded_read,
    canonical_schema,
    inspect_artifact,
    write_artifact,
)
from golden_capture_g0.sync import (
    build_frame_slots,
    input_manifest_sha256,
    profile_sha256,
)

PROFILE = SyncProfile(
    Fraction(30),
    20_000_000,
    REQUIRE_TRUSTED_STREAM_TO_CAPTURE_MAPPING,
    ROUND_HALF_EVEN,
    EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL,
)


def test_canonical_parquet_has_slot_grain_states_and_stable_sort(
    golden_data: GoldenData,
    tmp_path: Path,
) -> None:
    capture = golden_data.captures["case_d_local_gap_and_invalid"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE)
    artifact = write_artifact(
        slots,
        tmp_path / "case-d.parquet",
        input_manifest_sha256=input_manifest_sha256(capture, streams),
        sync_profile_sha256=profile_sha256(PROFILE),
        options=WriterOptions("zstd", 15, 3),
    )
    rows = pq.read_table(artifact.path).to_pylist()
    keys = [
        (
            row["capture_id"],
            row["sync_revision_id"],
            row["sync_frame_index"],
            row["camera_number"],
        )
        for row in rows
    ]

    assert len(rows) == len(slots)
    assert len(keys) == len(set(keys))
    assert keys == sorted(keys)
    assert {row["frame_state"] for row in rows} == {"PRESENT", "MISSING", "INVALID"}
    assert {row["hand_detection_state"] for row in rows} >= {"NOT_RUN", "NO_DETECTION"}


def test_artifact_and_logical_digests_are_repeatable(
    golden_data: GoldenData, tmp_path: Path
) -> None:
    capture = golden_data.captures["case_b_missing_routes"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE)
    manifest_digest = input_manifest_sha256(capture, streams)
    sync_profile_digest = profile_sha256(PROFILE)
    options = WriterOptions("zstd", 15, 3)
    first = write_artifact(
        slots,
        tmp_path / "first.parquet",
        input_manifest_sha256=manifest_digest,
        sync_profile_sha256=sync_profile_digest,
        options=options,
    )
    second = write_artifact(
        slots,
        tmp_path / "second.parquet",
        input_manifest_sha256=manifest_digest,
        sync_profile_sha256=sync_profile_digest,
        options=options,
    )

    assert first.logical_content_sha256 == second.logical_content_sha256
    assert first.artifact_sha256 == second.artifact_sha256
    metadata = inspect_artifact(first.path)["metadata"]
    assert metadata["logical_content_sha256"] == first.logical_content_sha256
    assert metadata["input_manifest_sha256"] == manifest_digest


def test_bounded_read_uses_row_group_ranges(golden_data: GoldenData, tmp_path: Path) -> None:
    capture = golden_data.captures["case_b_missing_routes"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE)
    artifact = write_artifact(
        slots,
        tmp_path / "bounded.parquet",
        input_manifest_sha256=input_manifest_sha256(capture, streams),
        sync_profile_sha256=profile_sha256(PROFILE),
        options=WriterOptions("zstd", 5, 3),
    )
    result = bounded_read(artifact.path, 12, 17, {1, 3, 5})

    assert result.rows_returned == 15
    assert result.rows_read < len(slots)
    assert result.selected_row_groups == (2, 3)
    assert result.keys == tuple(sorted(result.keys))


def test_physical_schema_uses_explicit_integer_and_fixed_digest_types() -> None:
    schema = canonical_schema()

    assert str(schema.field("sync_frame_index").type) == "int64"
    assert str(schema.field("capture_timestamp_ns").type) == "int64"
    assert str(schema.field("camera_number").type) == "int32"
    assert str(schema.field("model_sha256").type) == "fixed_size_binary[32]"
    assert schema.field("annotation_revision_id").nullable
