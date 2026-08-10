from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import mediapipe as mp
import numpy as np

from .geometry import LANDMARK_NAMES, Landmark, calculate_bbox
from .models import HandObservation, WorldLandmark


@dataclass(frozen=True)
class LandmarkerConfig:
    num_hands: int = 2
    min_detection_confidence: float = 0.5
    min_presence_confidence: float = 0.5
    min_tracking_confidence: float = 0.5
    include_world_landmarks: bool = False
    swap_handedness: bool = False

    def validate(self) -> None:
        if not 1 <= self.num_hands <= 2:
            raise ValueError("num_hands must be 1 or 2 for this PoC.")
        for name, value in (
            ("min_detection_confidence", self.min_detection_confidence),
            ("min_presence_confidence", self.min_presence_confidence),
            ("min_tracking_confidence", self.min_tracking_confidence),
        ):
            if not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be between 0 and 1.")


class MediaPipeHandDetector:
    def __init__(self, model_path: Path, config: LandmarkerConfig) -> None:
        config.validate()
        if not model_path.is_file():
            raise ValueError("The offline Hand Landmarker model does not exist.")
        options = mp.tasks.vision.HandLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path.resolve())),
            running_mode=mp.tasks.vision.RunningMode.VIDEO,
            num_hands=config.num_hands,
            min_hand_detection_confidence=config.min_detection_confidence,
            min_hand_presence_confidence=config.min_presence_confidence,
            min_tracking_confidence=config.min_tracking_confidence,
        )
        self._delegate = mp.tasks.vision.HandLandmarker.create_from_options(options)
        self._config = config

    def __enter__(self) -> MediaPipeHandDetector:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def close(self) -> None:
        self._delegate.close()

    def detect(
        self, frame_rgb: np.ndarray[Any, np.dtype[np.uint8]], timestamp_ms: int
    ) -> tuple[HandObservation, ...]:
        if frame_rgb.ndim != 3 or frame_rgb.shape[2] != 3 or frame_rgb.dtype != np.uint8:
            raise ValueError("MediaPipe input must be an uint8 RGB image.")
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=frame_rgb)
        result = self._delegate.detect_for_video(image, timestamp_ms)
        if not (
            len(result.handedness) == len(result.hand_landmarks) == len(result.hand_world_landmarks)
        ):
            raise RuntimeError("MediaPipe returned misaligned hand result lists.")
        return tuple(self._to_hand(index, result) for index in range(len(result.hand_landmarks)))

    def _to_hand(self, index: int, result: Any) -> HandObservation:
        raw_landmarks = result.hand_landmarks[index]
        if len(raw_landmarks) != len(LANDMARK_NAMES):
            raise RuntimeError(
                f"MediaPipe hand {index} returned {len(raw_landmarks)} landmarks, expected 21."
            )
        landmarks = tuple(
            Landmark(point_id, LANDMARK_NAMES[point_id], point.x, point.y, point.z)
            for point_id, point in enumerate(raw_landmarks)
        )
        bbox, valid_count = calculate_bbox(landmarks)
        category = result.handedness[index][0] if result.handedness[index] else None
        handedness = (
            "Unknown" if category is None else _normalize_handedness(category.category_name)
        )
        if self._config.swap_handedness:
            handedness = {"Left": "Right", "Right": "Left"}.get(handedness, handedness)
        score = 0.0 if category is None or category.score is None else float(category.score)
        world_landmarks = None
        if self._config.include_world_landmarks:
            raw_world = result.hand_world_landmarks[index]
            if len(raw_world) != len(LANDMARK_NAMES):
                raise RuntimeError(
                    f"MediaPipe hand {index} returned {len(raw_world)} world landmarks, "
                    "expected 21."
                )
            world_landmarks = tuple(
                WorldLandmark(point_id, LANDMARK_NAMES[point_id], point.x, point.y, point.z)
                for point_id, point in enumerate(raw_world)
            )
        return HandObservation(
            hand_index=index,
            handedness=handedness,
            handedness_score=score,
            valid_landmark_count=valid_count,
            bbox_normalized=bbox,
            landmarks=landmarks,
            world_landmarks=world_landmarks,
        )


def _normalize_handedness(value: str | None) -> str:
    if value is None:
        return "Unknown"
    normalized = value.strip().lower()
    return {"left": "Left", "right": "Right"}.get(normalized, "Unknown")
