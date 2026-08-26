#!/usr/bin/env python3
"""Validate and report offline hand-pose evaluation evidence.

This module intentionally has no network, database, model, or media-runtime
integration.  It accepts only the versioned evidence document defined in the
tool directory and writes deterministic, redacted reports from that document.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, NoReturn

EVIDENCE_FORMAT = "hand-pose-evaluation.v1"

METRIC_NAMES = (
    "HAND_FRAME_RECALL",
    "NO_HAND_FRAME_FALSE_POSITIVE_RATE",
    "LANDMARK_21_VALID_RATE",
    "HANDEDNESS_ACCURACY",
    "FAST_MOTION_OCCLUSION_USEFULNESS",
    "OVERLAY_TIME_ERROR",
    "PROCESSING_TIME_PER_MINUTE",
)
RATE_METRICS = frozenset(
    {
        "HAND_FRAME_RECALL",
        "NO_HAND_FRAME_FALSE_POSITIVE_RATE",
        "LANDMARK_21_VALID_RATE",
        "HANDEDNESS_ACCURACY",
        "FAST_MOTION_OCCLUSION_USEFULNESS",
    }
)
METRIC_UNITS = {
    "HAND_FRAME_RECALL": "rate",
    "NO_HAND_FRAME_FALSE_POSITIVE_RATE": "rate",
    "LANDMARK_21_VALID_RATE": "rate",
    "HANDEDNESS_ACCURACY": "rate",
    "FAST_MOTION_OCCLUSION_USEFULNESS": "rate",
    "OVERLAY_TIME_ERROR": "milliseconds",
    "PROCESSING_TIME_PER_MINUTE": "seconds_per_video_minute",
}

INVARIANT_NAMES = (
    "WORKER_INTERRUPTION_RECOVERY",
    "ORIGINAL_VIDEO_UNMODIFIED",
    "CANDIDATE_NOT_FORMAL_ANNOTATION",
)

_SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
_SAFE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_UNSAFE_TEXT_PATTERNS = (
    re.compile(r"https?://|s3://|presign(?:ed)?", re.IGNORECASE),
    re.compile(r"(?:access[_ -]?token|bearer\s+|authorization\s*:)", re.IGNORECASE),
    re.compile(r"患者|patient|医院账号|hospital account", re.IGNORECASE),
    re.compile(r"\.(?:mp4|mov|mkv|avi)\b", re.IGNORECASE),
    re.compile(r"-----BEGIN [A-Z ]+ KEY-----", re.IGNORECASE),
)

_TOP_LEVEL_FIELDS = frozenset(
    {
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
    }
)
_METRIC_FIELDS = frozenset({"name", "status", "value"})
_INVARIANT_FIELDS = frozenset({"name", "status", "evidence"})
_EVIDENCE_FIELDS = frozenset({"reference", "description"})
_AUTHORIZATION_FIELDS = frozenset({"status", "evidence"})


class EvidenceError(ValueError):
    """Raised when an evidence document cannot be safely normalized."""


@dataclass(frozen=True)
class MetricEvidence:
    name: str
    status: str
    value: float | None

    def to_mapping(self) -> dict[str, object]:
        return {"name": self.name, "status": self.status, "value": self.value}


@dataclass(frozen=True)
class InvariantEvidence:
    name: str
    status: str
    evidence: dict[str, str | None] | None

    def to_mapping(self) -> dict[str, object]:
        return {"name": self.name, "status": self.status, "evidence": self.evidence}


@dataclass(frozen=True)
class EvidenceDocument:
    evaluation_id: str
    sample_version: str | None
    sample_sha256: str | None
    sample_authorization: dict[str, str]
    model_sha256: str
    profile_sha256: str
    parameter_adjustment_note: str
    metrics: tuple[MetricEvidence, ...]
    invariants: tuple[InvariantEvidence, ...]

    def to_mapping(self) -> dict[str, object]:
        return {
            "format": EVIDENCE_FORMAT,
            "evaluation_id": self.evaluation_id,
            "sample_version": self.sample_version,
            "sample_sha256": self.sample_sha256,
            "sample_authorization": self.sample_authorization,
            "model_sha256": self.model_sha256,
            "profile_sha256": self.profile_sha256,
            "parameter_adjustment_note": self.parameter_adjustment_note,
            "metrics": [metric.to_mapping() for metric in self.metrics],
            "invariants": [invariant.to_mapping() for invariant in self.invariants],
        }


def _expect_mapping(value: object, context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise EvidenceError(f"{context} 必须是对象。")
    return value


def _expect_list(value: object, context: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvidenceError(f"{context} 必须是数组。")
    return value


def _reject_unknown_fields(data: dict[str, Any], allowed: frozenset[str], context: str) -> None:
    unknown = sorted(set(data) - allowed)
    if unknown:
        raise EvidenceError(f"{context} 包含不支持的字段：{', '.join(unknown)}。")


def _require_field(data: dict[str, Any], field: str, context: str) -> Any:
    if field not in data:
        raise EvidenceError(f"{context} 缺少字段 {field}。")
    return data[field]


def _safe_text(value: object, context: str, *, max_length: int = 2000) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvidenceError(f"{context} 必须是非空字符串。")
    normalized = value.strip()
    if len(normalized) > max_length:
        raise EvidenceError(f"{context} 过长。")
    if any(pattern.search(normalized) for pattern in _UNSAFE_TEXT_PATTERNS):
        raise EvidenceError(f"{context} 包含不允许的敏感内容或外部地址。")
    return normalized


def _safe_identifier(value: object, context: str) -> str:
    normalized = _safe_text(value, context, max_length=128)
    if not _SAFE_ID_PATTERN.fullmatch(normalized):
        raise EvidenceError(f"{context} 只能包含字母、数字、点、下划线、冒号和短横线。")
    return normalized


def _optional_text(value: object, context: str, *, max_length: int = 256) -> str | None:
    if value is None:
        return None
    return _safe_text(value, context, max_length=max_length)


def _required_sha256(value: object, context: str) -> str:
    if not isinstance(value, str) or not _SHA256_PATTERN.fullmatch(value):
        raise EvidenceError(f"{context} 必须是 64 位十六进制 SHA-256。")
    return value.lower()


def _optional_sha256(value: object, context: str) -> str | None:
    if value is None:
        return None
    return _required_sha256(value, context)


def _parse_sample_authorization(value: object) -> dict[str, str]:
    data = _expect_mapping(value, "sample_authorization")
    _reject_unknown_fields(data, _AUTHORIZATION_FIELDS, "sample_authorization")
    status = _require_field(data, "status", "sample_authorization")
    if status != "AUTHORIZED":
        raise EvidenceError("sample_authorization.status 必须为 AUTHORIZED；未授权样本拒绝处理。")
    evidence = _safe_text(
        _require_field(data, "evidence", "sample_authorization"),
        "sample_authorization.evidence",
    )
    return {"status": "AUTHORIZED", "evidence": evidence}


def _parse_metric(value: object, index: int) -> MetricEvidence:
    context = f"metrics[{index}]"
    data = _expect_mapping(value, context)
    _reject_unknown_fields(data, _METRIC_FIELDS, context)
    name = _safe_identifier(_require_field(data, "name", context), f"{context}.name")
    if name not in METRIC_NAMES:
        raise EvidenceError(f"{context}.name 不是冻结的评估指标。")
    status = _require_field(data, "status", context)
    if status not in {"PENDING", "RECORDED"}:
        raise EvidenceError(f"{context}.status 必须为 PENDING 或 RECORDED。")
    value = _require_field(data, "value", context)
    if status == "PENDING":
        if value is not None:
            raise EvidenceError(f"{context} 为 PENDING 时 value 必须为 null。")
        return MetricEvidence(name=name, status=status, value=None)

    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"{context} 为 RECORDED 时 value 必须是数字。")
    try:
        numeric_value = float(value)
    except (OverflowError, ValueError) as exc:
        raise EvidenceError(f"{context}.value 不是有限数字。") from exc
    if not math.isfinite(numeric_value) or numeric_value < 0:
        raise EvidenceError(f"{context}.value 必须是有限非负数字。")
    if name in RATE_METRICS and numeric_value > 1:
        raise EvidenceError(f"{context}.value 作为 rate 必须位于 0..1。")
    return MetricEvidence(name=name, status=status, value=numeric_value)


def _parse_invariant_evidence(value: object, context: str) -> dict[str, str | None] | None:
    if value is None:
        return None
    data = _expect_mapping(value, context)
    _reject_unknown_fields(data, _EVIDENCE_FIELDS, context)
    reference = _optional_text(data.get("reference"), f"{context}.reference")
    description = _optional_text(
        data.get("description"),
        f"{context}.description",
        max_length=2000,
    )
    if reference is None and description is None:
        raise EvidenceError(f"{context} 至少需要 reference 或 description。")
    return {"reference": reference, "description": description}


def _parse_invariant(value: object, index: int) -> InvariantEvidence:
    context = f"invariants[{index}]"
    data = _expect_mapping(value, context)
    _reject_unknown_fields(data, _INVARIANT_FIELDS, context)
    name = _safe_identifier(_require_field(data, "name", context), f"{context}.name")
    if name not in INVARIANT_NAMES:
        raise EvidenceError(f"{context}.name 不是冻结的 P0 不变量。")
    status = _require_field(data, "status", context)
    if status not in {"PASS", "PENDING"}:
        raise EvidenceError(f"{context}.status 必须为 PASS 或 PENDING。")
    evidence = _parse_invariant_evidence(
        _require_field(data, "evidence", context),
        f"{context}.evidence",
    )
    if status == "PASS" and evidence is None:
        raise EvidenceError(f"{context} 为 PASS 时必须提供 evidence。")
    return InvariantEvidence(name=name, status=status, evidence=evidence)


def normalize_evidence(value: object) -> EvidenceDocument:
    """Validate and normalize an evidence JSON object."""

    data = _expect_mapping(value, "evidence")
    _reject_unknown_fields(data, _TOP_LEVEL_FIELDS, "evidence")
    if _require_field(data, "format", "evidence") != EVIDENCE_FORMAT:
        raise EvidenceError(f"evidence.format 必须为 {EVIDENCE_FORMAT}。")

    evaluation_id = _safe_identifier(
        _require_field(data, "evaluation_id", "evidence"),
        "evaluation_id",
    )
    sample_version = _optional_text(
        _require_field(data, "sample_version", "evidence"),
        "sample_version",
    )
    sample_sha256 = _optional_sha256(
        _require_field(data, "sample_sha256", "evidence"),
        "sample_sha256",
    )
    if sample_version is None and sample_sha256 is None:
        raise EvidenceError("sample_version 和 sample_sha256 至少提供一个。")

    model_sha256 = _required_sha256(
        _require_field(data, "model_sha256", "evidence"),
        "model_sha256",
    )
    profile_sha256 = _required_sha256(
        _require_field(data, "profile_sha256", "evidence"),
        "profile_sha256",
    )
    parameter_adjustment_note = _safe_text(
        _require_field(data, "parameter_adjustment_note", "evidence"),
        "parameter_adjustment_note",
        max_length=4000,
    )

    raw_metrics = _expect_list(_require_field(data, "metrics", "evidence"), "metrics")
    if len(raw_metrics) != len(METRIC_NAMES):
        raise EvidenceError(f"metrics 必须恰好包含 {len(METRIC_NAMES)} 个冻结指标。")
    metrics = tuple(_parse_metric(item, index) for index, item in enumerate(raw_metrics))
    metric_names = [metric.name for metric in metrics]
    if len(set(metric_names)) != len(metric_names):
        raise EvidenceError("metrics 不允许重复指标。")
    missing_metrics = [name for name in METRIC_NAMES if name not in metric_names]
    if missing_metrics:
        raise EvidenceError(f"metrics 缺少指标：{', '.join(missing_metrics)}。")
    metrics = tuple(sorted(metrics, key=lambda metric: METRIC_NAMES.index(metric.name)))

    raw_invariants = _expect_list(
        _require_field(data, "invariants", "evidence"),
        "invariants",
    )
    if len(raw_invariants) != len(INVARIANT_NAMES):
        raise EvidenceError(f"invariants 必须恰好包含 {len(INVARIANT_NAMES)} 个 P0 不变量。")
    invariants = tuple(_parse_invariant(item, index) for index, item in enumerate(raw_invariants))
    invariant_names = [invariant.name for invariant in invariants]
    if len(set(invariant_names)) != len(invariant_names):
        raise EvidenceError("invariants 不允许重复不变量。")
    missing_invariants = [name for name in INVARIANT_NAMES if name not in invariant_names]
    if missing_invariants:
        raise EvidenceError(f"invariants 缺少不变量：{', '.join(missing_invariants)}。")
    invariants = tuple(sorted(invariants, key=lambda item: INVARIANT_NAMES.index(item.name)))

    return EvidenceDocument(
        evaluation_id=evaluation_id,
        sample_version=sample_version,
        sample_sha256=sample_sha256,
        sample_authorization=_parse_sample_authorization(
            _require_field(data, "sample_authorization", "evidence")
        ),
        model_sha256=model_sha256,
        profile_sha256=profile_sha256,
        parameter_adjustment_note=parameter_adjustment_note,
        metrics=metrics,
        invariants=invariants,
    )


def _reject_json_constant(value: str) -> NoReturn:
    raise EvidenceError(f"JSON 不允许使用非标准数字 {value}。")


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError(f"JSON 对象包含重复字段 {key}。")
        result[key] = value
    return result


def load_evidence(path: Path) -> EvidenceDocument:
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise EvidenceError(f"无法读取证据文件：{path}") from exc
    try:
        value = json.loads(
            raw,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except json.JSONDecodeError as exc:
        raise EvidenceError(f"证据 JSON 无效：第 {exc.lineno} 行。") from exc
    return normalize_evidence(value)


def canonical_json(document: EvidenceDocument) -> str:
    """Serialize a normalized document deterministically."""

    return (
        json.dumps(
            document.to_mapping(),
            ensure_ascii=False,
            indent=2,
            separators=(",", ": "),
        )
        + "\n"
    )


def _markdown_cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def render_markdown(document: EvidenceDocument) -> str:
    """Render a concise human-readable summary without adding source data."""

    sample_binding = document.sample_version or "（未提供版本）"
    if document.sample_sha256:
        sample_binding += f" / sha256 `{document.sample_sha256}`"
    lines = [
        "# Hand-pose evaluation evidence",
        "",
        f"- Format: `{EVIDENCE_FORMAT}`",
        f"- Evaluation ID: `{document.evaluation_id}`",
        f"- Sample: `{sample_binding}`",
        f"- Sample authorization: `{document.sample_authorization['status']}` — "
        f"{_markdown_cell(document.sample_authorization['evidence'])}",
        f"- Model SHA-256: `{document.model_sha256}`",
        f"- Profile SHA-256: `{document.profile_sha256}`",
        f"- Parameter adjustment: {_markdown_cell(document.parameter_adjustment_note)}",
        "",
        "## Metrics",
        "",
        "| Metric | Status | Value | Unit |",
        "| --- | --- | ---: | --- |",
    ]
    for metric in document.metrics:
        value = "PENDING" if metric.value is None else format(metric.value, ".15g")
        lines.append(f"| `{metric.name}` | `{metric.status}` | {value} | {METRIC_UNITS[metric.name]} |")
    lines.extend(
        [
            "",
            "## P0 invariant evidence",
            "",
            "| Invariant | Status | Evidence |",
            "| --- | --- | --- |",
        ]
    )
    for invariant in document.invariants:
        if invariant.evidence is None:
            evidence = "待证明"
        else:
            evidence_parts = [
                part
                for part in (
                    invariant.evidence.get("reference"),
                    invariant.evidence.get("description"),
                )
                if part
            ]
            evidence = "；".join(evidence_parts)
        lines.append(f"| `{invariant.name}` | `{invariant.status}` | {_markdown_cell(evidence)} |")
    lines.extend(
        [
            "",
            "> PENDING means no evaluation evidence has been recorded; it is not zero and is not a pass.",
            "> CandidateAnnotation geometry and handedness remain auxiliary evidence, not Ground Truth.",
            "",
        ]
    )
    return "\n".join(lines)


def _write_text(path: Path, content: str) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")
    except OSError as exc:
        raise EvidenceError(f"无法写入报告：{path}") from exc


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate offline hand-pose evaluation evidence.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="validate an evidence document")
    validate_parser.add_argument("input", type=Path)

    normalize_parser = subparsers.add_parser("normalize", help="write canonical evidence JSON")
    normalize_parser.add_argument("input", type=Path)
    normalize_parser.add_argument("--output", type=Path)

    report_parser = subparsers.add_parser("report", help="write canonical JSON and Markdown reports")
    report_parser.add_argument("input", type=Path)
    report_parser.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        document = load_evidence(args.input)
        if args.command == "validate":
            print(f"VALID: {EVIDENCE_FORMAT} ({document.evaluation_id})")
        elif args.command == "normalize":
            content = canonical_json(document)
            if args.output is None:
                print(content, end="")
            else:
                _write_text(args.output, content)
                print(f"WROTE: {args.output}")
        elif args.command == "report":
            output_dir: Path = args.output
            _write_text(output_dir / "hand_pose_evaluation.json", canonical_json(document))
            _write_text(output_dir / "hand_pose_evaluation.md", render_markdown(document))
            print(f"WROTE: {output_dir}")
        else:
            parser.error("unknown command")
    except EvidenceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
