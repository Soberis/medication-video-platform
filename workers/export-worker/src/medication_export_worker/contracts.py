from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Final
from uuid import UUID

CURRENT_PROFILE_KEY: Final = "p0-dataset-export-v2"
CURRENT_PROFILE_DIGEST: Final = "5f86b48ccd02ef8ea0a05c967b85017bb9cd5de59b8f466b7bb93dd552fdcb96"
LEGACY_PROFILE_KEY: Final = "p0-dataset-export-v1"
LEGACY_PROFILE_DIGEST: Final = "3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888"
JOB_FIELDS: Final = frozenset(
    {
        "schema_version",
        "message_id",
        "export_id",
        "job_type",
        "attempt",
        "lease_token",
        "project_id",
        "dataset_version_id",
        "snapshot_digest",
        "export_profile_key",
        "export_profile_digest",
        "requested_at",
    }
)
SHA256_PATTERN: Final = re.compile(r"^[a-f0-9]{64}$")
LEASE_PATTERN: Final = re.compile(r"^[A-Za-z0-9_-]{43,128}$")


class ContractError(ValueError):
    """Raised when an untrusted broker message violates the frozen contract."""


@dataclass(frozen=True, slots=True)
class DatasetExportJob:
    message_id: UUID
    export_id: UUID
    attempt: int
    lease_token: str
    project_id: UUID
    dataset_version_id: UUID
    snapshot_digest: str
    export_profile_key: str
    export_profile_digest: str
    requested_at: datetime

    @classmethod
    def from_bytes(cls, body: bytes) -> DatasetExportJob:
        try:
            value = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ContractError("Broker body is not valid UTF-8 JSON") from exc
        if not isinstance(value, dict):
            raise ContractError("Broker body must be a JSON object")
        if set(value) != JOB_FIELDS:
            raise ContractError("Broker body fields do not match dataset-export-job.v1")
        if value["schema_version"] != "dataset-export-job.v1":
            raise ContractError("Unsupported dataset export schema")
        if value["job_type"] != "DATASET_EXPORT":
            raise ContractError("Unsupported dataset export job type")

        profile_key = _string(value, "export_profile_key")
        profile_digest = _matching_string(value, "export_profile_digest", SHA256_PATTERN)
        approved_pair = (profile_key, profile_digest) in {
            (CURRENT_PROFILE_KEY, CURRENT_PROFILE_DIGEST),
            (LEGACY_PROFILE_KEY, LEGACY_PROFILE_DIGEST),
        }
        if not approved_pair:
            raise ContractError("Dataset export profile key and digest do not match")
        return cls(
            message_id=_uuid(value, "message_id"),
            export_id=_uuid(value, "export_id"),
            attempt=_positive_int(value, "attempt"),
            lease_token=_matching_string(value, "lease_token", LEASE_PATTERN),
            project_id=_uuid(value, "project_id"),
            dataset_version_id=_uuid(value, "dataset_version_id"),
            snapshot_digest=_matching_string(value, "snapshot_digest", SHA256_PATTERN),
            export_profile_key=profile_key,
            export_profile_digest=profile_digest,
            requested_at=_date_time(value, "requested_at"),
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


def _string(value: dict[str, object], field: str) -> str:
    candidate = value[field]
    if not isinstance(candidate, str):
        raise ContractError(f"{field} must be a string")
    return candidate


def _matching_string(value: dict[str, object], field: str, pattern: re.Pattern[str]) -> str:
    candidate = _string(value, field)
    if pattern.fullmatch(candidate) is None:
        raise ContractError(f"{field} has an invalid format")
    return candidate


def _date_time(value: dict[str, object], field: str) -> datetime:
    candidate = _string(value, field)
    try:
        parsed = datetime.fromisoformat(candidate.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ContractError(f"{field} must be an RFC3339 timestamp") from exc
    if parsed.tzinfo is None:
        raise ContractError(f"{field} must include an offset")
    return parsed
