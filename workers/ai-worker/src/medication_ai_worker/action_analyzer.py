from __future__ import annotations

import logging
import subprocess
from dataclasses import dataclass
from pathlib import Path

LOGGER = logging.getLogger(__name__)

# Mirror of the ACTIVE label schema (migration V77, schema_key
# ``medication-primary-step`` v2). Keep this list in the same order as
# ``label_definition.sort_order`` so the Worker's fallback matches the Core API
# fallback in ``ActionJobEnvelopeFactory``.
DEFAULT_LABELS = (
    "prepare_workspace",
    "retrieve_medicine",
    "prepare_dose",
    "final_review",
    "cleanup",
    "open_cap",
    "draw_liquid",
    "inject_mix",
    "check_info",
    "label_seal",
)

# The protocol order used to lay out the scaffold. It intentionally starts with the
# five primary steps and then the fine-grained action steps.
PROTOCOL_ORDER = DEFAULT_LABELS

# Fixed placeholder confidence. This slice does NOT run a vision-language model; the
# value is a constant marker so downstream review can recognise scaffold output. Replace
# it together with a real inference implementation and model provenance.
PLACEHOLDER_CONFIDENCE = 0.92

# Reported as ``prompt_version`` so the Core API and any reviewer can tell that the result
# came from the deterministic scaffold rather than a vision-language prompt.
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
        return max(1000, round(secs * 1000))
    except Exception as exc:
        raise RuntimeError(f"Failed to probe video duration for {video_path}: {exc}") from exc


def analyze_video_actions(
    video_path: Path,
    allowed_labels: tuple[str, ...],
) -> tuple[int, list[ActionCandidateSegment]]:
    """Return a protocol-aligned scaffold for the medication preparation video.

    PLACEHOLDER: this slice does not load or query a vision-language model. The video is
    never decoded; only its container duration is probed with ``ffprobe`` and the protocol
    steps are laid out evenly across it. Every segment therefore carries the fixed
    ``PLACEHOLDER_CONFIDENCE`` marker instead of a measured score.

    The output is written as ``CandidateAnnotation`` only. It must be presented to users as
    a draft to verify, never as verified model inference.
    """
    duration_ms = probe_duration_ms(video_path)
    labels = [lbl for lbl in allowed_labels if lbl] if allowed_labels else list(DEFAULT_LABELS)

    matched = [lbl for lbl in PROTOCOL_ORDER if lbl in labels]
    if not matched:
        matched = labels[: min(len(labels), 5)]

    step_count = max(1, min(len(matched), max(1, duration_ms // 10000)))
    active_steps = matched[:step_count]
    step_duration = duration_ms // len(active_steps)

    segments: list[ActionCandidateSegment] = []
    for i, label in enumerate(active_steps):
        start = i * step_duration
        end = duration_ms if i == len(active_steps) - 1 else (i + 1) * step_duration
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

    LOGGER.info(
        "Produced a %d-segment protocol scaffold (no model inference) for a %d ms clip",
        len(segments),
        duration_ms,
    )
    return duration_ms, segments
