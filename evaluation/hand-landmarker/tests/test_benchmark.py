from __future__ import annotations

import copy
import csv
import json
import sys
from pathlib import Path
from typing import Any

import pytest

TOOL_ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(TOOL_ROOT))

import compare_profiles  # noqa: E402
import evaluator  # noqa: E402
import metrics  # noqa: E402


def hand(hand_id: str = "h1", *, x: float = 0, side: str = "RIGHT") -> dict[str, Any]:
    return {
        "hand_id": hand_id,
        "handedness": side,
        "normalization_scale": 1,
        "landmarks": [{"x": x, "y": 0, "z": 0} for _ in range(21)],
    }


def prediction(hand_id: str = "h1", *, x: float = 0, side: str = "RIGHT") -> dict[str, Any]:
    value = hand(hand_id, x=x, side=side)
    value.pop("normalization_scale")
    return value


def frame(
    frame_id: str, timestamp: int, truth: list[dict[str, Any]], predicted: list[dict[str, Any]]
) -> dict[str, Any]:
    return {
        "frame_id": frame_id,
        "timestamp_ms": timestamp,
        "groups": {},
        "ground_truth_hands": truth,
        "predicted_hands": predicted,
    }


def benchmark_frames() -> list[dict[str, Any]]:
    return [
        frame("perfect", 0, [hand()], [prediction()]),
        frame("missed", 1, [hand()], []),
        frame("false-positive", 2, [], [prediction("false")]),
        frame("offset", 3, [hand()], [prediction(x=0.1)]),
        frame("handedness-mismatch", 4, [hand()], [prediction(side="LEFT")]),
        frame(
            "two-hands",
            5,
            [hand("left", side="LEFT"), hand("right", x=1)],
            [prediction("left", side="LEFT"), prediction("right", x=1)],
        ),
    ]


def test_detection_and_localization_are_separate() -> None:
    calculated, counts = metrics.calculate_metrics(benchmark_frames())

    assert calculated["HAND_PRESENT_RECALL"] == pytest.approx(4 / 5)
    assert calculated["NO_HAND_FALSE_POSITIVE_RATE"] == 1
    assert calculated["PCK@0.05"] == pytest.approx(84 / 105)
    assert calculated["PCK@0.10"] == pytest.approx(1)
    assert calculated["NORMALIZED_MEAN_ERROR"] == pytest.approx(0.02)
    assert calculated["MEDIAN_NORMALIZED_ERROR"] == 0
    assert calculated["PER_LANDMARK_ERROR"] == pytest.approx([0.02] * 21)
    assert calculated["HANDEDNESS_ACCURACY"] == pytest.approx(4 / 5)
    assert counts["missed_hands"] == 1
    assert counts["extra_predicted_hands"] == 1
    assert counts["matched_hands"] == 5


def test_temporal_jitter_uses_motion_residual() -> None:
    frames = [
        frame("t0", 0, [hand("moving")], [prediction("moving")]),
        frame("t1", 1, [hand("moving", x=0.2)], [prediction("moving", x=0.3)]),
    ]
    values, pairs = metrics.temporal_jitter(frames)

    assert values == pytest.approx(0.1)
    assert pairs == 1


def test_empty_denominators_are_null() -> None:
    empty = [frame("none", 0, [], [])]
    calculated, counts = metrics.calculate_metrics(empty)

    assert calculated["HAND_PRESENT_RECALL"] is None
    assert calculated["PCK@0.05"] is None
    assert calculated["NORMALIZED_MEAN_ERROR"] is None
    assert calculated["HANDEDNESS_ACCURACY"] is None
    assert calculated["TEMPORAL_JITTER"] is None
    assert counts["frames"] == 1


def provenance(**overrides: object) -> dict[str, Any]:
    value: dict[str, Any] = {
        "model_sha256": "a" * 64,
        "profile": "synthetic-fps10-threshold05",
        "profile_sha256": "b" * 64,
        "fps": 10,
        "threshold": 0.5,
        "dataset_version": "synthetic-v1",
        "dataset_digest": "c" * 64,
        "evaluation_code_version": evaluator.EVALUATOR_VERSION,
        "timestamp": "2026-09-04T00:00:00Z",
        "dataset_kind": "SYNTHETIC",
        "authorization_reference": "SYNTHETIC_FIXTURE",
    }
    value.update(overrides)
    return value


