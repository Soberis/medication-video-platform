from __future__ import annotations

import logging
import tempfile
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Protocol
from uuid import UUID

from medication_media_worker.api import (
    CoreApiClient,
    InputDigestMismatchError,
    LeaseGrant,
    LeaseMonitor,
    ObjectDownloadError,
    PermanentDeliveryError,
    RetryableDeliveryError,
    StaleDeliveryError,
)
from medication_media_worker.contracts import ContractError, MediaProbeJob
from medication_media_worker.probe import MediaProber, ProbeFailure

LOGGER = logging.getLogger(__name__)


class DeliveryDisposition(Enum):
    ACK = "ACK"
    REJECT = "REJECT"
    REQUEUE = "REQUEUE"


class DeadLetterReason(Enum):
    CONTRACT_INVALID = "MEDIA_JOB_CONTRACT_INVALID"
    ACQUIRE_REJECTED = "MEDIA_JOB_ACQUIRE_REJECTED"
    LEASE_REJECTED = "MEDIA_JOB_LEASE_REJECTED"
    RESULT_REJECTED = "MEDIA_JOB_RESULT_REJECTED"


@dataclass(frozen=True, slots=True)
class ProcessingOutcome:
    disposition: DeliveryDisposition
    dead_letter_reason: DeadLetterReason | None = None
    message_id: UUID | None = None
    job_id: UUID | None = None
    attempt: int | None = None

    @classmethod
    def for_job(
        cls,
        disposition: DeliveryDisposition,
        job: MediaProbeJob,
        dead_letter_reason: DeadLetterReason | None = None,
    ) -> ProcessingOutcome:
        return cls(
            disposition,
            dead_letter_reason,
            job.message_id,
            job.job_id,
            job.attempt,
        )


class ProbeClient(Protocol):
    def acquire_input(self, job: MediaProbeJob) -> LeaseGrant: ...

    def download_input(self, grant: LeaseGrant, job: MediaProbeJob, target: Path) -> None: ...

    def maintain_lease(self, job: MediaProbeJob, grant: LeaseGrant) -> LeaseMonitor: ...

    def submit_result(self, job: MediaProbeJob, result: dict[str, object]) -> None: ...


class Prober(Protocol):
    def probe(self, input_path: Path) -> dict[str, object]: ...


class JobProcessor:
    def __init__(
        self,
        client: CoreApiClient,
        prober: MediaProber,
        software_digest: str,
        temp_dir: Path | None,
    ) -> None:
        self._client: ProbeClient = client
        self._prober: Prober = prober
        self._software_digest = software_digest
        self._temp_dir = temp_dir

    def process(self, body: bytes) -> ProcessingOutcome:
        try:
            job = MediaProbeJob.from_bytes(body)
        except ContractError:
            LOGGER.warning("Rejected a media message that failed contract validation")
            return ProcessingOutcome(
                DeliveryDisposition.REJECT,
                DeadLetterReason.CONTRACT_INVALID,
            )

        try:
            grant = self._client.acquire_input(job)
        except StaleDeliveryError:
            LOGGER.info(
                "Acknowledged stale media attempt job_id=%s attempt=%d", job.job_id, job.attempt
            )
            return ProcessingOutcome.for_job(DeliveryDisposition.ACK, job)
        except RetryableDeliveryError:
            LOGGER.warning("Core API unavailable while acquiring media job_id=%s", job.job_id)
            return ProcessingOutcome.for_job(DeliveryDisposition.REQUEUE, job)
        except PermanentDeliveryError:
            LOGGER.error("Worker identity or lease request was rejected for job_id=%s", job.job_id)
            return ProcessingOutcome.for_job(
                DeliveryDisposition.REJECT,
                job,
                DeadLetterReason.ACQUIRE_REJECTED,
            )

        try:
            with self._client.maintain_lease(job, grant):
                started_at = datetime.now(UTC)
                try:
                    with tempfile.TemporaryDirectory(
                        dir=self._temp_dir, prefix="media-probe-"
                    ) as directory:
                        input_path = Path(directory) / "input.media"
                        self._client.download_input(grant, job, input_path)
                        report = self._prober.probe(input_path)
                    result = self._success_result(job, started_at, report)
                except InputDigestMismatchError:
                    result = self._failure_result(
                        job,
                        started_at,
                        ProbeFailure(
                            "NON_RETRYABLE_INPUT",
                            "MEDIA_INPUT_DIGEST_MISMATCH",
                            "Downloaded input does not match the frozen SHA-256 and size",
                            retryable=False,
                        ),
                    )
                except ObjectDownloadError:
                    result = self._failure_result(
                        job,
                        started_at,
                        ProbeFailure(
                            "RETRYABLE_TRANSIENT",
                            "OBJECT_STORAGE_UNAVAILABLE",
                            "Approved media input could not be read from object storage",
                            retryable=True,
                        ),
                    )
                except ProbeFailure as failure:
                    result = self._failure_result(job, started_at, failure)
                except OSError:
                    result = self._failure_result(
                        job,
                        started_at,
                        ProbeFailure(
                            "RETRYABLE_RESOURCE",
                            "MEDIA_PROBE_TIMEOUT",
                            "Worker temporary storage is unavailable",
                            retryable=True,
                        ),
                    )
                except Exception:
                    LOGGER.error("Unexpected media probe failure job_id=%s", job.job_id)
                    result = self._failure_result(
                        job,
                        started_at,
                        ProbeFailure(
                            "INTERNAL_BUG",
                            "MEDIA_PROBE_INTERNAL",
                            "Media probe failed because of an internal error",
                            retryable=True,
                        ),
                    )
        except StaleDeliveryError:
            return ProcessingOutcome.for_job(DeliveryDisposition.ACK, job)
        except RetryableDeliveryError:
            return ProcessingOutcome.for_job(DeliveryDisposition.REQUEUE, job)
        except PermanentDeliveryError:
            return ProcessingOutcome.for_job(
                DeliveryDisposition.REJECT,
                job,
                DeadLetterReason.LEASE_REJECTED,
            )

        try:
            self._client.submit_result(job, result)
        except StaleDeliveryError:
            return ProcessingOutcome.for_job(DeliveryDisposition.ACK, job)
        except RetryableDeliveryError:
            return ProcessingOutcome.for_job(DeliveryDisposition.REQUEUE, job)
        except PermanentDeliveryError:
            return ProcessingOutcome.for_job(
                DeliveryDisposition.REJECT,
                job,
                DeadLetterReason.RESULT_REJECTED,
            )
        return ProcessingOutcome.for_job(DeliveryDisposition.ACK, job)

    def _success_result(
        self, job: MediaProbeJob, started_at: datetime, report: dict[str, object]
    ) -> dict[str, object]:
        return self._base_result(job, started_at) | {
            "status": "SUCCEEDED",
            "report": report,
            "error": None,
        }

    def _failure_result(
        self, job: MediaProbeJob, started_at: datetime, failure: ProbeFailure
    ) -> dict[str, object]:
        return self._base_result(job, started_at) | {
            "status": "FAILED",
            "report": None,
            "error": failure.as_contract(),
        }

    def _base_result(self, job: MediaProbeJob, started_at: datetime) -> dict[str, object]:
        return {
            "schema_version": "media-probe-result.v1",
            "message_id": str(job.message_id),
            "job_id": str(job.job_id),
            "attempt": job.attempt,
            "lease_token": job.lease_token,
            "input_sha256": job.input_sha256,
            "recipe_digest": job.recipe_digest,
            "software_digest": self._software_digest,
            "started_at": started_at.isoformat().replace("+00:00", "Z"),
            "completed_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        }
