from __future__ import annotations

import hashlib
import os
import re
import socket
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

WORKER_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")


@dataclass(frozen=True, slots=True)
class Settings:
    platform_api_base_url: str
    rabbitmq_url: str
    bearer_token: str | None = field(repr=False)
    bearer_token_file: Path | None
    model_path: Path
    model_sha256: str
    worker_id: str
    temp_dir: Path | None

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> Settings:
        values = os.environ if environ is None else environ
        base_url = values.get("PLATFORM_API_BASE_URL", "http://localhost:8080").rstrip("/")
        if not _is_url(base_url, {"http", "https"}):
            raise ValueError("PLATFORM_API_BASE_URL must be an absolute HTTP(S) URL")
        rabbitmq_url = values.get(
            "RABBITMQ_URL", "amqp://medication_local:change-me-local-only@localhost:5672/medication"
        )
        if not _is_url(rabbitmq_url, {"amqp", "amqps"}):
            raise ValueError("RABBITMQ_URL must be an absolute AMQP(S) URL")
        if values.get("DATA_PROFILE") != "synthetic":
            if urlparse(base_url).scheme != "https":
                raise ValueError(
                    "PLATFORM_API_BASE_URL must use HTTPS outside synthetic data profiles"
                )
            if urlparse(rabbitmq_url).scheme != "amqps":
                raise ValueError("RABBITMQ_URL must use AMQPS outside synthetic data profiles")
        token, token_file = _identity(values)
        model_path = Path(values.get("AI_HAND_POSE_MODEL_PATH", "")).resolve()
        if not model_path.is_file():
            raise ValueError("AI_HAND_POSE_MODEL_PATH must name an existing offline .task model")
        model_sha256 = values.get("AI_HAND_POSE_MODEL_SHA256", "")
        if not _sha256(model_sha256) or _file_sha256(model_path) != model_sha256:
            raise ValueError("AI_HAND_POSE_MODEL_SHA256 must match the offline model bytes")
        worker_id = values.get("AI_WORKER_ID", f"ai-worker-{socket.gethostname()}-{os.getpid()}")
        if WORKER_ID.fullmatch(worker_id) is None:
            raise ValueError("AI_WORKER_ID must contain 1 to 128 safe identifier characters")
        temp = values.get("AI_WORKER_TEMP_DIR")
        return cls(
            base_url,
            rabbitmq_url,
            token,
            token_file,
            model_path,
            model_sha256,
            worker_id,
            Path(temp).resolve() if temp else None,
        )

    def read_bearer_token(self) -> str:
        candidate = (
            self.bearer_token_file.read_text(encoding="utf-8").strip()
            if self.bearer_token_file
            else self.bearer_token or ""
        )
        if len(candidate) < 32 or any(character.isspace() for character in candidate):
            raise ValueError("AI Worker bearer token must contain at least 32 non-space characters")
        return candidate


def _identity(values: Mapping[str, str]) -> tuple[str | None, Path | None]:
    token = values.get("AI_WORKER_BEARER_TOKEN") or None
    filename = values.get("AI_WORKER_BEARER_TOKEN_FILE") or None
    if (token is None) == (filename is None):
        raise ValueError(
            "Configure exactly one of AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE"
        )
    if token is not None and values.get("DATA_PROFILE") != "synthetic":
        raise ValueError(
            "AI_WORKER_BEARER_TOKEN is synthetic-only; use AI_WORKER_BEARER_TOKEN_FILE"
        )
    file = Path(filename) if filename else None
    if file is not None and not file.is_absolute():
        raise ValueError("AI_WORKER_BEARER_TOKEN_FILE must be absolute")
    return token, file


def _is_url(value: str, schemes: set[str]) -> bool:
    parsed = urlparse(value)
    return parsed.scheme in schemes and bool(parsed.netloc)


def _sha256(value: str) -> bool:
    return len(value) == 64 and all(character in "0123456789abcdef" for character in value)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
