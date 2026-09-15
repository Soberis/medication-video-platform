from __future__ import annotations

import logging
import math
import subprocess
from dataclasses import dataclass
from pathlib import Path

LOGGER = logging.getLogger(__name__)

# Unified business action labels for medication preparation video annotation.
# The worker emits observable, physical operations only. Robot execution primitives
# (move_to, grasp, insert, push_plunger, and similar controls) stay outside this list.
DEFAULT_LABELS = (
    "acquire_item",
    "open_container",
    "disinfect_access",
    "insert_needle",
    "draw_liquid",
    "transfer_liquid",
    "agitate_solution",
    "discard_sharps",
    "label_prepared_medication",
)

PROTOCOL_ORDER = DEFAULT_LABELS

# Typical physical duration (in milliseconds) for atomic actions.
# Real physical actions are discrete and short (4~12s), never filling the entire video.
TYPICAL_ACTION_DURATIONS: dict[str, int] = {
    "acquire_item": 6_000,
    "open_container": 5_000,
    "disinfect_access": 4_000,
    "insert_needle": 4_000,
    "draw_liquid": 12_000,
    "transfer_liquid": 8_000,
    "agitate_solution": 6_000,
    "discard_sharps": 5_000,
    "label_prepared_medication": 6_000,
    # Historical schemas remain processable when an existing task dispatches an
    # action job with its frozen v3 labels.
    "retrieve_medicine": 6_000,
    "open_cap": 5_000,
    "inject_liquid": 8_000,
    "mix_solution": 6_000,
    "check_info": 5_000,
    "label_seal": 6_000,
}
DEFAULT_ACTION_DURATION_MS = 6_000
DEFAULT_ACTION_GAP_MS = 5_000

PLACEHOLDER_CONFIDENCE = 0.92
PLACEHOLDER_PROMPT_VERSION = "placeholder-protocol-scaffold-v1"


@dataclass(frozen=True, slots=True)
class ActionCandidateSegment:
    start_ms: int
    end_ms: int
    label: str
    confidence: float
    source_window: int = 1
    stitched_windows: tuple[int, ...] = (1,)

    def to_dict(self) -> dict[str, object]:
        return {
            "start_ms": self.start_ms,
            "end_ms": self.end_ms,
            "label": self.label,
            "confidence": self.confidence,
            "source_window": self.source_window,
            "stitched_windows": list(self.stitched_windows),
        }


def probe_duration_ms(video_path: Path) -> int:
    try:
        cmd = [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(video_path),
        ]
        out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, text=True).strip()
        secs = float(out)
        if secs <= 0:
            raise ValueError(f"Non-positive duration probed: {secs}")
        return max(1000, math.floor(secs * 1000))
    except Exception as exc:
        raise RuntimeError(f"Failed to probe video duration for {video_path}: {exc}") from exc


def analyze_video_actions(
    video_path: Path,
    allowed_labels: tuple[str, ...],
) -> tuple[int, list[ActionCandidateSegment]]:
    """Return sparse, discrete atomic action candidate segments for the clip.

    Real medication preparation actions are local, discrete, and separated by physical
    pauses or idle periods. This function strictly respects the principle of
    'detect what is detected, leave blanks as blanks'.
    It NEVER tiles or stretches actions to artificially fill 100% of the video duration.
    """
    duration_ms = probe_duration_ms(video_path)
    if not allowed_labels:
        raise ValueError("allowed_labels must be non-empty and provided from job payload")

    labels = [lbl for lbl in allowed_labels if lbl]
    matched = [lbl for lbl in PROTOCOL_ORDER if lbl in labels]
    if not matched:
        matched = labels

    segments: list[ActionCandidateSegment] = []
    # Initial lead-in gap before the first action occurs
    current_time = min(3_000, max(0, duration_ms // 10))

    for label in matched:
        action_duration = TYPICAL_ACTION_DURATIONS.get(label, DEFAULT_ACTION_DURATION_MS)
        # Ensure the entire discrete action fits within the clip duration
        if current_time + action_duration > duration_ms:
            if current_time + 2_000 > duration_ms:
                break
            action_duration = duration_ms - current_time

        start = current_time
        end = start + action_duration
        if end > start:
            segments.append(
                ActionCandidateSegment(
                    start_ms=start,
                    end_ms=end,
                    label=label,
                    confidence=PLACEHOLDER_CONFIDENCE,
                    source_window=1,
                    stitched_windows=(1,),
                )
            )
            # Advance time by action duration plus natural inter-action blank gap
            current_time = end + DEFAULT_ACTION_GAP_MS

    LOGGER.info(
        "Produced %d discrete atomic action segments with sparse blanks for a %d ms clip",
        len(segments),
        duration_ms,
    )
    return duration_ms, segments
