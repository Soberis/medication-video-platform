from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from fractions import Fraction
from pathlib import Path
from typing import Any, Protocol

import av
import numpy as np
from av.video.codeccontext import VideoCodecContext

from .models import FrameObservation, HandObservation
from .renderer import render_overlay
from .timestamps import SampleGate, TimestampNormalizer

LOGGER = logging.getLogger(__name__)


class HandDetector(Protocol):
    def detect(
        self, frame_rgb: np.ndarray[Any, np.dtype[np.uint8]], timestamp_ms: int
    ) -> tuple[HandObservation, ...]: ...


@dataclass(frozen=True)
class PipelineConfig:
    sample_fps: int | None
    allow_timestamp_fallback: bool
    progress_interval: int = 300


@dataclass
class RunStatistics:
    decoded_frames: int = 0
    inference_frames: int = 0
    frames_with_no_hand: int = 0
    frames_with_one_hand: int = 0
    frames_with_two_hands: int = 0
    fallback_timestamp_frames: int = 0
    last_timestamp_ms: int = 0


@dataclass(frozen=True)
class VideoFacts:
    width: int
    height: int
    nominal_fps: float
    codec: str
    duration_ms: int
    decoded_frames: int
    audio_preserved: bool = False


def process_video(
    input_path: Path,
    output_dir: Path,
    detector: HandDetector,
    config: PipelineConfig,
) -> tuple[VideoFacts, RunStatistics]:
    if config.progress_interval <= 0:
        raise ValueError("progress_interval must be positive.")
    output_dir.mkdir(parents=True, exist_ok=True)
    overlay_path = output_dir / "hand_pose_overlay.mp4"
    jsonl_path = output_dir / "hand_landmarks.jsonl"
    overlay_partial = output_dir / "hand_pose_overlay.mp4.partial"
    jsonl_partial = output_dir / "hand_landmarks.jsonl.partial"

    with av.open(str(input_path), mode="r") as input_container:
        video_stream = next(iter(input_container.streams.video), None)
        if video_stream is None:
            raise ValueError("The input container has no video stream.")
        nominal_rate = video_stream.average_rate
        if nominal_rate is None or nominal_rate <= 0:
            raise ValueError("The input video has no positive nominal frame rate.")
        nominal_fps = Fraction(nominal_rate)
        codec_context = video_stream.codec_context
        if not isinstance(codec_context, VideoCodecContext):
            raise ValueError("The input stream does not have a video codec context.")
        width = codec_context.width
        height = codec_context.height
        if width <= 0 or height <= 0:
            raise ValueError("The input video has invalid dimensions.")

        normalizer = TimestampNormalizer(nominal_fps, config.allow_timestamp_fallback)
        sample_gate = SampleGate(config.sample_fps)
        statistics = RunStatistics()

        with (
            av.open(str(overlay_partial), mode="w", format="mp4") as output_container,
            jsonl_partial.open("w", encoding="utf-8", newline="\n") as jsonl,
        ):
            output_stream = output_container.add_stream("libx264", rate=nominal_rate)
            output_stream.width = width
            output_stream.height = height
            output_stream.pix_fmt = "yuv420p"

            for frame_index, frame in enumerate(input_container.decode(video_stream)):
                if not isinstance(frame, av.VideoFrame):
                    raise ValueError("The selected video stream decoded a non-video frame.")
                timestamp = normalizer.next(frame_index, frame.pts, frame.time_base)
                frame_rgb = np.asarray(frame.to_ndarray(format="rgb24"), dtype=np.uint8)
                should_infer = sample_gate.should_infer(timestamp.timestamp_ms)
                hands = detector.detect(frame_rgb, timestamp.timestamp_ms) if should_infer else ()
                observation = FrameObservation(
                    frame_index=frame_index,
                    source_timestamp_ms=timestamp.source_timestamp_ms,
                    timestamp_ms=timestamp.timestamp_ms,
                    timestamp_source=timestamp.source,
                    inference_performed=should_infer,
                    image_width=width,
                    image_height=height,
                    hands=hands,
                )
                jsonl.write(
                    json.dumps(observation.to_dict(), ensure_ascii=False, separators=(",", ":"))
                    + "\n"
                )
                rendered = render_overlay(frame_rgb, hands) if should_infer else frame_rgb
                output_frame = av.VideoFrame.from_ndarray(rendered, format="rgb24")
                output_frame.pts = frame.pts
                output_frame.time_base = frame.time_base
                for packet in output_stream.encode(output_frame):
                    output_container.mux(packet)
                _record(statistics, should_infer, len(hands), timestamp.timestamp_ms)
                if statistics.decoded_frames % config.progress_interval == 0:
                    LOGGER.info("Processed %d frames", statistics.decoded_frames)

            for packet in output_stream.encode():
                output_container.mux(packet)

        statistics.fallback_timestamp_frames = normalizer.fallback_frames
        codec = video_stream.codec_context.name or "unknown"
        overlay_partial.replace(overlay_path)
        jsonl_partial.replace(jsonl_path)
        return VideoFacts(
            width,
            height,
            float(nominal_fps),
            codec,
            statistics.last_timestamp_ms,
            statistics.decoded_frames,
        ), statistics


def statistics_dict(statistics: RunStatistics) -> dict[str, Any]:
    return asdict(statistics)


def _record(statistics: RunStatistics, inferred: bool, hand_count: int, timestamp_ms: int) -> None:
    statistics.decoded_frames += 1
    statistics.last_timestamp_ms = timestamp_ms
    if not inferred:
        return
    statistics.inference_frames += 1
    if hand_count == 0:
        statistics.frames_with_no_hand += 1
    elif hand_count == 1:
        statistics.frames_with_one_hand += 1
    else:
        statistics.frames_with_two_hands += 1
