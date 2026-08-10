from __future__ import annotations

import json
from types import SimpleNamespace
from typing import cast
from uuid import UUID

from medication_ai_worker.api import CoreApiClient, PermanentError
from medication_ai_worker.config import Settings
from medication_ai_worker.landmarker import PROFILE_SHA256
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
