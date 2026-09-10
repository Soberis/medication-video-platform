from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from medication_ai_worker.config import ACTION_QUEUE, HAND_POSE_QUEUE, Settings


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


def test_job_queues_default_to_the_hand_pose_queue_only(tmp_path: Path) -> None:
    settings = Settings.from_environment(_environment(tmp_path, "ai-worker-1"))

    assert settings.job_queues == (HAND_POSE_QUEUE,)


def test_job_queues_accept_the_action_queue_alongside_hand_pose(tmp_path: Path) -> None:
    environment = _environment(tmp_path, "ai-worker-1")
    environment["AI_WORKER_JOB_QUEUES"] = f" {HAND_POSE_QUEUE} , {ACTION_QUEUE} ,{ACTION_QUEUE}"

    settings = Settings.from_environment(environment)

    assert settings.job_queues == (HAND_POSE_QUEUE, ACTION_QUEUE)


@pytest.mark.parametrize(
    "declared",
    ["platform.ai.hand pose.v1", "platform/ai/action", ",", "platform.ai.hand-pose.v1,"],
)
def test_job_queues_reject_unsafe_or_empty_entries(tmp_path: Path, declared: str) -> None:
    environment = _environment(tmp_path, "ai-worker-1")
    environment["AI_WORKER_JOB_QUEUES"] = declared

    with pytest.raises(ValueError, match="AI_WORKER_JOB_QUEUES"):
        Settings.from_environment(environment)
