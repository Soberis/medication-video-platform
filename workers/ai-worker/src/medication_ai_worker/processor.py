from __future__ import annotations

import logging
import tempfile
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from uuid import UUID

from medication_ai_worker.api import CoreApiClient, PermanentError, RetryableError, StaleError
from medication_ai_worker.config import Settings
from medication_ai_worker.contracts import AiJob, ContractError
from medication_ai_worker.landmarker import (
    PROFILE_SHA256,
    InferenceSummary,
    analyze_video,
)

LOGGER = logging.getLogger(__name__)


class Disposition(Enum):
    ACK = "ACK"
    REQUEUE = "REQUEUE"
    REJECT = "REJECT"


class DeadLetterReason(Enum):
    CONTRACT_INVALID = "AI_HAND_POSE_CONTRACT_INVALID"
    PROFILE_UNSUPPORTED = "AI_HAND_POSE_PROFILE_UNSUPPORTED"
    MODEL_UNSUPPORTED = "AI_HAND_POSE_MODEL_UNSUPPORTED"
    ACQUIRE_REJECTED = "AI_HAND_POSE_ACQUIRE_REJECTED"
    LEASE_REJECTED = "AI_HAND_POSE_LEASE_REJECTED"
    RESULT_REJECTED = "AI_HAND_POSE_RESULT_REJECTED"


@dataclass(frozen=True, slots=True)
class ProcessingOutcome:
    disposition: Disposition
    dead_letter_reason: DeadLetterReason | None = None
    message_id: UUID | None = None
    job_id: UUID | None = None
    attempt: int | None = None

    @classmethod
    def for_job(
        cls,
        disposition: Disposition,
        job: AiJob,
        dead_letter_reason: DeadLetterReason | None = None,
    ) -> ProcessingOutcome:
        return cls(
            disposition,
            dead_letter_reason,
            job.message_id,
            job.job_id,
            job.attempt,
        )


