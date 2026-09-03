from __future__ import annotations

import csv
import sys
from pathlib import Path

import pytest

TOOL_ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(TOOL_ROOT))

import hand_landmark_csv_readback as readback  # noqa: E402


def _write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=readback.CSV_COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def _valid_row() -> dict[str, str]:
    row = {column: "value" for column in readback.CSV_COLUMNS}
    row.update(
        {
            "capture_group_id": "group-1",
            "camera_stream_id": "stream-1",
            "camera_number": "1",
            "clip_asset_id": "asset-1",
            "clip_asset_sha256": "a" * 64,
            "frame_timestamp_ms": "120",
            "hand_index": "0",
            "handedness": "LEFT",
            "valid_landmark_count": "21",
            "ai_job_id": "job-1",
            "ai_job_attempt_id": "attempt-1",
            "algorithm_key": "MEDIAPIPE_HAND_LANDMARKER",
            "algorithm_version": "v1",
            "model_sha256": "b" * 64,
            "profile_sha256": "c" * 64,
            "input_sha256": "a" * 64,
            "result_sha256": "d" * 64,
        }
    )
    for index in range(21):
        row[f"landmark_{index:02d}_x"] = str(index / 100)
        row[f"landmark_{index:02d}_y"] = str(index / 200)
        row[f"landmark_{index:02d}_z"] = str(-index / 300)
    return row


def test_readback_exposes_one_sample_as_21_by_3_numeric_structure(tmp_path: Path) -> None:
    path = tmp_path / "hand-landmarks.csv"
    _write_csv(path, [_valid_row()])

    result = readback.read_csv(path)

    assert result.row_count == 1
    assert len(result.landmarks) == 21
    assert all(len(point) == 3 for point in result.landmarks)
    assert len(result.flattened_landmarks) == 63
    assert result.landmarks[1] == (0.01, 0.005, -0.0033333333333333335)
    assert set(result.provenance) == set(readback.PROVENANCE_COLUMNS)


def test_readback_rejects_csv_without_data_rows(tmp_path: Path) -> None:
    path = tmp_path / "empty.csv"
    _write_csv(path, [])

    with pytest.raises(readback.CsvContractError, match="at least one data row"):
        readback.read_csv(path)


def test_readback_rejects_non_finite_coordinate(tmp_path: Path) -> None:
    row = _valid_row()
    row["landmark_07_z"] = "NaN"
    path = tmp_path / "invalid.csv"
    _write_csv(path, [row])

    with pytest.raises(readback.CsvContractError, match=r"landmark_07_z.*finite"):
        readback.read_csv(path)
