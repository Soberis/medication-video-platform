from __future__ import annotations

from fractions import Fraction
from types import SimpleNamespace

import pytest

from medication_ai_worker.landmarker import PROFILE_SHA256, _timestamp_ms


def test_profile_digest_freezes_worker_inference_configuration() -> None:
    assert PROFILE_SHA256 == "3a3be8035ec8a9756a32f43b007a4bb2cc2e2c0468d744d248f19c692fd90626"


def test_timestamp_uses_container_pts_without_synthesizing_time() -> None:
    frame = SimpleNamespace(pts=123)
    stream = SimpleNamespace(time_base=Fraction(1, 1_000))

    assert _timestamp_ms(frame, stream, previous=100) == 123


@pytest.mark.parametrize(
    ("frame_pts", "time_base"),
    [(None, Fraction(1, 1_000)), (1, None)],
)
def test_timestamp_rejects_missing_container_pts(
    frame_pts: int | None, time_base: Fraction | None
) -> None:
    frame = SimpleNamespace(pts=frame_pts)
    stream = SimpleNamespace(time_base=time_base)

    with pytest.raises(ValueError, match="presentation timestamps"):
        _timestamp_ms(frame, stream, previous=-1)


def test_timestamp_rejects_non_increasing_container_pts() -> None:
    frame = SimpleNamespace(pts=100)
    stream = SimpleNamespace(time_base=Fraction(1, 1_000))

    with pytest.raises(ValueError, match="strictly increasing"):
        _timestamp_ms(frame, stream, previous=100)
