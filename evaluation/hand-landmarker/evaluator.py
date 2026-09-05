#!/usr/bin/env python3
"""Validate offline hand-landmarker benchmark data and emit a provenance report."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast

from metrics import calculate_metrics

REPORT_FORMAT = "mediapipe-hand-landmarker-benchmark.v1"
EVALUATOR_VERSION = "hand-landmarker-evaluator.v1"
LANDMARK_COUNT = 21
SUPPORTED_GROUPS = frozenset({"occlusion", "fast_motion", "two_hands", "view", "frame_edge"})
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SENSITIVE_RE = re.compile(
    r"https?://|s3://|presign|bearer\s+|access[_ -]?token|authorization\s*:|"
    r"患者|patient|医院账号|hospital account|\.(?:mp4|mov|mkv|avi|webm)\b|"
    r"[\\/]"
    r"|-----BEGIN [A-Z ]+ KEY-----",
    re.IGNORECASE,
)
PROVENANCE_FIELDS = frozenset(
    {
        "model_sha256",
        "profile",
        "profile_sha256",
        "fps",
        "threshold",
        "dataset_version",
        "dataset_digest",
        "evaluation_code_version",
        "timestamp",
        "dataset_kind",
        "authorization_reference",
    }
)
FRAME_FIELDS = frozenset({"frame_id", "timestamp_ms", "groups", "ground_truth_hands", "predicted_hands"})
HAND_FIELDS = frozenset({"hand_id", "handedness", "normalization_scale", "landmarks"})
POINT_FIELDS = frozenset({"x", "y", "z"})
CSV_FIELDS = (
    "model_sha256",
    "profile",
    "profile_sha256",
    "fps",
    "threshold",
    "dataset_version",
    "dataset_digest",
    "evaluation_code_version",
    "timestamp",
    "dataset_kind",
    "authorization_reference",
    "frame_id",
    "timestamp_ms",
    "groups",
    "ground_truth_hands",
    "predicted_hands",
)


class EvaluationError(ValueError):
    """Raised when benchmark input is malformed or provenance is incomplete."""


@dataclass(frozen=True)
class Dataset:
    provenance: dict[str, object]
    frames: tuple[dict[str, object], ...]
    source_format: str
    source_sha256: str


def _object(value: object, context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise EvaluationError(f"{context} must be an object")
    return value


def _list(value: object, context: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvaluationError(f"{context} must be an array")
    return value


def _reject_unknown(data: Mapping[str, object], allowed: frozenset[str], context: str) -> None:
    unknown = sorted(set(data) - allowed)
    if unknown:
        raise EvaluationError(f"{context} has unsupported fields: {', '.join(unknown)}")


def _required(data: Mapping[str, object], field: str, context: str) -> object:
    if field not in data:
        raise EvaluationError(f"{context} is missing {field}")
    return data[field]


def _text(value: object, context: str, *, sensitive: bool = False) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvaluationError(f"{context} must be a non-empty string")
    normalized = value.strip()
    if len(normalized) > 256:
        raise EvaluationError(f"{context} is too long")
    if sensitive and SENSITIVE_RE.search(normalized):
        raise EvaluationError(f"{context} contains a path, URL, secret, or sensitive identifier")
    return normalized


def _identifier(value: object, context: str) -> str:
    normalized = _text(value, context, sensitive=True)
    if not IDENTIFIER_RE.fullmatch(normalized):
        raise EvaluationError(f"{context} must be a safe identifier")
    return normalized


def _sha256(value: object, context: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise EvaluationError(f"{context} must be a 64-character SHA-256")
    return value.lower()


def _number(
    value: object, context: str, *, minimum: float | None = None, maximum: float | None = None
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvaluationError(f"{context} must be numeric")
    number = float(value)
    if not math.isfinite(number):
        raise EvaluationError(f"{context} must be finite")
    if minimum is not None and number < minimum:
        raise EvaluationError(f"{context} must be >= {minimum}")
    if maximum is not None and number > maximum:
        raise EvaluationError(f"{context} must be <= {maximum}")
    return number


def _timestamp(value: object, context: str) -> str:
    raw = _text(value, context)
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError as error:
        raise EvaluationError(f"{context} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise EvaluationError(f"{context} must include a timezone")
    return parsed.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_provenance(value: object) -> dict[str, object]:
    data = _object(value, "provenance")
    _reject_unknown(data, PROVENANCE_FIELDS, "provenance")
    dataset_kind = _required(data, "dataset_kind", "provenance")
    if dataset_kind not in {"SYNTHETIC", "AUTHORIZED_EXTERNAL"}:
        raise EvaluationError("provenance.dataset_kind must be SYNTHETIC or AUTHORIZED_EXTERNAL")
    return {
        "model_sha256": _sha256(_required(data, "model_sha256", "provenance"), "provenance.model_sha256"),
        "profile": _identifier(_required(data, "profile", "provenance"), "provenance.profile"),
        "profile_sha256": _sha256(
            _required(data, "profile_sha256", "provenance"), "provenance.profile_sha256"
        ),
        "fps": _number(_required(data, "fps", "provenance"), "provenance.fps", minimum=0.000001),
        "threshold": _number(
            _required(data, "threshold", "provenance"), "provenance.threshold", minimum=0, maximum=1
        ),
        "dataset_version": _identifier(
            _required(data, "dataset_version", "provenance"), "provenance.dataset_version"
        ),
        "dataset_digest": _sha256(
            _required(data, "dataset_digest", "provenance"), "provenance.dataset_digest"
        ),
        "evaluation_code_version": _identifier(
            _required(data, "evaluation_code_version", "provenance"),
            "provenance.evaluation_code_version",
        ),
        "timestamp": _timestamp(_required(data, "timestamp", "provenance"), "provenance.timestamp"),
        "dataset_kind": dataset_kind,
        "authorization_reference": _text(
            _required(data, "authorization_reference", "provenance"),
            "provenance.authorization_reference",
            sensitive=True,
        ),
    }


def _point(value: object, context: str) -> dict[str, float]:
    if isinstance(value, list):
        if len(value) != 3:
            raise EvaluationError(f"{context} array must contain x, y, and z")
        return {
            axis: _number(value[index], f"{context}[{index}]") for index, axis in enumerate(("x", "y", "z"))
        }
    data = _object(value, context)
    _reject_unknown(data, POINT_FIELDS, context)
    return {axis: _number(_required(data, axis, context), f"{context}.{axis}") for axis in ("x", "y", "z")}


def _hand(value: object, context: str, *, reference: bool) -> dict[str, object]:
    data = _object(value, context)
    _reject_unknown(data, HAND_FIELDS, context)
    handedness = _required(data, "handedness", context)
    if handedness not in {"LEFT", "RIGHT", "UNKNOWN"}:
        raise EvaluationError(f"{context}.handedness must be LEFT, RIGHT, or UNKNOWN")
    points = _list(_required(data, "landmarks", context), f"{context}.landmarks")
    if len(points) != LANDMARK_COUNT:
        raise EvaluationError(f"{context}.landmarks must contain exactly {LANDMARK_COUNT} points")
    normalized: dict[str, object] = {
        "hand_id": _identifier(_required(data, "hand_id", context), f"{context}.hand_id"),
        "handedness": handedness,
        "landmarks": [_point(point, f"{context}.landmarks[{index}]") for index, point in enumerate(points)],
    }
    if reference:
        normalized["normalization_scale"] = _number(
            _required(data, "normalization_scale", context),
            f"{context}.normalization_scale",
            minimum=1e-12,
        )
    elif "normalization_scale" in data:
        raise EvaluationError(f"{context}.normalization_scale is only valid on reference hands")
    return normalized


def _group_value(value: object, context: str) -> str | bool | int | float:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return _number(value, context)
    if isinstance(value, str):
        return _text(value, context, sensitive=True)
    raise EvaluationError(f"{context} must be a string, boolean, or number")


def parse_frame(value: object, index: int, previous_timestamp: float | None = None) -> dict[str, object]:
    data = _object(value, f"frames[{index}]")
    _reject_unknown(data, FRAME_FIELDS, f"frames[{index}]")
    frame_id = _identifier(_required(data, "frame_id", f"frames[{index}]"), f"frames[{index}].frame_id")
    timestamp_ms = _number(
        _required(data, "timestamp_ms", f"frames[{index}]"),
        f"frames[{index}].timestamp_ms",
        minimum=0,
    )
    if previous_timestamp is not None and timestamp_ms <= previous_timestamp:
        raise EvaluationError("frame timestamps must be strictly increasing")
    groups_value = data.get("groups", {})
    groups_data = _object(groups_value, f"frames[{index}].groups")
    groups: dict[str, str | bool | int | float] = {}
    for key, group_value in groups_data.items():
        group_key = _identifier(key, f"frames[{index}].groups key")
        groups[group_key] = _group_value(group_value, f"frames[{index}].groups.{group_key}")
    truth_values = _list(
        _required(data, "ground_truth_hands", f"frames[{index}]"),
        f"frames[{index}].ground_truth_hands",
    )
    predicted_values = _list(
        _required(data, "predicted_hands", f"frames[{index}]"),
        f"frames[{index}].predicted_hands",
    )
    truth = [
        _hand(item, f"frames[{index}].ground_truth_hands[{i}]", reference=True)
        for i, item in enumerate(truth_values)
    ]
    predicted = [
        _hand(item, f"frames[{index}].predicted_hands[{i}]", reference=False)
        for i, item in enumerate(predicted_values)
    ]
    for field, hands in (("ground_truth_hands", truth), ("predicted_hands", predicted)):
        ids = [str(hand["hand_id"]) for hand in hands]
        if len(ids) != len(set(ids)):
            raise EvaluationError(f"frames[{index}].{field} contains duplicate hand_id")
    if len(truth) > 2 or len(predicted) > 2:
        raise EvaluationError("MediaPipe benchmark supports at most two hands per frame")
    return {
        "frame_id": frame_id,
        "timestamp_ms": timestamp_ms,
        "groups": groups,
        "ground_truth_hands": truth,
        "predicted_hands": predicted,
    }


def _parse_frames(value: object) -> tuple[dict[str, object], ...]:
    raw_frames = _list(value, "frames")
    if not raw_frames:
        raise EvaluationError("frames must contain at least one frame")
    frames: list[dict[str, object]] = []
    previous: float | None = None
    for index, raw_frame in enumerate(raw_frames):
        frame = parse_frame(raw_frame, index, previous)
        previous = float(cast(float, frame["timestamp_ms"]))
        frames.append(frame)
    return tuple(frames)


def _strict_json(value: str, context: str) -> object:
    try:
        return json.loads(value)
    except json.JSONDecodeError as error:
        raise EvaluationError(f"{context} is not valid JSON: {error.msg}") from error


def _no_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise EvaluationError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _read_json(path: Path) -> tuple[dict[str, object], tuple[dict[str, object], ...]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise EvaluationError(f"cannot read JSON input: {error}") from error
    data = _object(value, "input")
    _reject_unknown(data, frozenset({"format", "provenance", "frames"}), "input")
    provenance = parse_provenance(_required(data, "provenance", "input"))
    return provenance, _parse_frames(_required(data, "frames", "input"))


def _read_jsonl(path: Path) -> tuple[dict[str, object], tuple[dict[str, object], ...]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise EvaluationError(f"cannot read JSONL input: {error}") from error
    if len(lines) < 2:
        raise EvaluationError("JSONL input needs one provenance line and at least one frame")
    first = _object(_strict_json(lines[0], "JSONL provenance"), "JSONL provenance")
    if first.get("type") != "provenance":
        raise EvaluationError("JSONL first line must have type=provenance")
    _reject_unknown(first, frozenset({"type", *PROVENANCE_FIELDS}), "JSONL provenance")
    provenance = parse_provenance({key: value for key, value in first.items() if key != "type"})
    raw_frames: list[object] = []
    for line_number, line in enumerate(lines[1:], start=2):
        if not line.strip():
            raise EvaluationError(f"JSONL line {line_number} is blank")
        frame = _object(_strict_json(line, f"JSONL line {line_number}"), f"JSONL line {line_number}")
        if frame.get("type") == "frame":
            frame = {key: value for key, value in frame.items() if key != "type"}
        raw_frames.append(frame)
    return provenance, _parse_frames(raw_frames)


def _csv_provenance(row: Mapping[str, str], row_number: int) -> dict[str, object]:
    return parse_provenance(
        {
            "model_sha256": row["model_sha256"],
            "profile": row["profile"],
            "profile_sha256": row["profile_sha256"],
            "fps": _strict_csv_number(row["fps"], "fps", row_number),
            "threshold": _strict_csv_number(row["threshold"], "threshold", row_number),
            "dataset_version": row["dataset_version"],
            "dataset_digest": row["dataset_digest"],
            "evaluation_code_version": row["evaluation_code_version"],
            "timestamp": row["timestamp"],
            "dataset_kind": row["dataset_kind"],
            "authorization_reference": row["authorization_reference"],
        }
    )


def _strict_csv_number(value: str, field: str, row_number: int) -> float:
    try:
        return float(value)
    except ValueError as error:
        raise EvaluationError(f"CSV row {row_number} {field} must be numeric") from error


def _read_csv(path: Path) -> tuple[dict[str, object], tuple[dict[str, object], ...]]:
    try:
        with path.open("r", encoding="utf-8-sig", newline="") as source:
            reader = csv.DictReader(source, strict=True)
            if tuple(reader.fieldnames or ()) != CSV_FIELDS:
                raise EvaluationError("CSV header does not match the benchmark input format")
            provenance: dict[str, object] | None = None
            raw_frames: list[object] = []
            for row_number, row in enumerate(reader, start=2):
                if None in row or any(value is None for value in row.values()):
                    raise EvaluationError(f"CSV row {row_number} has the wrong number of fields")
                current = _csv_provenance(row, row_number)
                if provenance is None:
                    provenance = current
                elif current != provenance:
                    raise EvaluationError("CSV provenance must be identical on every row")
                raw_frames.append(
                    {
                        "frame_id": row["frame_id"],
                        "timestamp_ms": _strict_csv_number(row["timestamp_ms"], "timestamp_ms", row_number),
                        "groups": _strict_json(row["groups"], f"CSV row {row_number} groups"),
                        "ground_truth_hands": _strict_json(
                            row["ground_truth_hands"], f"CSV row {row_number} ground_truth_hands"
                        ),
                        "predicted_hands": _strict_json(
                            row["predicted_hands"], f"CSV row {row_number} predicted_hands"
                        ),
                    }
                )
    except EvaluationError:
        raise
    except (OSError, UnicodeError, csv.Error) as error:
        raise EvaluationError(f"cannot read CSV input: {error}") from error
    if provenance is None:
        raise EvaluationError("CSV input must contain at least one row")
    return provenance, _parse_frames(raw_frames)


def load_dataset(path: str | Path) -> Dataset:
    """Read and validate JSON, JSONL, or CSV benchmark input."""

    input_path = Path(path)
    try:
        raw = input_path.read_bytes()
    except OSError as error:
        raise EvaluationError(f"cannot read input: {error}") from error
    suffix = input_path.suffix.lower()
    if suffix in {".jsonl", ".ndjson"}:
        provenance, frames = _read_jsonl(input_path)
        source_format = "JSONL"
    elif suffix == ".csv":
        provenance, frames = _read_csv(input_path)
        source_format = "CSV"
    else:
        provenance, frames = _read_json(input_path)
        source_format = "JSON"
    return Dataset(
        provenance=provenance,
        frames=frames,
        source_format=source_format,
        source_sha256=hashlib.sha256(raw).hexdigest(),
    )


def _group_label(key: str, value: object) -> str:
    if isinstance(value, bool):
        rendered = str(value).lower()
    else:
        rendered = str(value)
    return f"{key}={rendered}"


def evaluate_dataset(dataset: Dataset) -> dict[str, object]:
    metrics, counts = calculate_metrics(dataset.frames)
    grouped_frames: dict[str, list[Mapping[str, Any]]] = {}
    for frame in dataset.frames:
        groups = cast(Mapping[str, object], frame["groups"])
        for key, value in groups.items():
            grouped_frames.setdefault(_group_label(str(key), value), []).append(frame)
    grouped_metrics: dict[str, object] = {}
    for label, frames in sorted(grouped_frames.items()):
        group_metrics, group_counts = calculate_metrics(frames)
        grouped_metrics[label] = {"metrics": group_metrics, "counts": group_counts}
    dataset_kind = dataset.provenance["dataset_kind"]
    conclusion = (
        "SYNTHETIC_FIXTURE_ONLY: metrics exercise evaluator semantics and are not production or "
        "model-effect claims."
        if dataset_kind == "SYNTHETIC"
        else "AUTHORIZED_DATA_METRICS: require benchmark-owner review before any algorithm or "
        "production conclusion."
    )
    return {
        "format": REPORT_FORMAT,
        "provenance": dataset.provenance,
        "source_format": dataset.source_format,
        "source_sha256": dataset.source_sha256,
        "evaluator_version": EVALUATOR_VERSION,
        "metrics": metrics,
        "counts": counts,
        "grouped_metrics": grouped_metrics,
        "conclusion": conclusion,
    }


def _json_report(report: Mapping[str, object]) -> str:
    return json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _write_report(report: Mapping[str, object], output: Path) -> None:
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(_json_report(report), encoding="utf-8")
    except OSError as error:
        raise EvaluationError(f"cannot write report: {error}") from error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Evaluate offline MediaPipe hand-landmarker predictions")
    parser.add_argument("input", type=Path, help="JSON, JSONL, or CSV benchmark input")
    parser.add_argument("--output", type=Path, help="JSON report path; stdout when omitted")
    args = parser.parse_args(argv)
    try:
        report = evaluate_dataset(load_dataset(args.input))
        if args.output:
            _write_report(report, args.output)
        else:
            sys.stdout.write(_json_report(report))
    except EvaluationError as error:
        print(f"EVALUATION FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
