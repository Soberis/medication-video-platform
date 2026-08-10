from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Final

LANDMARK_NAMES: Final[tuple[str, ...]] = (
    "WRIST",
    "THUMB_CMC",
    "THUMB_MCP",
    "THUMB_IP",
    "THUMB_TIP",
    "INDEX_FINGER_MCP",
    "INDEX_FINGER_PIP",
    "INDEX_FINGER_DIP",
    "INDEX_FINGER_TIP",
    "MIDDLE_FINGER_MCP",
    "MIDDLE_FINGER_PIP",
    "MIDDLE_FINGER_DIP",
    "MIDDLE_FINGER_TIP",
    "RING_FINGER_MCP",
    "RING_FINGER_PIP",
    "RING_FINGER_DIP",
    "RING_FINGER_TIP",
    "PINKY_MCP",
    "PINKY_PIP",
    "PINKY_DIP",
    "PINKY_TIP",
)

HAND_CONNECTIONS: Final[tuple[tuple[int, int], ...]] = (
    (0, 1),
    (1, 2),
    (2, 3),
    (3, 4),
    (0, 5),
    (5, 6),
    (6, 7),
    (7, 8),
    (5, 9),
    (9, 10),
    (10, 11),
    (11, 12),
    (9, 13),
    (13, 14),
    (14, 15),
    (15, 16),
    (13, 17),
    (0, 17),
    (17, 18),
    (18, 19),
    (19, 20),
)


@dataclass(frozen=True)
class Landmark:
    id: int
    name: str
    x: float
    y: float
    z: float


@dataclass(frozen=True)
class BoundingBox:
    xmin: float
    ymin: float
    xmax: float
    ymax: float


def calculate_bbox(landmarks: Iterable[Landmark]) -> tuple[BoundingBox, int]:
    valid = [point for point in landmarks if math.isfinite(point.x) and math.isfinite(point.y)]
    if not valid:
        raise ValueError("A bounding box requires at least one finite landmark.")
    return (
        BoundingBox(
            xmin=_clamp(min(point.x for point in valid)),
            ymin=_clamp(min(point.y for point in valid)),
            xmax=_clamp(max(point.x for point in valid)),
            ymax=_clamp(max(point.y for point in valid)),
        ),
        len(valid),
    )


def normalized_to_pixel(value: float, extent: int) -> int:
    if extent <= 0:
        raise ValueError("Image extent must be positive.")
    if not math.isfinite(value):
        raise ValueError("A drawable coordinate must be finite.")
    return min(extent - 1, max(0, round(value * extent)))


def _clamp(value: float) -> float:
    return min(1.0, max(0.0, value))
