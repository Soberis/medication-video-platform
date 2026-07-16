from __future__ import annotations

import json

import pytest

from medication_media_worker.contracts import ContractError, MediaProbeJob


def test_parses_frozen_job_contract(job_body: bytes) -> None:
    job = MediaProbeJob.from_bytes(job_body)

    assert job.attempt == 1
    assert job.probe_profile_key == "media-probe-baseline-v1"
    assert str(job.job_id) == "20000000-0000-0000-0000-000000000002"


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("lease_token", "short"),
        ("input_sha256", "A" * 64),
        ("attempt", 0),
        ("requested_at", "2026-07-16T00:00:00"),
    ],
)
def test_rejects_invalid_contract_fields(job_body: bytes, field: str, value: object) -> None:
    body = json.loads(job_body)
    body[field] = value

    with pytest.raises(ContractError):
        MediaProbeJob.from_bytes(json.dumps(body).encode())


def test_rejects_unknown_contract_field(job_body: bytes) -> None:
    body = json.loads(job_body)
    body["object_key"] = "must-not-cross-broker"

    with pytest.raises(ContractError):
        MediaProbeJob.from_bytes(json.dumps(body).encode())
