from __future__ import annotations

import json
from contextlib import nullcontext
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast
from uuid import UUID

import pytest

from medication_ai_worker.api import CoreApiClient, LeaseGrant, PermanentError
from medication_ai_worker.config import Settings
from medication_ai_worker.landmarker import PROFILE_SHA256, InferenceResult, InferenceSummary
from medication_ai_worker.processor import Disposition, Processor


class _RejectingClient:
    def acquire(self, _job: object) -> object:
        raise PermanentError("Core API rejected AI Worker request")


def test_permanent_core_api_rejection_is_not_requeued_forever() -> None:
    processor = Processor(
        cast(CoreApiClient, _RejectingClient()),
        cast(Settings, SimpleNamespace(model_sha256="b" * 64)),
    )

    disposition = processor.process(json.dumps(_payload()).encode())

    assert disposition is Disposition.REJECT


@pytest.mark.parametrize(
    ("field", "value", "failure_reason"),
    [
        ("model_sha256", "c" * 64, "AI_HAND_POSE_MODEL_UNSUPPORTED"),
        ("profile_sha256", "c" * 64, "AI_HAND_POSE_PROFILE_UNSUPPORTED"),
    ],
)
def test_unsupported_inputs_acquire_lease_before_submitting_failed_run(
    field: str, value: str, failure_reason: str
) -> None:
    client = _CapturingClient()
    payload = _payload()
    payload[field] = value
    processor = Processor(
        cast(CoreApiClient, client),
        cast(
            Settings,
            SimpleNamespace(
                model_sha256="b" * 64,
                model_path=Path("approved.task"),
                temp_dir=None,
            ),
        ),
    )

    disposition = processor.process(json.dumps(payload).encode())

    assert disposition is Disposition.ACK
    assert client.acquire_count == 1
    assert client.failure_reason == failure_reason
    assert client.submitted_success is False


def test_invalid_hand_output_is_submitted_as_a_failed_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = _CapturingClient()
    processor = Processor(
        cast(CoreApiClient, client),
        cast(
            Settings,
            SimpleNamespace(
                model_sha256="b" * 64,
                model_path=Path("approved.task"),
                temp_dir=None,
            ),
        ),
    )
    monkeypatch.setattr(
        "medication_ai_worker.processor.analyze_video",
        lambda path, model_path, sampling_fps: InferenceResult(
            [], InferenceSummary(10, 1, 0, 1, 0, 0, 1, 1)
        ),
    )

    disposition = processor.process(json.dumps(_payload()).encode())

    assert disposition is Disposition.ACK
    assert client.failure_reason == "AI_HAND_POSE_INVALID_OUTPUT"
    assert client.failure_summary is not None
    assert client.failure_summary.invalid_hand_frame_count == 1
    assert client.submitted_success is False


def test_empty_video_is_not_submitted_as_a_successful_no_hand_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = _CapturingClient()
    processor = Processor(
        cast(CoreApiClient, client),
        cast(
            Settings,
            SimpleNamespace(
                model_sha256="b" * 64,
                model_path=Path("approved.task"),
                temp_dir=None,
            ),
        ),
    )
    monkeypatch.setattr(
        "medication_ai_worker.processor.analyze_video",
        lambda path, model_path, sampling_fps: InferenceResult(
            [], InferenceSummary(0, 0, 0, 0, 0, 0, 0, 0)
        ),
    )

    disposition = processor.process(json.dumps(_payload()).encode())

    assert disposition is Disposition.ACK
    assert client.failure_reason == "AI_HAND_POSE_INVALID_OUTPUT"
    assert client.submitted_success is False


class _CapturingClient:
    def __init__(self) -> None:
        self.acquire_count = 0
        self.failure_reason: str | None = None
        self.failure_summary: InferenceSummary | None = None
        self.submitted_success = False

    def acquire(self, job: Any) -> LeaseGrant:
        self.acquire_count += 1
        now = datetime.now(UTC)
        return LeaseGrant(
            "https://storage.invalid/input.mp4",
            job.input_sha256,
            job.input_size_bytes,
            job.model_sha256,
            job.profile_sha256,
            now + timedelta(minutes=5),
            60,
        )

    def maintain_lease(self, job: Any, grant: LeaseGrant) -> Any:
        del job, grant
        return nullcontext()

    def download(self, grant: LeaseGrant, target: Path) -> None:
        del grant
        target.write_bytes(b"synthetic")

    def submit_failure(
        self,
        job: Any,
        started_at: datetime,
        failure_reason: str,
        processing_duration_ms: int,
        summary: InferenceSummary | None = None,
    ) -> None:
        del job, started_at, processing_duration_ms
        self.failure_reason = failure_reason
        self.failure_summary = summary

    def submit(
        self, job: Any, started_at: datetime, result: InferenceResult, processing_duration_ms: int
    ) -> None:
        del job, started_at, result, processing_duration_ms
        self.submitted_success = True


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
