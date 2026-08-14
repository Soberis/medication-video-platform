from __future__ import annotations

import importlib.util
from decimal import Decimal
from pathlib import Path

import pyarrow.parquet as parquet
import pytest

ROOT = Path(__file__).parents[3]
SCRIPT = ROOT / "tools" / "minimal-export" / "export_training_data.py"
SPEC = importlib.util.spec_from_file_location("export_training_data", SCRIPT)
assert SPEC and SPEC.loader
exporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(exporter)


def eligible_asset() -> dict[str, object]:
    return {
        "video_id": "50000000-0000-4000-8000-000000000001",
        "content_type": "video/mp4",
        "asset_status": "INGESTED",
        "asset_deleted": False,
        "quality_decision": "QUALIFIED",
        "task_status": "COMPLETED",
        "annotation_review_decision": "APPROVED",
        "cleaning_outcome": "PASS",
        "cleaning_run_status": "SUCCEEDED",
        "annotation_revision_id": "80000000-0000-4000-8000-000000000001",
        "annotation_task_id": "81000000-0000-4000-8000-000000000001",
        "label_schema_id": "10000000-0000-0000-0000-000000000001",
        "annotation_review_id": "82000000-0000-4000-8000-000000000001",
        "cleaning_result_id": "82000000-0000-4000-8000-000000000001",
        "cleaning_run_id": "83000000-0000-4000-8000-000000000001",
        "cleaning_rule_set_id": "30000000-0000-0000-0000-000000000001",
        "asset_sha256": "a" * 64,
        "document_sha256": "b" * 64,
        "label_schema_digest": "c" * 64,
        "rule_set_digest": "d" * 64,
        "annotation_document": {
            "schema_version": "annotation-document.v1",
            "segments": [{"start_ms": 100, "end_ms": 500, "label": "retrieve_medicine"}],
        },
    }


def candidate(timestamp_ms: int = 120) -> dict[str, object]:
    return {
        "candidate_annotation_id": "90000000-0000-4000-8000-000000000001",
        "clip_asset_id": "50000000-0000-4000-8000-000000000001",
        "annotation_revision_id": "80000000-0000-4000-8000-000000000001",
        "algorithm_key": "MEDIAPIPE_HAND_LANDMARKER",
        "algorithm_version": "mediapipe-hand-landmarker.v1",
        "frame_timestamp_ms": timestamp_ms,
        "hand_index": 0,
        "handedness": "LEFT",
        "handedness_score": Decimal("0.75"),
        "model_sha256": "e" * 64,
        "profile_sha256": "f" * 64,
        "valid_landmark_count": 21,
        "landmarks": [{"x": index / 100, "y": 0.2, "z": -0.01} for index in range(21)],
    }


def test_cleaning_pass_rows_write_and_read_parquet(tmp_path: Path) -> None:
    rows, counts = exporter.build_training_rows([eligible_asset()], [candidate()])
    output = tmp_path / "training_data.parquet"
    exporter.write_parquet(output, rows, force=False)

    table = parquet.read_table(output)
    assert counts["training_rows"] == 1
    assert table.num_rows == 1
    expected_schema_names = [
        "video_id",
        "timestamp_ms",
        "action_start_ms",
        "action_end_ms",
        "action_label",
        "annotation_revision_id",
        "hand_index",
        "hand_presence",
        "hand_mask_available",
        "candidate_annotation_id",
        "handedness",
        "handedness_score",
        "handedness_is_ground_truth",
        "algorithm_key",
        "algorithm_version",
        "model_sha256",
        "profile_sha256",
        *exporter.landmark_columns(),
    ]
    assert table.schema.names == expected_schema_names
    assert table.column("video_id").to_pylist() == ["50000000-0000-4000-8000-000000000001"]
    assert table.column("timestamp_ms").to_pylist() == [120]
    assert table.column("action_start_ms").to_pylist() == [100]
    assert table.column("action_end_ms").to_pylist() == [500]
    assert 100 <= table.column("timestamp_ms")[0].as_py() < 500
    assert table.column("action_label").to_pylist() == ["retrieve_medicine"]
    assert table.column("landmark_20_z").to_pylist() == pytest.approx([-0.01])
    assert table.column("hand_presence").to_pylist() == [True]
    assert table.column("hand_mask_available").to_pylist() == [False]
    assert "confidence" not in table.schema.names
    assert table.column("algorithm_key").to_pylist() == ["MEDIAPIPE_HAND_LANDMARKER"]
    assert table.column("algorithm_version").to_pylist() == ["mediapipe-hand-landmarker.v1"]
    assert table.column("model_sha256").to_pylist() == ["e" * 64]
    assert table.column("handedness_is_ground_truth").to_pylist() == [False]
    assert table.schema.metadata[b"cleaning_gate"] == b"PASS"
    assert table.schema.metadata[b"action_label_source"] == b"human_annotation_revision"
    assert table.schema.metadata[b"coordinate_system"] == b"normalized_image_xy_and_mediapipe_relative_z"
    assert table.schema.metadata[b"hand_geometry_is_ground_truth"] == b"false"
    assert table.schema.metadata[b"action_interval_source"] == (
        b"human_annotation_revision.annotation_document.segments"
    )
    assert table.schema.metadata[b"handedness_score_source"] == b"candidate_annotation.handedness_score"
    assert b"confidence_source" not in table.schema.metadata
    assert table.schema.field("handedness").nullable is False


def test_non_pass_cleaning_data_is_rejected() -> None:
    asset = eligible_asset()
    asset["cleaning_outcome"] = "WARNING"
    with pytest.raises(exporter.ExportError, match="cleaning_outcome"):
        exporter.build_training_rows([asset], [candidate()])


def test_candidate_outside_human_segment_is_not_training_row() -> None:
    rows, counts = exporter.build_training_rows([eligible_asset()], [candidate(timestamp_ms=600)])
    assert rows == []
    assert counts["unlabeled_candidate_rows"] == 1


def test_candidate_at_human_segment_end_is_not_training_row() -> None:
    rows, counts = exporter.build_training_rows([eligible_asset()], [candidate(timestamp_ms=500)])
    assert rows == []
    assert counts["unlabeled_candidate_rows"] == 1


@pytest.mark.parametrize(
    "score",
    [True, Decimal("NaN"), Decimal("Infinity"), Decimal("-0.01"), Decimal("1.01")],
)
def test_invalid_handedness_score_is_rejected(score: object) -> None:
    invalid_candidate = candidate()
    invalid_candidate["handedness_score"] = score
    with pytest.raises(exporter.ExportError, match="手部元数据无效"):
        exporter.build_training_rows([eligible_asset()], [invalid_candidate])


def test_profile_guard_requires_explicit_synthetic(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DATA_PROFILE", raising=False)
    with pytest.raises(exporter.ExportError, match="必须显式设置 DATA_PROFILE=synthetic"):
        exporter.require_synthetic_profile()

    monkeypatch.setenv("DATA_PROFILE", " ")
    with pytest.raises(exporter.ExportError, match="必须显式设置 DATA_PROFILE=synthetic"):
        exporter.require_synthetic_profile()

    monkeypatch.setenv("DATA_PROFILE", "synthetic")
    exporter.require_synthetic_profile()