def input_document(**overrides: object) -> dict[str, Any]:
    return {
        "format": "mediapipe-hand-landmarker-benchmark-input.v1",
        "provenance": provenance(**overrides),
        "frames": benchmark_frames(),
    }


def test_json_fixture_reports_provenance_and_grouped_metrics() -> None:
    dataset = evaluator.load_dataset(TOOL_ROOT / "fixtures" / "synthetic_benchmark.json")
    report = evaluator.evaluate_dataset(dataset)

    assert report["format"] == evaluator.REPORT_FORMAT
    assert report["source_format"] == "JSON"
    assert report["provenance"]["dataset_kind"] == "SYNTHETIC"  # type: ignore[index]
    assert "occlusion=heavy" in report["grouped_metrics"]  # type: ignore[operator]
    assert str(report["conclusion"]).startswith("SYNTHETIC_FIXTURE_ONLY")


@pytest.mark.parametrize(
    "field", ["model_sha256", "profile", "fps", "threshold", "dataset_digest", "timestamp"]
)
def test_missing_or_invalid_provenance_fails(field: str) -> None:
    data = input_document()
    if field in {"model_sha256", "dataset_digest"}:
        data["provenance"][field] = "bad"  # type: ignore[index]
    elif field in {"fps", "threshold"}:
        data["provenance"][field] = -1  # type: ignore[index]
    else:
        data["provenance"][field] = ""  # type: ignore[index]
    with pytest.raises(evaluator.EvaluationError):
        evaluator.parse_provenance(data["provenance"])


def test_invalid_timestamp_order_and_landmark_shape_fail() -> None:
    data = input_document()
    data["frames"][1]["timestamp_ms"] = 0  # type: ignore[index]
    with pytest.raises(evaluator.EvaluationError, match="strictly increasing"):
        evaluator._parse_frames(data["frames"])
    malformed = copy.deepcopy(input_document())
    malformed["frames"][0]["ground_truth_hands"][0]["landmarks"] = []  # type: ignore[index]
    with pytest.raises(evaluator.EvaluationError, match="exactly 21"):
        evaluator._parse_frames(malformed["frames"])


def test_jsonl_and_csv_inputs_are_supported(tmp_path: Path) -> None:
    data = input_document()
    jsonl_path = tmp_path / "benchmark.jsonl"
    lines = [{"type": "provenance", **data["provenance"]}]
    lines.extend({"type": "frame", **item} for item in data["frames"])
    jsonl_path.write_text("\n".join(json.dumps(item) for item in lines) + "\n", encoding="utf-8")
    assert evaluator.load_dataset(jsonl_path).source_format == "JSONL"

    csv_path = tmp_path / "benchmark.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as source:
        writer = csv.DictWriter(source, fieldnames=evaluator.CSV_FIELDS)
        writer.writeheader()
        for item in data["frames"]:
            writer.writerow(
                {
                    **data["provenance"],
                    "frame_id": item["frame_id"],
                    "timestamp_ms": item["timestamp_ms"],
                    "groups": json.dumps(item["groups"]),
                    "ground_truth_hands": json.dumps(item["ground_truth_hands"]),
                    "predicted_hands": json.dumps(item["predicted_hands"]),
                }
            )
    assert evaluator.load_dataset(csv_path).source_format == "CSV"


def test_profile_comparison_requires_complete_grid(tmp_path: Path) -> None:
    paths: list[Path] = []
    for fps in (5, 10, 15):
        for threshold in (0.3, 0.5, 0.7):
            path = tmp_path / f"{fps}-{threshold}.json"
            path.write_text(
                json.dumps(
                    input_document(
                        fps=fps,
                        threshold=threshold,
                        profile=f"synthetic-fps{fps}-threshold{str(threshold).replace('.', '')}",
                    )
                ),
                encoding="utf-8",
            )
            paths.append(path)
    report = compare_profiles.compare(paths)
    assert len(report["combinations"]) == 9  # type: ignore[arg-type]
    with pytest.raises(evaluator.EvaluationError, match="exactly nine"):
        compare_profiles.compare(paths[:8])
