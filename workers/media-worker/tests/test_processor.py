from __future__ import annotations

from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import cast

from medication_media_worker.api import (
    CoreApiClient,
    InputDigestMismatchError,
    LeaseGrant,
    LeaseMonitor,
    PermanentDeliveryError,
    StaleDeliveryError,
)
from medication_media_worker.probe import MediaProber
from medication_media_worker.processor import DeadLetterReason, DeliveryDisposition, JobProcessor


class FakeClient:
    def __init__(
        self,
        *,
        digest_mismatch: bool = False,
        permanent_failure_at: str | None = None,
        stale_acquire: bool = False,
    ) -> None:
        self.digest_mismatch = digest_mismatch
        self.permanent_failure_at = permanent_failure_at
        self.stale_acquire = stale_acquire
        self.acquire_calls = 0
        self.maintain_lease_calls = 0
        self.download_calls = 0
        self.submitted: dict[str, object] | None = None

    def acquire_input(self, job: object) -> LeaseGrant:
        del job
        self.acquire_calls += 1
        if self.stale_acquire:
            raise StaleDeliveryError("synthetic stale acquire")
        if self.permanent_failure_at == "acquire":
            raise PermanentDeliveryError("synthetic acquire rejection")
        now = datetime.now(UTC)
        return LeaseGrant("http://object.test/input", now + timedelta(minutes=15), now, 300)

    def maintain_lease(self, job: object, grant: LeaseGrant) -> LeaseMonitor:
        del job, grant
        self.maintain_lease_calls += 1
        if self.permanent_failure_at == "lease":
            raise PermanentDeliveryError("synthetic lease rejection")
        return cast(LeaseMonitor, NoopLeaseMonitor())

    def download_input(self, grant: LeaseGrant, job: object, target: Path) -> None:
        del grant, job
        self.download_calls += 1
        if self.digest_mismatch:
            raise InputDigestMismatchError("mismatch")
        target.write_bytes(b"synthetic")

    def submit_result(self, job: object, result: dict[str, object]) -> None:
        del job
        if self.permanent_failure_at == "result":
            raise PermanentDeliveryError("synthetic result rejection")
        self.submitted = result


class FakeProber:
    def __init__(self) -> None:
        self.probe_calls = 0

    def probe(self, input_path: Path) -> dict[str, object]:
        self.probe_calls += 1
        assert input_path.read_bytes() == b"synthetic"
        return {"decode_verified": True, "input_digest_verified": True}


class NoopLeaseMonitor:
    def __enter__(self) -> NoopLeaseMonitor:
        return self

    def __exit__(self, *args: object) -> None:
        del args


def test_submits_success_before_ack(job_body: bytes, tmp_path: Path) -> None:
    client = FakeClient()
    processor = JobProcessor(
        cast(CoreApiClient, client),
        cast(MediaProber, FakeProber()),
        "sha256:" + "c" * 64,
        tmp_path,
    )

    outcome = processor.process(job_body)

    assert outcome.disposition is DeliveryDisposition.ACK
    assert outcome.dead_letter_reason is None
    assert client.submitted is not None
    assert client.submitted["status"] == "SUCCEEDED"
    assert client.submitted["message_id"] == "10000000-0000-0000-0000-000000000001"


def test_maps_digest_mismatch_to_non_retryable_result(job_body: bytes, tmp_path: Path) -> None:
    client = FakeClient(digest_mismatch=True)
    processor = JobProcessor(
        cast(CoreApiClient, client),
        cast(MediaProber, FakeProber()),
        "sha256:" + "c" * 64,
        tmp_path,
    )

    outcome = processor.process(job_body)

    assert outcome.disposition is DeliveryDisposition.ACK
    assert client.submitted is not None
    assert client.submitted["status"] == "FAILED"
    error = client.submitted["error"]
    assert isinstance(error, dict)
    assert error["error_code"] == "MEDIA_INPUT_DIGEST_MISMATCH"


def test_rejects_invalid_envelope_without_calling_core_api(tmp_path: Path) -> None:
    client = FakeClient()
    processor = JobProcessor(
        cast(CoreApiClient, client),
        cast(MediaProber, FakeProber()),
        "sha256:" + "c" * 64,
        tmp_path,
    )

    outcome = processor.process(b'{"schema_version":"unknown"}')

    assert outcome.disposition is DeliveryDisposition.REJECT
    assert outcome.dead_letter_reason is DeadLetterReason.CONTRACT_INVALID
    assert outcome.job_id is None
    assert client.submitted is None


def test_rejected_job_retains_only_safe_diagnostic_identity(
    job_body: bytes,
    tmp_path: Path,
) -> None:
    client = FakeClient(permanent_failure_at="acquire")
    processor = JobProcessor(
        cast(CoreApiClient, client),
        cast(MediaProber, FakeProber()),
        "sha256:" + "c" * 64,
        tmp_path,
    )

    outcome = processor.process(job_body)

    assert outcome.disposition is DeliveryDisposition.REJECT
    assert outcome.dead_letter_reason is DeadLetterReason.ACQUIRE_REJECTED
    assert str(outcome.message_id) == "10000000-0000-0000-0000-000000000001"
    assert str(outcome.job_id) == "20000000-0000-0000-0000-000000000002"
    assert outcome.attempt == 1


def test_acknowledges_stale_acquire_without_side_effects(
    job_body: bytes,
    tmp_path: Path,
) -> None:
    client = FakeClient(stale_acquire=True)
    prober = FakeProber()
    processor = JobProcessor(
        cast(CoreApiClient, client),
        cast(MediaProber, prober),
        "sha256:" + "c" * 64,
        tmp_path,
    )

    outcome = processor.process(job_body)

    assert outcome.disposition is DeliveryDisposition.ACK
    assert outcome.dead_letter_reason is None
    assert str(outcome.message_id) == "10000000-0000-0000-0000-000000000001"
    assert str(outcome.job_id) == "20000000-0000-0000-0000-000000000002"
    assert outcome.attempt == 1
    assert client.acquire_calls == 1
    assert client.maintain_lease_calls == 0
    assert client.download_calls == 0
    assert prober.probe_calls == 0
    assert client.submitted is None
    assert list(tmp_path.glob("media-probe-*")) == []
