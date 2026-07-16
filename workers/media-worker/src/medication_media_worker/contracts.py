from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Final
from uuid import UUID

JOB_FIELDS: Final = frozenset(
    {
        "schema_version",
        "message_id",
        "job_id",
        "job_type",
        "attempt",
        "lease_token",
        "project_id",
        "clip_asset_id",
        "input_sha256",
        "input_size_bytes",
        "content_type",
        "probe_profile_key",
        "recipe_digest",
        "requested_at",
    }
)
SHA256_PATTERN: Final = re.compile(r"^[a-f0-9]{64}$")
LEASE_PATTERN: Final = re.compile(r"^[A-Za-z0-9_-]{43,128}$")
PROFILE_PATTERN: Final = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
CONTENT_TYPE_PATTERN: Final = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$"
)


class ContractError(ValueError):
    """Raised when an untrusted broker message violates the frozen contract."""


@dataclass(frozen=True, slots=True)
class MediaProbeJob:
    message_id: UUID
    job_id: UUID
    attempt: int
    lease_token: str
    project_id: UUID
    clip_asset_id: UUID
    input_sha256: str
    input_size_bytes: int
    content_type: str
    probe_profile_key: str
    recipe_digest: str
    requested_at: datetime

    @classmethod
    def from_bytes(cls, body: bytes) -> MediaProbeJob:
        try:
            value = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ContractError("Broker body is not valid UTF-8 JSON") from exc
        if not isinstance(value, dict):
            raise ContractError("Broker body must be a JSON object")
        if set(value) != JOB_FIELDS:
            raise ContractError("Broker body fields do not match media-probe-job.v1")
        if value["schema_version"] != "media-probe-job.v1":
            raise ContractError("Unsupported media probe job schema")
        if value["job_type"] != "MEDIA_PROBE":
            raise ContractError("Unsupported media job type")

        message_id = _uuid(value, "message_id")
        job_id = _uuid(value, "job_id")
        project_id = _uuid(value, "project_id")
        clip_asset_id = _uuid(value, "clip_asset_id")
        attempt = _positive_int(value, "attempt")
        input_size_bytes = _positive_int(value, "input_size_bytes")
        lease_token = _matching_string(value, "lease_token", LEASE_PATTERN)
        input_sha256 = _matching_string(value, "input_sha256", SHA256_PATTERN)
        recipe_digest = _matching_string(value, "recipe_digest", SHA256_PATTERN)
        content_type = _matching_string(value, "content_type", CONTENT_TYPE_PATTERN)
        probe_profile_key = _matching_string(value, "probe_profile_key", PROFILE_PATTERN)
        requested_at = _date_time(value, "requested_at")
        return cls(
            message_id=message_id,
            job_id=job_id,
            attempt=attempt,
            lease_token=lease_token,
            project_id=project_id,
            clip_asset_id=clip_asset_id,
            input_sha256=input_sha256,
            input_size_bytes=input_size_bytes,
            content_type=content_type,
            probe_profile_key=probe_profile_key,
            recipe_digest=recipe_digest,
            requested_at=requested_at,
        )


def _uuid(value: dict[str, object], field: str) -> UUID:
    candidate = value[field]
    if not isinstance(candidate, str):
        raise ContractError(f"{field} must be a UUID string")
    try:
        return UUID(candidate)
    except ValueError as exc:
        raise ContractError(f"{field} must be a UUID string") from exc


def _positive_int(value: dict[str, object], field: str) -> int:
    candidate = value[field]
    if isinstance(candidate, bool) or not isinstance(candidate, int) or candidate < 1:
        raise ContractError(f"{field} must be a positive integer")
    return candidate


def _matching_string(value: dict[str, object], field: str, pattern: re.Pattern[str]) -> str:
    candidate = value[field]
    if not isinstance(candidate, str) or pattern.fullmatch(candidate) is None:
        raise ContractError(f"{field} has an invalid format")
    return candidate


def _date_time(value: dict[str, object], field: str) -> datetime:
    candidate = value[field]
    if not isinstance(candidate, str):
        raise ContractError(f"{field} must be an RFC3339 timestamp")
    try:
        parsed = datetime.fromisoformat(candidate.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ContractError(f"{field} must be an RFC3339 timestamp") from exc
    if parsed.tzinfo is None:
        raise ContractError(f"{field} must include an offset")
    return parsed
