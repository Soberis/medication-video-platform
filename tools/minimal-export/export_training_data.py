#!/usr/bin/env python3
"""Export Cleaning PASS hand observations and human action labels to Parquet.

The tool is deliberately read-only.  It reads the existing relational facts,
keeps MediaPipe geometry as candidate data, and never treats a candidate as a
formal action label or as handedness ground truth.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from collections.abc import Iterable
from pathlib import Path
from typing import Any
from uuid import UUID

EXPORT_SCHEMA_VERSION = "training-data.v2"
LANDMARK_COUNT = 21
LANDMARK_AXES = ("x", "y", "z")
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")


class ExportError(RuntimeError):
    """Raised when the source evidence cannot produce safe training rows."""


def load_dotenv(path: Path) -> None:
    """Load simple KEY=VALUE entries without overriding the process environment."""

    if not path.is_file():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if value and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        os.environ.setdefault(key, value)


def parse_jsonb(value: Any) -> Any:
    if isinstance(value, (str, bytes, bytearray)):
        try:
            return json.loads(value)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ExportError("数据库中的 JSONB 值无法解析。") from exc
    return value


def build_database_url() -> str:
    explicit = os.getenv("DATABASE_URL")
    if explicit:
        return explicit
    host = os.getenv("POSTGRES_HOST", "127.0.0.1")
    port = os.getenv("POSTGRES_PORT", "15432")
    database = os.getenv("POSTGRES_DB", "medication_platform")
    user = os.getenv("POSTGRES_USER", "medication_local")
    password = os.getenv("POSTGRES_PASSWORD", "change-me-local-only")
    return f"postgresql://{user}:{password}@{host}:{port}/{database}"


def query_eligible_assets(connection: Any, project_id: UUID) -> list[dict[str, Any]]:
    """Read the authoritative project-level eligibility projection."""

    sql = """
        SELECT video_id, project_id, content_type, asset_status, asset_deleted,
               asset_sha256, quality_decision, quality_review_version, task_status,
               annotation_revision_id, annotation_task_id, annotation_document,
               document_sha256, label_schema_id, label_schema_digest,
               annotation_review_id, annotation_review_decision, cleaning_result_id,
               cleaning_run_id, cleaning_outcome, cleaning_run_status,
               cleaning_rule_set_id, rule_set_digest
        FROM training_data_eligibility
        WHERE project_id = %s
        ORDER BY video_id
    """
    with connection.cursor() as cursor:
        cursor.execute(sql, (project_id,))
        return list(cursor.fetchall())


def query_candidates(connection: Any, project_id: UUID, video_ids: Iterable[UUID]) -> list[dict[str, Any]]:
    ids = list(video_ids)
    if not ids:
        return []
    placeholders = ", ".join(["%s"] * len(ids))
    sql = f"""
        SELECT candidate_annotation_id,
               project_id,
               video_id AS clip_asset_id,
               annotation_revision_id,
               algorithm_key,
               algorithm_version,
               frame_timestamp_ms,
               hand_index,
               handedness,
               handedness_score,
               landmarks,
               model_sha256,
               profile_sha256,
               valid_landmark_count
        FROM training_data_candidate_observations
        WHERE project_id = %s
          AND video_id IN ({placeholders})
        ORDER BY video_id, frame_timestamp_ms, hand_index, candidate_annotation_id
    """
    with connection.cursor() as cursor:
        cursor.execute(sql, (project_id, *ids))
        return list(cursor.fetchall())


def validate_eligibility(asset: dict[str, Any]) -> None:
    expected = {
        "asset_status": "INGESTED",
        "asset_deleted": False,
        "quality_decision": "QUALIFIED",
        "task_status": "COMPLETED",
        "annotation_review_decision": "APPROVED",
        "cleaning_outcome": "PASS",
        "cleaning_run_status": "SUCCEEDED",
    }
    for field, expected_value in expected.items():
        if asset.get(field) != expected_value:
            raise ExportError(
                f"视频 {asset.get('video_id')} 不满足训练数据准入：{field}={asset.get(field)!r}。"
            )
    if str(asset.get("content_type", "")).lower() != "video/mp4":
        raise ExportError(f"视频 {asset.get('video_id')} 不是 MP4。")
    for field in (
        "video_id",
        "annotation_revision_id",
        "annotation_task_id",
        "label_schema_id",
        "annotation_review_id",
        "cleaning_result_id",
        "cleaning_run_id",
        "cleaning_rule_set_id",
        "asset_sha256",
        "document_sha256",
        "label_schema_digest",
        "rule_set_digest",
    ):
        if asset.get(field) is None:
            raise ExportError(f"视频 {asset.get('video_id')} 缺少 Cleaning PASS 证据字段 {field}。")
    for field in (
        "asset_sha256",
        "document_sha256",
        "label_schema_digest",
        "rule_set_digest",
    ):
        if not isinstance(asset.get(field), str) or not SHA256_PATTERN.fullmatch(asset[field]):
            raise ExportError(f"视频 {asset.get('video_id')} 的证据字段 {field} 不是有效 SHA-256。")


def parse_segments(document: Any) -> list[dict[str, Any]]:
    value = parse_jsonb(document)
    if not isinstance(value, dict) or value.get("schema_version") != "annotation-document.v1":
        raise ExportError("人工 AnnotationRevision 不是 annotation-document.v1。")
    raw_segments = value.get("segments")
    if not isinstance(raw_segments, list) or not raw_segments:
        raise ExportError("人工 AnnotationRevision 没有有效动作 segments。")
    segments: list[dict[str, Any]] = []
    for segment in raw_segments:
        if not isinstance(segment, dict):
            raise ExportError("人工 AnnotationRevision 的 segment 不是对象。")
        start_ms = segment.get("start_ms")
        end_ms = segment.get("end_ms")
        label = segment.get("label")
        if (
            isinstance(start_ms, bool)
            or not isinstance(start_ms, int)
            or isinstance(end_ms, bool)
            or not isinstance(end_ms, int)
            or start_ms < 0
            or end_ms <= start_ms
            or not isinstance(label, str)
            or not label
        ):
            raise ExportError("人工 AnnotationRevision 含有无效动作 segment。")
        segments.append({"start_ms": start_ms, "end_ms": end_ms, "label": label})
    segments.sort(key=lambda item: (item["start_ms"], item["end_ms"]))
    previous_end = -1
    for segment in segments:
        if segment["start_ms"] < previous_end:
            raise ExportError("人工 AnnotationRevision 的动作 segments 存在重叠。")
        previous_end = segment["end_ms"]
    return segments


def action_segment_at(segments: list[dict[str, Any]], timestamp_ms: int) -> dict[str, Any] | None:
    for segment in segments:
        if segment["start_ms"] <= timestamp_ms < segment["end_ms"]:
            return segment
        if timestamp_ms < segment["start_ms"]:
            break
    return None


def landmark_columns() -> list[str]:
    return [f"landmark_{index:02d}_{axis}" for index in range(LANDMARK_COUNT) for axis in LANDMARK_AXES]


def coerce_unit_interval(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        numeric_value = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    if not math.isfinite(numeric_value) or not 0 <= numeric_value <= 1:
        return None
    return numeric_value


def flatten_landmarks(value: Any) -> dict[str, float]:
    landmarks = parse_jsonb(value)
    if not isinstance(landmarks, list) or len(landmarks) != LANDMARK_COUNT:
        raise ExportError("CandidateAnnotation 不是完整的 21 点 landmarks。")
    flattened: dict[str, float] = {}
    for index, point in enumerate(landmarks):
        if not isinstance(point, dict):
            raise ExportError("CandidateAnnotation 的 landmark 不是对象。")
        for axis in LANDMARK_AXES:
            coordinate = point.get(axis)
            if isinstance(coordinate, bool) or not isinstance(coordinate, (int, float)):
                raise ExportError("CandidateAnnotation 的 landmark 坐标不是数字。")
            coordinate_value = float(coordinate)
            if not math.isfinite(coordinate_value):
                raise ExportError("CandidateAnnotation 的 landmark 坐标不是有限数。")
            flattened[f"landmark_{index:02d}_{axis}"] = coordinate_value
    return flattened


def build_training_rows(
    assets: list[dict[str, Any]], candidates: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    assets_by_video = {str(asset["video_id"]): asset for asset in assets}
    segments_by_video: dict[str, list[dict[str, Any]]] = {}
    for asset in assets:
        validate_eligibility(asset)
        video_id = str(asset["video_id"])
        segments_by_video[video_id] = parse_segments(asset["annotation_document"])

    rows: list[dict[str, Any]] = []
    unlabeled_candidates = 0
    for candidate in candidates:
        video_id = str(candidate["clip_asset_id"])
        matched: dict[str, Any] | None = assets_by_video.get(video_id)
        if matched is None:
            raise ExportError(
                f"CandidateAnnotation {candidate.get('candidate_annotation_id')} 不属于准入视频。"
            )
        timestamp_ms = candidate.get("frame_timestamp_ms")
        if isinstance(timestamp_ms, bool) or not isinstance(timestamp_ms, int) or timestamp_ms < 0:
            raise ExportError("CandidateAnnotation 的 frame_timestamp_ms 无效。")
        action_segment = action_segment_at(segments_by_video[video_id], timestamp_ms)
        if action_segment is None:
            unlabeled_candidates += 1
            continue
        if candidate.get("annotation_revision_id") != matched["annotation_revision_id"]:
            raise ExportError(
                f"CandidateAnnotation {candidate.get('candidate_annotation_id')} "
                "未绑定当前人工 AnnotationRevision。"
            )
        hand_index = candidate.get("hand_index")
        handedness = candidate.get("handedness")
        handedness_score = coerce_unit_interval(candidate.get("handedness_score"))
        if (
            isinstance(hand_index, bool)
            or not isinstance(hand_index, int)
            or hand_index not in (0, 1)
            or handedness not in {"LEFT", "RIGHT", "UNKNOWN"}
            or handedness_score is None
        ):
            raise ExportError(
                f"CandidateAnnotation {candidate.get('candidate_annotation_id')} 的手部元数据无效。"
            )
        for field in ("model_sha256", "profile_sha256"):
            if not isinstance(candidate.get(field), str) or not SHA256_PATTERN.fullmatch(candidate[field]):
                raise ExportError(
                    f"CandidateAnnotation {candidate.get('candidate_annotation_id')} 缺少有效 {field}。"
                )
        if (
            candidate.get("algorithm_key") != "MEDIAPIPE_HAND_LANDMARKER"
            or not isinstance(candidate.get("algorithm_version"), str)
            or not candidate["algorithm_version"]
            or len(candidate["algorithm_version"]) > 32
        ):
            raise ExportError(
                f"CandidateAnnotation {candidate.get('candidate_annotation_id')} 缺少有效算法 provenance。"
            )
        if candidate.get("valid_landmark_count") != LANDMARK_COUNT:
            raise ExportError(
                f"CandidateAnnotation {candidate.get('candidate_annotation_id')} 不是完整的 21 点结果。"
            )
        rows.append(
            {
                "video_id": video_id,
                "timestamp_ms": timestamp_ms,
                "action_start_ms": action_segment["start_ms"],
                "action_end_ms": action_segment["end_ms"],
                "action_label": action_segment["label"],
                "hand_index": hand_index,
                "hand_presence": True,
                "hand_mask_available": False,
                "annotation_revision_id": str(matched["annotation_revision_id"]),
                "candidate_annotation_id": str(candidate["candidate_annotation_id"]),
                "handedness": handedness,
                "handedness_score": float(handedness_score),
                "handedness_is_ground_truth": False,
                "algorithm_key": candidate["algorithm_key"],
                "algorithm_version": candidate["algorithm_version"],
                "model_sha256": candidate["model_sha256"],
                "profile_sha256": candidate["profile_sha256"],
                **flatten_landmarks(candidate["landmarks"]),
            }
        )
    rows.sort(key=lambda row: (row["video_id"], row["timestamp_ms"], row["hand_index"]))
    return rows, {
        "eligible_videos": len(assets),
        "candidate_rows": len(candidates),
        "training_rows": len(rows),
        "unlabeled_candidate_rows": unlabeled_candidates,
    }


def parquet_schema() -> Any:
    try:
        import pyarrow as pa
    except ImportError as exc:
        raise ExportError("缺少 PyArrow，请先执行 uv sync --project tools/minimal-export。") from exc
    fields = [
        pa.field("video_id", pa.string(), nullable=False),
        pa.field("timestamp_ms", pa.int64(), nullable=False),
        pa.field("action_start_ms", pa.int64(), nullable=False),
        pa.field("action_end_ms", pa.int64(), nullable=False),
        pa.field("action_label", pa.string(), nullable=False),
        pa.field("annotation_revision_id", pa.string(), nullable=False),
        pa.field("hand_index", pa.int32(), nullable=False),
        pa.field("hand_presence", pa.bool_(), nullable=False),
        pa.field("hand_mask_available", pa.bool_(), nullable=False),
        pa.field("candidate_annotation_id", pa.string(), nullable=False),
        pa.field("handedness", pa.string(), nullable=False),
        pa.field("handedness_score", pa.float32(), nullable=False),
        pa.field("handedness_is_ground_truth", pa.bool_(), nullable=False),
        pa.field("algorithm_key", pa.string(), nullable=False),
        pa.field("algorithm_version", pa.string(), nullable=False),
        pa.field("model_sha256", pa.string(), nullable=False),
        pa.field("profile_sha256", pa.string(), nullable=False),
    ]
    fields.extend(pa.field(name, pa.float32(), nullable=False) for name in landmark_columns())
    metadata = {
        "schema_version": EXPORT_SCHEMA_VERSION,
        "cleaning_gate": "PASS",
        "action_label_source": "human_annotation_revision",
        "action_interval_source": "human_annotation_revision.annotation_document.segments",
        "hand_geometry_source": "candidate_annotation",
        "hand_geometry_is_ground_truth": "false",
        "handedness_is_ground_truth": "false",
        "handedness_score_source": "candidate_annotation.handedness_score",
        "candidate_algorithm_source": "ai_job.algorithm_key+algorithm_version",
        "hand_mask_policy": "not_available",
        "eligibility_projection": "training_data_eligibility",
        "candidate_projection": "training_data_candidate_observations",
        "coordinate_system": "normalized_image_xy_and_mediapipe_relative_z",
        "video_policy": "video_id_only_no_mp4",
    }
    return pa.schema(fields, metadata={key.encode(): value.encode() for key, value in metadata.items()})


def write_parquet(output: Path, rows: list[dict[str, Any]], force: bool) -> None:
    try:
        import pyarrow as pa
        import pyarrow.parquet as parquet
    except ImportError as exc:
        raise ExportError("缺少 PyArrow，请先执行 uv sync --project tools/minimal-export。") from exc
    if output.exists() and not force:
        raise ExportError(f"输出文件已存在：{output}。如需覆盖请明确传 --force。")
    output.parent.mkdir(parents=True, exist_ok=True)
    schema = parquet_schema()
    table = pa.Table.from_pylist(rows, schema=schema)
    temporary = output.with_name(output.name + ".partial")
    if temporary.exists():
        temporary.unlink()
    try:
        parquet.write_table(table, temporary, compression="zstd")
        temporary.replace(output)
    except Exception:
        if temporary.exists():
            temporary.unlink()
        raise


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导出 Cleaning PASS 的最小训练数据 Parquet。")
    parser.add_argument("--project-id", required=True, type=UUID, help="要导出的项目 UUID")
    parser.add_argument(
        "--output", type=Path, default=Path("training_data.parquet"), help="输出 Parquet 路径"
    )
    parser.add_argument("--env-file", type=Path, default=Path(".env"), help="本地环境文件，默认 .env")
    parser.add_argument("--force", action="store_true", help="允许覆盖明确指定的已有输出文件")
    return parser.parse_args(argv)


def require_synthetic_profile() -> None:
    profile = os.getenv("DATA_PROFILE")
    if profile is None or profile.strip().lower() != "synthetic":
        raise ExportError(
            "当前没有正式的非合成数据授权模型；必须显式设置 DATA_PROFILE=synthetic，"
            "工具拒绝缺省或空 profile。"
        )


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(args.env_file)
    require_synthetic_profile()
    try:
        import psycopg
        from psycopg.rows import dict_row
    except ImportError as exc:
        raise ExportError("缺少 psycopg，请先执行 uv sync --project tools/minimal-export。") from exc

    try:
        with psycopg.connect(
            build_database_url(),
            row_factory=dict_row,
            connect_timeout=10,
            options="-c statement_timeout=30000 -c default_transaction_read_only=on",
        ) as connection:
            # Both eligibility and candidate reads must observe one immutable snapshot.
            # The session-level read-only setting also makes the tool's safety claim
            # enforceable by PostgreSQL rather than by convention alone.
            connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            assets = query_eligible_assets(connection, args.project_id)
            candidates = query_candidates(
                connection,
                args.project_id,
                (asset["video_id"] for asset in assets),
            )
    except psycopg.Error as exc:
        raise ExportError(f"读取 PostgreSQL 失败：{exc}") from exc

    rows, counts = build_training_rows(assets, candidates)
    write_parquet(args.output, rows, args.force)
    print(json.dumps({"output": str(args.output), **counts}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ExportError as exc:
        print(f"导出失败：{exc}", file=sys.stderr)
        raise SystemExit(2) from exc
