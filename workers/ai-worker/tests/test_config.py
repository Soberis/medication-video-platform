from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from medication_ai_worker.config import Settings


def _environment(tmp_path: Path, worker_id: str) -> dict[str, str]:
    model = tmp_path / "synthetic-hand-landmarker.task"
    model.write_bytes(b"synthetic-offline-model")
    return {
        "AI_WORKER_BEARER_TOKEN": "synthetic-ai-worker-token-value-0001",
        "DATA_PROFILE": "synthetic",
        "AI_HAND_POSE_MODEL_PATH": str(model),
        "AI_HAND_POSE_MODEL_SHA256": hashlib.sha256(model.read_bytes()).hexdigest(),
        "AI_WORKER_ID": worker_id,
    }


@pytest.mark.parametrize("worker_id", ["a", "ai-worker_1", "service@example:1-2"])
def test_worker_id_accepts_the_core_api_identifier_alphabet(tmp_path: Path, worker_id: str) -> None:
    settings = Settings.from_environment(_environment(tmp_path, worker_id))

    assert settings.worker_id == worker_id


@pytest.mark.parametrize("worker_id", ["", "bad worker", "-leading", "worker/1", "a" * 129])
def test_worker_id_rejects_values_the_core_api_cannot_authenticate(
    tmp_path: Path, worker_id: str
) -> None:
    with pytest.raises(ValueError, match="safe identifier"):
        Settings.from_environment(_environment(tmp_path, worker_id))
