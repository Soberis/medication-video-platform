from __future__ import annotations

from datetime import UTC, datetime, timedelta
from time import sleep

import httpx
import pytest

from medication_media_worker.api import (
    CoreApiClient,
    HeartbeatGrant,
    LeaseGrant,
    LeaseMonitor,
    StaleDeliveryError,
)
from medication_media_worker.contracts import MediaProbeJob


def test_lease_monitor_renews_during_long_processing(job_body: bytes) -> None:
    job = MediaProbeJob.from_bytes(job_body)
    now = datetime.now(UTC)
    renewals = 0

    def renew(current: MediaProbeJob) -> HeartbeatGrant:
        nonlocal renewals
        assert current == job
        renewals += 1
        return HeartbeatGrant(datetime.now(UTC) + timedelta(seconds=1), 0.01)

    grant = LeaseGrant("http://object.test/input", now, now + timedelta(seconds=1), 0.01)
    with LeaseMonitor(job, grant, renew):
        sleep(0.04)

    assert renewals >= 1


def test_lease_monitor_surfaces_stale_heartbeat(job_body: bytes) -> None:
    job = MediaProbeJob.from_bytes(job_body)
    now = datetime.now(UTC)

    def renew(current: MediaProbeJob) -> HeartbeatGrant:
        del current
        raise StaleDeliveryError("stale")

    grant = LeaseGrant("http://object.test/input", now, now + timedelta(seconds=1), 0.01)
    with pytest.raises(StaleDeliveryError), LeaseMonitor(job, grant, renew):
        sleep(0.03)


def test_core_requests_read_a_rotated_bearer_token(job_body: bytes) -> None:
    tokens = iter(("a" * 32, "b" * 32))
    observed: list[str] = []

    def respond(request: httpx.Request) -> httpx.Response:
        observed.append(request.headers["Authorization"])
        return httpx.Response(204, request=request)

    client = CoreApiClient(
        "http://core-api.invalid",
        lambda: next(tokens),
        "media-worker-test",
        transport=httpx.MockTransport(respond),
    )
    current_job = MediaProbeJob.from_bytes(job_body)
    try:
        client.submit_result(current_job, {})
        client.submit_result(current_job, {})
    finally:
        client.close()

    assert observed == [f"Bearer {'a' * 32}", f"Bearer {'b' * 32}"]
