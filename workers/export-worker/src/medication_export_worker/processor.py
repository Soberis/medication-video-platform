from __future__ import annotations

import logging
import tempfile
from contextlib import AbstractContextManager
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Protocol
from uuid import UUID

from medication_export_worker.api import (
    ExportStream,
    InputDigestMismatchError,
    InputDownloadError,
    LeaseGrant,
    OutputUploadError,
    PermanentDeliveryError,
    RetryableDeliveryError,
    StaleDeliveryError,
)
from medication_export_worker.contracts import (
    CURRENT_PROFILE_KEY,
    ContractError,
    DatasetExportJob,
)
from medication_export_worker.package_builder import (
    PackageBuildError,
    PackageInputError,
    PackageResult,
)

LOGGER = logging.getLogger(__name__)


class DeliveryDisposition(Enum):
    ACK = "ACK"
    REJECT = "REJECT"
    REQUEUE = "REQUEUE"


class DeadLetterReason(Enum):
    CONTRACT_INVALID = "DATASET_EXPORT_JOB_CONTRACT_INVALID"
    PROFILE_UNSUPPORTED = "DATASET_EXPORT_JOB_PROFILE_UNSUPPORTED"
    ACQUIRE_REJECTED = "DATASET_EXPORT_JOB_ACQUIRE_REJECTED"
    LEASE_REJECTED = "DATASET_EXPORT_JOB_LEASE_REJECTED"
    RESULT_REJECTED = "DATASET_EXPORT_JOB_RESULT_REJECTED"


@dataclass(frozen=True, slots=True)
class ProcessingOutcome:
    disposition: DeliveryDisposition
    dead_letter_reason: DeadLetterReason | None = None
    message_id: UUID | None = None
    export_id: UUID | None = None
    attempt: int | None = None

    @classmethod
    def for_job(
        cls,
        disposition: DeliveryDisposition,
        job: DatasetExportJob,
        dead_letter_reason: DeadLetterReason | None = None,
    ) -> ProcessingOutcome:
        return cls(
            disposition,
            dead_letter_reason,
            job.message_id,
            job.export_id,
            job.attempt,
        )


class ExportClient(Protocol):
    def acquire(self, job: DatasetExportJob) -> LeaseGrant: ...

    def maintain_lease(
        self,
        job: DatasetExportJob,
        grant: LeaseGrant,
    ) -> AbstractContextManager[object]: ...

    def download_input(self, stream: ExportStream, target: Path) -> None: ...

    def upload_package(self, job: DatasetExportJob, grant: LeaseGrant, package: Path) -> None: ...

    def submit_result(self, job: DatasetExportJob, result: dict[str, object]) -> None: ...


class PackageBuilder(Protocol):
    def build(
        self,
        job: DatasetExportJob,
        grant: LeaseGrant,
        downloaded_inputs: dict[str, Path],
        target: Path,
    ) -> PackageResult: ...


class SyntheticFirstAttemptFailureBuilder:
    """Fails exactly one first attempt for the isolated local recovery rehearsal."""

    def __init__(self, delegate: PackageBuilder) -> None:
        self._delegate = delegate
        self._failed = False

    def build(
        self,
        job: DatasetExportJob,
        grant: LeaseGrant,
        downloaded_inputs: dict[str, Path],
        target: Path,
    ) -> PackageResult:
        if job.attempt == 1 and not self._failed:
            self._failed = True
            raise PackageBuildError("Synthetic recovery acceptance requested one retryable failure")
        return self._delegate.build(job, grant, downloaded_inputs, target)


@dataclass(frozen=True, slots=True)
class ExportFailure:
    error_class: str
    error_code: str
    safe_message: str
    retryable: bool


