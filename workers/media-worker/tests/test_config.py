from __future__ import annotations

from pathlib import Path

import pytest

from medication_media_worker import config
from medication_media_worker.config import Settings

CALCULATED_DIGEST = "sha256:" + "a" * 64


def environment(**overrides: str) -> dict[str, str]:
    return {
        "MEDIA_WORKER_BEARER_TOKEN": "x" * 32,
        "DATA_PROFILE": "synthetic",
        "PLATFORM_API_BASE_URL": "http://localhost:8080",
        "RABBITMQ_URL": "amqp://user:password@localhost:5672/medication",
        **overrides,
    }


def test_reads_the_current_bearer_token_file_for_each_request(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)
    token_file = tmp_path / "media-worker.jwt"
    token_file.write_text("a" * 32 + "\n", encoding="utf-8")
    values = environment(MEDIA_WORKER_BEARER_TOKEN_FILE=str(token_file))
    del values["MEDIA_WORKER_BEARER_TOKEN"]

    settings = Settings.from_environment(values)
    assert settings.read_worker_bearer_token() == "a" * 32

    token_file.write_text("b" * 32 + "\n", encoding="utf-8")
    assert settings.read_worker_bearer_token() == "b" * 32


def test_requires_exactly_one_bearer_token_source(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)
    token_file = tmp_path / "media-worker.jwt"
    token_file.write_text("a" * 32, encoding="utf-8")

    with pytest.raises(ValueError, match="exactly one"):
        Settings.from_environment(environment(MEDIA_WORKER_BEARER_TOKEN_FILE=str(token_file)))

    values = environment()
    del values["MEDIA_WORKER_BEARER_TOKEN"]
    with pytest.raises(ValueError, match="exactly one"):
        Settings.from_environment(values)


def test_rejects_an_environment_bearer_token_outside_synthetic_data(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="synthetic-only"):
        Settings.from_environment(environment(DATA_PROFILE="controlled"))


def test_default_local_approved_digest_matches_the_worker_source() -> None:
    repository_root = Path(__file__).resolve().parents[3]
    example_environment = repository_root / ".env.example"
    key = "MEDIA_WORKER_APPROVED_SOFTWARE_DIGEST"
    value = next(
        (
            line.removeprefix(f"{key}=")
            for line in example_environment.read_text(encoding="utf-8").splitlines()
            if line.startswith(f"{key}=")
        ),
        None,
    )

    assert value is not None, f"{key} must be present in .env.example"
    assert value == config.calculate_software_digest(repository_root / "workers" / "media-worker")
