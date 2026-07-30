from __future__ import annotations

import hashlib
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from threading import Event, Lock, Thread
from types import TracebackType
from typing import Self
from urllib.parse import urlparse
from uuid import UUID

import httpx

from medication_export_worker.contracts import DatasetExportJob


class RetryableDeliveryError(RuntimeError):
    """A transient dependency failure; the broker delivery may be retried."""


class PermanentDeliveryError(RuntimeError):
    """A contract, authorization, or configuration failure that requeue cannot repair."""


class StaleDeliveryError(RuntimeError):
    """The export attempt is no longer current and the delivery can be acknowledged."""


class InputDownloadError(RuntimeError):
    """An approved input could not be downloaded completely."""


class InputDigestMismatchError(RuntimeError):
    """Downloaded bytes differ from the frozen input."""


class OutputUploadError(RuntimeError):
    """The generated package could not be uploaded to the granted object."""


@dataclass(frozen=True, slots=True)
class ExportStream:
    camera_stream_id: UUID
    clip_asset_id: UUID
    camera_index: int
    content_type: str
    size_bytes: int
    sha256: str
    input_url: str
    video_path: str
    annotation_revision_id: UUID
    annotation_schema_version: str
    annotation_document_sha256: str
    annotation_document: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class ExportSession:
    capture_session_id: UUID
    split: str
    streams: tuple[ExportStream, ...]


@dataclass(frozen=True, slots=True)
class LeaseGrant:
    lease_until: datetime
    heartbeat_interval_seconds: float
    input_urls_expire_at: datetime
    output_part_size_bytes: int
    package_file_name: str
    label_schema: Mapping[str, object]
    sessions: tuple[ExportSession, ...]


@dataclass(frozen=True, slots=True)
class HeartbeatGrant:
    lease_until: datetime
    heartbeat_interval_seconds: float


class LeaseMonitor:
    def __init__(
        self,
        job: DatasetExportJob,
        initial_grant: LeaseGrant,
        renew: Callable[[DatasetExportJob], HeartbeatGrant],
    ) -> None:
        self._job = job
        self._renew = renew
        self._stop = Event()
        self._lock = Lock()
        self._lease_until = initial_grant.lease_until
        self._interval = initial_grant.heartbeat_interval_seconds
        self._failure: StaleDeliveryError | PermanentDeliveryError | None = None
        self._thread = Thread(
            target=self._run,
            name=f"dataset-export-lease-{job.attempt}",
            daemon=True,
        )

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
        delay = self._interval
        while not self._stop.wait(delay):
            try:
                grant = self._renew(self._job)
            except RetryableDeliveryError:
                with self._lock:
                    remaining = (self._lease_until - datetime.now(UTC)).total_seconds()
                    if remaining <= 0:
                        self._failure = StaleDeliveryError(
                            "Dataset export lease expired while the Core API was unavailable"
                        )
                        return
                delay = max(0.1, min(5.0, remaining / 2))
            except (StaleDeliveryError, PermanentDeliveryError) as exc:
                with self._lock:
                    self._failure = exc
                return
            else:
                with self._lock:
                    self._lease_until = grant.lease_until
                    self._interval = grant.heartbeat_interval_seconds
                delay = grant.heartbeat_interval_seconds


