from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).parents[3]
SCRIPT = ROOT / "tools" / "candidate-capacity-review" / "candidate_capacity_review.py"
MEASUREMENT_TEMPLATE = ROOT / "tools" / "candidate-capacity-review" / "scenarios" / "measured-template.json"
SPEC = importlib.util.spec_from_file_location("candidate_capacity_review", SCRIPT)
assert SPEC and SPEC.loader
review = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = review
SPEC.loader.exec_module(review)


def evidence(value: Any, status: str = "MEASURED", note: str = "synthetic fixture") -> dict[str, Any]:
    return {"status": status, "value": value, "note": note}


def base_payload() -> dict[str, Any]:
    return {
        "schema_version": "candidate-capacity-review.v1",
        "scenario_id": "synthetic-test-scenario",
        "description": "Synthetic deterministic fixture.",
        "inputs": {
            "average_video_duration_minutes": evidence(10.0),
            "p95_video_duration_minutes": evidence(15.0),
            "average_camera_count": evidence(1.0),
            "videos_per_day": evidence(1000),
            "video_count_basis": evidence("camera_video"),
            "inference_fps": evidence(10.0),
            "max_hands_per_frame": evidence(2),
            "observed_or_assumed_candidate_density": evidence(0.5),
            "estimated_candidate_row_bytes": evidence(512.0),
            "index_overhead_ratio": evidence(0.25),
            "growth_factor": evidence(1.0),
            "expected_query_patterns": evidence(["single_video_time_window"]),
        },
        "retention_days": [30, 90, 365],
        "decision_criteria": {
            "postgresql_max_retained_rows": 10_000_000_000,
            "postgresql_max_retained_storage_bytes": 10_000_000_000_000,
            "postgresql_hot_query_patterns": ["single_video_time_window", "small_candidate_window"],
            "s3_artifact_query_patterns": ["bulk_export", "long_range_scan"],
            "required_measured_inputs": [
                *review.REQUIRED_INPUTS,
            ],
            "postgresql_partition_index_feasible": evidence(True),
            "postgresql_operational_complexity_acceptable": evidence(True),
            "s3_artifact_retrieval_tradeoff_acceptable": evidence(True),
        },
    }


def scenario(payload: dict[str, Any] | None = None) -> Any:
    return review.Scenario.from_mapping(payload or base_payload())


def test_theoretical_maximum_is_1200_candidates_per_minute() -> None:
    result = review.calculate_capacity(scenario())

    assert result["theoretical_candidates_per_minute"] == 1200


def test_documented_1000_video_example_is_12000000_rows() -> None:
    result = review.calculate_capacity(scenario())

    assert result["cases"]["theoretical_upper_bound"]["rows_per_video"] == 12_000
    assert result["cases"]["theoretical_upper_bound"]["rows_per_day"] == 12_000_000


def test_retention_calculations_cover_30_90_and_365_days() -> None:
    payload = base_payload()
    payload["inputs"]["average_video_duration_minutes"] = evidence(1.0)
    payload["inputs"]["p95_video_duration_minutes"] = evidence(1.0)
    payload["inputs"]["videos_per_day"] = evidence(10)
    result = review.calculate_capacity(scenario(payload))

    assert result["cases"]["average_case"]["rows_per_day"] == 6_000
    assert result["retention"]["30"]["average_case"]["retained_rows"] == 180_000
    assert result["retention"]["90"]["average_case"]["retained_rows"] == 540_000
    assert result["retention"]["365"]["average_case"]["retained_rows"] == 2_190_000
    assert result["retention"]["30"]["average_case"]["raw_storage_bytes"] == 92_160_000


def test_p95_duration_estimate_is_separate_from_average_case() -> None:
    result = review.calculate_capacity(scenario())

    average_rows = result["cases"]["average_case"]["rows_per_video"]
    p95_rows = result["cases"]["p95_duration_case"]["rows_per_video"]
    assert average_rows == 6_000
    assert p95_rows == 9_000
    assert p95_rows > average_rows


def test_capture_session_daily_count_multiplies_by_average_camera_count() -> None:
    payload = base_payload()
    payload["inputs"]["average_camera_count"] = evidence(2.0)
    payload["inputs"]["videos_per_day"] = evidence(10)
    payload["inputs"]["video_count_basis"] = evidence("capture_session")

    result = review.calculate_capacity(scenario(payload))

    assert result["daily_camera_video_assets"] == 20
    assert result["cases"]["average_case"]["rows_per_day"] == 120_000


def test_candidate_density_below_theoretical_maximum_reduces_average_estimate() -> None:
    result = review.calculate_capacity(scenario())

    assert result["cases"]["average_case"]["candidate_density"] == 0.5
    assert result["cases"]["average_case"]["rows_per_video"] == 6_000
    assert result["cases"]["theoretical_upper_bound"]["rows_per_video"] == 12_000


def test_invalid_negative_input_is_rejected() -> None:
    payload = base_payload()
    payload["inputs"]["average_video_duration_minutes"] = evidence(-1)

    with pytest.raises(review.ScenarioError, match="average_video_duration_minutes"):
        scenario(payload)


def test_retention_days_must_be_explicit_and_are_not_defaulted() -> None:
    payload = base_payload()
    del payload["retention_days"]

    with pytest.raises(review.ScenarioError, match="不会使用默认保留期"):
        scenario(payload)


