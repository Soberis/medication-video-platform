from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction


class TimestampError(ValueError):
    """Raised when a video cannot provide a safe VIDEO-mode timestamp."""


@dataclass(frozen=True)
class VideoTimestamp:
    source_timestamp_ms: int
    timestamp_ms: int
    source: str


class TimestampNormalizer:
    def __init__(self, nominal_fps: Fraction, allow_fallback: bool = False) -> None:
        if nominal_fps <= 0:
            raise TimestampError("The nominal video FPS must be positive.")
        self._nominal_fps = nominal_fps
        self._allow_fallback = allow_fallback
        self._origin_ms: int | None = None
        self._previous_ms: int | None = None
        self._timeline_source: str | None = None
        self.fallback_frames = 0

    def next(self, frame_index: int, pts: int | None, time_base: Fraction | None) -> VideoTimestamp:
        if pts is not None and time_base is not None:
            source_ms = _round_fraction(Fraction(pts) * time_base * 1000)
            source = "container_pts"
        elif self._allow_fallback:
            source_ms = _round_fraction(Fraction(frame_index * 1000, 1) / self._nominal_fps)
            source = "nominal_fps_fallback"
            self.fallback_frames += 1
        else:
            raise TimestampError(
                "A decoded frame has no container PTS. Re-run with "
                "--allow-fps-timestamp-fallback only after reviewing the source video."
            )

        if self._timeline_source is None:
            self._timeline_source = source
        elif source != self._timeline_source:
            raise TimestampError(
                "A video timestamp timeline cannot mix container PTS with nominal-FPS fallback; "
                f"frame={frame_index}, active={self._timeline_source}, received={source}."
            )

        if self._origin_ms is None:
            self._origin_ms = source_ms
        timestamp_ms = source_ms - self._origin_ms
        if timestamp_ms < 0 or (
            self._previous_ms is not None and timestamp_ms <= self._previous_ms
        ):
            raise TimestampError(
                "Frame timestamps must be strictly increasing after millisecond conversion; "
                f"frame={frame_index}, previous={self._previous_ms}, current={timestamp_ms}."
            )
        self._previous_ms = timestamp_ms
        return VideoTimestamp(source_ms, timestamp_ms, source)


class SampleGate:
    def __init__(self, sample_fps: int | None) -> None:
        if sample_fps is not None and not 1 <= sample_fps <= 15:
            raise ValueError("sample_fps must be between 1 and 15.")
        self._interval_ms = None if sample_fps is None else Fraction(1000, sample_fps)
        self._next_due_ms = Fraction(0)

    def should_infer(self, timestamp_ms: int) -> bool:
        if self._interval_ms is None:
            return True
        if Fraction(timestamp_ms) < self._next_due_ms:
            return False
        while self._next_due_ms <= timestamp_ms:
            self._next_due_ms += self._interval_ms
        return True


def _round_fraction(value: Fraction) -> int:
    return round(value)
