from __future__ import annotations

from hand_pose_poc.geometry import (
    HAND_CONNECTIONS,
    LANDMARK_NAMES,
    Landmark,
    calculate_bbox,
    normalized_to_pixel,
)


def test_landmark_names_are_exactly_zero_through_twenty() -> None:
    assert len(LANDMARK_NAMES) == 21
    assert LANDMARK_NAMES[0] == "WRIST"
    assert LANDMARK_NAMES[20] == "PINKY_TIP"
    assert list(range(len(LANDMARK_NAMES))) == list(range(21))


def test_hand_connections_only_reference_known_landmarks() -> None:
    assert len(HAND_CONNECTIONS) == 21
    assert all(0 <= endpoint <= 20 for connection in HAND_CONNECTIONS for endpoint in connection)


def test_bbox_clamps_finite_landmarks_to_normalized_image() -> None:
    points = (
        Landmark(0, "WRIST", -0.1, 0.25, 0.0),
        Landmark(1, "THUMB_CMC", 0.75, 1.2, 0.0),
    )

    bbox, valid_count = calculate_bbox(points)

    assert valid_count == 2
    assert bbox.xmin == 0.0
    assert bbox.ymin == 0.25
    assert bbox.xmax == 0.75
    assert bbox.ymax == 1.0


def test_normalized_to_pixel_clamps_at_image_edges() -> None:
    assert normalized_to_pixel(0.5, 100) == 50
    assert normalized_to_pixel(0.25, 200) == 50
    assert normalized_to_pixel(-1.0, 100) == 0
    assert normalized_to_pixel(1.0, 100) == 99
