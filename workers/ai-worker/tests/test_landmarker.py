from __future__ import annotations

import math
from collections.abc import Iterator
from fractions import Fraction
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

import medication_ai_worker.landmarker as landmarker
from medication_ai_worker.landmarker import PROFILE_SHA256, _detect_all, _timestamp_ms


def test_profile_digest_freezes_worker_inference_configuration() -> None:
    assert PROFILE_SHA256 == "239a403b289c6b379af660988516f752daf1899ce2a05613e333a67a6f95af09"


@pytest.mark.parametrize("fps", [24, 25, 30, 60])
def test_fixed_grid_samples_ten_frames_per_second_without_drift(
    monkeypatch: pytest.MonkeyPatch, fps: int
) -> None:
    frames = [
        SimpleNamespace(pts=index, width=640, height=360, to_ndarray=lambda format: object())
        for index in range(fps * 10)
    ]
    container = _FakeContainer(frames)
    container.streams.video[0].time_base = Fraction(1, fps)
    timestamps: list[int] = []

    def detect(image: object, timestamp: int) -> SimpleNamespace:
        timestamps.append(timestamp)
        return SimpleNamespace(hand_landmarks=[], handedness=[])

    monkeypatch.setattr(landmarker, "av", SimpleNamespace(open=lambda path: container))
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )
    summary = _detect_all(Path("synthetic.mp4"), SimpleNamespace(detect_for_video=detect), 10, [])
    assert summary.inference_frame_count == 100
    source_timestamps = {round(index * 1000 / fps) for index in range(fps * 10)}
    assert all(timestamp in source_timestamps for timestamp in timestamps)
    assert all(
        index * 100 <= timestamp < index * 100 + math.ceil(1000 / fps)
        for index, timestamp in enumerate(timestamps)
    )


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


