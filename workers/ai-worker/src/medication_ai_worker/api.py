from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import httpx

from medication_ai_worker.contracts import AiJob


class RetryableError(RuntimeError):
    pass


class StaleError(RuntimeError):
    pass


class PermanentError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class LeaseGrant:
    input_url: str
    input_sha256: str
    input_size_bytes: int
    model_sha256: str


class CoreApiClient:
    def __init__(self, base_url: str, token: str, worker_id: str) -> None:
        self._worker_id = worker_id
        self._client = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {token}"},
            timeout=httpx.Timeout(30, read=120),
            follow_redirects=False,
        )

    def close(self) -> None:
        self._client.close()

    def acquire(self, job: AiJob) -> LeaseGrant:
        response = self._request(
            "POST",
            f"/api/v1/internal/ai-jobs/{job.job_id}/attempts/{job.attempt}/lease",
            job,
            {"worker_id": self._worker_id},
        )
        body = self._json(response)
        try:
            input_size = body["input_size_bytes"]
            if isinstance(input_size, bool) or not isinstance(input_size, int):
                raise ValueError("input_size_bytes must be an integer")
            grant = LeaseGrant(
                str(body["input_url"]),
                str(body["input_sha256"]),
                input_size,
                str(body["model_sha256"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise PermanentError("Core API returned an invalid AI lease grant") from exc
        if (
            grant.input_sha256 != job.input_sha256
            or grant.input_size_bytes != job.input_size_bytes
            or grant.model_sha256 != job.model_sha256
        ):
            raise StaleError("Core API lease grant does not match frozen broker inputs")
        return grant

    def download(self, grant: LeaseGrant, target: Path) -> None:
        digest, size = hashlib.sha256(), 0
        try:
            with httpx.stream(
                "GET", grant.input_url, timeout=httpx.Timeout(30, read=120), follow_redirects=False
            ) as response:
                response.raise_for_status()
                with target.open("xb") as output:
                    for chunk in response.iter_bytes():
                        size += len(chunk)
                        if size > grant.input_size_bytes:
                            raise PermanentError("Downloaded input exceeds frozen size")
                        output.write(chunk)
                        digest.update(chunk)
        except (httpx.HTTPError, OSError) as exc:
            raise RetryableError("Approved media input could not be read") from exc
        if size != grant.input_size_bytes or digest.hexdigest() != grant.input_sha256:
            raise PermanentError("Downloaded input digest differs from frozen AI job")

    def submit(self, job: AiJob, started_at: datetime, candidates: list[dict[str, object]]) -> None:
        result = {
            "schema_version": "ai-hand-pose-result.v1",
            "message_id": str(job.message_id),
            "job_id": str(job.job_id),
            "attempt": job.attempt,
            "lease_token": job.lease_token,
            "input_sha256": job.input_sha256,
            "model_sha256": job.model_sha256,
            "started_at": started_at.isoformat().replace("+00:00", "Z"),
            "completed_at": datetime.now(started_at.tzinfo).isoformat().replace("+00:00", "Z"),
            "candidates": candidates,
        }
        self._request(
            "PUT",
            f"/api/v1/internal/ai-jobs/{job.job_id}/attempts/{job.attempt}/result",
            job,
            result,
        )

    def _request(
        self, method: str, path: str, job: AiJob, body: dict[str, object]
    ) -> httpx.Response:
        try:
            response = self._client.request(
                method, path, headers={"X-AI-Lease-Token": job.lease_token}, json=body
            )
        except httpx.RequestError as exc:
            raise RetryableError("Core API is temporarily unavailable") from exc
        if response.status_code in {404, 409}:
            raise StaleError("AI job attempt is no longer current")
        if response.status_code in {401, 403} or (400 <= response.status_code < 500):
            raise PermanentError("Core API rejected AI Worker request")
        if response.status_code >= 500:
            raise RetryableError("Core API is temporarily unavailable")
        return response

    @staticmethod
    def _json(response: httpx.Response) -> dict[str, object]:
        try:
            value = response.json()
        except ValueError as exc:
            raise PermanentError("Core API returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise PermanentError("Core API returned invalid JSON")
        return value