class CoreApiClient:
    def __init__(self, base_url: str, bearer_token: str, worker_id: str) -> None:
        self._worker_id = worker_id
        self._client = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {bearer_token}"},
            timeout=httpx.Timeout(30.0, read=120.0),
            follow_redirects=False,
        )

    def close(self) -> None:
        self._client.close()

    def acquire(self, job: DatasetExportJob) -> LeaseGrant:
        response = self._core_request(
            "POST",
            self._attempt_path(job, "lease"),
            job,
            payload={"worker_id": self._worker_id},
        )
        return _parse_lease(response, job)

    def maintain_lease(self, job: DatasetExportJob, grant: LeaseGrant) -> LeaseMonitor:
        return LeaseMonitor(job, grant, self.heartbeat)

    def heartbeat(self, job: DatasetExportJob) -> HeartbeatGrant:
        response = self._core_request(
            "POST",
            self._attempt_path(job, "heartbeat"),
            job,
            payload={"worker_id": self._worker_id},
        )
        body = _json_object(response, "heartbeat grant")
        lease_until = _date_time(body, "lease_until")
        interval = _positive_number(body.get("heartbeat_interval_seconds"))
        if (
            _uuid(body, "export_id") != job.export_id
            or _positive_int(body, "attempt") != job.attempt
        ):
            raise PermanentDeliveryError("Core API returned a mismatched heartbeat grant")
        return HeartbeatGrant(lease_until, interval)

    def download_input(self, stream: ExportStream, target: Path) -> None:
        digest = hashlib.sha256()
        size = 0
        try:
            timeout = httpx.Timeout(30.0, read=300.0)
            with httpx.stream("GET", stream.input_url, timeout=timeout) as response:
                if response.status_code != 200:
                    raise InputDownloadError("Object storage rejected an approved input read")
                with target.open("xb") as output:
                    for chunk in _bounded_chunks(response.iter_bytes(), stream.size_bytes):
                        output.write(chunk)
                        digest.update(chunk)
                        size += len(chunk)
        except InputDigestMismatchError:
            raise
        except (httpx.RequestError, OSError) as exc:
            raise InputDownloadError("Approved input could not be read") from exc
        if size != stream.size_bytes or digest.hexdigest() != stream.sha256:
            raise InputDigestMismatchError(
                "Downloaded input size or SHA-256 does not match the frozen snapshot"
            )

    def upload_package(self, job: DatasetExportJob, grant: LeaseGrant, package: Path) -> None:
        part_number = 1
        try:
            with package.open("rb") as source:
                while chunk := source.read(grant.output_part_size_bytes):
                    if part_number > 10_000:
                        raise OutputUploadError("Dataset package exceeds the multipart limit")
                    upload_url = self._authorize_part(job, part_number)
                    response = httpx.put(
                        upload_url,
                        content=chunk,
                        headers={"Content-Length": str(len(chunk))},
                        timeout=httpx.Timeout(30.0, read=300.0, write=300.0),
                        follow_redirects=False,
                    )
                    if response.status_code not in {200, 201, 204}:
                        raise OutputUploadError("Object storage rejected a dataset package part")
                    part_number += 1
        except OutputUploadError:
            raise
        except (httpx.RequestError, OSError) as exc:
            raise OutputUploadError("Dataset package upload failed") from exc
        if part_number == 1:
            raise OutputUploadError("Dataset package is empty")

    def submit_result(self, job: DatasetExportJob, result: dict[str, object]) -> None:
        self._core_request(
            "PUT",
            self._attempt_path(job, "result"),
            job,
            payload=result,
        )

    def _authorize_part(self, job: DatasetExportJob, part_number: int) -> str:
        path = (
            f"/api/v1/internal/dataset-exports/{job.export_id}/attempts/{job.attempt}"
            f"/output-parts/{part_number}/authorization"
        )
        response = self._core_request(
            "POST",
            path,
            job,
            payload={"worker_id": self._worker_id},
        )
        body = _json_object(response, "part authorization")
        if (
            _uuid(body, "export_id") != job.export_id
            or _positive_int(body, "attempt") != job.attempt
            or _positive_int(body, "part_number") != part_number
        ):
            raise PermanentDeliveryError("Core API returned a mismatched part authorization")
        return _absolute_http_url(body, "upload_url")

    def _core_request(
        self,
        method: str,
        path: str,
        job: DatasetExportJob,
        payload: object,
    ) -> httpx.Response:
        try:
            response = self._client.request(
                method,
                path,
                headers={"X-Export-Lease-Token": job.lease_token},
                json=payload,
            )
        except httpx.RequestError as exc:
            raise RetryableDeliveryError("Core API is temporarily unavailable") from exc
        error_code, retryable = _error_response(response)
        if error_code in {
            "DATASET_EXPORT_NOT_FOUND",
            "STALE_ATTEMPT",
            "EXPORT_LEASE_EXPIRED",
            "EXPORT_ATTEMPT_ALREADY_LEASED",
        }:
            raise StaleDeliveryError("Dataset export attempt is no longer current")
        if response.status_code in {401, 403}:
            raise PermanentDeliveryError("Export Worker service identity was rejected")
        if response.status_code >= 500 or retryable:
            raise RetryableDeliveryError("Core API is temporarily unavailable")
        if response.status_code not in {200, 201, 202, 204}:
            raise PermanentDeliveryError("Core API rejected the Export Worker request")
        return response

    @staticmethod
    def _attempt_path(job: DatasetExportJob, operation: str) -> str:
        return (
            f"/api/v1/internal/dataset-exports/{job.export_id}/attempts/{job.attempt}/"
            f"{operation}"
        )


def _parse_lease(response: httpx.Response, job: DatasetExportJob) -> LeaseGrant:
    body = _json_object(response, "lease grant")
    if _uuid(body, "export_id") != job.export_id or _positive_int(body, "attempt") != job.attempt:
        raise PermanentDeliveryError("Core API returned a mismatched lease grant")
    if _string(body, "snapshot_digest") != job.snapshot_digest:
        raise PermanentDeliveryError("Core API returned a mismatched snapshot digest")
    if (
        _string(body, "export_profile_key") != job.export_profile_key
        or _string(body, "export_profile_digest") != job.export_profile_digest
    ):
        raise PermanentDeliveryError("Core API returned a mismatched export profile")

    raw_schema = body.get("label_schema")
    raw_sessions = body.get("sessions")
    if not isinstance(raw_schema, dict) or not isinstance(raw_sessions, list) or not raw_sessions:
        raise PermanentDeliveryError("Core API returned incomplete frozen export inputs")
    sessions = tuple(_parse_session(value) for value in raw_sessions)
    paths = [stream.video_path for session in sessions for stream in session.streams]
    if len(paths) != len(set(paths)):
        raise PermanentDeliveryError("Core API returned duplicate package video paths")
    return LeaseGrant(
        lease_until=_date_time(body, "lease_until"),
        heartbeat_interval_seconds=_positive_number(body.get("heartbeat_interval_seconds")),
        input_urls_expire_at=_date_time(body, "input_urls_expire_at"),
        output_part_size_bytes=_positive_int(body, "output_part_size_bytes"),
        package_file_name=_string(body, "package_file_name"),
        label_schema=raw_schema,
        sessions=sessions,
    )


