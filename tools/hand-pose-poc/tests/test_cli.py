from __future__ import annotations

from pathlib import Path

import pytest

from hand_pose_poc.cli import _prepare_output_dir
from hand_pose_poc.pipeline import PARTIAL_OUTPUT_FILENAMES


def test_prepare_output_dir_removes_only_known_partial_artifacts(tmp_path: Path) -> None:
    output = tmp_path / "output"
    output.mkdir()
    for filename in PARTIAL_OUTPUT_FILENAMES:
        (output / filename).write_text("incomplete", encoding="utf-8")

    _prepare_output_dir(output)

    assert list(output.iterdir()) == []


def test_prepare_output_dir_rejects_existing_evidence(tmp_path: Path) -> None:
    output = tmp_path / "output"
    output.mkdir()
    (output / "hand_landmarks.jsonl").write_text("evidence", encoding="utf-8")

    with pytest.raises(ValueError, match="must be empty"):
        _prepare_output_dir(output)
