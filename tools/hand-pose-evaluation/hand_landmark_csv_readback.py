#!/usr/bin/env python3
"""Read back a hand-landmarks.v1 CSV without training or media dependencies."""

from __future__ import annotations

import argparse
import csv
import math
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

IDENTITY_COLUMNS = (
    "capture_group_id",
    "camera_stream_id",
    "camera_number",
    "clip_asset_id",
    "clip_asset_sha256",
    "frame_timestamp_ms",
    "hand_index",
    "handedness",
)
PROVENANCE_COLUMNS = (
    "ai_job_id",
    "ai_job_attempt_id",
    "algorithm_key",
    "algorithm_version",
    "model_sha256",
    "profile_sha256",
    "input_sha256",
    "result_sha256",
)
LANDMARK_COLUMNS = tuple(f"landmark_{index:02d}_{axis}" for index in range(21) for axis in ("x", "y", "z"))
CSV_COLUMNS = (
    *IDENTITY_COLUMNS[:5],
    "candidate_annotation_id",
    *IDENTITY_COLUMNS[5:],
    "handedness_score",
    "image_width",
    "image_height",
    "valid_landmark_count",
    "bounding_box_x",
    "bounding_box_y",
    "bounding_box_width",
    "bounding_box_height",
    *LANDMARK_COLUMNS,
    "ai_job_id",
    "ai_job_attempt_id",
    "attempt",
    *PROVENANCE_COLUMNS[2:],
)
REQUIRED_COLUMNS = (
    *IDENTITY_COLUMNS,
    "valid_landmark_count",
    *LANDMARK_COLUMNS,
    *PROVENANCE_COLUMNS,
)


class CsvContractError(ValueError):
    """Raised when a hand-landmarks.v1 CSV cannot be consumed safely."""


@dataclass(frozen=True, slots=True)
class CsvReadback:
    row_count: int
    sample: dict[str, str]
    landmarks: tuple[tuple[float, float, float], ...]
    provenance: dict[str, str]

    @property
    def flattened_landmarks(self) -> tuple[float, ...]:
        return tuple(coordinate for point in self.landmarks for coordinate in point)


def read_csv(path: str | Path) -> CsvReadback:
    csv_path = Path(path)
    try:
        with csv_path.open("r", encoding="utf-8-sig", newline="") as source:
            reader = csv.DictReader(source, strict=True)
            fieldnames = reader.fieldnames
            if not fieldnames:
                raise CsvContractError("CSV header is missing")
            if any(not field for field in fieldnames):
                raise CsvContractError("CSV header contains an empty field name")
            if len(fieldnames) != len(set(fieldnames)):
                raise CsvContractError("CSV header contains duplicate field names")
            if tuple(fieldnames) != CSV_COLUMNS:
                raise CsvContractError("CSV header does not match the hand-landmarks.v1 contract")

            missing = [field for field in REQUIRED_COLUMNS if field not in fieldnames]
            if missing:
                raise CsvContractError("CSV is missing required fields: " + ", ".join(missing))
            landmark_fields = [field for field in fieldnames if field.startswith("landmark_")]
            if set(landmark_fields) != set(LANDMARK_COLUMNS) or len(landmark_fields) != 63:
                raise CsvContractError(
                    f"CSV must contain exactly 63 landmark coordinate fields; found {len(landmark_fields)}"
                )

            first_row: dict[str, str] | None = None
            first_landmarks: tuple[tuple[float, float, float], ...] | None = None
            first_provenance: dict[str, str] | None = None
            row_count = 0
            for row_number, row in enumerate(reader, start=2):
                if None in row:
                    raise CsvContractError(f"row {row_number} contains extra CSV fields")
                if any(value is None for value in row.values()):
                    raise CsvContractError(f"row {row_number} has fewer values than the CSV header")
                row_count += 1
                _validate_row(row, row_number)
                landmarks = _landmarks(row, row_number)
                provenance = {field: _required(row, field, row_number) for field in PROVENANCE_COLUMNS}
                if first_row is None:
                    first_row = {field: row[field] or "" for field in fieldnames}
                    first_landmarks = landmarks
                    first_provenance = provenance

            if first_row is None or first_landmarks is None or first_provenance is None:
                raise CsvContractError("CSV must contain at least one data row")
            return CsvReadback(row_count, first_row, first_landmarks, first_provenance)
    except CsvContractError:
        raise
    except (OSError, UnicodeError, csv.Error) as error:
        raise CsvContractError(f"cannot read CSV: {error}") from error


def _validate_row(row: dict[str, str | None], row_number: int) -> None:
    for field in (*IDENTITY_COLUMNS, "valid_landmark_count", *PROVENANCE_COLUMNS):
        _required(row, field, row_number)
    try:
        valid_landmark_count = int(_required(row, "valid_landmark_count", row_number))
    except ValueError as error:
        raise CsvContractError(f"row {row_number} valid_landmark_count is not an integer") from error
    if valid_landmark_count != 21:
        raise CsvContractError(f"row {row_number} valid_landmark_count must equal 21")


def _landmarks(row: dict[str, str | None], row_number: int) -> tuple[tuple[float, float, float], ...]:
    return tuple(
        (
            _finite(row, f"landmark_{index:02d}_x", row_number),
            _finite(row, f"landmark_{index:02d}_y", row_number),
            _finite(row, f"landmark_{index:02d}_z", row_number),
        )
        for index in range(21)
    )


def _required(row: dict[str, str | None], field: str, row_number: int) -> str:
    value = row.get(field)
    if value is None or not value.strip():
        raise CsvContractError(f"row {row_number} {field} is missing")
    return value.strip()


def _finite(row: dict[str, str | None], field: str, row_number: int) -> float:
    value = _required(row, field, row_number)
    try:
        number = float(value)
    except ValueError as error:
        raise CsvContractError(f"row {row_number} {field} is not numeric") from error
    if not math.isfinite(number):
        raise CsvContractError(f"row {row_number} {field} is not finite")
    return number


def _short(value: str, limit: int = 40) -> str:
    return value if len(value) <= limit else value[: limit - 1] + "…"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Smoke-validate a hand-landmarks.v1 CSV")
    parser.add_argument("csv_path", type=Path)
    args = parser.parse_args(argv)
    try:
        result = read_csv(args.csv_path)
    except CsvContractError as error:
        print(f"CSV defect: {error}", file=sys.stderr)
        print("HAND-LANDMARKS CSV READBACK FAIL")
        return 1

    sample = result.sample
    print(f"rows={result.row_count}")
    print(
        "sample: "
        f"capture_group={_short(sample['capture_group_id'])} "
        f"camera={sample['camera_number']} "
        f"timestamp={sample['frame_timestamp_ms']} "
        f"handedness={sample['handedness']} "
        "shape=(21, 3) flattened_shape=(63,)"
    )
    print(
        "provenance: "
        f"fields={len(result.provenance)}/{len(PROVENANCE_COLUMNS)} "
        f"algorithm={_short(result.provenance['algorithm_key'])} "
        f"version={_short(result.provenance['algorithm_version'])}"
    )
    print("HAND-LANDMARKS CSV READBACK PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
