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
    ffmpeg_path: str
    ffprobe_path: str
    probe_timeout_seconds: int
    temp_dir: Path | None
    software_digest: str

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> Settings:
        values = os.environ if environ is None else environ
        root = Path(__file__).resolve().parents[2]
        token = values.get("MEDIA_WORKER_BEARER_TOKEN", "")
        if len(token) < 32:
            raise ValueError("MEDIA_WORKER_BEARER_TOKEN must contain at least 32 characters")

        base_url = values.get("PLATFORM_API_BASE_URL", "http://localhost:8080").rstrip("/")
        parsed_base_url = urlparse(base_url)
        if parsed_base_url.scheme not in {"http", "https"} or not parsed_base_url.netloc:
            raise ValueError("PLATFORM_API_BASE_URL must be an absolute HTTP(S) URL")

        rabbitmq_url = values.get(
            "RABBITMQ_URL",
            "amqp://medication_local:change-me-local-only@localhost:5672/medication",
        )
        parsed_rabbitmq_url = urlparse(rabbitmq_url)
        if parsed_rabbitmq_url.scheme not in {"amqp", "amqps"} or not parsed_rabbitmq_url.hostname:
            raise ValueError("RABBITMQ_URL must be an absolute AMQP(S) URL")

        timeout = _positive_int(values.get("MEDIA_PROBE_TIMEOUT_SECONDS", "1800"), "timeout")
        temp_dir_value = values.get("MEDIA_WORKER_TEMP_DIR")
        temp_dir = Path(temp_dir_value).resolve() if temp_dir_value else None
        default_worker_id = f"media-worker-{socket.gethostname()}-{os.getpid()}"
        worker_id = values.get("MEDIA_WORKER_ID", default_worker_id)
        if not worker_id or len(worker_id) > 128:
            raise ValueError("MEDIA_WORKER_ID must contain 1 to 128 characters")

        configured_digest = values.get("MEDIA_WORKER_SOFTWARE_DIGEST")
        software_digest = configured_digest or calculate_software_digest(root)
        if len(software_digest) != 71 or not software_digest.startswith("sha256:"):
            raise ValueError("MEDIA_WORKER_SOFTWARE_DIGEST must use sha256:<64 hex>")
        try:
            int(software_digest.removeprefix("sha256:"), 16)
        except ValueError as exc:
            raise ValueError("MEDIA_WORKER_SOFTWARE_DIGEST must use sha256:<64 hex>") from exc

        return cls(
            platform_api_base_url=base_url,
            worker_bearer_token=token,
            rabbitmq_url=rabbitmq_url,
            worker_id=worker_id,
            queue_name="platform.media.probe.v1",
            dead_letter_exchange="platform.dead-letter.v1",
            dead_letter_routing_key="media.dead-letter.v1",
            ffmpeg_path=values.get("FFMPEG_PATH", "ffmpeg"),
            ffprobe_path=values.get("FFPROBE_PATH", "ffprobe"),
            probe_timeout_seconds=timeout,
            temp_dir=temp_dir,
            software_digest=software_digest,
        )


def calculate_software_digest(project_root: Path) -> str:
    digest = hashlib.sha256()
    paths = [project_root / name for name in SOFTWARE_DIGEST_FILES]
    paths.extend(sorted((project_root / "src" / "medication_media_worker").glob("*.py")))
    profile = project_root.parents[1] / "contracts" / "profiles" / "media-probe-baseline-v1.json"
    paths.append(profile)
    for path in paths:
        if not path.is_file():
            raise ValueError(f"Software digest input is missing: {path.name}")
        relative_name = path.relative_to(project_root.parents[1]).as_posix().encode()
        digest.update(len(relative_name).to_bytes(4, "big"))
        digest.update(relative_name)
        content = path.read_bytes()
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return f"sha256:{digest.hexdigest()}"


def _positive_int(value: str, label: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"{label} must be a positive integer") from exc
    if parsed < 1:
        raise ValueError(f"{label} must be a positive integer")
    return parsed
