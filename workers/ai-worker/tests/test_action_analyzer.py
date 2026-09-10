from __future__ import annotations

from pathlib import Path

import pytest

from medication_ai_worker import action_analyzer
from medication_ai_worker.action_analyzer import (
    DEFAULT_LABELS,
    PLACEHOLDER_CONFIDENCE,
    PLACEHOLDER_PROMPT_VERSION,
    analyze_video_actions,
)


def _pin_duration(monkeypatch: pytest.MonkeyPatch, duration_ms: int) -> None:
    monkeypatch.setattr(action_analyzer, "probe_duration_ms", lambda _path: duration_ms)


def test_scaffold_labels_stay_inside_the_active_label_schema(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _pin_duration(monkeypatch, 60_000)

    _, segments = analyze_video_actions(Path("synthetic.mp4"), DEFAULT_LABELS)

    assert segments
    assert {segment.label for segment in segments} <= set(DEFAULT_LABELS)
    assert all(segment.confidence == PLACEHOLDER_CONFIDENCE for segment in segments)


def test_scaffold_segments_are_ordered_and_never_overlap(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _pin_duration(monkeypatch, 45_000)

    duration_ms, segments = analyze_video_actions(Path("synthetic.mp4"), DEFAULT_LABELS)

    assert duration_ms == 45_000
    previous_end = 0
    for segment in segments:
        assert 0 <= segment.start_ms < segment.end_ms
        assert segment.start_ms >= previous_end
        assert segment.end_ms <= duration_ms
        previous_end = segment.end_ms


def test_scaffold_ignores_labels_outside_the_frozen_protocol_order(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _pin_duration(monkeypatch, 60_000)

    _, segments = analyze_video_actions(Path("synthetic.mp4"), ("invented_label", "cleanup"))

    assert {segment.label for segment in segments} == {"cleanup"}


def test_scaffold_falls_back_to_the_offered_labels_for_an_unknown_protocol(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _pin_duration(monkeypatch, 60_000)

    _, segments = analyze_video_actions(Path("synthetic.mp4"), ("alpha", "beta"))

    assert [segment.label for segment in segments] == ["alpha", "beta"]


def test_placeholder_result_is_flagged_as_non_model_output() -> None:
    assert PLACEHOLDER_PROMPT_VERSION == "placeholder-protocol-scaffold-v1"
    assert not PLACEHOLDER_PROMPT_VERSION.startswith("v")


def test_probe_duration_ms_raises_when_ffprobe_fails() -> None:
    with pytest.raises(RuntimeError, match="Failed to probe video duration"):
        action_analyzer.probe_duration_ms(Path("non-existent-video.mp4"))