class Processor:
    def __init__(self, client: CoreApiClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings
        self._last_outcome = ProcessingOutcome(Disposition.ACK)

    @property
    def last_outcome(self) -> ProcessingOutcome:
        """Most recent delivery outcome, used by the consumer's DLX publisher."""

        return self._last_outcome

    def process(self, body: bytes) -> Disposition:
        """Compatibility wrapper returning the historical enum disposition."""

        outcome = self.process_delivery(body)
        self._last_outcome = outcome
        return outcome.disposition

    def process_delivery(self, body: bytes) -> ProcessingOutcome:
        try:
            job = AiJob.from_bytes(body)
        except ContractError:
            LOGGER.warning("Rejected an AI message that failed contract validation")
            return ProcessingOutcome(Disposition.REJECT, DeadLetterReason.CONTRACT_INVALID)

        started_at = datetime.now(UTC)
        try:
            grant = self._client.acquire(job)
        except StaleError:
            LOGGER.info(
                "Acknowledged stale AI attempt job_id=%s attempt=%d", job.job_id, job.attempt
            )
            return ProcessingOutcome.for_job(Disposition.ACK, job)
        except RetryableError:
            LOGGER.warning("Retrying AI attempt after transient lease error job_id=%s", job.job_id)
            return ProcessingOutcome.for_job(Disposition.REQUEUE, job)
        except PermanentError:
            LOGGER.error("AI lease request was rejected job_id=%s", job.job_id)
            return self._best_effort_failure(
                job,
                started_at,
                "AI_HAND_POSE_ACQUIRE_REJECTED",
                DeadLetterReason.ACQUIRE_REJECTED,
                stale_is_reject=True,
            )

        # Acquire first so Core owns an active lease before the Worker rejects
        # a deployment/configuration mismatch.  This lets the terminal FAILED
        # result bind to the real attempt instead of leaving it QUEUED while a
        # broker diagnostic is emitted.
        if job.model_sha256 != self._settings.model_sha256:
            LOGGER.error("Rejected AI job with an unapproved model digest job_id=%s", job.job_id)
            return self._best_effort_failure(
                job,
                started_at,
                "AI_HAND_POSE_MODEL_UNSUPPORTED",
                DeadLetterReason.MODEL_UNSUPPORTED,
            )
        if job.profile_sha256 != PROFILE_SHA256:
            LOGGER.error("Rejected AI job with an unsupported profile job_id=%s", job.job_id)
            return self._best_effort_failure(
                job,
                started_at,
                "AI_HAND_POSE_PROFILE_UNSUPPORTED",
                DeadLetterReason.PROFILE_UNSUPPORTED,
            )

        try:
            with self._client.maintain_lease(job, grant):
                started_clock = time.monotonic()
                with tempfile.TemporaryDirectory(
                    dir=self._settings.temp_dir, prefix="ai-hand-pose-"
                ) as directory:
                    input_path = Path(directory) / "input.mp4"
                    self._client.download(grant, input_path)
                    result = analyze_video(input_path, self._settings.model_path, job.sampling_fps)
                processing_duration_ms = max(0, round((time.monotonic() - started_clock) * 1000))
                if (
                    result.summary.invalid_hand_frame_count > 0
                    or result.summary.total_video_frames <= 0
                    or result.summary.inference_frame_count <= 0
                ):
                    return self._best_effort_failure(
                        job,
                        started_at,
                        "AI_HAND_POSE_INVALID_OUTPUT",
                        DeadLetterReason.RESULT_REJECTED,
                        processing_duration_ms,
                        result.summary,
                    )
                if len(result.candidates) > 20_000:
                    return self._best_effort_failure(
                        job,
                        started_at,
                        "AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT",
                        DeadLetterReason.RESULT_REJECTED,
                        processing_duration_ms,
                        result.summary,
                    )
                try:
                    self._client.submit(job, started_at, result, processing_duration_ms)
                except StaleError:
                    return ProcessingOutcome.for_job(Disposition.ACK, job)
                except RetryableError:
                    return ProcessingOutcome.for_job(Disposition.REQUEUE, job)
                except PermanentError:
                    return self._best_effort_failure(
                        job,
                        started_at,
                        "AI_HAND_POSE_RESULT_REJECTED",
                        DeadLetterReason.RESULT_REJECTED,
                        processing_duration_ms,
                        stale_is_reject=True,
                    )
        except StaleError:
            return ProcessingOutcome.for_job(Disposition.ACK, job)
        except RetryableError:
            return ProcessingOutcome.for_job(Disposition.REQUEUE, job)
        except PermanentError:
            return self._best_effort_failure(
                job,
                started_at,
                "AI_HAND_POSE_LEASE_REJECTED",
                DeadLetterReason.LEASE_REJECTED,
                stale_is_reject=False,
            )
        except (OSError, RuntimeError, ValueError):
            LOGGER.exception(
                "AI inference failed; submitting a terminal Worker failure job_id=%s", job.job_id
            )
            return self._best_effort_failure(
                job,
                started_at,
                "AI_HAND_POSE_INFERENCE_FAILED",
                DeadLetterReason.RESULT_REJECTED,
            )
        return ProcessingOutcome.for_job(Disposition.ACK, job)

    def _best_effort_failure(
        self,
        job: AiJob,
        started_at: datetime,
        failure_reason: str,
        dead_letter_reason: DeadLetterReason,
        processing_duration_ms: int = 0,
        summary: InferenceSummary | None = None,
        *,
        stale_is_reject: bool = False,
    ) -> ProcessingOutcome:
        submit_failure = getattr(self._client, "submit_failure", None)
        if not callable(submit_failure):
            return ProcessingOutcome.for_job(Disposition.REJECT, job, dead_letter_reason)
        try:
            submit_failure(
                job,
                started_at,
                failure_reason,
                processing_duration_ms,
                summary,
            )
        except RetryableError:
            return ProcessingOutcome.for_job(Disposition.REQUEUE, job)
        except StaleError:
            if stale_is_reject:
                return ProcessingOutcome.for_job(Disposition.REJECT, job, dead_letter_reason)
            return ProcessingOutcome.for_job(Disposition.ACK, job)
        except PermanentError:
            return ProcessingOutcome.for_job(Disposition.REJECT, job, dead_letter_reason)
        return ProcessingOutcome.for_job(Disposition.ACK, job)
