from __future__ import annotations

import csv
import io
import math

METADATA_COLUMNS = [
    "capture_group_id",
    "annotation_revision_id",
    "clip_asset_id",
    "clip_asset_sha256",
    "camera_number",
    "frame_timestamp_ms",
    "hand_index",
    "handedness",
    "handedness_score",
    "image_width",
    "image_height",
    "valid_landmark_count",
    "action_label",
    "segment_start_ms",
    "segment_end_ms",
    "ai_job_id",
    "ai_job_attempt_id",
    "algorithm_key",
    "algorithm_version",
    "model_sha256",
    "profile_sha256",
    "bounding_box_x",
    "bounding_box_y",
    "bounding_box_width",
    "bounding_box_height",
]
LANDMARK_COLUMNS = [f"landmark_{index:02d}_{axis}" for index in range(21) for axis in ("x", "y", "z")]
CSV_COLUMNS = [*METADATA_COLUMNS, *LANDMARK_COLUMNS]


def _cell(row: dict[str, str | None], column: str) -> str:
    value = row.get(column)
    assert value is not None
    return value


def _synthetic_csv() -> str:
    rows: list[dict[str, str]] = []
    for timestamp in (520, 100):
        row = {column: "" for column in CSV_COLUMNS}
        row.update(
            {
                "capture_group_id": "00000000-0000-0000-0000-000000000001",
                "annotation_revision_id": "00000000-0000-0000-0000-000000000002",
                "clip_asset_id": "00000000-0000-0000-0000-000000000003",
                "clip_asset_sha256": "a" * 64,
                "camera_number": "1",
                "frame_timestamp_ms": str(timestamp),
                "hand_index": "0",
                "handedness": "LEFT",
                "handedness_score": "0.95",
                "image_width": "1920",
                "image_height": "1080",
                "valid_landmark_count": "21",
                "action_label": "prepare_workspace",
                "segment_start_ms": "0",
                "segment_end_ms": "1000",
                "ai_job_id": "00000000-0000-0000-0000-000000000004",
                "ai_job_attempt_id": "00000000-0000-0000-0000-000000000005",
                "algorithm_key": "mediapipe-hand-landmarker",
                "algorithm_version": "0.1.0",
                "model_sha256": "b" * 64,
                "profile_sha256": "c" * 64,
                "bounding_box_x": "0.1",
                "bounding_box_y": "0.2",
                "bounding_box_width": "0.4",
                "bounding_box_height": "0.5",
            }
        )
        for index in range(21):
            row[f"landmark_{index:02d}_x"] = f"{0.1 + index / 1000:.6f}"
            row[f"landmark_{index:02d}_y"] = f"{0.2 + index / 1000:.6f}"
            row[f"landmark_{index:02d}_z"] = f"{-0.01 - index / 1000:.6f}"
        rows.append(row)

    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=CSV_COLUMNS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue()


def test_hand_landmark_csv_readback_preserves_ml_sample_shape() -> None:
    """Synthetic readback only; this is not model training or real UAT evidence."""
    reader = csv.DictReader(io.StringIO(_synthetic_csv()))

    assert reader.fieldnames == CSV_COLUMNS
    rows = list(reader)
    assert len(rows) == 2
    assert all(set(row) == set(CSV_COLUMNS) for row in rows)

    ordered_rows = sorted(rows, key=lambda row: int(_cell(row, "frame_timestamp_ms")))
    assert [int(_cell(row, "frame_timestamp_ms")) for row in ordered_rows] == [100, 520]
    assert {_cell(row, "capture_group_id") for row in rows} == {"00000000-0000-0000-0000-000000000001"}
    assert {_cell(row, "action_label") for row in rows} == {"prepare_workspace"}

    for row in rows:
        assert int(_cell(row, "valid_landmark_count")) == 21
        for index in range(21):
            for axis in ("x", "y", "z"):
                coordinate = float(_cell(row, f"landmark_{index:02d}_{axis}"))
                assert math.isfinite(coordinate)
