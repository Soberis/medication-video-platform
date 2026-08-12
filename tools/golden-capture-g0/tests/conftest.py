from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pytest

from golden_capture_g0.media import generate_capture_media, probe_capture_media
from golden_capture_g0.model import CaptureSpec, ProbedStream, load_capture_specs


@dataclass(frozen=True, slots=True)
class GoldenData:
    captures: dict[str, CaptureSpec]
    streams: dict[str, dict[int, ProbedStream]]


@pytest.fixture(scope="session")
def golden_data(tmp_path_factory: pytest.TempPathFactory) -> GoldenData:
    tool_dir = Path(__file__).resolve().parents[1]
    captures = load_capture_specs(tool_dir / "fixtures" / "golden-captures.json")
    media_dir = tmp_path_factory.mktemp("golden-capture-media")
    streams: dict[str, dict[int, ProbedStream]] = {}
    for capture in captures:
        paths = generate_capture_media(capture, media_dir)
        streams[capture.name] = probe_capture_media(capture, paths)
    return GoldenData({capture.name: capture for capture in captures}, streams)
