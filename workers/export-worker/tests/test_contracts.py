from __future__ import annotations

import json
from uuid import UUID

import pytest

from medication_export_worker.contracts import (
    CURRENT_PROFILE_DIGEST,
    CURRENT_PROFILE_KEY,
    ContractError,
    DatasetExportJob,
)

MESSAGE_ID = "00000000-0000-0000-0000-000000000101"
EXPORT_ID = "00000000-0000-0000-0000-000000000102"
PROJECT_ID = "00000000-0000-0000-0000-000000000103"
VERSION_ID = "00000000-0000-0000-0000-000000000104"


def valid_job() -> dict[str, object]:
    return {
        "schema_version": "dataset-export-job.v1",
        "message_id": MESSAGE_ID,
        "export_id": EXPORT_ID,
        "job_type": "DATASET_EXPORT",
        "attempt": 1,
        "lease_token": "a" * 43,
        "project_id": PROJECT_ID,
        "dataset_version_id": VERSION_ID,
        "snapshot_digest": "b" * 64,
        "export_profile_key": CURRENT_PROFILE_KEY,
        "export_profile_digest": CURRENT_PROFILE_DIGEST,
        "requested_at": "2026-07-30T08:00:00Z",
    }


def encode(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def test_accepts_exact_v1_message_shape() -> None:
    job = DatasetExportJob.from_bytes(encode(valid_job()))

    assert job.message_id == UUID(MESSAGE_ID)
    assert job.export_id == UUID(EXPORT_ID)
    assert job.export_profile_key == CURRENT_PROFILE_KEY
    assert job.snapshot_digest == "b" * 64


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: value.update({"unexpected": True}),
        lambda value: value.update({"schema_version": "dataset-export-job.v2"}),
        lambda value: value.update({"lease_token": "short"}),
        lambda value: value.update({"export_profile_digest": "c" * 64}),
        lambda value: value.update({"attempt": 0}),
    ],
)
def test_rejects_untrusted_message_variants(mutate: object) -> None:
    value = valid_job()
    assert callable(mutate)
    mutate(value)

    with pytest.raises(ContractError):
        DatasetExportJob.from_bytes(encode(value))
