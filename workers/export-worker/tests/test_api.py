from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID

import httpx
import pytest

from medication_export_worker.api import (
    CoreApiClient,
    PermanentDeliveryError,
    RetryableDeliveryError,
    StaleDeliveryError,
)
from medication_export_worker.contracts import (
    CURRENT_PROFILE_DIGEST,
    CURRENT_PROFILE_KEY,
    DatasetExportJob,
)


def job() -> DatasetExportJob:
    return DatasetExportJob(
        message_id=UUID("00000000-0000-0000-0000-000000000101"),
        export_id=UUID("00000000-0000-0000-0000-000000000102"),
        attempt=1,
        lease_token="a" * 43,
        project_id=UUID("00000000-0000-0000-0000-000000000103"),
        dataset_version_id=UUID("00000000-0000-0000-0000-000000000104"),
        snapshot_digest="b" * 64,
        export_profile_key=CURRENT_PROFILE_KEY,
        export_profile_digest=CURRENT_PROFILE_DIGEST,
        requested_at=datetime(2026, 7, 30, 8, tzinfo=UTC),
    )


def client_for(status: int, error_code: str, retryable: bool) -> CoreApiClient:
    def respond(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            status,
            request=request,
            json={"error_code": error_code, "retryable": retryable},
        )

    client = CoreApiClient("http://core-api.invalid", "x" * 32, "export-worker-test")
    client._client.close()
    client._client = httpx.Client(
        base_url="http://core-api.invalid",
        transport=httpx.MockTransport(respond),
    )
    return client


def test_retryable_conflict_is_requeued_instead_of_misclassified_as_stale() -> None:
    client = client_for(409, "EXPORT_OUTPUT_MISSING", True)
    try:
        with pytest.raises(RetryableDeliveryError):
            client.submit_result(job(), {})
    finally:
        client.close()


def test_expired_attempt_is_acknowledged_as_stale() -> None:
    client = client_for(409, "EXPORT_LEASE_EXPIRED", False)
    try:
        with pytest.raises(StaleDeliveryError):
            client.heartbeat(job())
    finally:
        client.close()


def test_non_retryable_contract_rejection_is_dead_lettered() -> None:
    client = client_for(409, "EXPORT_WORKER_SOFTWARE_UNAPPROVED", False)
    try:
        with pytest.raises(PermanentDeliveryError):
            client.submit_result(job(), {})
    finally:
        client.close()
