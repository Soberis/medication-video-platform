from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from typing import Any

import pytest

TOOL_ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(TOOL_ROOT))

import hand_pose_evaluation as evaluator  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "synthetic_evidence.json"


def fixture_data() -> dict[str, Any]:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def metric(data: dict[str, Any], name: str) -> dict[str, Any]:
    metrics = data["metrics"]
    assert isinstance(metrics, list)
    item = next(item for item in metrics if item["name"] == name)
    assert isinstance(item, dict)
    return item


def invariant(data: dict[str, Any], name: str) -> dict[str, Any]:
    invariants = data["invariants"]
    assert isinstance(invariants, list)
    item = next(item for item in invariants if item["name"] == name)
    assert isinstance(item, dict)
    return item


def test_complete_synthetic_fixture_has_all_pending_metrics() -> None:
    document = evaluator.load_evidence(FIXTURE)

    assert document.evaluation_id == "synthetic-hand-pose-eval-001"
    assert [item.name for item in document.metrics] == list(evaluator.METRIC_NAMES)
    assert all(item.status == "PENDING" and item.value is None for item in document.metrics)
    assert [item.name for item in document.invariants] == list(evaluator.INVARIANT_NAMES)


def test_valid_recorded_rate_metrics_are_normalized() -> None:
    data = fixture_data()
    for index, name in enumerate(evaluator.RATE_METRICS):
        item = metric(data, name)
        item["status"] = "RECORDED"
        item["value"] = index / 10

    document = evaluator.normalize_evidence(data)

    recorded = {item.name: item.value for item in document.metrics if item.status == "RECORDED"}
    assert len(recorded) == len(evaluator.RATE_METRICS)
    assert all(value is not None and 0 <= value <= 1 for value in recorded.values())


def test_rate_above_one_is_rejected() -> None:
    data = fixture_data()
    item = metric(data, "HAND_FRAME_RECALL")
    item.update(status="RECORDED", value=1.01)

    with pytest.raises(evaluator.EvidenceError, match=r"0\.\.1"):
        evaluator.normalize_evidence(data)


def test_negative_metric_is_rejected() -> None:
    data = fixture_data()
    item = metric(data, "OVERLAY_TIME_ERROR")
    item.update(status="RECORDED", value=-0.01)

    with pytest.raises(evaluator.EvidenceError, match="有限非负"):
        evaluator.normalize_evidence(data)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_non_finite_metric_is_rejected(value: float) -> None:
    data = fixture_data()
    item = metric(data, "PROCESSING_TIME_PER_MINUTE")
    item.update(status="RECORDED", value=value)

    with pytest.raises(evaluator.EvidenceError, match="有限非负"):
        evaluator.normalize_evidence(data)


def test_pending_metric_must_have_null_value() -> None:
    data = fixture_data()
    metric(data, "HAND_FRAME_RECALL")["value"] = 0

    with pytest.raises(evaluator.EvidenceError, match=r"PENDING.*null"):
        evaluator.normalize_evidence(data)


def test_recorded_metric_must_have_value() -> None:
    data = fixture_data()
    item = metric(data, "HAND_FRAME_RECALL")
    item.update(status="RECORDED", value=None)

    with pytest.raises(evaluator.EvidenceError, match=r"RECORDED.*数字"):
        evaluator.normalize_evidence(data)


def test_missing_metric_is_rejected() -> None:
    data = fixture_data()
    data["metrics"] = [item for item in data["metrics"] if item["name"] != "HANDEDNESS_ACCURACY"]

    with pytest.raises(evaluator.EvidenceError, match="恰好包含"):
        evaluator.normalize_evidence(data)


def test_duplicate_metric_is_rejected() -> None:
    data = fixture_data()
    metrics = data["metrics"]
    assert isinstance(metrics, list)
    metrics[-1] = copy.deepcopy(metrics[0])

    with pytest.raises(evaluator.EvidenceError, match="重复指标"):
        evaluator.normalize_evidence(data)


@pytest.mark.parametrize("field", ["model_sha256", "profile_sha256"])
def test_invalid_sha256_is_rejected(field: str) -> None:
    data = fixture_data()
    data[field] = "not-a-sha256"

    with pytest.raises(evaluator.EvidenceError, match="SHA-256"):
        evaluator.normalize_evidence(data)


def test_missing_sample_binding_is_rejected() -> None:
    data = fixture_data()
    data["sample_version"] = None
    data["sample_sha256"] = None

    with pytest.raises(evaluator.EvidenceError, match="至少提供一个"):
        evaluator.normalize_evidence(data)


def test_pass_invariant_requires_evidence() -> None:
    data = fixture_data()
    item = invariant(data, "ORIGINAL_VIDEO_UNMODIFIED")
    item["status"] = "PASS"

    with pytest.raises(evaluator.EvidenceError, match=r"PASS.*evidence"):
        evaluator.normalize_evidence(data)


def test_canonical_report_has_frozen_order_and_normalized_hashes() -> None:
    data = fixture_data()
    data["model_sha256"] = "D" * 64
    document = evaluator.normalize_evidence(data)
    report = document.to_mapping()

    assert list(report) == [
        "format",
        "evaluation_id",
        "sample_version",
        "sample_sha256",
        "sample_authorization",
        "model_sha256",
        "profile_sha256",
        "parameter_adjustment_note",
        "metrics",
        "invariants",
    ]
    assert report["model_sha256"] == "d" * 64
    report_metrics = report["metrics"]
    assert isinstance(report_metrics, list)
    assert [item["name"] for item in report_metrics] == list(evaluator.METRIC_NAMES)


def test_markdown_report_generation(tmp_path: Path) -> None:
    output_dir = tmp_path / "report"
    assert evaluator.main(["report", str(FIXTURE), "--output", str(output_dir)]) == 0

    machine_report = json.loads((output_dir / "hand_pose_evaluation.json").read_text(encoding="utf-8"))
    markdown = (output_dir / "hand_pose_evaluation.md").read_text(encoding="utf-8")
    assert machine_report["format"] == evaluator.EVIDENCE_FORMAT
    assert "## Metrics" in markdown
    assert "`PENDING`" in markdown
    assert "CandidateAnnotation geometry" in markdown
    assert "no algorithm accuracy is claimed" not in markdown


def test_invalid_cli_input_returns_non_zero(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    invalid = tmp_path / "invalid.json"
    invalid.write_text(json.dumps({"format": "wrong"}), encoding="utf-8")

    assert evaluator.main(["validate", str(invalid)]) != 0
    assert "ERROR:" in capsys.readouterr().err
