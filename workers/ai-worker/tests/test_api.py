from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import httpx
import pytest

from medication_ai_worker.api import CoreApiClient, PermanentError, StaleError
from medication_ai_worker.contracts import AiJob
from medication_ai_worker.landmarker import PROFILE_SHA256


def test_core_requests_read_a_rotated_bearer_token_from_file(tmp_path: Path) -> None:
    token_file = tmp_path / "ai-worker.jwt"
    token_file.write_text("a" * 32, encoding="utf-8")
    observed: list[str] = []

    def respond(request: httpx.Request) -> httpx.Response:
        observed.append(request.headers["Authorization"])
        return httpx.Response(
            200,
            request=request,
            json={
                "lease_until": "2026-08-11T00:05:00Z",
                "heartbeat_interval_seconds": 5,
            },
        )

    client = CoreApiClient(
        "http://core-api.invalid",
        lambda: token_file.read_text(encoding="utf-8").strip(),
        "ai-worker-test",
        transport=httpx.MockTransport(respond),
    )
    job = AiJob.from_bytes(json.dumps(_job_payload()).encode())
    try:
        client.heartbeat(job)
        token_file.write_text("b" * 32, encoding="utf-8")
        client.heartbeat(job)
    finally:
        client.close()

    assert observed == [f"Bearer {'a' * 32}", f"Bearer {'b' * 32}"]


def test_lease_grant_must_match_every_frozen_broker_input() -> None:
    responses = [
        {
            "input_url": "https://object.invalid/input.mp4?signature=synthetic",
            "input_sha256": "a" * 64,
            "input_size_bytes": 12,
            "content_type": "video/mp4",
            "model_sha256": "b" * 64,
            "sampling_fps": 9,
            "profile_sha256": PROFILE_SHA256,
            "lease_until": "2026-08-11T00:05:00Z",
            "heartbeat_interval_seconds": 5,
        }
    ]

    def respond(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, request=request, json=responses.pop())

    client = CoreApiClient(
        "http://core-api.invalid",
        "t" * 32,
        "ai-worker-test",
        transport=httpx.MockTransport(respond),
    )
    job = AiJob.from_bytes(json.dumps(_job_payload()).encode())
    try:
        with pytest.raises(StaleError, match="frozen broker inputs"):
            client.acquire(job)
    finally:
        client.close()


def test_lease_grant_rejects_malformed_authoritative_fields() -> None:
    def respond(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            request=request,
            json={
                "input_url": "https://object.invalid/input.mp4",
                "input_sha256": "a" * 64,
                "input_size_bytes": 12,
                "content_type": "video/mp4",
                "model_sha256": "b" * 64,
                "sampling_fps": True,
                "profile_sha256": PROFILE_SHA256,
                "lease_until": "2026-08-11T00:05:00Z",
                "heartbeat_interval_seconds": 5,
            },
        )

    client = CoreApiClient(
        "http://core-api.invalid",
        "t" * 32,
        "ai-worker-test",
        transport=httpx.MockTransport(respond),
    )
    job = AiJob.from_bytes(json.dumps(_job_payload()).encode())
    try:
        with pytest.raises(PermanentError, match="invalid AI lease grant"):
            client.acquire(job)
    finally:
        client.close()


def _job_payload() -> dict[str, object]:
    return {
        "schema_version": "ai-hand-pose-job.v1",
        "message_id": str(UUID("00000000-0000-0000-0000-000000000011")),
        "job_id": str(UUID("00000000-0000-0000-0000-000000000012")),
        "attempt": 1,
        "lease_token": "A" * 43,
        "project_id": str(UUID("00000000-0000-0000-0000-000000000013")),
        "clip_asset_id": str(UUID("00000000-0000-0000-0000-000000000014")),
        "input_sha256": "a" * 64,
        "input_size_bytes": 12,
        "content_type": "video/mp4",
        "algorithm_key": "MEDIAPIPE_HAND_LANDMARKER",
        "algorithm_version": "v1",
        "model_sha256": "b" * 64,
        "sampling_fps": 10,
        "profile_sha256": PROFILE_SHA256,
        "requested_at": datetime.now(UTC).isoformat(),
    }
