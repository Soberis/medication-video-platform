from __future__ import annotations

import pytest

from medication_export_worker import config
from medication_export_worker.config import Settings

CALCULATED_DIGEST = "sha256:" + "a" * 64


def environment(**overrides: str) -> dict[str, str]:
    return {
        "EXPORT_WORKER_BEARER_TOKEN": "x" * 32,
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


def test_accepts_a_matching_configured_source_digest(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "calculate_software_digest", lambda _: CALCULATED_DIGEST)

    settings = Settings.from_environment(
        environment(EXPORT_WORKER_SOFTWARE_DIGEST=CALCULATED_DIGEST),
    )

    assert settings.software_digest == CALCULATED_DIGEST


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
