from __future__ import annotations

import hashlib
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import httpx

from medication_media_worker.contracts import MediaProbeJob


class RetryableDeliveryError(RuntimeError):
    """A transient Core API failure; the broker delivery may be retried."""


class PermanentDeliveryError(RuntimeError):
    """A configuration or authorization failure; retrying the same delivery is unsafe."""


class StaleDeliveryError(RuntimeError):
    """The attempt is no longer current and the broker delivery can be acknowledged."""


class ObjectDownloadError(RuntimeError):
    """The granted object could not be read completely."""


class InputDigestMismatchError(RuntimeError):
    """The downloaded bytes differ from the frozen job input."""


@dataclass(frozen=True, slots=True)
class LeaseGrant:
    input_url: str
    expires_at: datetime


class CoreApiClient:
    def __init__(self, base_url: str, bearer_token: str, worker_id: str) -> None:
        self._worker_id = worker_id
        self._client = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {bearer_token}"},
            timeout=httpx.Timeout(30.0, read=60.0),
            follow_redirects=False,
        )

    def close(self) -> None:
        self._client.close()

    def acquire_input(self, job: MediaProbeJob) -> LeaseGrant:
        try:
            response = self._client.post(
                f"/api/v1/internal/media-jobs/{job.job_id}/attempts/{job.attempt}/lease",
                headers={"X-Media-Lease-Token": job.lease_token},
                json={"worker_id": self._worker_id},
            )
        except httpx.RequestError as exc:
            raise RetryableDeliveryError("Core API is temporarily unavailable") from exc
        if response.status_code in {404, 409}:
            raise StaleDeliveryError("Media job attempt is no longer acquirable")
        if response.status_code in {401, 403}:
            raise PermanentDeliveryError("Worker service identity was rejected")
        if response.status_code >= 500:
            raise RetryableDeliveryError("Core API is temporarily unavailable")
        if response.status_code != 200:
            raise PermanentDeliveryError("Core API rejected the lease request")
        try:
            body = response.json()
            input_url = body["input_url"]
            expires_at = datetime.fromisoformat(body["expires_at"].replace("Z", "+00:00"))
        except (KeyError, TypeError, ValueError) as exc:
            raise PermanentDeliveryError("Core API returned an invalid lease grant") from exc
        if not isinstance(input_url, str) or not input_url.startswith(("http://", "https://")):
            raise PermanentDeliveryError("Core API returned an invalid lease grant")
        if expires_at.tzinfo is None:
            raise PermanentDeliveryError("Core API returned an invalid lease grant")
        return LeaseGrant(input_url=input_url, expires_at=expires_at)

    def download_input(self, grant: LeaseGrant, job: MediaProbeJob, target: Path) -> None:
        digest = hashlib.sha256()
        size = 0
        try:
            timeout = httpx.Timeout(30.0, read=120.0)
            with httpx.stream("GET", grant.input_url, timeout=timeout) as response:
                if response.status_code != 200:
                    raise ObjectDownloadError("Object storage did not return the approved input")
                with target.open("xb") as output:
                    for chunk in _bounded_chunks(response.iter_bytes(), job.input_size_bytes):
                        output.write(chunk)
                        digest.update(chunk)
                        size += len(chunk)
        except (httpx.RequestError, OSError) as exc:
            raise ObjectDownloadError("Object storage input could not be read") from exc
        if size != job.input_size_bytes or digest.hexdigest() != job.input_sha256:
            raise InputDigestMismatchError(
                "Downloaded input size or SHA-256 does not match the job"
            )

    def submit_result(self, job: MediaProbeJob, result: dict[str, object]) -> None:
        try:
            response = self._client.put(
                f"/api/v1/internal/media-jobs/{job.job_id}/attempts/{job.attempt}/result",
                headers={"X-Media-Lease-Token": job.lease_token},
                json=result,
            )
        except httpx.RequestError as exc:
            raise RetryableDeliveryError(
                "Core API result endpoint is temporarily unavailable"
            ) from exc
        if response.status_code in {200, 201, 204}:
            return
        if response.status_code in {404, 409}:
            raise StaleDeliveryError("Media job result is stale or already terminal")
        if response.status_code in {401, 403}:
            raise PermanentDeliveryError("Worker service identity was rejected")
        if response.status_code >= 500:
            raise RetryableDeliveryError("Core API result endpoint is temporarily unavailable")
        raise PermanentDeliveryError("Core API rejected the media result")


def _bounded_chunks(chunks: Iterator[bytes], expected_size: int) -> Iterator[bytes]:
    observed = 0
    for chunk in chunks:
        observed += len(chunk)
        if observed > expected_size:
            raise InputDigestMismatchError("Downloaded input exceeds the frozen size")
        yield chunk
