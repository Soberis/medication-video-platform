from __future__ import annotations

from pathlib import Path

import pytest

from medication_export_worker import config
from medication_export_worker.config import Settings

CALCULATED_DIGEST = "sha256:" + "a" * 64


def environment(**overrides: str) -> dict[str, str]:
    return {
        "EXPORT_WORKER_BEARER_TOKEN": "x" * 32,
        "DATA_PROFILE": "synthetic",
        "PLATFORM_API_BASE_URL": "http://localhost:8080",
        "RABBITMQ_URL": "amqp://user:password@localhost:5672/medication",
        **overrides,
    }


def test_uses_the_calculated_source_digest_when_no_assertion_is_configured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    settings = Settings.from_environment(environment())

    assert settings.software_digest == CALCULATED_DIGEST
    assert settings.synthetic_fail_first_attempt is False
    assert settings.synthetic_lease_loss_first_attempt is False


def test_accepts_a_matching_configured_source_digest(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    settings = Settings.from_environment(
        environment(EXPORT_WORKER_SOFTWARE_DIGEST=CALCULATED_DIGEST),
    )

    assert settings.software_digest == CALCULATED_DIGEST


def test_reads_the_current_bearer_token_file_for_each_request(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)
    token_file = tmp_path / "export-worker.jwt"
    token_file.write_text("a" * 32 + "\n", encoding="utf-8")
    values = environment(EXPORT_WORKER_BEARER_TOKEN_FILE=str(token_file))
    del values["EXPORT_WORKER_BEARER_TOKEN"]

    settings = Settings.from_environment(values)
    assert settings.read_worker_bearer_token() == "a" * 32

    token_file.write_text("b" * 32 + "\n", encoding="utf-8")
    assert settings.read_worker_bearer_token() == "b" * 32


def test_requires_exactly_one_bearer_token_source(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)
    token_file = tmp_path / "export-worker.jwt"
    token_file.write_text("a" * 32, encoding="utf-8")

    with pytest.raises(ValueError, match="exactly one"):
        Settings.from_environment(environment(EXPORT_WORKER_BEARER_TOKEN_FILE=str(token_file)))

    values = environment()
    del values["EXPORT_WORKER_BEARER_TOKEN"]
    with pytest.raises(ValueError, match="exactly one"):
        Settings.from_environment(values)


def test_rejects_an_environment_bearer_token_outside_synthetic_data(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="synthetic-only"):
        Settings.from_environment(environment(DATA_PROFILE="controlled"))


def test_rejects_a_configured_digest_that_does_not_match_the_source(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="must match the calculated source digest"):
        Settings.from_environment(
            environment(EXPORT_WORKER_SOFTWARE_DIGEST="sha256:" + "b" * 64),
        )


def test_rejects_an_invalid_configured_digest(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="must use sha256"):
        Settings.from_environment(environment(EXPORT_WORKER_SOFTWARE_DIGEST="not-a-digest"))


def test_enables_synthetic_failure_injection_only_with_all_explicit_guards(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    settings = Settings.from_environment(
        environment(
            DATA_PROFILE="synthetic",
            RUN_DATASET_EXPORT_ACCEPTANCE="true",
            RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE="true",
            EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT="true",
        ),
    )

    assert settings.synthetic_fail_first_attempt is True


def test_rejects_synthetic_failure_injection_outside_explicit_recovery_acceptance(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="recovery acceptance authorization"):
        Settings.from_environment(
            environment(
                DATA_PROFILE="synthetic",
                RUN_DATASET_EXPORT_ACCEPTANCE="true",
                EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT="true",
            ),
        )


@pytest.mark.parametrize(
    "endpoint_override",
    [
        {"PLATFORM_API_BASE_URL": "https://api.example.test"},
        {"RABBITMQ_URL": ("amqps://user:password@rabbitmq.example.test/medication")},
    ],
)
def test_rejects_synthetic_failure_injection_for_non_loopback_dependencies(
    monkeypatch: pytest.MonkeyPatch,
    endpoint_override: dict[str, str],
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="requires loopback Core API and RabbitMQ"):
        Settings.from_environment(
            environment(
                DATA_PROFILE="synthetic",
                RUN_DATASET_EXPORT_ACCEPTANCE="true",
                RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE="true",
                EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT="true",
                **endpoint_override,
            ),
        )


def test_enables_synthetic_lease_loss_only_with_an_independent_authorization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    settings = Settings.from_environment(
        environment(
            RUN_DATASET_EXPORT_ACCEPTANCE="true",
            RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE="true",
            EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT="true",
        ),
    )

    assert settings.synthetic_lease_loss_first_attempt is True


def test_rejects_synthetic_lease_loss_without_its_independent_authorization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="lease-loss acceptance authorization"):
        Settings.from_environment(
            environment(
                RUN_DATASET_EXPORT_ACCEPTANCE="true",
                EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT="true",
            ),
        )


@pytest.mark.parametrize(
    "endpoint_override",
    [
        {"PLATFORM_API_BASE_URL": "https://api.example.test"},
        {"RABBITMQ_URL": "amqps://user:password@rabbitmq.example.test/medication"},
    ],
)
def test_rejects_synthetic_lease_loss_for_non_loopback_dependencies(
    monkeypatch: pytest.MonkeyPatch,
    endpoint_override: dict[str, str],
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="requires loopback Core API and RabbitMQ"):
        Settings.from_environment(
            environment(
                RUN_DATASET_EXPORT_ACCEPTANCE="true",
                RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE="true",
                EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT="true",
                **endpoint_override,
            ),
        )


def test_rejects_mutually_exclusive_synthetic_injection_modes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    with pytest.raises(ValueError, match="mutually exclusive"):
        Settings.from_environment(
            environment(
                RUN_DATASET_EXPORT_ACCEPTANCE="true",
                RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE="true",
                RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE="true",
                EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT="true",
                EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT="true",
            ),
        )
