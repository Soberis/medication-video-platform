#!/usr/bin/env python3
"""Reproducible, offline CandidateAnnotation capacity review.

The tool only consumes a versioned JSON scenario.  It does not connect to a
database, object storage, worker, queue, or production system.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, cast

SCHEMA_VERSION = "candidate-capacity-review.v1"
EVIDENCE_STATUSES = {"MEASURED", "ASSUMED", "PENDING"}
VIDEO_COUNT_BASES = {"camera_video", "capture_session"}
REQUIRED_INPUTS = (
    "average_video_duration_minutes",
    "p95_video_duration_minutes",
    "average_camera_count",
    "videos_per_day",
    "video_count_basis",
    "inference_fps",
    "max_hands_per_frame",
    "observed_or_assumed_candidate_density",
    "estimated_candidate_row_bytes",
    "index_overhead_ratio",
    "growth_factor",
    "expected_query_patterns",
)
REQUIRED_CRITERIA_EVIDENCE = (
    "postgresql_partition_index_feasible",
    "postgresql_operational_complexity_acceptable",
    "s3_artifact_retrieval_tradeoff_acceptable",
)
DEFAULT_RETENTION_DAYS = (30, 90, 365)

EvidenceStatus = Literal["MEASURED", "ASSUMED", "PENDING"]


class ScenarioError(ValueError):
    """Raised when a scenario is missing, ambiguous, or invalid."""


@dataclass(frozen=True)
class Evidence:
    status: EvidenceStatus
    value: Any
    note: str

    @classmethod
    def from_mapping(cls, field_name: str, raw: Any) -> Evidence:
        if not isinstance(raw, Mapping):
            raise ScenarioError(f"输入 {field_name} 必须是带 status/value/note 的对象。")
        status = raw.get("status")
        if status not in EVIDENCE_STATUSES:
            raise ScenarioError(f"输入 {field_name} 的 status 必须是 MEASURED、ASSUMED 或 PENDING。")
        value = raw.get("value")
        note = raw.get("note", "")
        if not isinstance(note, str):
            raise ScenarioError(f"输入 {field_name} 的 note 必须是字符串。")
        if status == "PENDING" and value is not None:
            raise ScenarioError(f"输入 {field_name} 为 PENDING 时 value 必须为 null。")
        if status != "PENDING" and value is None:
            raise ScenarioError(f"输入 {field_name} 为 {status} 时必须提供 value。")
        return cls(cast(EvidenceStatus, status), value, note)

    def as_dict(self) -> dict[str, Any]:
        return {"status": self.status, "value": self.value, "note": self.note}


@dataclass(frozen=True)
class Scenario:
    schema_version: str
    scenario_id: str
    description: str
    inputs: dict[str, Evidence]
    retention_days: tuple[int, ...]
    decision_criteria: dict[str, Any]

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any]) -> Scenario:
        if raw.get("schema_version") != SCHEMA_VERSION:
            raise ScenarioError(f"schema_version 必须是 {SCHEMA_VERSION}。")
        scenario_id = raw.get("scenario_id")
        if not isinstance(scenario_id, str) or not scenario_id.strip():
            raise ScenarioError("scenario_id 不能为空。")
        description = raw.get("description", "")
        if not isinstance(description, str):
            raise ScenarioError("description 必须是字符串。")

        raw_inputs = raw.get("inputs")
        if not isinstance(raw_inputs, Mapping):
            raise ScenarioError("inputs 必须是对象。")
        missing_inputs = [field for field in REQUIRED_INPUTS if field not in raw_inputs]
        if missing_inputs:
            raise ScenarioError(f"inputs 缺少字段：{', '.join(missing_inputs)}。")
        inputs = {field: Evidence.from_mapping(field, raw_inputs[field]) for field in REQUIRED_INPUTS}
        _validate_inputs(inputs)

        raw_retention_days = raw.get("retention_days", list(DEFAULT_RETENTION_DAYS))
        retention_days = _validate_retention_days(raw_retention_days)

        raw_criteria = raw.get("decision_criteria")
        if not isinstance(raw_criteria, Mapping):
            raise ScenarioError("decision_criteria 必须是对象。")
        criteria = dict(raw_criteria)
        _validate_decision_criteria(criteria)
        return cls(
            schema_version=SCHEMA_VERSION,
            scenario_id=scenario_id,
            description=description,
            inputs=inputs,
            retention_days=retention_days,
            decision_criteria=criteria,
        )


def _validate_inputs(inputs: Mapping[str, Evidence]) -> None:
    _validate_non_negative_number(
        inputs["average_video_duration_minutes"], "average_video_duration_minutes", True
    )
    _validate_positive_number(inputs["p95_video_duration_minutes"], "p95_video_duration_minutes")
    _validate_positive_number(inputs["average_camera_count"], "average_camera_count")
    _validate_non_negative_number(inputs["videos_per_day"], "videos_per_day", False)
    _validate_enum(inputs["video_count_basis"], "video_count_basis", VIDEO_COUNT_BASES)
    _validate_positive_number(inputs["inference_fps"], "inference_fps")
    _validate_positive_integer(inputs["max_hands_per_frame"], "max_hands_per_frame")
    _validate_ratio(inputs["observed_or_assumed_candidate_density"], "observed_or_assumed_candidate_density")
    _validate_positive_number(inputs["estimated_candidate_row_bytes"], "estimated_candidate_row_bytes")
    _validate_ratio(inputs["index_overhead_ratio"], "index_overhead_ratio")
    _validate_at_least_one(inputs["growth_factor"], "growth_factor", 1.0)
    _validate_query_patterns(inputs["expected_query_patterns"])


def _validate_non_negative_number(evidence: Evidence, field_name: str, strictly_positive: bool) -> None:
    value = _numeric_value(evidence, field_name)
    if value is None:
        return
    if not math.isfinite(value) or (value <= 0 if strictly_positive else value < 0):
        comparison = "大于 0" if strictly_positive else "大于等于 0"
        raise ScenarioError(f"输入 {field_name} 必须{comparison}。")


def _validate_positive_number(evidence: Evidence, field_name: str) -> None:
    _validate_non_negative_number(evidence, field_name, True)


def _validate_positive_integer(evidence: Evidence, field_name: str) -> None:
    if evidence.status == "PENDING":
        return
    value = evidence.value
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ScenarioError(f"输入 {field_name} 必须是大于 0 的整数。")


def _validate_at_least_one(evidence: Evidence, field_name: str, minimum: float) -> None:
    value = _numeric_value(evidence, field_name)
    if value is not None and (not math.isfinite(value) or value < minimum):
        raise ScenarioError(f"输入 {field_name} 必须大于等于 {minimum:g}。")


def _validate_ratio(evidence: Evidence, field_name: str) -> None:
    value = _numeric_value(evidence, field_name)
    if value is not None and (not math.isfinite(value) or not 0 <= value <= 1):
        raise ScenarioError(f"输入 {field_name} 必须是 0 到 1 之间的比例。")


def _validate_enum(evidence: Evidence, field_name: str, allowed: set[str]) -> None:
    if evidence.status != "PENDING" and evidence.value not in allowed:
        options = "、".join(sorted(allowed))
        raise ScenarioError(f"输入 {field_name} 必须是 {options} 之一。")


def _validate_query_patterns(evidence: Evidence) -> None:
    if evidence.status == "PENDING":
        return
    if (
        not isinstance(evidence.value, list)
        or not evidence.value
        or not all(isinstance(item, str) and item.strip() for item in evidence.value)
    ):
        raise ScenarioError("输入 expected_query_patterns 必须是非空字符串数组。")


def _validate_retention_days(raw: Any) -> tuple[int, ...]:
    if not isinstance(raw, list) or not raw:
        raise ScenarioError("retention_days 必须是非空整数数组。")
    values: list[int] = []
    for value in raw:
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ScenarioError("retention_days 必须只包含大于 0 的整数。")
        values.append(value)
    if len(set(values)) != len(values):
        raise ScenarioError("retention_days 不能包含重复值。")
    for required in DEFAULT_RETENTION_DAYS:
        if required not in values:
            raise ScenarioError("retention_days 必须至少包含 30、90 和 365。")
    return tuple(sorted(values))


def _validate_decision_criteria(criteria: Mapping[str, Any]) -> None:
    required = (
        "postgresql_max_retained_rows",
        "postgresql_max_retained_storage_bytes",
        "postgresql_hot_query_patterns",
        "s3_artifact_query_patterns",
        "required_measured_inputs",
        *REQUIRED_CRITERIA_EVIDENCE,
    )
    missing = [field for field in required if field not in criteria]
    if missing:
        raise ScenarioError(f"decision_criteria 缺少字段：{', '.join(missing)}。")
    for field in ("postgresql_max_retained_rows", "postgresql_max_retained_storage_bytes"):
        value = criteria[field]
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ScenarioError(f"decision_criteria.{field} 必须是大于 0 的整数。")
    for field in ("postgresql_hot_query_patterns", "s3_artifact_query_patterns", "required_measured_inputs"):
        value = criteria[field]
        if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
            raise ScenarioError(f"decision_criteria.{field} 必须是字符串数组。")
    for field in REQUIRED_CRITERIA_EVIDENCE:
        evidence = Evidence.from_mapping(f"decision_criteria.{field}", criteria[field])
        if evidence.status != "PENDING" and not isinstance(evidence.value, bool):
            raise ScenarioError(f"decision_criteria.{field} 的 value 必须是布尔值。")


def _numeric_value(evidence: Evidence, field_name: str) -> float | None:
    if evidence.status == "PENDING":
        return None
    value = evidence.value
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ScenarioError(f"输入 {field_name} 必须是数字。")
    return float(value)


def _input_value(scenario: Scenario, field_name: str) -> Any | None:
    evidence = scenario.inputs[field_name]
    return None if evidence.status == "PENDING" else evidence.value


def _ceil_estimate(value: float | None) -> int | None:
    if value is None:
        return None
    return math.ceil(value)


def _multiply(*values: float | None) -> float | None:
    result = 1.0
    for value in values:
        if value is None:
            return None
        result *= value
    return result


def _daily_camera_video_assets(scenario: Scenario) -> int | None:
    videos_per_day = _input_value(scenario, "videos_per_day")
    average_camera_count = _input_value(scenario, "average_camera_count")
    basis = _input_value(scenario, "video_count_basis")
    if videos_per_day is None or average_camera_count is None or basis is None:
        return None
    if basis == "capture_session":
        return _ceil_estimate(float(videos_per_day) * float(average_camera_count))
    return _ceil_estimate(float(videos_per_day))


def _case_estimate(
    scenario: Scenario,
    duration_minutes: float | None,
    density: float | None,
    theoretical_candidates_per_minute: float | None,
    daily_camera_video_assets: int | None,
) -> dict[str, Any]:
    candidates_per_video = _ceil_estimate(
        _multiply(duration_minutes, theoretical_candidates_per_minute, density)
    )
    rows_per_day = _ceil_estimate(
        _multiply(
            candidates_per_video,
            float(daily_camera_video_assets) if daily_camera_video_assets is not None else None,
        )
    )
    return {
        "rows_per_video": candidates_per_video,
        "rows_per_day": rows_per_day,
        "duration_minutes": duration_minutes,
        "candidate_density": density,
    }


def _retention_estimate(
    daily_rows: int | None,
    retention_days: int,
    growth_factor: float | None,
    row_bytes: float | None,
    index_overhead_ratio: float | None,
) -> dict[str, Any]:
    retained_rows = _ceil_estimate(
        _multiply(float(daily_rows) if daily_rows is not None else None, retention_days)
    )
    adjusted_rows = _ceil_estimate(
        _multiply(float(retained_rows) if retained_rows is not None else None, growth_factor)
    )
    raw_storage = _ceil_estimate(
        _multiply(float(adjusted_rows) if adjusted_rows is not None else None, row_bytes)
    )
    indexed_storage = _ceil_estimate(
        _multiply(
            float(raw_storage) if raw_storage is not None else None,
            1 + index_overhead_ratio if index_overhead_ratio is not None else None,
        )
    )
    return {
        "retained_rows": retained_rows,
        "retained_rows_with_growth_factor": adjusted_rows,
        "raw_storage_bytes": raw_storage,
        "storage_bytes_including_index_overhead": indexed_storage,
    }


def calculate_capacity(scenario: Scenario) -> dict[str, Any]:
    """Calculate capacity estimates while preserving nulls for PENDING evidence."""

    inference_fps = _input_value(scenario, "inference_fps")
    max_hands = _input_value(scenario, "max_hands_per_frame")
    theoretical_per_minute = _ceil_estimate(
        _multiply(
            float(inference_fps) if inference_fps is not None else None,
            60,
            float(max_hands) if max_hands is not None else None,
        )
    )
    average_duration = _input_value(scenario, "average_video_duration_minutes")
    p95_duration = _input_value(scenario, "p95_video_duration_minutes")
    density = _input_value(scenario, "observed_or_assumed_candidate_density")
    daily_assets = _daily_camera_video_assets(scenario)
    average_case = _case_estimate(
        scenario,
        float(average_duration) if average_duration is not None else None,
        float(density) if density is not None else None,
        float(theoretical_per_minute) if theoretical_per_minute is not None else None,
        daily_assets,
    )
    p95_case = _case_estimate(
        scenario,
        float(p95_duration) if p95_duration is not None else None,
        float(density) if density is not None else None,
        float(theoretical_per_minute) if theoretical_per_minute is not None else None,
        daily_assets,
    )
    upper_bound_case = _case_estimate(
        scenario,
        float(average_duration) if average_duration is not None else None,
        1.0,
        float(theoretical_per_minute) if theoretical_per_minute is not None else None,
        daily_assets,
    )
    p95_upper_bound_case = _case_estimate(
        scenario,
        float(p95_duration) if p95_duration is not None else None,
        1.0,
        float(theoretical_per_minute) if theoretical_per_minute is not None else None,
        daily_assets,
    )

    growth_factor = _input_value(scenario, "growth_factor")
    row_bytes = _input_value(scenario, "estimated_candidate_row_bytes")
    index_ratio = _input_value(scenario, "index_overhead_ratio")
    cases: dict[str, dict[str, Any]] = {
        "average_case": average_case,
        "p95_duration_case": p95_case,
        "theoretical_upper_bound": upper_bound_case,
        "p95_theoretical_upper_bound": p95_upper_bound_case,
    }
    retention: dict[str, dict[str, Any]] = {}
    for days in scenario.retention_days:
        retention[str(days)] = {
            case_name: _retention_estimate(
                case["rows_per_day"],
                days,
                float(growth_factor) if growth_factor is not None else None,
                float(row_bytes) if row_bytes is not None else None,
                float(index_ratio) if index_ratio is not None else None,
            )
            for case_name, case in cases.items()
        }
    return {
        "theoretical_candidates_per_minute": theoretical_per_minute,
        "daily_camera_video_assets": daily_assets,
        "video_count_basis": _input_value(scenario, "video_count_basis"),
        "cases": cases,
        "retention": retention,
        "storage_inputs": {
            "estimated_candidate_row_bytes": row_bytes,
            "index_overhead_ratio": index_ratio,
            "growth_factor": growth_factor,
        },
    }


def _evidence_items(scenario: Scenario, status: EvidenceStatus) -> list[dict[str, Any]]:
    return [
        {"name": name, **evidence.as_dict()}
        for name, evidence in scenario.inputs.items()
        if evidence.status == status
    ]


def _criteria_evidence(scenario: Scenario, name: str) -> Evidence:
    return Evidence.from_mapping(f"decision_criteria.{name}", scenario.decision_criteria[name])


def evaluate_decision(scenario: Scenario, capacity: Mapping[str, Any]) -> dict[str, Any]:
    criteria = scenario.decision_criteria
    required_measured = cast(list[str], criteria["required_measured_inputs"])
    required_measurement_names = list(dict.fromkeys([*required_measured, "expected_query_patterns"]))
    missing_measurements = [
        name
        for name in required_measurement_names
        if name not in scenario.inputs or scenario.inputs[name].status != "MEASURED"
    ]
    missing_criteria_evidence = [
        name for name in REQUIRED_CRITERIA_EVIDENCE if _criteria_evidence(scenario, name).status != "MEASURED"
    ]
    expected_query_patterns = _input_value(scenario, "expected_query_patterns")
    retained = capacity["retention"][str(max(scenario.retention_days))]["p95_duration_case"]
    retained_rows = retained["retained_rows_with_growth_factor"]
    retained_storage = retained["storage_bytes_including_index_overhead"]

    gates: list[dict[str, Any]] = []
    gates.append(
        {
            "name": "required_measured_evidence",
            "status": "PASS" if not missing_measurements else "PENDING",
            "missing": missing_measurements,
        }
    )
    gates.append(
        {
            "name": "decision_criteria_evidence",
            "status": "PASS" if not missing_criteria_evidence else "PENDING",
            "missing": missing_criteria_evidence,
        }
    )
    gates.append(
        {
            "name": "p95_retained_capacity_available",
            "status": "PASS" if retained_rows is not None and retained_storage is not None else "PENDING",
            "retained_rows": retained_rows,
            "retained_storage_bytes": retained_storage,
        }
    )
    if missing_measurements or missing_criteria_evidence or retained_rows is None or retained_storage is None:
        return {
            "status": "DECISION PENDING",
            "option": None,
            "evaluated_retention_days": max(scenario.retention_days),
            "evaluated_case": "p95_duration_case",
            "reason": "缺少足够的实测容量、查询或运维证据，不能选择 PostgreSQL 或 S3。",
            "gates": gates,
        }

    patterns = set(cast(list[str], expected_query_patterns))
    postgres_patterns = set(cast(list[str], criteria["postgresql_hot_query_patterns"]))
    s3_patterns = set(cast(list[str], criteria["s3_artifact_query_patterns"]))
    postgres_fit = (
        retained_rows <= criteria["postgresql_max_retained_rows"]
        and retained_storage <= criteria["postgresql_max_retained_storage_bytes"]
    )
    postgres_fit = (
        postgres_fit and _criteria_evidence(scenario, "postgresql_partition_index_feasible").value is True
    )
    postgres_fit = (
        postgres_fit
        and _criteria_evidence(scenario, "postgresql_operational_complexity_acceptable").value is True
    )
    postgres_query_fit = patterns.issubset(postgres_patterns)
    s3_tradeoff_ok = _criteria_evidence(scenario, "s3_artifact_retrieval_tradeoff_acceptable").value is True
    gates.extend(
        [
            {
                "name": "postgresql_thresholds",
                "status": "PASS" if postgres_fit else "FAIL",
                "retained_rows": retained_rows,
                "retained_storage_bytes": retained_storage,
                "max_retained_rows": criteria["postgresql_max_retained_rows"],
                "max_retained_storage_bytes": criteria["postgresql_max_retained_storage_bytes"],
            },
            {
                "name": "postgresql_query_pattern_fit",
                "status": "PASS" if postgres_query_fit else "FAIL",
                "observed": sorted(patterns),
                "supported": sorted(postgres_patterns),
            },
            {
                "name": "s3_artifact_tradeoff",
                "status": "PASS" if s3_tradeoff_ok else "FAIL",
            },
        ]
    )
    if postgres_fit and postgres_query_fit:
        return {
            "status": "DECISION READY",
            "option": "A_POSTGRESQL_DENSE_CANDIDATE",
            "evaluated_retention_days": max(scenario.retention_days),
            "evaluated_case": "p95_duration_case",
            "reason": "实测容量在 PostgreSQL 阈值内，分区/索引与运维证据通过，查询模式适合热查询。",
            "gates": gates,
        }
    if s3_tradeoff_ok and (not postgres_fit or not postgres_query_fit or patterns & s3_patterns):
        return {
            "status": "DECISION READY",
            "option": "B_POSTGRESQL_FACTS_S3_DENSE_ARTIFACT",
            "evaluated_retention_days": max(scenario.retention_days),
            "evaluated_case": "p95_duration_case",
            "reason": "密集结果超出 PostgreSQL 容量/查询边界，或查询模式更适合版本化对象工件。",
            "gates": gates,
        }
    return {
        "status": "DECISION PENDING",
        "option": None,
        "evaluated_retention_days": max(scenario.retention_days),
        "evaluated_case": "p95_duration_case",
        "reason": "当前配置没有通过任一完整决策路径，需补充边界证据。",
        "gates": gates,
    }


def build_report(scenario: Scenario) -> dict[str, Any]:
    capacity = calculate_capacity(scenario)
    decision = evaluate_decision(scenario, capacity)
    required_measured = cast(list[str], scenario.decision_criteria["required_measured_inputs"])
    measurement_gaps = [
        name
        for name in dict.fromkeys([*required_measured, "expected_query_patterns"])
        if name not in scenario.inputs or scenario.inputs[name].status != "MEASURED"
    ]
    pending_evidence = [item["name"] for item in _evidence_items(scenario, "PENDING")]
    pending_evidence.extend(measurement_gaps)
    pending_evidence.extend(
        f"decision_criteria.{name}"
        for name in REQUIRED_CRITERIA_EVIDENCE
        if _criteria_evidence(scenario, name).status != "MEASURED"
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "scenario": {
            "scenario_id": scenario.scenario_id,
            "description": scenario.description,
            "retention_days": list(scenario.retention_days),
            "inputs": {name: evidence.as_dict() for name, evidence in scenario.inputs.items()},
            "decision_criteria": scenario.decision_criteria,
        },
        "facts": [
            {
                "name": "theoretical_candidates_per_minute_formula",
                "value": "inference_fps × 60 × max_hands_per_frame",
            },
            {
                "name": "theoretical_candidates_per_minute",
                "value": capacity["theoretical_candidates_per_minute"],
            },
            {
                "name": "candidate_density_definition",
                "value": "observed candidates / theoretical frame-hand slots",
            },
            *_evidence_items(scenario, "MEASURED"),
        ],
        "assumptions": _evidence_items(scenario, "ASSUMED"),
        "calculated_results": capacity,
        "pending_evidence": list(dict.fromkeys(pending_evidence)),
        "decision": decision,
        "safety_boundary": (
            "This review prepares evidence only; it never enables P1 batch or automatic policy behavior."
        ),
    }


def _display_value(value: Any) -> str:
    if value is None:
        return "PENDING"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float) and value.is_integer():
        return f"{int(value):,}"
    if isinstance(value, (int, float)):
        return f"{value:,}"
    if isinstance(value, list):
        return ", ".join(str(item) for item in value)
    return str(value)


def render_markdown(scenario: Scenario, report: Mapping[str, Any] | None = None) -> str:
    report_data = report if report is not None else build_report(scenario)
    capacity = cast(dict[str, Any], report_data["calculated_results"])
    decision = cast(dict[str, Any], report_data["decision"])
    lines = [
        "# CandidateAnnotation Capacity Review",
        "",
        f"- Scenario: `{scenario.scenario_id}`",
        f"- Schema: `{SCHEMA_VERSION}`",
        f"- Decision: **{decision['status']}**",
        "",
        "## FACTS",
        "",
        "- Theoretical candidates/minute = `inference_fps × 60 × max_hands_per_frame`.",
        (
            "- Configured theoretical candidates/minute: "
            f"**{_display_value(capacity['theoretical_candidates_per_minute'])}**."
        ),
        "- Candidate density is an observation ratio, not Ground Truth.",
    ]
    for item in report_data["facts"]:
        if item["name"].startswith("theoretical_") or item["name"] == "candidate_density_definition":
            continue
        lines.append(f"- `{item['name']}` ({item['status']}): {_display_value(item['value'])}.")
    lines.extend(
        ["", "## ASSUMPTIONS", "", "| Input | Status | Value | Note |", "| --- | --- | ---: | --- |"]
    )
    for item in report_data["assumptions"]:
        lines.append(
            f"| `{item['name']}` | {item['status']} | {_display_value(item['value'])} | {item['note']} |"
        )
    if not report_data["assumptions"]:
        lines.append("| — | — | — | None |")

    lines.extend(
        [
            "",
            "## CALCULATED RESULTS",
            "",
            "### Daily estimates",
            "",
            "| Case | Rows/video | Rows/day |",
            "| --- | ---: | ---: |",
        ]
    )
    for case_name, case in capacity["cases"].items():
        lines.append(
            f"| `{case_name}` | {_display_value(case['rows_per_video'])} | "
            f"{_display_value(case['rows_per_day'])} |"
        )
    lines.extend(
        [
            "",
            "### Retention and storage",
            "",
            "| Retention | Case | Rows | Raw bytes | With index overhead |",
            "| ---: | --- | ---: | ---: | ---: |",
        ]
    )
    for days in scenario.retention_days:
        for case_name, values in capacity["retention"][str(days)].items():
            lines.append(
                f"| {days} days | `{case_name}` | "
                f"{_display_value(values['retained_rows_with_growth_factor'])} "
                f"| {_display_value(values['raw_storage_bytes'])} | "
                f"{_display_value(values['storage_bytes_including_index_overhead'])} |"
            )
    lines.extend(["", "## PENDING EVIDENCE", ""])
    pending = report_data["pending_evidence"]
    if pending:
        lines.extend(f"- `{name}`" for name in pending)
    else:
        lines.append("- None")
    lines.extend(["", "## DECISION GATES", "", "| Gate | Status | Detail |", "| --- | --- | --- |"])
    for gate in decision["gates"]:
        detail = ", ".join(
            f"{key}={_display_value(value)}"
            for key, value in gate.items()
            if key not in {"name", "status", "missing"}
        )
        missing = gate.get("missing")
        if missing:
            detail = f"missing: {', '.join(missing)}" + (f"; {detail}" if detail else "")
        lines.append(f"| `{gate['name']}` | {gate['status']} | {detail} |")
    lines.extend(
        [
            "",
            f"**Reason:** {decision['reason']}",
            "",
            (
                "> This tool prepares evidence only. It does not enable P1 batch hand-pose, automatic "
                "policy behavior, dense-result migration, S3 runtime, jobs, schema changes, or UI."
            ),
            "",
        ]
    )
    return "\n".join(lines)


def load_scenario(path: Path) -> Scenario:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ScenarioError(f"无法读取 scenario 文件：{path}") from exc
    except json.JSONDecodeError as exc:
        raise ScenarioError(f"scenario JSON 无法解析：{path}") from exc
    if not isinstance(raw, Mapping):
        raise ScenarioError("scenario JSON 顶层必须是对象。")
    return Scenario.from_mapping(raw)


def write_outputs(scenario: Scenario, json_output: Path, markdown_output: Path) -> dict[str, Any]:
    report = build_report(scenario)
    json_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_output.write_text(render_markdown(scenario, report), encoding="utf-8")
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate an offline CandidateAnnotation capacity review.")
    parser.add_argument("--input", required=True, type=Path, help="Versioned scenario JSON file.")
    parser.add_argument("--json-output", required=True, type=Path, help="Machine-readable JSON output path.")
    parser.add_argument("--markdown-output", required=True, type=Path, help="Concise Markdown report path.")
    args = parser.parse_args(argv)
    try:
        scenario = load_scenario(args.input)
        report = write_outputs(scenario, args.json_output, args.markdown_output)
    except (OSError, ScenarioError) as exc:
        print(f"Candidate capacity review failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "ok", "decision": report["decision"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
