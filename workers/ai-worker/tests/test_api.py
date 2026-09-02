from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import httpx
import pytest

from medication_ai_worker.api import CoreApiClient, PermanentError, StaleError
from medication_ai_worker.contracts import AiJob
from medication_ai_worker.landmarker import PROFILE_SHA256, InferenceResult, InferenceSummary


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


def test_submit_preserves_complete_candidate_fields_in_result_payload() -> None:
    observed_requests: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        observed_requests.append(request)
        return httpx.Response(204, request=request)

    client = CoreApiClient(
        "http://core-api.invalid",
        "t" * 32,
        "ai-worker-test",
        transport=httpx.MockTransport(respond),
    )
    job = AiJob.from_bytes(json.dumps(_job_payload()).encode())
    candidate = {
        "frame_timestamp_ms": 100,
        "image_width": 1280,
        "image_height": 720,
        "hand_index": 0,
        "handedness": "RIGHT",
        "handedness_score": 0.91,
        "bounding_box": {"x": 0.1, "y": 0.2, "width": 0.02, "height": 0.04},
        "landmarks": [{"x": 0.1 + index * 0.001, "y": 0.2, "z": -0.01} for index in range(21)],
        "valid_landmark_count": 21,
    }
    result = InferenceResult([candidate], InferenceSummary(3, 3, 1, 2, 0, 1, 0, 0))
    try:
        client.submit(job, datetime(2026, 8, 11, tzinfo=UTC), result, 17)
    finally:
        client.close()

    assert len(observed_requests) == 1
    payload = json.loads(observed_requests[0].content)
    assert payload["schema_version"] == "ai-hand-pose-result.v2"
    assert payload["hand_result"] == "HAND_DETECTED"
    assert payload["frames_with_hand"] == 1
    assert payload["right_hand_detection_count"] == 1
    assert payload["candidates"] == [candidate]
    assert len(payload["candidates"][0]["landmarks"]) == 21


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
