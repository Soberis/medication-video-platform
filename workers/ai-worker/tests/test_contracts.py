from __future__ import annotations

import json
from uuid import UUID

import pytest

from medication_ai_worker.contracts import AiJob, ContractError

PROFILE_SHA256 = "239a403b289c6b379af660988516f752daf1899ce2a05613e333a67a6f95af09"


def test_job_contract_requires_the_frozen_profile_digest_field() -> None:
    payload = _payload()
    payload.pop("profile_sha256")

    with pytest.raises(ContractError, match="fields"):
        AiJob.from_bytes(json.dumps(payload).encode())


def test_job_contract_exposes_the_frozen_profile_digest() -> None:
    job = AiJob.from_bytes(json.dumps(_payload()).encode())

    assert job.profile_sha256 == PROFILE_SHA256


@pytest.mark.parametrize("sampling_fps", [1, 9, 11, 15])
def test_job_contract_rejects_non_frozen_sampling_rate(sampling_fps: int) -> None:
    payload = _payload()
    payload["sampling_fps"] = sampling_fps

    with pytest.raises(ContractError, match="sampling_fps"):
        AiJob.from_bytes(json.dumps(payload).encode())


def _payload() -> dict[str, object]:
    return {
        "schema_version": "ai-hand-pose-job.v1",
        "message_id": str(UUID("00000000-0000-0000-0000-000000000011")),
        "job_id": str(UUID("00000000-0000-0000-0000-000000000012")),
        "attempt": 1,
        "lease_token": "A" * 43,
        "project_id": str(UUID("00000000-0000-0000-0000-000000000013")),
        "clip_asset_id": str(UUID("00000000-0000-0000-0000-000000000014")),
        "input_sha256": "a" * 64,
        "input_size_bytes": 1,
        "content_type": "video/mp4",
        "algorithm_key": "MEDIAPIPE_HAND_LANDMARKER",
        "algorithm_version": "v1",
        "model_sha256": "b" * 64,
        "sampling_fps": 10,
        "profile_sha256": PROFILE_SHA256,
        "requested_at": "2026-08-10T00:00:00Z",
    }


def _action_payload() -> dict[str, object]:
    return {
        "schema_version": "ai-action-job.v1",
        "message_id": str(UUID("00000000-0000-0000-0000-000000000021")),
        "job_id": str(UUID("00000000-0000-0000-0000-000000000022")),
        "attempt": 1,
        "lease_token": "B" * 43,
        "project_id": str(UUID("00000000-0000-0000-0000-000000000023")),
        "capture_group_id": str(UUID("00000000-0000-0000-0000-000000000024")),
        "clip_asset_id": str(UUID("00000000-0000-0000-0000-000000000025")),
        "allowed_labels": ["prepare", "draw_liquid", "cleanup"],
        "input_sha256": "c" * 64,
        "input_size_bytes": 2048,
        "content_type": "video/mp4",
        "algorithm_key": "QWEN3_VL_ACTION",
        "algorithm_version": "v1",
        "model_sha256": "qwen3-vl-8b-bf16",
        "sampling_fps": 1,
        "profile_sha256": "qwen3-vl-8b-bf16",
        "requested_at": "2026-09-10T00:00:00Z",
    }


def test_action_job_contract_parses_valid_payload() -> None:
    from medication_ai_worker.contracts import ActionJob

    raw = json.dumps(_action_payload()).encode()
    job = ActionJob.from_bytes(raw)
    assert job.algorithm_key == "QWEN3_VL_ACTION"
    assert job.allowed_labels == ("prepare", "draw_liquid", "cleanup")
    assert job.sampling_fps == 1


def test_parse_job_routes_both_hand_pose_and_action() -> None:
    from medication_ai_worker.contracts import ActionJob, AiJob, parse_job

    hand_job = parse_job(json.dumps(_payload()).encode())
    assert isinstance(hand_job, AiJob)

    action_job = parse_job(json.dumps(_action_payload()).encode())
    assert isinstance(action_job, ActionJob)

