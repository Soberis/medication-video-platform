from __future__ import annotations

import hashlib
import os
import socket
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

SOFTWARE_DIGEST_FILES = ("pyproject.toml", "uv.lock")


@dataclass(frozen=True, slots=True)
class Settings:
    platform_api_base_url: str
    worker_bearer_token: str
    rabbitmq_url: str
    worker_id: str
    queue_name: str
    dead_letter_exchange: str
    dead_letter_routing_key: str
    temp_dir: Path | None
    synthetic_fail_first_attempt: bool
    software_digest: str

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> Settings:
        values = os.environ if environ is None else environ
        root = Path(__file__).resolve().parents[2]
        token = values.get("EXPORT_WORKER_BEARER_TOKEN", "")
        if len(token) < 32:
            raise ValueError("EXPORT_WORKER_BEARER_TOKEN must contain at least 32 characters")

        base_url = values.get("PLATFORM_API_BASE_URL", "http://localhost:8080").rstrip("/")
        _require_url(base_url, {"http", "https"}, "PLATFORM_API_BASE_URL")
        rabbitmq_url = values.get(
            "RABBITMQ_URL",
            "amqp://medication_local:change-me-local-only@localhost:5672/medication",
        )
        _require_url(rabbitmq_url, {"amqp", "amqps"}, "RABBITMQ_URL")

        default_worker_id = f"export-worker-{socket.gethostname()}-{os.getpid()}"
        worker_id = values.get("EXPORT_WORKER_ID", default_worker_id)
        if not worker_id or len(worker_id) > 128:
            raise ValueError("EXPORT_WORKER_ID must contain 1 to 128 characters")

        temp_dir_value = values.get("EXPORT_WORKER_TEMP_DIR")
        temp_dir = Path(temp_dir_value).resolve() if temp_dir_value else None
        calculated_digest = calculate_software_digest(root)
        configured_digest = values.get("EXPORT_WORKER_SOFTWARE_DIGEST")
        if configured_digest is not None and not _is_software_digest(configured_digest):
            raise ValueError("EXPORT_WORKER_SOFTWARE_DIGEST must use sha256:<64 hex>")
        if configured_digest is not None and configured_digest != calculated_digest:
            raise ValueError(
                "EXPORT_WORKER_SOFTWARE_DIGEST must match the calculated source digest"
            )
        synthetic_fail_first_attempt = _synthetic_fail_first_attempt(values)

        return cls(
            platform_api_base_url=base_url,
            worker_bearer_token=token,
            rabbitmq_url=rabbitmq_url,
            worker_id=worker_id,
            queue_name="platform.dataset-export.v1",
            dead_letter_exchange="platform.dead-letter.v1",
            dead_letter_routing_key="dataset-export.dead-letter.v1",
            temp_dir=temp_dir,
            synthetic_fail_first_attempt=synthetic_fail_first_attempt,
            software_digest=calculated_digest,
        )


def calculate_software_digest(project_root: Path) -> str:
    digest = hashlib.sha256()
    paths = [project_root / name for name in SOFTWARE_DIGEST_FILES]
    paths.extend(sorted((project_root / "src" / "medication_export_worker").glob("*.py")))
    repository_root = project_root.parents[1]
    paths.extend(
        [
            repository_root / "contracts" / "profiles" / "p0-dataset-export-v2.json",
            repository_root / "contracts" / "events" / "dataset-export-job-v1.schema.json",
            repository_root / "contracts" / "events" / "dataset-export-result-v1.schema.json",
        ]
    )
    for path in paths:
        if not path.is_file():
            raise ValueError(f"Software digest input is missing: {path.name}")
        relative_name = path.relative_to(repository_root).as_posix().encode()
        content = path.read_bytes()
        digest.update(len(relative_name).to_bytes(4, "big"))
        digest.update(relative_name)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return f"sha256:{digest.hexdigest()}"


def _require_url(value: str, schemes: set[str], label: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme not in schemes or not parsed.hostname:
        raise ValueError(f"{label} must be an absolute URL")


def _is_software_digest(value: str) -> bool:
    if len(value) != 71 or not value.startswith("sha256:"):
        return False
    try:
        int(value.removeprefix("sha256:"), 16)
    except ValueError:
        return False
    return True


def _synthetic_fail_first_attempt(values: Mapping[str, str]) -> bool:
    enabled = values.get("EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT", "false")
    if enabled not in {"false", "true"}:
        raise ValueError("EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT must be true or false")
    if enabled == "false":
        return False
    if values.get("DATA_PROFILE") != "synthetic":
        raise ValueError("Synthetic export failure injection requires DATA_PROFILE=synthetic")
    if values.get("RUN_DATASET_EXPORT_ACCEPTANCE") != "true":
        raise ValueError(
            "Synthetic export failure injection requires explicit acceptance authorization"
        )
    if values.get("RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE") != "true":
        raise ValueError(
            "Synthetic export failure injection requires recovery acceptance authorization"
        )
    return True
