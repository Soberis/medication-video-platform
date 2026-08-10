from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from .geometry import BoundingBox, Landmark


@dataclass(frozen=True)
class WorldLandmark:
    id: int
    name: str
    x: float
    y: float
    z: float


@dataclass(frozen=True)
class HandObservation:
    hand_index: int
    handedness: str
    handedness_score: float
    valid_landmark_count: int
    bbox_normalized: BoundingBox
    landmarks: tuple[Landmark, ...]
    world_landmarks: tuple[WorldLandmark, ...] | None = None


@dataclass(frozen=True)
class FrameObservation:
    frame_index: int
    source_timestamp_ms: int
    timestamp_ms: int
    timestamp_source: str
    inference_performed: bool
    image_width: int
    image_height: int
    hands: tuple[HandObservation, ...]

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        for hand in payload["hands"]:
            if hand["world_landmarks"] is None:
                hand.pop("world_landmarks")
        return payload
