from __future__ import annotations

import json
from typing import Any
from uuid import UUID

import pytest


@pytest.fixture
def job_body() -> bytes:
    value: dict[str, Any] = {
        "schema_version": "media-probe-job.v1",
        "message_id": str(UUID("10000000-0000-0000-0000-000000000001")),
        "job_id": str(UUID("20000000-0000-0000-0000-000000000002")),
        "job_type": "MEDIA_PROBE",
        "attempt": 1,
        "lease_token": "A" * 43,
        "project_id": str(UUID("30000000-0000-0000-0000-000000000003")),
        "clip_asset_id": str(UUID("40000000-0000-0000-0000-000000000004")),
        "input_sha256": "a" * 64,
        "input_size_bytes": 123,
        "content_type": "video/mp4",
        "probe_profile_key": "media-probe-baseline-v1",
        "recipe_digest": "b" * 64,
        "requested_at": "2026-07-16T00:00:00Z",
    }
    return json.dumps(value).encode()
