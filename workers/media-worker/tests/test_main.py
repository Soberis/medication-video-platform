from __future__ import annotations

import logging

from medication_media_worker.__main__ import _configure_logging


def test_http_client_request_targets_are_not_logged_at_info() -> None:
    _configure_logging()

    assert logging.getLogger("httpx").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("httpcore").getEffectiveLevel() >= logging.WARNING
