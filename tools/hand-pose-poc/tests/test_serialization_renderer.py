from __future__ import annotations

import numpy as np

from hand_pose_poc.models import FrameObservation
from hand_pose_poc.renderer import render_overlay

from .helpers import hand


def test_empty_hand_result_serializes_without_dropping_frame() -> None:
    frame = FrameObservation(3, 100, 100, "container_pts", True, 640, 480, ())

    assert frame.to_dict()["hands"] == ()
    assert frame.to_dict()["inference_performed"] is True


def test_two_hand_result_serializes_left_and_right_without_world_data() -> None:
    frame = FrameObservation(
        3,
        100,
        100,
        "container_pts",
        True,
        640,
        480,
        (hand("Left"), hand("Right", 0.4)),
    )

    payload = frame.to_dict()

    assert [item["handedness"] for item in payload["hands"]] == ["Left", "Right"]
    assert all(len(item["landmarks"]) == 21 for item in payload["hands"])
    assert all("world_landmarks" not in item for item in payload["hands"])


def test_renderer_draws_skeleton_without_gui() -> None:
    source = np.zeros((200, 300, 3), dtype=np.uint8)

    rendered = render_overlay(source, (hand(),))

    assert rendered.shape == source.shape
    assert rendered.dtype == np.uint8
    assert np.count_nonzero(rendered) > 0
