from __future__ import annotations

import math
from typing import Any, Final

import numpy as np
from PIL import Image, ImageDraw

from .geometry import HAND_CONNECTIONS, normalized_to_pixel
from .models import HandObservation

_COLORS: Final[dict[str, tuple[int, int, int]]] = {
    "Left": (0, 220, 255),
    "Right": (255, 80, 190),
    "Unknown": (255, 210, 0),
}


def render_overlay(
    frame_rgb: np.ndarray[Any, np.dtype[np.uint8]], hands: tuple[HandObservation, ...]
) -> np.ndarray[Any, np.dtype[np.uint8]]:
    height, width = frame_rgb.shape[:2]
    image = Image.fromarray(frame_rgb, mode="RGB")
    draw = ImageDraw.Draw(image)
    line_width = max(2, round(min(width, height) / 360))
    radius = max(3, round(min(width, height) / 240))

    for hand in hands:
        color = _COLORS.get(hand.handedness, _COLORS["Unknown"])
        points = tuple(_point_or_none(point.x, point.y, width, height) for point in hand.landmarks)
        for start, end in HAND_CONNECTIONS:
            start_point = points[start]
            end_point = points[end]
            if start_point is not None and end_point is not None:
                draw.line((start_point, end_point), fill=color, width=line_width)
        for point in points:
            if point is None:
                continue
            x, y = point
            draw.ellipse(
                (x - radius, y - radius, x + radius, y + radius), fill=color, outline=(0, 0, 0)
            )
        label_x = normalized_to_pixel(hand.bbox_normalized.xmin, width)
        label_y = max(0, normalized_to_pixel(hand.bbox_normalized.ymin, height) - 18)
        label = f"{hand.handedness} {hand.handedness_score:.2f}"
        text_box = draw.textbbox((label_x, label_y), label)
        draw.rectangle(text_box, fill=(0, 0, 0))
        draw.text((label_x, label_y), label, fill=color)

    return np.asarray(image, dtype=np.uint8)


def _point_or_none(x: float, y: float, width: int, height: int) -> tuple[int, int] | None:
    if not math.isfinite(x) or not math.isfinite(y):
        return None
    return normalized_to_pixel(x, width), normalized_to_pixel(y, height)
