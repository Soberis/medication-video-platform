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

    assert DEFAULT_LABELS == (
        "acquire_item",
        "open_container",
        "disinfect_access",
        "insert_needle",
        "draw_liquid",
        "transfer_liquid",
        "agitate_solution",
        "discard_sharps",
        "label_prepared_medication",
    )
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

    _, segments = analyze_video_actions(Path("synthetic.mp4"), ("invented_label", "open_container"))

    assert {segment.label for segment in segments} == {"open_container"}


def test_scaffold_falls_back_to_the_offered_labels_for_an_unknown_protocol(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _pin_duration(monkeypatch, 60_000)

    _, segments = analyze_video_actions(Path("synthetic.mp4"), ("alpha", "beta"))

    assert [segment.label for segment in segments] == ["alpha", "beta"]


def test_atomic_actions_have_short_durations_and_natural_gaps(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _pin_duration(monkeypatch, 168_000)

    duration_ms, segments = analyze_video_actions(Path("synthetic.mp4"), DEFAULT_LABELS)

    assert duration_ms == 168_000
    assert len(segments) > 0
    # Segments must be short discrete atomic actions, not 33s chunks
    for seg in segments:
        duration = seg.end_ms - seg.start_ms
        assert 3_000 <= duration <= 15_000

    # Ensure natural gaps exist between segments (not contiguous tiling)
    for i in range(1, len(segments)):
        gap = segments[i].start_ms - segments[i - 1].end_ms
        assert gap > 0

    # Total action time must NOT cover 100% of video
    total_action_time = sum(seg.end_ms - seg.start_ms for seg in segments)
    assert total_action_time < duration_ms * 0.7


def test_placeholder_result_is_flagged_as_non_model_output() -> None:
    assert PLACEHOLDER_PROMPT_VERSION == "placeholder-protocol-scaffold-v1"
    assert not PLACEHOLDER_PROMPT_VERSION.startswith("v")


def test_probe_duration_ms_raises_when_ffprobe_fails() -> None:
    with pytest.raises(RuntimeError, match="Failed to probe video duration"):
        action_analyzer.probe_duration_ms(Path("non-existent-video.mp4"))