def test_two_invalid_hands_in_one_frame_increment_invalid_frame_count_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    invalid_points = [SimpleNamespace(x=0.1, y=0.2, z=0.0) for _ in range(21)]
    invalid_points[0] = SimpleNamespace(x=float("nan"), y=0.2, z=0.0)
    result = SimpleNamespace(
        hand_landmarks=[invalid_points, invalid_points],
        handedness=[
            [SimpleNamespace(category_name="Left", score=0.9)],
            [SimpleNamespace(category_name="Right", score=0.8)],
        ],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    container = _FakeContainer([frame])
    monkeypatch.setattr(landmarker, "av", SimpleNamespace(open=lambda path: container))
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_landmark_count == 2
    assert summary.invalid_hand_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert candidates == []


@pytest.mark.parametrize("raw_landmarks", [None, 123])
def test_malformed_landmark_container_is_invalid_not_no_hand(
    monkeypatch: pytest.MonkeyPatch, raw_landmarks: object
) -> None:
    result = SimpleNamespace(hand_landmarks=raw_landmarks, handedness=[])
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_hand_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert candidates == []


def test_missing_landmark_field_is_invalid_not_no_hand(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = SimpleNamespace(handedness=[])
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_hand_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert candidates == []


def test_invalid_hand_frame_is_not_counted_as_no_hand_when_mixed_with_true_no_hand(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    invalid_points = [SimpleNamespace(x=0.1, y=0.2, z=0.0) for _ in range(20)]
    results = iter(
        (
            SimpleNamespace(hand_landmarks=[invalid_points], handedness=[[]]),
            SimpleNamespace(hand_landmarks=[], handedness=[]),
        )
    )
    frames = [
        SimpleNamespace(pts=0, width=1280, height=720, to_ndarray=lambda format: object()),
        SimpleNamespace(pts=100, width=1280, height=720, to_ndarray=lambda format: object()),
    ]
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: next(results))
    monkeypatch.setattr(landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer(frames)))
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_hand_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 1


def test_more_than_two_hand_observations_make_the_frame_invalid(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    landmarks = [SimpleNamespace(x=0.1, y=0.2, z=0.0) for _ in range(21)]
    result = SimpleNamespace(
        hand_landmarks=[landmarks, landmarks, landmarks],
        handedness=[
            [SimpleNamespace(category_name="Left", score=0.9)],
            [SimpleNamespace(category_name="Right", score=0.8)],
            [SimpleNamespace(category_name="Left", score=0.7)],
        ],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_hand_frame_count == 1
    assert summary.invalid_landmark_count == 0
    assert summary.inference_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 0
    assert summary.right_hand_detection_count == 0
    assert candidates == []


def test_valid_hand_emits_exactly_21_finite_landmarks_and_bounded_metadata(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    landmarks = [
        SimpleNamespace(x=0.1 + index * 0.01, y=0.2 + index * 0.01, z=-0.01) for index in range(21)
    ]
    result = SimpleNamespace(
        hand_landmarks=[landmarks],
        handedness=[[SimpleNamespace(category_name="Left", score=0.91)]],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_landmark_count == 0
    assert summary.invalid_hand_frame_count == 0
    assert summary.inference_frame_count == 1
    assert summary.frames_with_hand == 1
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 1
    assert summary.right_hand_detection_count == 0
    assert len(candidates) == 1
    candidate = candidates[0]
    candidate_landmarks = cast(list[dict[str, float]], candidate["landmarks"])
    assert candidate["handedness"] == "LEFT"
    assert candidate["handedness_score"] == 0.91
    assert candidate["valid_landmark_count"] == 21
    assert len(candidate_landmarks) == 21
    assert all(math.isfinite(value) for point in candidate_landmarks for value in point.values())
    candidate_box = cast(dict[str, float], candidate["bounding_box"])
    assert all(math.isfinite(value) for value in candidate_box.values())


def test_three_timestamp_fixture_preserves_both_hands_and_all_landmark_fields(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def hand(seed: int, label: str, score: float) -> SimpleNamespace:
        points = [
            SimpleNamespace(
                x=0.1 + seed * 0.01 + index * 0.001,
                y=0.2 + seed * 0.01 + index * 0.002,
                z=-0.01 - seed * 0.001 - index * 0.001,
            )
            for index in range(21)
        ]
        return SimpleNamespace(
            landmarks=points,
            category=SimpleNamespace(category_name=label, score=score),
        )

    observed_hands = [hand(0, "Right", 0.91), hand(1, "Left", 0.92)]
    results = iter(
        (
            SimpleNamespace(
                hand_landmarks=[observed_hands[0].landmarks],
                handedness=[[observed_hands[0].category]],
            ),
            SimpleNamespace(
                hand_landmarks=[observed_hands[1].landmarks],
                handedness=[[observed_hands[1].category]],
            ),
            SimpleNamespace(
                hand_landmarks=[
                    hand(2, "Left", 0.93).landmarks,
                    hand(3, "Right", 0.94).landmarks,
                ],
                handedness=[
                    [hand(2, "Left", 0.93).category],
                    [hand(3, "Right", 0.94).category],
                ],
            ),
        )
    )
    frames = [
        SimpleNamespace(pts=100, width=1280, height=720, to_ndarray=lambda format: object()),
        SimpleNamespace(pts=200, width=1280, height=720, to_ndarray=lambda format: object()),
        SimpleNamespace(pts=300, width=1280, height=720, to_ndarray=lambda format: object()),
    ]
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: next(results))
    monkeypatch.setattr(landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer(frames)))
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.inference_frame_count == 3
    assert summary.frames_with_hand == 3
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 2
    assert summary.right_hand_detection_count == 2
    assert summary.invalid_landmark_count == 0
    assert summary.invalid_hand_frame_count == 0
    assert [
        (candidate["frame_timestamp_ms"], candidate["hand_index"], candidate["handedness"])
        for candidate in candidates
    ] == [(100, 0, "RIGHT"), (200, 0, "LEFT"), (300, 0, "LEFT"), (300, 1, "RIGHT")]
    assert [candidate["handedness_score"] for candidate in candidates] == [0.91, 0.92, 0.93, 0.94]

    expected_keys = {
        "frame_timestamp_ms",
        "image_width",
        "image_height",
        "hand_index",
        "handedness",
        "handedness_score",
        "bounding_box",
        "landmarks",
        "valid_landmark_count",
    }
    for candidate in candidates:
        assert set(candidate) == expected_keys
        assert candidate["image_width"] == 1280
        assert candidate["image_height"] == 720
        assert candidate["valid_landmark_count"] == 21
        candidate_box = cast(dict[str, float], candidate["bounding_box"])
        assert set(candidate_box) == {"x", "y", "width", "height"}
        assert candidate_box["width"] > 0
        assert candidate_box["height"] > 0
        assert all(math.isfinite(value) for value in candidate_box.values())
        candidate_landmarks = cast(list[dict[str, float]], candidate["landmarks"])
        assert len(candidate_landmarks) == 21
        assert all(set(point) == {"x", "y", "z"} for point in candidate_landmarks)
        assert all(
            math.isfinite(value) for point in candidate_landmarks for value in point.values()
        )


def test_valid_no_hand_observation_counts_as_a_valid_no_hand_frame(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = SimpleNamespace(hand_landmarks=[], handedness=[])
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.inference_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 1
    assert summary.invalid_hand_frame_count == 0
    assert summary.left_hand_detection_count == 0
    assert summary.right_hand_detection_count == 0
    assert candidates == []


def test_valid_and_invalid_hands_in_one_frame_invalidate_and_discard_the_whole_frame(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    valid_points = [SimpleNamespace(x=0.1 + index * 0.01, y=0.2, z=0.0) for index in range(21)]
    invalid_points = list(valid_points)
    invalid_points[0] = SimpleNamespace(x=float("nan"), y=0.2, z=0.0)
    result = SimpleNamespace(
        hand_landmarks=[valid_points, invalid_points],
        handedness=[
            [SimpleNamespace(category_name="Left", score=0.9)],
            [SimpleNamespace(category_name="Right", score=0.8)],
        ],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.inference_frame_count == 1
    assert summary.invalid_hand_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 0
    assert summary.right_hand_detection_count == 0
    assert candidates == []


def test_two_valid_hands_commit_two_candidates_and_count_each_handedness(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    landmarks = [SimpleNamespace(x=0.1 + index * 0.01, y=0.2, z=0.0) for index in range(21)]
    result = SimpleNamespace(
        hand_landmarks=[landmarks, landmarks],
        handedness=[
            [SimpleNamespace(category_name="Left", score=0.9)],
            [SimpleNamespace(category_name="Right", score=0.8)],
        ],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.inference_frame_count == 1
    assert summary.frames_with_hand == 1
    assert summary.frames_without_hand == 0
    assert summary.invalid_hand_frame_count == 0
    assert summary.left_hand_detection_count == 1
    assert summary.right_hand_detection_count == 1
    assert len(candidates) == 2


def test_nonfinite_handedness_score_invalidates_the_observed_hand(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    landmarks = [SimpleNamespace(x=0.1, y=0.2, z=0.0) for _ in range(21)]
    result = SimpleNamespace(
        hand_landmarks=[landmarks],
        handedness=[[SimpleNamespace(category_name="Left", score=float("inf"))]],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.invalid_landmark_count == 0
    assert summary.invalid_hand_frame_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 0
    assert summary.right_hand_detection_count == 0
    assert candidates == []


@pytest.mark.parametrize("coordinate", ["x", "y", "z"])
def test_nonfinite_landmark_coordinate_invalidates_the_whole_frame(
    monkeypatch: pytest.MonkeyPatch, coordinate: str
) -> None:
    values = {"x": 0.1, "y": 0.2, "z": 0.0}
    values[coordinate] = float("nan")
    landmarks = [SimpleNamespace(**values)] + [
        SimpleNamespace(x=0.1, y=0.2, z=0.0) for _ in range(20)
    ]
    result = SimpleNamespace(
        hand_landmarks=[landmarks],
        handedness=[[SimpleNamespace(category_name="Left", score=0.9)]],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.inference_frame_count == 1
    assert summary.invalid_hand_frame_count == 1
    assert summary.invalid_landmark_count == 1
    assert summary.frames_with_hand == 0
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 0
    assert summary.right_hand_detection_count == 0
    assert candidates == []


def test_unrecognized_handedness_keeps_the_candidate_with_unknown_side(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    landmarks = [SimpleNamespace(x=0.1, y=0.2, z=0.0) for _ in range(21)]
    result = SimpleNamespace(
        hand_landmarks=[landmarks],
        handedness=[[SimpleNamespace(category_name="Sideways", score=0.9)]],
    )
    frame = SimpleNamespace(
        pts=0,
        width=1280,
        height=720,
        to_ndarray=lambda format: object(),
    )
    detector = SimpleNamespace(detect_for_video=lambda image, timestamp: result)
    monkeypatch.setattr(
        landmarker, "av", SimpleNamespace(open=lambda path: _FakeContainer([frame]))
    )
    monkeypatch.setattr(
        landmarker,
        "mp",
        SimpleNamespace(Image=lambda **kwargs: kwargs["data"], ImageFormat=SimpleNamespace(SRGB=1)),
    )

    candidates: list[dict[str, object]] = []
    summary = _detect_all(Path("synthetic.mp4"), detector, 10, candidates)

    assert summary.inference_frame_count == 1
    assert summary.invalid_hand_frame_count == 0
    assert summary.invalid_landmark_count == 0
    assert summary.frames_with_hand == 1
    assert summary.frames_without_hand == 0
    assert summary.left_hand_detection_count == 0
    assert summary.right_hand_detection_count == 0
    assert len(candidates) == 1
    assert candidates[0]["handedness"] == "UNKNOWN"


class _FakeContainer:
    def __init__(self, frames: list[Any]) -> None:
        self.streams = SimpleNamespace(video=[SimpleNamespace(time_base=Fraction(1, 1_000))])
        self._frames = frames

    def __enter__(self) -> _FakeContainer:
        return self

    def __exit__(
        self, exc_type: object, exc_value: BaseException | None, traceback: object
    ) -> None:
        del exc_type, exc_value, traceback

    def decode(self, stream: Any) -> Iterator[Any]:
        del stream
        return iter(self._frames)
