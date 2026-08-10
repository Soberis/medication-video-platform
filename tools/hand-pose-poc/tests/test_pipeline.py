from __future__ import annotations

import json
from fractions import Fraction
from pathlib import Path
from typing import Any

import av
import numpy as np

from hand_pose_poc.models import HandObservation
from hand_pose_poc.pipeline import PipelineConfig, process_video

from .helpers import hand


class SyntheticDetector:
    def detect(
        self, frame_rgb: np.ndarray[Any, np.dtype[np.uint8]], timestamp_ms: int
    ) -> tuple[HandObservation, ...]:
        assert frame_rgb.shape == (64, 96, 3)
        return () if timestamp_ms == 0 else (hand(),)


class RecordingDetector:
    def __init__(self) -> None:
        self.timestamps: list[int] = []

    def detect(
        self, frame_rgb: np.ndarray[Any, np.dtype[np.uint8]], timestamp_ms: int
    ) -> tuple[HandObservation, ...]:
        self.timestamps.append(timestamp_ms)
        return (hand(),)


def test_pipeline_writes_overlay_and_one_json_record_per_decoded_frame(tmp_path: Path) -> None:
    source = tmp_path / "synthetic.mp4"
    output = tmp_path / "output"
    _write_video(source)

    video, statistics = process_video(
        source, output, SyntheticDetector(), PipelineConfig(None, False)
    )

    records = [
        json.loads(line) for line in (output / "hand_landmarks.jsonl").read_text().splitlines()
    ]
    assert (output / "hand_pose_overlay.mp4").stat().st_size > 0
    with av.open(str(output / "hand_pose_overlay.mp4"), mode="r") as overlay:
        overlay_stream = overlay.streams.video[0]
        overlay_timestamps = [
            round(frame.pts * frame.time_base * 1000)
            for frame in overlay.decode(overlay_stream)
            if frame.pts is not None and frame.time_base is not None
        ]
    assert overlay_timestamps == [0, 100, 200]
    assert len(records) == 3
    assert [record["timestamp_ms"] for record in records] == [0, 100, 200]
    assert records[0]["hands"] == []
    assert len(records[1]["hands"][0]["landmarks"]) == 21
    assert video.width == 96
    assert video.height == 64
    assert video.last_frame_timestamp_ms == 200
    assert video.decoded_frames == 3
    assert video.audio_preserved is False
    assert statistics.decoded_frames == 3
    assert statistics.inference_frames == 3


def test_sampled_frames_are_explicitly_not_inferred_not_false_no_hand(tmp_path: Path) -> None:
    source = tmp_path / "synthetic.mp4"
    output = tmp_path / "output"
    detector = RecordingDetector()
    _write_video(source)

    _, statistics = process_video(source, output, detector, PipelineConfig(5, False))

    records = [
        json.loads(line) for line in (output / "hand_landmarks.jsonl").read_text().splitlines()
    ]
    assert detector.timestamps == [0, 200]
    assert [record["inference_performed"] for record in records] == [True, False, True]
    assert records[1]["hands"] == []
    assert statistics.decoded_frames == 3
    assert statistics.inference_frames == 2


def _write_video(path: Path) -> None:
    with av.open(str(path), mode="w") as container:
        stream = container.add_stream("mpeg4", rate=10)
        stream.width = 96
        stream.height = 64
        stream.pix_fmt = "yuv420p"
        for index in range(3):
            pixels = np.full((64, 96, 3), index * 20, dtype=np.uint8)
            frame = av.VideoFrame.from_ndarray(pixels, format="rgb24")
            frame.pts = index
            frame.time_base = Fraction(1, 10)
            for packet in stream.encode(frame):
                container.mux(packet)
        for packet in stream.encode():
            container.mux(packet)