def _parse_session(value: object) -> ExportSession:
    if not isinstance(value, dict):
        raise PermanentDeliveryError("Core API returned an invalid dataset session")
    split = _string(value, "split")
    streams = value.get("streams")
    if split not in {"PILOT", "HOLDOUT"} or not isinstance(streams, list) or not streams:
        raise PermanentDeliveryError("Core API returned an invalid dataset session")
    return ExportSession(
        capture_session_id=_uuid(value, "capture_session_id"),
        split=split,
        streams=tuple(_parse_stream(stream) for stream in streams),
    )


def _parse_stream(value: object) -> ExportStream:
    if not isinstance(value, dict):
        raise PermanentDeliveryError("Core API returned an invalid dataset stream")
    document = value.get("annotation_document")
    if not isinstance(document, dict):
        raise PermanentDeliveryError("Core API returned an invalid annotation document")
    video_path = _string(value, "video_path")
    path = Path(video_path)
    if (
        not video_path.startswith("videos/raw/")
        or path.is_absolute()
        or ".." in path.parts
        or "\\" in video_path
    ):
        raise PermanentDeliveryError("Core API returned an unsafe package video path")
    sha256 = _string(value, "sha256")
    document_sha256 = _string(value, "annotation_document_sha256")
    if not _is_sha256(sha256) or not _is_sha256(document_sha256):
        raise PermanentDeliveryError("Core API returned an invalid frozen digest")
    return ExportStream(
        camera_stream_id=_uuid(value, "camera_stream_id"),
        clip_asset_id=_uuid(value, "clip_asset_id"),
        camera_index=_non_negative_int(value, "camera_index"),
        content_type=_string(value, "content_type"),
        size_bytes=_positive_int(value, "size_bytes"),
        sha256=sha256,
        input_url=_absolute_http_url(value, "input_url"),
        video_path=video_path,
        annotation_revision_id=_uuid(value, "annotation_revision_id"),
        annotation_schema_version=_string(value, "annotation_schema_version"),
        annotation_document_sha256=document_sha256,
        annotation_document=document,
    )


def _json_object(response: httpx.Response, label: str) -> dict[str, object]:
    try:
        body = response.json()
    except ValueError as exc:
        raise PermanentDeliveryError(f"Core API returned an invalid {label}") from exc
    if not isinstance(body, dict):
        raise PermanentDeliveryError(f"Core API returned an invalid {label}")
    return body


def _error_response(response: httpx.Response) -> tuple[str | None, bool]:
    if response.status_code < 400:
        return None, False
    try:
        body = response.json()
    except ValueError:
        return None, False
    if not isinstance(body, dict):
        return None, False
    error_code = body.get("error_code")
    retryable = body.get("retryable")
    return (
        error_code if isinstance(error_code, str) else None,
        retryable if isinstance(retryable, bool) else False,
    )


def _uuid(value: Mapping[str, object], field: str) -> UUID:
    candidate = _string(value, field)
    try:
        return UUID(candidate)
    except ValueError as exc:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}") from exc


def _string(value: Mapping[str, object], field: str) -> str:
    candidate = value.get(field)
    if not isinstance(candidate, str) or not candidate:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}")
    return candidate


def _positive_int(value: Mapping[str, object], field: str) -> int:
    candidate = value.get(field)
    if isinstance(candidate, bool) or not isinstance(candidate, int) or candidate < 1:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}")
    return candidate


def _non_negative_int(value: Mapping[str, object], field: str) -> int:
    candidate = value.get(field)
    if isinstance(candidate, bool) or not isinstance(candidate, int) or candidate < 0:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}")
    return candidate


def _positive_number(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        raise PermanentDeliveryError("Core API returned an invalid heartbeat interval")
    return float(value)


def _date_time(value: Mapping[str, object], field: str) -> datetime:
    candidate = _string(value, field)
    try:
        parsed = datetime.fromisoformat(candidate.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}") from exc
    if parsed.tzinfo is None:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}")
    return parsed


def _absolute_http_url(value: Mapping[str, object], field: str) -> str:
    candidate = _string(value, field)
    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise PermanentDeliveryError(f"Core API returned an invalid {field}")
    return candidate


def _is_sha256(value: str) -> bool:
    return len(value) == 64 and all(character in "0123456789abcdef" for character in value)


def _bounded_chunks(chunks: Iterator[bytes], expected_size: int) -> Iterator[bytes]:
    observed = 0
    for chunk in chunks:
        observed += len(chunk)
        if observed > expected_size:
            raise InputDigestMismatchError("Downloaded input exceeds the frozen size")
        yield chunk
