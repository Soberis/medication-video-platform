from __future__ import annotations

from fractions import Fraction

import pytest

from hand_pose_poc.timestamps import SampleGate, TimestampError, TimestampNormalizer


def test_container_pts_becomes_strict_zero_based_video_timestamp() -> None:
    normalizer = TimestampNormalizer(Fraction(30, 1))

    first = normalizer.next(0, 90, Fraction(1, 1000))
    second = normalizer.next(1, 123, Fraction(1, 1000))

    assert first.source_timestamp_ms == 90
    assert first.timestamp_ms == 0
    assert first.source == "container_pts"
    assert second.timestamp_ms == 33


def test_duplicate_millisecond_timestamp_fails_instead_of_being_silently_changed() -> None:
    normalizer = TimestampNormalizer(Fraction(30, 1))
    normalizer.next(0, 0, Fraction(1, 1000))

    with pytest.raises(TimestampError, match="strictly increasing"):
        normalizer.next(1, 0, Fraction(1, 1000))


def test_missing_pts_requires_explicit_fallback() -> None:
    with pytest.raises(TimestampError, match="no container PTS"):
        TimestampNormalizer(Fraction(25, 1)).next(0, None, None)

    normalizer = TimestampNormalizer(Fraction(25, 1), allow_fallback=True)
    assert normalizer.next(0, None, None).timestamp_ms == 0
    assert normalizer.next(1, None, None).timestamp_ms == 40
    assert normalizer.fallback_frames == 2


def test_pts_and_fallback_cannot_mix_in_one_video_timeline() -> None:
    normalizer = TimestampNormalizer(Fraction(25, 1), allow_fallback=True)
    normalizer.next(0, 0, Fraction(1, 1000))

    with pytest.raises(TimestampError, match="cannot mix"):
        normalizer.next(1, None, None)


def test_sample_gate_uses_timestamps_not_frame_modulo() -> None:
    gate = SampleGate(10)
    assert gate.should_infer(0)
    assert not gate.should_infer(33)
    assert not gate.should_infer(99)
    assert gate.should_infer(100)
    assert gate.should_infer(205)
