from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
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


@dataclass(frozen=True, slots=True)
class InferenceSummary:
    total_video_frames: int
    inference_frame_count: int
    frames_with_hand: int
    frames_without_hand: int
    left_hand_detection_count: int
    right_hand_detection_count: int
    invalid_landmark_count: int
    invalid_hand_frame_count: int


@dataclass(frozen=True, slots=True)
class InferenceResult:
    candidates: list[dict[str, object]]
    summary: InferenceSummary


def candidates_from_video(
    path: Path, model_path: Path, sampling_fps: int
) -> list[dict[str, object]]:
    return analyze_video(path, model_path, sampling_fps).candidates


def analyze_video(path: Path, model_path: Path, sampling_fps: int) -> InferenceResult:
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
        summary = _detect_all(path, detector, sampling_fps, candidates)
    finally:
        detector.close()
    return InferenceResult(candidates, summary)


def _detect_all(
    path: Path, detector: Any, sampling_fps: int, candidates: list[dict[str, object]]
) -> InferenceSummary:
    total_video_frames = 0
    inference_frame_count = 0
    frames_with_hand = 0
    frames_without_hand = 0
    left_hand_detection_count = 0
    right_hand_detection_count = 0
    invalid_landmark_count = 0
    invalid_hand_frame_count = 0
    with av.open(path) as container:
        stream = container.streams.video[0]
        # Keep this timestamp current for every decoded frame, including frames
        # that are skipped by the sampler.  Otherwise a duplicate PTS hidden in
        # a skipped interval could reach MediaPipe and violate VIDEO mode's
        # monotonic timestamp contract.
        last_timestamp = -1
        next_sample = 0
        for frame in container.decode(stream):
            total_video_frames += 1
            timestamp = _timestamp_ms(frame, stream, last_timestamp)
            last_timestamp = timestamp
            if timestamp < next_sample:
                continue
            next_sample = timestamp + 1000 // sampling_fps
            inference_frame_count += 1
            rgb = frame.to_ndarray(format="rgb24")
            result = detector.detect_for_video(
                mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), timestamp
            )
            frame_candidates: list[dict[str, object]] = []
            frame_left_count = 0
            frame_right_count = 0
            has_landmarks_field = hasattr(result, "hand_landmarks")
            raw_landmarks = getattr(result, "hand_landmarks", [])
            frame_has_invalid_hand = not has_landmarks_field
            if has_landmarks_field:
                try:
                    detected_landmarks = list(raw_landmarks)
                except TypeError:
                    # ``None``/non-iterable detector output is an invalid
                    # observation, not a legitimate no-hand frame.
                    detected_landmarks = []
                    frame_has_invalid_hand = True
            else:
                detected_landmarks = []
            frame_has_invalid_hand |= len(detected_landmarks) > MAX_HANDS
            handedness_groups = getattr(result, "handedness", [])
            for index, landmarks in enumerate(detected_landmarks[:MAX_HANDS]):
                try:
                    points_in_observation = list(landmarks)
                except TypeError:
                    points_in_observation = []
                    frame_has_invalid_hand = True
                landmark_count = len(points_in_observation)
                if landmark_count != 21:
                    invalid_landmark_count += abs(landmark_count - 21)
                    frame_has_invalid_hand = True
                    continue

                points: list[dict[str, float]] = []
                invalid_points = 0
                for point in points_in_observation:
                    try:
                        values = {"x": float(point.x), "y": float(point.y), "z": float(point.z)}
                    except (AttributeError, TypeError, ValueError):
                        invalid_points += 1
                        continue
                    if not all(math.isfinite(value) for value in values.values()):
                        invalid_points += 1
                        continue
                    points.append(values)
                invalid_landmark_count += invalid_points
                if invalid_points > 0:
                    frame_has_invalid_hand = True
                    continue

                category = _first_category(handedness_groups, index)
                handedness_score = _handedness_score(category)
                try:
                    image_width = int(frame.width)
                    image_height = int(frame.height)
                except (AttributeError, OverflowError, TypeError, ValueError):
                    image_width = 0
                    image_height = 0
                if handedness_score is None or image_width <= 0 or image_height <= 0:
                    frame_has_invalid_hand = True
                    continue
                normalized_handedness = _handedness(
                    None if category is None else getattr(category, "category_name", None)
                )
                if normalized_handedness is None:
                    frame_has_invalid_hand = True
                    continue

                min_x, max_x = (
                    min(point["x"] for point in points),
                    max(point["x"] for point in points),
                )
                min_y, max_y = (
                    min(point["y"] for point in points),
                    max(point["y"] for point in points),
                )
                bounding_box = {
                    "x": min_x,
                    "y": min_y,
                    "width": max(max_x - min_x, 0.000001),
                    "height": max(max_y - min_y, 0.000001),
                }
                if not all(math.isfinite(value) for value in bounding_box.values()):
                    frame_has_invalid_hand = True
                    continue
                candidate = {
                    "frame_timestamp_ms": timestamp,
                    "image_width": image_width,
                    "image_height": image_height,
                    "hand_index": index,
                    "handedness": normalized_handedness,
                    "handedness_score": handedness_score,
                    "bounding_box": bounding_box,
                    "landmarks": points,
                    "valid_landmark_count": 21,
                }
                frame_candidates.append(candidate)
                if normalized_handedness == "LEFT":
                    frame_left_count += 1
                elif normalized_handedness == "RIGHT":
                    frame_right_count += 1
            if frame_has_invalid_hand:
                invalid_hand_frame_count += 1
            elif frame_candidates:
                candidates.extend(frame_candidates)
                frames_with_hand += 1
                left_hand_detection_count += frame_left_count
                right_hand_detection_count += frame_right_count
            else:
                # Invalid-only frames are intentionally excluded from both
                # hand/no-hand counts.  A true no-hand observation is only an
                # empty, structurally valid detector result.
                frames_without_hand += 1

    return InferenceSummary(
        total_video_frames=total_video_frames,
        inference_frame_count=inference_frame_count,
        frames_with_hand=frames_with_hand,
        frames_without_hand=frames_without_hand,
        left_hand_detection_count=left_hand_detection_count,
        right_hand_detection_count=right_hand_detection_count,
        invalid_landmark_count=invalid_landmark_count,
        invalid_hand_frame_count=invalid_hand_frame_count,
    )


def _timestamp_ms(frame: Any, stream: Any, previous: int) -> int:
    if frame.pts is None or stream.time_base is None:
        raise ValueError("Video frames must provide container presentation timestamps")
    timestamp = round(float(frame.pts * stream.time_base) * 1000)
    if timestamp <= previous:
        raise ValueError("Video presentation timestamps must be strictly increasing")
    return timestamp


def _first_category(groups: Any, index: int) -> Any | None:
    try:
        group = groups[index]
        return group[0] if group else None
    except (IndexError, KeyError, TypeError):
        return None


def _handedness_score(category: Any | None) -> float | None:
    if category is None:
        return 0.0
    raw_score = getattr(category, "score", 0.0)
    if raw_score is None:
        return 0.0
    try:
        score = float(raw_score)
    except (TypeError, ValueError):
        return None
    return score if math.isfinite(score) and 0 <= score <= 1 else None


def _handedness(value: str | None) -> str | None:
    if value is None:
        return "UNKNOWN"
    if not isinstance(value, str):
        return None
    normalized = value.lower()
    return {"left": "LEFT", "right": "RIGHT", "unknown": "UNKNOWN"}.get(normalized)
