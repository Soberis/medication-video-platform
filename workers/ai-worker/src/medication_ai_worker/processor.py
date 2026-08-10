from __future__ import annotations

import logging
import tempfile
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path

from medication_ai_worker.api import CoreApiClient, PermanentError, RetryableError, StaleError
from medication_ai_worker.config import Settings
from medication_ai_worker.contracts import AiJob, ContractError
from medication_ai_worker.landmarker import candidates_from_video

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
        try:
            grant = self._client.acquire(job)
            started_at = datetime.now(UTC)
            with tempfile.TemporaryDirectory(
                dir=self._settings.temp_dir, prefix="ai-hand-pose-"
            ) as directory:
                input_path = Path(directory) / "input.mp4"
                self._client.download(grant, input_path)
                candidates = candidates_from_video(
                    input_path, self._settings.model_path, job.sampling_fps
                )
            self._client.submit(job, started_at, candidates)
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
        except (OSError, RuntimeError, ValueError):
            LOGGER.exception(
                "AI inference failed; broker delivery will retry job_id=%s", job.job_id
            )
            return Disposition.REQUEUE
        except PermanentError:
            LOGGER.error("Rejected AI attempt because Core API rejected it job_id=%s", job.job_id)
            return Disposition.REJECT
        return Disposition.ACK
