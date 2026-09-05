"""Pure functions for frame-level hand-landmarker benchmark metrics.

The evaluator validates the input shape.  This module deliberately only works
with normalized image coordinates and never calls MediaPipe, a database, or a
media decoder.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from itertools import pairwise
from statistics import median
from typing import Any

LANDMARK_COUNT = 21
PCK_THRESHOLDS = (0.05, 0.10)


def _hands(frame: Mapping[str, Any], field: str) -> list[Mapping[str, Any]]:
    return list(frame[field])


def _points(hand: Mapping[str, Any]) -> list[Mapping[str, float]]:
    return list(hand["landmarks"])


def _distance(first: Mapping[str, float], second: Mapping[str, float]) -> float:
    return math.hypot(first["x"] - second["x"], first["y"] - second["y"])


def _scale(hand: Mapping[str, Any]) -> float:
    return float(hand["normalization_scale"])


def _hand_error(predicted: Mapping[str, Any], truth: Mapping[str, Any]) -> float:
    scale = _scale(truth)
    return sum(
        _distance(predicted_point, truth_point)
        for predicted_point, truth_point in zip(_points(predicted), _points(truth), strict=True)
    ) / (LANDMARK_COUNT * scale)


def match_hands(
    frame: Mapping[str, Any],
) -> tuple[list[tuple[Mapping[str, Any], Mapping[str, Any]]], int, int]:
    """Match predictions to reference hands deterministically.

    Explicit ``hand_id`` matches win; remaining hands use the smallest mean
    normalized landmark error.  The returned counts keep detection misses and
    extra detections separate from localization metrics.
    """

    truth_hands = _hands(frame, "ground_truth_hands")
    predicted_hands = _hands(frame, "predicted_hands")
    unused = set(range(len(predicted_hands)))
    matches: list[tuple[Mapping[str, Any], Mapping[str, Any]]] = []
    for truth in truth_hands:
        truth_id = truth["hand_id"]
        by_id = next(
            (index for index in unused if predicted_hands[index]["hand_id"] == truth_id),
            None,
        )
        candidate_indices = [by_id] if by_id is not None else sorted(unused)
        if not candidate_indices:
            continue
        selected = min(
            candidate_indices, key=lambda index: (_hand_error(predicted_hands[index], truth), index)
        )
        unused.remove(selected)
        matches.append((truth, predicted_hands[selected]))
    return matches, len(truth_hands) - len(matches), len(unused)


def _localization_errors(
    frames: Sequence[Mapping[str, Any]],
) -> tuple[list[float], list[list[float]], int, int, int]:
    errors: list[float] = []
    per_landmark: list[list[float]] = [[] for _ in range(LANDMARK_COUNT)]
    matched_hands = missed_hands = extra_hands = 0
    for frame in frames:
        matches, missed, extra = match_hands(frame)
        matched_hands += len(matches)
        missed_hands += missed
        extra_hands += extra
        for truth, predicted in matches:
            scale = _scale(truth)
            point_errors = [
                _distance(predicted_point, truth_point) / scale
                for predicted_point, truth_point in zip(_points(predicted), _points(truth), strict=True)
            ]
            errors.extend(point_errors)
            for index, error in enumerate(point_errors):
                per_landmark[index].append(error)
    return errors, per_landmark, matched_hands, missed_hands, extra_hands


def detection_metrics(frames: Sequence[Mapping[str, Any]]) -> dict[str, float | None]:
    """Return frame-level hand recall and no-hand false-positive rate."""

    present = [bool(_hands(frame, "ground_truth_hands")) for frame in frames]
    detected = [bool(_hands(frame, "predicted_hands")) for frame in frames]
    positive_count = sum(present)
    negative_count = len(frames) - positive_count
    return {
        "HAND_PRESENT_RECALL": (
            sum(
                present_value and detected_value
                for present_value, detected_value in zip(present, detected, strict=True)
            )
            / positive_count
            if positive_count
            else None
        ),
        "NO_HAND_FALSE_POSITIVE_RATE": (
            sum(
                not present_value and detected_value
                for present_value, detected_value in zip(present, detected, strict=True)
            )
            / negative_count
            if negative_count
            else None
        ),
    }


def localization_metrics(frames: Sequence[Mapping[str, Any]]) -> tuple[dict[str, Any], dict[str, int]]:
    """Return PCK, normalized errors, and per-landmark errors.

    Detection failures are excluded here; their counts remain in the second
    return value so they cannot be mistaken for localization error.
    """

    errors, per_landmark, matched_hands, missed_hands, extra_hands = _localization_errors(frames)
    metrics: dict[str, Any] = {
        "PCK@0.05": sum(error <= 0.05 for error in errors) / len(errors) if errors else None,
        "PCK@0.10": sum(error <= 0.10 for error in errors) / len(errors) if errors else None,
        "NORMALIZED_MEAN_ERROR": sum(errors) / len(errors) if errors else None,
        "MEDIAN_NORMALIZED_ERROR": median(errors) if errors else None,
        "PER_LANDMARK_ERROR": [sum(values) / len(values) if values else None for values in per_landmark],
    }
    return metrics, {
        "matched_hands": matched_hands,
        "missed_hands": missed_hands,
        "extra_predicted_hands": extra_hands,
        "landmark_points": len(errors),
    }


def handedness_accuracy(frames: Sequence[Mapping[str, Any]]) -> tuple[float | None, int]:
    """Compare only known reference LEFT/RIGHT labels on matched hands."""

    correct = compared = 0
    for frame in frames:
        for truth, predicted in match_hands(frame)[0]:
            if truth["handedness"] not in {"LEFT", "RIGHT"}:
                continue
            compared += 1
            correct += truth["handedness"] == predicted["handedness"]
    return (correct / compared if compared else None), compared


def temporal_jitter(frames: Sequence[Mapping[str, Any]]) -> tuple[float | None, int]:
    """Measure normalized prediction velocity error across adjacent frames.

    For a hand present in both adjacent frames, jitter is the mean 2-D distance
    between predicted and reference landmark displacement, normalized by the
    reference scale.  It therefore measures temporal instability separately
    from static landmark localization error.
    """

    frame_matches: list[dict[str, tuple[Mapping[str, Any], Mapping[str, Any]]]] = []
    for frame in frames:
        frame_matches.append(
            {truth["hand_id"]: (truth, predicted) for truth, predicted in match_hands(frame)[0]}
        )

    errors: list[float] = []
    pair_count = 0
    for previous, current in pairwise(frame_matches):
        shared_ids = sorted(previous.keys() & current.keys())
        for hand_id in shared_ids:
            previous_truth, previous_predicted = previous[hand_id]
            current_truth, current_predicted = current[hand_id]
            scale = (_scale(previous_truth) + _scale(current_truth)) / 2
            for (
                previous_truth_point,
                current_truth_point,
                previous_predicted_point,
                current_predicted_point,
            ) in zip(
                _points(previous_truth),
                _points(current_truth),
                _points(previous_predicted),
                _points(current_predicted),
                strict=True,
            ):
                truth_delta = {
                    "x": current_truth_point["x"] - previous_truth_point["x"],
                    "y": current_truth_point["y"] - previous_truth_point["y"],
                }
                predicted_delta = {
                    "x": current_predicted_point["x"] - previous_predicted_point["x"],
                    "y": current_predicted_point["y"] - previous_predicted_point["y"],
                }
                errors.append(_distance(predicted_delta, truth_delta) / scale)
            pair_count += 1
    return (sum(errors) / len(errors) if errors else None), pair_count


def calculate_metrics(frames: Sequence[Mapping[str, Any]]) -> tuple[dict[str, Any], dict[str, int]]:
    """Calculate all benchmark metrics and denominator/count evidence."""

    metrics = detection_metrics(frames)
    localization, localization_counts = localization_metrics(frames)
    metrics.update(localization)
    metrics["HANDEDNESS_ACCURACY"], handedness_count = handedness_accuracy(frames)
    metrics["TEMPORAL_JITTER"], temporal_pairs = temporal_jitter(frames)
    truth_frame_count = sum(bool(_hands(frame, "ground_truth_hands")) for frame in frames)
    no_hand_frame_count = len(frames) - truth_frame_count
    predicted_frame_count = sum(bool(_hands(frame, "predicted_hands")) for frame in frames)
    return metrics, {
        "frames": len(frames),
        "ground_truth_hand_frames": truth_frame_count,
        "no_hand_frames": no_hand_frame_count,
        "predicted_hand_frames": predicted_frame_count,
        "handedness_comparisons": handedness_count,
        "temporal_hand_pairs": temporal_pairs,
        **localization_counts,
    }