class JobProcessor:
    def __init__(
        self,
        client: ExportClient,
        builder: PackageBuilder,
        software_digest: str,
        temp_dir: Path | None,
    ) -> None:
        self._client: ExportClient = client
        self._builder: PackageBuilder = builder
        self._software_digest = software_digest
        self._temp_dir = temp_dir

    def process(self, body: bytes) -> ProcessingOutcome:
        try:
            job = DatasetExportJob.from_bytes(body)
        except ContractError:
            LOGGER.warning("Rejected a dataset export message that failed contract validation")
            return ProcessingOutcome(
                DeliveryDisposition.REJECT,
                DeadLetterReason.CONTRACT_INVALID,
            )
        if job.export_profile_key != CURRENT_PROFILE_KEY:
            LOGGER.error(
                "Rejected an export profile without a real Worker producer export_id=%s",
                job.export_id,
            )
            return ProcessingOutcome.for_job(
                DeliveryDisposition.REJECT,
                job,
                DeadLetterReason.PROFILE_UNSUPPORTED,
            )

        try:
            grant = self._client.acquire(job)
        except StaleDeliveryError:
            return ProcessingOutcome.for_job(DeliveryDisposition.ACK, job)
        except RetryableDeliveryError:
            return ProcessingOutcome.for_job(DeliveryDisposition.REQUEUE, job)
        except PermanentDeliveryError:
            return ProcessingOutcome.for_job(
                DeliveryDisposition.REJECT,
                job,
                DeadLetterReason.ACQUIRE_REJECTED,
            )

        started_at = datetime.now(UTC)
        try:
            with self._client.maintain_lease(job, grant):
                result = self._execute(job, grant, started_at)
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

    def _execute(
        self,
        job: DatasetExportJob,
        grant: LeaseGrant,
        started_at: datetime,
    ) -> dict[str, object]:
        try:
            with tempfile.TemporaryDirectory(
                dir=self._temp_dir, prefix="dataset-export-"
            ) as directory:
                root = Path(directory)
                inputs: dict[str, Path] = {}
                for index, stream in enumerate(
                    stream for session in grant.sessions for stream in session.streams
                ):
                    target = root / f"input-{index:06d}.media"
                    self._client.download_input(stream, target)
                    inputs[stream.video_path] = target
                package = self._builder.build(job, grant, inputs, root / grant.package_file_name)
                self._client.upload_package(job, grant, package.path)
                return self._success_result(job, started_at, package)
        except InputDigestMismatchError:
            return self._failure_result(
                job,
                started_at,
                ExportFailure(
                    "NON_RETRYABLE_INPUT",
                    "INPUT_DIGEST_MISMATCH",
                    "Downloaded input does not match the frozen size and SHA-256",
                    False,
                ),
            )
        except InputDownloadError:
            return self._failure_result(
                job,
                started_at,
                ExportFailure(
                    "RETRYABLE_TRANSIENT",
                    "INPUT_DOWNLOAD_FAILED",
                    "Approved dataset input could not be read from object storage",
                    True,
                ),
            )
        except PackageInputError:
            return self._failure_result(
                job,
                started_at,
                ExportFailure(
                    "NON_RETRYABLE_INPUT",
                    "CONTRACT_INVALID",
                    "Frozen dataset metadata does not match the approved v2 profile",
                    False,
                ),
            )
        except (PackageBuildError, OSError):
            return self._failure_result(
                job,
                started_at,
                ExportFailure(
                    "RETRYABLE_RESOURCE",
                    "PACKAGE_BUILD_FAILED",
                    "Dataset package could not be built on Worker temporary storage",
                    True,
                ),
            )
        except OutputUploadError:
            return self._failure_result(
                job,
                started_at,
                ExportFailure(
                    "RETRYABLE_TRANSIENT",
                    "OUTPUT_UPLOAD_FAILED",
                    "Dataset package could not be uploaded to object storage",
                    True,
                ),
            )
        except Exception:
            LOGGER.exception("Unexpected dataset export failure export_id=%s", job.export_id)
            return self._failure_result(
                job,
                started_at,
                ExportFailure(
                    "INTERNAL_BUG",
                    "INTERNAL_ERROR",
                    "Dataset export failed because of an internal Worker error",
                    True,
                ),
            )

    def _success_result(
        self,
        job: DatasetExportJob,
        started_at: datetime,
        package: PackageResult,
    ) -> dict[str, object]:
        return self._base_result(job, started_at) | {
            "status": "SUCCEEDED",
            "package_sha256": package.sha256,
            "package_size_bytes": package.size_bytes,
            "files": [file.as_contract() for file in package.files],
            "error": None,
        }

    def _failure_result(
        self,
        job: DatasetExportJob,
        started_at: datetime,
        failure: ExportFailure,
    ) -> dict[str, object]:
        return self._base_result(job, started_at) | {
            "status": "FAILED",
            "package_sha256": None,
            "package_size_bytes": None,
            "files": [],
            "error": {
                "error_class": failure.error_class,
                "error_code": failure.error_code,
                "safe_message": failure.safe_message,
                "retryable": failure.retryable,
            },
        }

    def _base_result(
        self, job: DatasetExportJob, started_at: datetime
    ) -> dict[str, object]:
        return {
            "schema_version": "dataset-export-result.v1",
            "message_id": str(job.message_id),
            "export_id": str(job.export_id),
            "attempt": job.attempt,
            "lease_token": job.lease_token,
            "snapshot_digest": job.snapshot_digest,
            "export_profile_digest": job.export_profile_digest,
            "software_digest": self._software_digest,
            "started_at": started_at.isoformat().replace("+00:00", "Z"),
            "completed_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        }