def test_evidence_note_is_required_for_traceability() -> None:
    payload = base_payload()
    del payload["inputs"]["inference_fps"]["note"]

    with pytest.raises(review.ScenarioError, match="必须提供 note"):
        scenario(payload)


def test_evidence_value_is_required_and_not_silently_defaulted() -> None:
    payload = base_payload()
    del payload["inputs"]["inference_fps"]["value"]

    with pytest.raises(review.ScenarioError, match="必须显式提供 value"):
        scenario(payload)


def test_p95_duration_cannot_be_below_average_duration() -> None:
    payload = base_payload()
    payload["inputs"]["average_video_duration_minutes"] = evidence(20.0)
    payload["inputs"]["p95_video_duration_minutes"] = evidence(15.0)

    with pytest.raises(review.ScenarioError, match="p95_video_duration_minutes"):
        scenario(payload)


@pytest.mark.parametrize(
    ("field_name", "value"),
    [
        ("observed_or_assumed_candidate_density", 1.1),
        ("index_overhead_ratio", -0.1),
    ],
)
def test_invalid_ratio_values_are_rejected(field_name: str, value: float) -> None:
    payload = base_payload()
    payload["inputs"][field_name] = evidence(value)

    with pytest.raises(review.ScenarioError, match="比例"):
        scenario(payload)


def test_pending_measured_data_remains_pending_in_results_and_decision() -> None:
    payload = base_payload()
    payload["inputs"]["p95_video_duration_minutes"] = evidence(None, "PENDING", "No duration sample yet.")
    payload["inputs"]["expected_query_patterns"] = evidence(None, "PENDING", "No query trace yet.")
    report = review.build_report(scenario(payload))

    assert report["calculated_results"]["cases"]["p95_duration_case"]["rows_per_video"] is None
    assert report["decision"]["status"] == "DECISION PENDING"
    assert "p95_video_duration_minutes" in report["pending_evidence"]
    assert "expected_query_patterns" in report["pending_evidence"]


def test_decision_requires_all_capacity_inputs_to_be_measured() -> None:
    payload = base_payload()
    payload["inputs"]["inference_fps"] = evidence(10.0, "ASSUMED", "Configured baseline, not measured yet.")
    payload["inputs"]["max_hands_per_frame"] = evidence(
        2, "ASSUMED", "Configured baseline, not measured yet."
    )
    payload["inputs"]["growth_factor"] = evidence(1.0, "ASSUMED", "Planning factor, not measured yet.")
    report = review.build_report(scenario(payload))

    assert report["decision"]["status"] == "DECISION PENDING"
    assert report["decision"]["option"] is None
    assert "inference_fps" in report["decision"]["gates"][0]["missing"]
    assert "max_hands_per_frame" in report["decision"]["gates"][0]["missing"]
    assert "growth_factor" in report["decision"]["gates"][0]["missing"]


def test_missing_decision_boundaries_keep_decision_pending() -> None:
    payload = base_payload()
    payload["decision_criteria"]["postgresql_max_retained_rows"] = None
    payload["decision_criteria"]["postgresql_max_retained_storage_bytes"] = None
    payload["decision_criteria"]["postgresql_hot_query_patterns"] = None
    payload["decision_criteria"]["s3_artifact_query_patterns"] = None
    report = review.build_report(scenario(payload))

    assert report["decision"]["status"] == "DECISION PENDING"
    assert report["decision"]["option"] is None
    assert report["decision"]["gates"][2]["status"] == "PENDING"


def test_measurement_template_contains_no_production_values() -> None:
    payload = json.loads(MEASUREMENT_TEMPLATE.read_text(encoding="utf-8"))
    report = review.build_report(review.load_scenario(MEASUREMENT_TEMPLATE))

    assert all(item["status"] == "PENDING" and item["value"] is None for item in payload["inputs"].values())
    assert all(
        payload["decision_criteria"][field] is None
        for field in (
            "postgresql_max_retained_rows",
            "postgresql_max_retained_storage_bytes",
            "postgresql_hot_query_patterns",
            "s3_artifact_query_patterns",
        )
    )
    assert report["decision"]["status"] == "DECISION PENDING"
    assert report["decision"]["option"] is None


def test_report_generation_separates_sections_and_writes_outputs(tmp_path: Path) -> None:
    current = scenario()
    json_output = tmp_path / "review.json"
    markdown_output = tmp_path / "review.md"

    report = review.write_outputs(current, json_output, markdown_output)
    saved_json = json.loads(json_output.read_text(encoding="utf-8"))
    saved_markdown = markdown_output.read_text(encoding="utf-8")

    assert saved_json["schema_version"] == "candidate-capacity-review.v1"
    assert saved_json["decision"] == report["decision"]
    assert "## FACTS" in saved_markdown
    assert "## ASSUMPTIONS" in saved_markdown
    assert "## CALCULATED RESULTS" in saved_markdown
    assert "## PENDING EVIDENCE" in saved_markdown
    assert "## DECISION GATES" in saved_markdown


def test_decision_is_pending_when_all_values_are_only_assumptions() -> None:
    payload = copy.deepcopy(base_payload())
    for item in payload["inputs"].values():
        item["status"] = "ASSUMED"
    for name in review.REQUIRED_CRITERIA_EVIDENCE:
        payload["decision_criteria"][name]["status"] = "ASSUMED"
    report = review.build_report(scenario(payload))

    assert report["decision"]["status"] == "DECISION PENDING"
    assert report["decision"]["option"] is None
