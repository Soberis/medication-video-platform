from __future__ import annotations

import logging
import tempfile
import time
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path

from medication_ai_worker.api import CoreApiClient, PermanentError, RetryableError, StaleError
from medication_ai_worker.config import Settings
from medication_ai_worker.contracts import AiJob, ContractError
from medication_ai_worker.landmarker import PROFILE_SHA256, analyze_video

LOGGER = logging.getLogger(__name__)


class Disposition(Enum):
    ACK = "ACK"
    REQUEUE = "REQUEUE"
    REJECT = "REJECT"


class Processor:
    def __init__(self, client: CoreApiClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    def process(self, body: bytes) -> Disposition:
        try:
            job = AiJob.from_bytes(body)
        except ContractError:
            LOGGER.warning("Rejected an AI message that failed contract validation")
            return Disposition.REJECT
        if job.model_sha256 != self._settings.model_sha256:
            LOGGER.error("Rejected AI job with an unapproved model digest job_id=%s", job.job_id)
            return Disposition.REJECT
        if job.profile_sha256 != PROFILE_SHA256:
            LOGGER.error("Rejected AI job with an unsupported profile job_id=%s", job.job_id)
            return Disposition.REJECT
        started_at = datetime.now(UTC)
        try:
            grant = self._client.acquire(job)
            with self._client.maintain_lease(job, grant):
                started_clock = time.monotonic()
                with tempfile.TemporaryDirectory(
                    dir=self._settings.temp_dir, prefix="ai-hand-pose-"
                ) as directory:
                    input_path = Path(directory) / "input.mp4"
                    self._client.download(grant, input_path)
                    result = analyze_video(input_path, self._settings.model_path, job.sampling_fps)
                processing_duration_ms = max(0, round((time.monotonic() - started_clock) * 1000))
                if len(result.candidates) > 20_000:
                    self._client.submit_failure(
                        job,
                        started_at,
                        "AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT",
                        processing_duration_ms,
                    )
                else:
                    self._client.submit(job, started_at, result, processing_duration_ms)
        except StaleError:
            LOGGER.info(
                "Acknowledged stale AI attempt job_id=%s attempt=%d", job.job_id, job.attempt
            )
            return Disposition.ACK
        except RetryableError:
            LOGGER.warning(
                "Retrying AI attempt after transient dependency error job_id=%s", job.job_id
            )
            return Disposition.REQUEUE
        except PermanentError as exc:
            LOGGER.error(
                "Rejected AI attempt because Core API rejected it job_id=%s reason=%s",
                job.job_id,
                exc,
            )
            return Disposition.REJECT
        except (OSError, RuntimeError, ValueError):
            LOGGER.exception(
                "AI inference failed; submitting a terminal Worker failure job_id=%s", job.job_id
            )
            try:
                self._client.submit_failure(job, started_at, "AI_HAND_POSE_INFERENCE_FAILED", 0)
            except StaleError:
                return Disposition.ACK
            except RetryableError:
                return Disposition.REQUEUE
            except PermanentError:
                return Disposition.REJECT
            return Disposition.ACK
        return Disposition.ACK
