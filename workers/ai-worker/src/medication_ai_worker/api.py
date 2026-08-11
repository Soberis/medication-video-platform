from __future__ import annotations

import hashlib
from collections.abc import Callable, Generator
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from threading import Event, Lock, Thread
from types import TracebackType
from typing import Self
from urllib.parse import urlparse

import httpx

from medication_ai_worker.contracts import CONTENT_TYPE, SHA256, AiJob
from medication_ai_worker.landmarker import InferenceResult, InferenceSummary


class RetryableError(RuntimeError):
    pass


class StaleError(RuntimeError):
    pass


class PermanentError(RuntimeError):
    pass


class _BearerTokenAuth(httpx.Auth):
    """Read the service identity for every Core API request.

    Production rotates short-lived JWTs by atomically replacing the configured
    token file.  Reading it from the auth flow (rather than at process startup)
    means the next request observes the new file without ever logging its
    contents.
    """

    def __init__(self, provider: Callable[[], str]) -> None:
        self._provider = provider

    def auth_flow(
        self,
        request: httpx.Request,
    ) -> Generator[httpx.Request, httpx.Response, None]:
        try:
            token = self._provider()
        except (OSError, ValueError) as exc:
            raise PermanentError("AI Worker service identity is unavailable") from exc
        if len(token) < 32 or any(character.isspace() for character in token):
            raise PermanentError("AI Worker service identity is unavailable")
        request.headers["Authorization"] = f"Bearer {token}"
        yield request


@dataclass(frozen=True, slots=True)
class LeaseGrant:
    input_url: str
    input_sha256: str
    input_size_bytes: int
    model_sha256: str
    profile_sha256: str
    lease_until: datetime
    heartbeat_interval_seconds: float
    # Defaults preserve the small test/fake-client constructor used by the
    # worker's existing unit tests.  Real Core API grants always populate and
    # validate both frozen fields below.
    sampling_fps: int = 10
    content_type: str = "video/mp4"


class LeaseMonitor:
    def __init__(self, client: CoreApiClient, job: AiJob, grant: LeaseGrant) -> None:
        self._client = client
        self._job = job
        self._lease_until = grant.lease_until
        self._interval = grant.heartbeat_interval_seconds
        self._stop = Event()
        self._lock = Lock()
        self._failure: Exception | None = None
        self._thread = Thread(target=self._run, name=f"ai-lease-{job.attempt}", daemon=True)

    def __enter__(self) -> Self:
        self._thread.start()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        del exc_value, traceback
        self._stop.set()
        self._thread.join()
        if exc_type is None:
            self.ensure_current()

    def ensure_current(self) -> None:
        with self._lock:
            failure = self._failure
        if failure is not None:
            raise failure

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                lease_until, interval = self._client.heartbeat(self._job)
            except RetryableError:
                with self._lock:
                    if datetime.now(UTC) >= self._lease_until:
                        self._failure = StaleError(
                            "AI job lease expired while Core API was unavailable"
                        )
                        return
                continue
            except (StaleError, PermanentError) as exc:
                with self._lock:
                    self._failure = exc
                return
            with self._lock:
                self._lease_until = lease_until
                self._interval = interval


