from __future__ import annotations

from hand_pose_poc.geometry import LANDMARK_NAMES, BoundingBox, Landmark
from hand_pose_poc.models import HandObservation


def hand(handedness: str = "Left", offset: float = 0.0) -> HandObservation:
    landmarks = tuple(
        Landmark(
            id=index,
            name=name,
            x=0.2 + offset + (index % 5) * 0.04,
            y=0.2 + (index // 5) * 0.08,
            z=-index / 100,
        )
        for index, name in enumerate(LANDMARK_NAMES)
    )
    return HandObservation(
        hand_index=0 if handedness == "Left" else 1,
        handedness=handedness,
        handedness_score=0.97,
        valid_landmark_count=21,
        bbox_normalized=BoundingBox(0.2 + offset, 0.2, 0.36 + offset, 0.52),
        landmarks=landmarks,
    )
