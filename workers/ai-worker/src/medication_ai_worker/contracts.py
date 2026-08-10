from __future__ import annotations

import json
import re
from dataclasses import dataclass
from uuid import UUID

SHA256 = re.compile(r"^[a-f0-9]{64}$")
LEASE = re.compile(r"^[A-Za-z0-9_-]{43,128}$")
CONTENT_TYPE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$")
FIELDS = frozenset(
    {
        "schema_version",
        "message_id",
        "job_id",
        "attempt",
        "lease_token",
        "project_id",
        "clip_asset_id",
        "input_sha256",
        "input_size_bytes",
        "content_type",
        "algorithm_key",
        "algorithm_version",
        "model_sha256",
        "sampling_fps",
        "requested_at",
    }
)


class ContractError(ValueError):
    """Raised when a broker envelope is not the frozen AI job contract."""


@dataclass(frozen=True, slots=True)
class AiJob:
    message_id: UUID
    job_id: UUID
    attempt: int
    lease_token: str
    input_sha256: str
    input_size_bytes: int
    content_type: str
    model_sha256: str
    sampling_fps: int

    @classmethod
    def from_bytes(cls, body: bytes) -> AiJob:
        try:
            value = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ContractError("Broker body is not valid UTF-8 JSON") from exc
        if not isinstance(value, dict) or set(value) != FIELDS:
            raise ContractError("Broker body fields do not match ai-hand-pose-job.v1")
        if (
            value.get("schema_version") != "ai-hand-pose-job.v1"
            or value.get("algorithm_key") != "MEDIAPIPE_HAND_LANDMARKER"
            or value.get("algorithm_version") != "v1"
        ):
            raise ContractError("Unsupported AI hand pose job profile")
        return cls(
            message_id=_uuid(value, "message_id"),
            job_id=_uuid(value, "job_id"),
            attempt=_positive(value, "attempt"),
            lease_token=_string(value, "lease_token", LEASE),
            input_sha256=_string(value, "input_sha256", SHA256),
            input_size_bytes=_positive(value, "input_size_bytes"),
            content_type=_string(value, "content_type", CONTENT_TYPE),
            model_sha256=_string(value, "model_sha256", SHA256),
            sampling_fps=_sampling(value),
        )


def _uuid(value: dict[str, object], field: str) -> UUID:
    try:
        return UUID(str(value[field]))
    except (KeyError, ValueError, AttributeError) as exc:
        raise ContractError(f"{field} must be a UUID") from exc


def _positive(value: dict[str, object], field: str) -> int:
    candidate = value.get(field)
    if isinstance(candidate, bool) or not isinstance(candidate, int) or candidate < 1:
        raise ContractError(f"{field} must be a positive integer")
    return candidate


def _sampling(value: dict[str, object]) -> int:
    candidate = _positive(value, "sampling_fps")
    if candidate > 15:
        raise ContractError("sampling_fps must not exceed 15")
    return candidate


def _string(value: dict[str, object], field: str, pattern: re.Pattern[str]) -> str:
    candidate = value.get(field)
    if not isinstance(candidate, str) or pattern.fullmatch(candidate) is None:
        raise ContractError(f"{field} has an invalid format")
    return candidate