class CoreApiClient:
    def __init__(
        self,
        base_url: str,
        bearer_token: str | Callable[[], str],
        worker_id: str,
        *,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._worker_id = worker_id
        token_provider = (lambda: bearer_token) if isinstance(bearer_token, str) else bearer_token
        self._client = httpx.Client(
            base_url=base_url,
            auth=_BearerTokenAuth(token_provider),
            timeout=httpx.Timeout(30, read=120),
            follow_redirects=False,
            transport=transport,
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
            input_url = _http_url(body, "input_url")
            input_sha256 = _digest(body, "input_sha256")
            input_size = _positive_integer(body, "input_size_bytes")
            content_type = _content_type(body)
            model_sha256 = _digest(body, "model_sha256")
            sampling_fps = _positive_integer(body, "sampling_fps")
            profile_sha256 = _digest(body, "profile_sha256")
            grant = LeaseGrant(
                input_url,
                input_sha256,
                input_size,
                model_sha256,
                profile_sha256,
                _timestamp(body, "lease_until"),
                _positive_number(body, "heartbeat_interval_seconds"),
                sampling_fps,
                content_type,
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise PermanentError("Core API returned an invalid AI lease grant") from exc
        if (
            grant.input_sha256 != job.input_sha256
            or grant.input_size_bytes != job.input_size_bytes
            or grant.content_type != job.content_type
            or grant.model_sha256 != job.model_sha256
            or grant.sampling_fps != job.sampling_fps
            or grant.profile_sha256 != job.profile_sha256
        ):
            raise StaleError("Core API lease grant does not match frozen broker inputs")
        return grant

    def maintain_lease(self, job: AiJob, grant: LeaseGrant) -> LeaseMonitor:
        return LeaseMonitor(self, job, grant)

    def heartbeat(self, job: AiJob) -> tuple[datetime, float]:
        response = self._request(
            "POST",
            f"/api/v1/internal/ai-jobs/{job.job_id}/attempts/{job.attempt}/heartbeat",
            job,
            {"worker_id": self._worker_id},
        )
        body = self._json(response)
        return _timestamp(body, "lease_until"), _positive_number(body, "heartbeat_interval_seconds")

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

    def submit(
        self, job: AiJob, started_at: datetime, result: InferenceResult, processing_duration_ms: int
    ) -> None:
        summary = result.summary
        payload: dict[str, object] = {
            "schema_version": "ai-hand-pose-result.v2",
            "message_id": str(job.message_id),
            "job_id": str(job.job_id),
            "attempt": job.attempt,
            "lease_token": job.lease_token,
            "input_sha256": job.input_sha256,
            "model_sha256": job.model_sha256,
            "profile_sha256": job.profile_sha256,
            "started_at": started_at.isoformat().replace("+00:00", "Z"),
            "completed_at": datetime.now(started_at.tzinfo).isoformat().replace("+00:00", "Z"),
            "outcome": "SUCCEEDED",
            "analysis_complete": True,
            "hand_result": "HAND_DETECTED" if summary.frames_with_hand > 0 else "NO_HAND_DETECTED",
            "total_video_frames": summary.total_video_frames,
            "inference_frame_count": summary.inference_frame_count,
            "frames_with_hand": summary.frames_with_hand,
            "frames_without_hand": summary.frames_without_hand,
            "left_hand_detection_count": summary.left_hand_detection_count,
            "right_hand_detection_count": summary.right_hand_detection_count,
            "invalid_landmark_count": summary.invalid_landmark_count,
            "processing_duration_ms": processing_duration_ms,
            "failure_reason": None,
            "candidates": result.candidates,
        }
        self._request(
            "PUT",
            f"/api/v1/internal/ai-jobs/{job.job_id}/attempts/{job.attempt}/result",
            job,
            payload,
        )

    def submit_failure(
        self,
        job: AiJob,
        started_at: datetime,
        failure_reason: str,
        processing_duration_ms: int,
        summary: InferenceSummary | None = None,
    ) -> None:
        completed_at = datetime.now(started_at.tzinfo)
        run_summary = summary or InferenceSummary(0, 0, 0, 0, 0, 0, 0, 0)
        result = {
            "schema_version": "ai-hand-pose-result.v2",
            "message_id": str(job.message_id),
            "job_id": str(job.job_id),
            "attempt": job.attempt,
            "lease_token": job.lease_token,
            "input_sha256": job.input_sha256,
            "model_sha256": job.model_sha256,
            "profile_sha256": job.profile_sha256,
            "started_at": started_at.isoformat().replace("+00:00", "Z"),
            "completed_at": completed_at.isoformat().replace("+00:00", "Z"),
            "outcome": "FAILED",
            "analysis_complete": False,
            "hand_result": "UNKNOWN",
            "total_video_frames": run_summary.total_video_frames,
            "inference_frame_count": run_summary.inference_frame_count,
            "frames_with_hand": run_summary.frames_with_hand,
            "frames_without_hand": run_summary.frames_without_hand,
            "left_hand_detection_count": run_summary.left_hand_detection_count,
            "right_hand_detection_count": run_summary.right_hand_detection_count,
            "invalid_landmark_count": run_summary.invalid_landmark_count,
            "processing_duration_ms": processing_duration_ms,
            "failure_reason": failure_reason[:128],
            "candidates": [],
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
            raise PermanentError(
                f"Core API rejected AI Worker request status={response.status_code}"
                f" error_code={_error_code(response)}"
            )
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


def _timestamp(value: dict[str, object], field: str) -> datetime:
    candidate = value.get(field)
    if not isinstance(candidate, str):
        raise PermanentError(f"Core API returned invalid {field}")
    try:
        parsed = datetime.fromisoformat(candidate.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PermanentError(f"Core API returned invalid {field}") from exc
    if parsed.tzinfo is None:
        raise PermanentError(f"Core API returned invalid {field}")
    return parsed


def _positive_number(value: dict[str, object], field: str) -> float:
    candidate = value.get(field)
    if isinstance(candidate, bool) or not isinstance(candidate, (int, float)) or candidate <= 0:
        raise PermanentError(f"Core API returned invalid {field}")
    return float(candidate)


def _positive_integer(value: dict[str, object], field: str) -> int:
    candidate = value.get(field)
    if isinstance(candidate, bool) or not isinstance(candidate, int) or candidate < 1:
        raise ValueError(f"Core API returned invalid {field}")
    return candidate


def _digest(value: dict[str, object], field: str) -> str:
    candidate = value.get(field)
    if not isinstance(candidate, str) or SHA256.fullmatch(candidate) is None:
        raise ValueError(f"Core API returned invalid {field}")
    return candidate


def _content_type(value: dict[str, object]) -> str:
    candidate = value.get("content_type")
    if not isinstance(candidate, str) or CONTENT_TYPE.fullmatch(candidate) is None:
        raise ValueError("Core API returned invalid content_type")
    return candidate


def _http_url(value: dict[str, object], field: str) -> str:
    candidate = value.get(field)
    if not isinstance(candidate, str):
        raise ValueError(f"Core API returned invalid {field}")
    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError(f"Core API returned invalid {field}")
    return candidate


def _error_code(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return "UNPARSEABLE"
    if isinstance(body, dict) and isinstance(body.get("error_code"), str):
        return str(body["error_code"])
    return "UNSPECIFIED"
