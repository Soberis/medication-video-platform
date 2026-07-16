from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

from medication_media_worker.probe import MediaProber


@pytest.mark.skipif(
    shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
    reason="FFmpeg is not installed",
)
def test_probes_and_fully_decodes_synthetic_video(tmp_path: Path) -> None:
    video = tmp_path / "synthetic.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=64x48:rate=10:duration=1",
            "-an",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-y",
            str(video),
        ],
        check=True,
        timeout=30,
    )

    report = MediaProber("ffprobe", "ffmpeg", 30).probe(video)

    assert report["container_format"] == "mov"
    assert report["duration_microseconds"] == 1_000_000
    assert report["video_stream_count"] == 1
    assert report["width"] == 64
    assert report["height"] == 48
    assert report["frame_count"] == 10
    assert report["decode_verified"] is True
    assert report["input_digest_verified"] is True
