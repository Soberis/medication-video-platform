from __future__ import annotations

import hashlib
import math
from pathlib import Path
from typing import Any

import av
import mediapipe as mp

ALGORITHM_KEY = "MEDIAPIPE_HAND_LANDMARKER"
ALGORITHM_VERSION = "v1"
MAX_HANDS = 2
MIN_HAND_DETECTION_CONFIDENCE = 0.5
MIN_HAND_PRESENCE_CONFIDENCE = 0.5
MIN_TRACKING_CONFIDENCE = 0.5
HANDEDNESS_POLICY = "MEDIAPIPE_REPORTED"
TIMESTAMP_POLICY = "CONTAINER_PTS_STRICTLY_INCREASING"
BOUNDING_BOX_RECIPE = "FINITE_NORMALIZED_LANDMARK_BOUNDS_V1"
PROFILE_SHA256 = hashlib.sha256(
    "\n".join(
        (
            ALGORITHM_KEY,
            ALGORITHM_VERSION,
            "10",
            str(MAX_HANDS),
            str(MIN_HAND_DETECTION_CONFIDENCE),
            str(MIN_HAND_PRESENCE_CONFIDENCE),
            str(MIN_TRACKING_CONFIDENCE),
            HANDEDNESS_POLICY,
            TIMESTAMP_POLICY,
            BOUNDING_BOX_RECIPE,
        )
    ).encode()
).hexdigest()


def candidates_from_video(
    path: Path, model_path: Path, sampling_fps: int
) -> list[dict[str, object]]:
    options = mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_hands=MAX_HANDS,
        min_hand_detection_confidence=MIN_HAND_DETECTION_CONFIDENCE,
        min_hand_presence_confidence=MIN_HAND_PRESENCE_CONFIDENCE,
        min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
    )
    candidates: list[dict[str, object]] = []
    detector = mp.tasks.vision.HandLandmarker.create_from_options(options)
    try:
        _detect_all(path, detector, sampling_fps, candidates)
    finally:
        detector.close()
    return candidates


def _detect_all(
    path: Path, detector: Any, sampling_fps: int, candidates: list[dict[str, object]]
) -> None:
    with av.open(path) as container:
        stream = container.streams.video[0]
        last_timestamp = -1
        next_sample = 0
        for frame in container.decode(stream):
            timestamp = _timestamp_ms(frame, stream, last_timestamp)
            if timestamp <= last_timestamp or timestamp < next_sample:
                continue
            last_timestamp = timestamp
            next_sample = timestamp + 1000 // sampling_fps
            rgb = frame.to_ndarray(format="rgb24")
            result = detector.detect_for_video(
                mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), timestamp
            )
            for index, landmarks in enumerate(result.hand_landmarks[:2]):
                if len(landmarks) != 21:
                    continue
                category = result.handedness[index][0] if result.handedness[index] else None
                points = [
                    {"x": float(point.x), "y": float(point.y), "z": float(point.z)}
                    for point in landmarks
                ]
                finite = [
                    point
                    for point in points
                    if math.isfinite(point["x"]) and math.isfinite(point["y"])
                ]
                if not finite:
                    continue
                min_x, max_x = (
                    min(point["x"] for point in finite),
                    max(point["x"] for point in finite),
                )
                min_y, max_y = (
                    min(point["y"] for point in finite),
                    max(point["y"] for point in finite),
                )
                candidates.append(
                    {
                        "frame_timestamp_ms": timestamp,
                        "image_width": frame.width,
                        "image_height": frame.height,
                        "hand_index": index,
                        "handedness": _handedness(
                            None if category is None else category.category_name
                        ),
                        "handedness_score": 0.0 if category is None else float(category.score or 0),
                        "bounding_box": {
                            "x": min_x,
                            "y": min_y,
                            "width": max(max_x - min_x, 0.000001),
                            "height": max(max_y - min_y, 0.000001),
                        },
                        "landmarks": points,
                        "valid_landmark_count": len(finite),
                    }
                )


def _timestamp_ms(frame: Any, stream: Any, previous: int) -> int:
    if frame.pts is None or stream.time_base is None:
        raise ValueError("Video frames must provide container presentation timestamps")
    timestamp = round(float(frame.pts * stream.time_base) * 1000)
    if timestamp <= previous:
        raise ValueError("Video presentation timestamps must be strictly increasing")
    return timestamp


def _handedness(value: str | None) -> str:
    return {"left": "LEFT", "right": "RIGHT"}.get((value or "").lower(), "UNKNOWN")
