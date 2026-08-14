from __future__ import annotations

import importlib.util
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
        "asset_status": "INGESTED",
        "quality_decision": "QUALIFIED",
        "task_status": "COMPLETED",
        "annotation_review_decision": "APPROVED",
        "cleaning_outcome": "PASS",
        "cleaning_run_status": "SUCCEEDED",
        "annotation_revision_id": "80000000-0000-4000-8000-000000000001",
        "cleaning_result_id": "82000000-0000-4000-8000-000000000001",
        "asset_sha256": "a" * 64,
        "document_sha256": "b" * 64,
        "label_schema_digest": "c" * 64,
        "annotation_document": {
            "schema_version": "annotation-document.v1",
            "segments": [{"start_ms": 100, "end_ms": 500, "label": "retrieve_medicine"}],
        },
    }


def candidate(timestamp_ms: int = 120) -> dict[str, object]:
    return {
        "candidate_annotation_id": "90000000-0000-4000-8000-000000000001",
        "clip_asset_id": "50000000-0000-4000-8000-000000000001",
        "frame_timestamp_ms": timestamp_ms,
        "hand_index": 0,
        "handedness": "LEFT",
        "handedness_score": 0.75,
        "landmarks": [{"x": index / 100, "y": 0.2, "z": -0.01} for index in range(21)],
    }


def test_cleaning_pass_rows_write_and_read_parquet(tmp_path: Path) -> None:
    rows, counts = exporter.build_training_rows([eligible_asset()], [candidate()])
    output = tmp_path / "training_data.parquet"
    exporter.write_parquet(output, rows, force=False)

    table = parquet.read_table(output)
    assert counts["training_rows"] == 1
    assert table.num_rows == 1
    assert table.column("video_id").to_pylist() == ["50000000-0000-4000-8000-000000000001"]
    assert table.column("action_label").to_pylist() == ["retrieve_medicine"]
    assert table.column("landmark_20_z").to_pylist() == pytest.approx([-0.01])
    assert table.column("handedness_is_ground_truth").to_pylist() == [False]
    assert table.schema.metadata[b"cleaning_gate"] == b"PASS"
    assert table.schema.metadata[b"action_label_source"] == b"human_annotation_revision"


def test_non_pass_cleaning_data_is_rejected() -> None:
    asset = eligible_asset()
    asset["cleaning_outcome"] = "WARNING"
    with pytest.raises(exporter.ExportError, match="cleaning_outcome"):
        exporter.build_training_rows([asset], [candidate()])


def test_candidate_outside_human_segment_is_not_training_row() -> None:
    rows, counts = exporter.build_training_rows([eligible_asset()], [candidate(timestamp_ms=600)])
    assert rows == []
    assert counts["unlabeled_candidate_rows"] == 1
