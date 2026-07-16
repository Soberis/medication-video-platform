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
)
from medication_media_worker.probe import MediaProber
from medication_media_worker.processor import DeadLetterReason, DeliveryDisposition, JobProcessor


class FakeClient:
    def __init__(
        self,
        *,
        digest_mismatch: bool = False,
        permanent_failure_at: str | None = None,
    ) -> None:
        self.digest_mismatch = digest_mismatch
        self.permanent_failure_at = permanent_failure_at
        self.submitted: dict[str, object] | None = None

    def acquire_input(self, job: object) -> LeaseGrant:
        del job
        if self.permanent_failure_at == "acquire":
            raise PermanentDeliveryError("synthetic acquire rejection")
        now = datetime.now(UTC)
        return LeaseGrant("http://object.test/input", now + timedelta(minutes=15), now, 300)

    def maintain_lease(self, job: object, grant: LeaseGrant) -> LeaseMonitor:
        del job, grant
        if self.permanent_failure_at == "lease":
            raise PermanentDeliveryError("synthetic lease rejection")
        return cast(LeaseMonitor, NoopLeaseMonitor())

    def download_input(self, grant: LeaseGrant, job: object, target: Path) -> None:
        del grant, job
        if self.digest_mismatch:
            raise InputDigestMismatchError("mismatch")
        target.write_bytes(b"synthetic")

    def submit_result(self, job: object, result: dict[str, object]) -> None:
        del job
        if self.permanent_failure_at == "result":
            raise PermanentDeliveryError("synthetic result rejection")
        self.submitted = result


class FakeProber:
    def probe(self, input_path: Path) -> dict[str, object]:
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
