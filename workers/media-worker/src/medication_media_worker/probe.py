from __future__ import annotations

import json
import subprocess
import threading
import time
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from fractions import Fraction
from pathlib import Path
from typing import Any, Final

WARNING_ORDER: Final = (
    "PTS_MISSING",
    "PTS_NON_MONOTONIC",
    "FRAME_COUNT_UNAVAILABLE",
    "VFR_DETECTED",
    "AUDIO_PRESENT",
)


class ProbeFailure(RuntimeError):
    def __init__(
        self,
        error_class: str,
        error_code: str,
        safe_message: str,
        *,
        retryable: bool,
    ) -> None:
        super().__init__(safe_message)
        self.error_class = error_class
        self.error_code = error_code
        self.safe_message = safe_message
        self.retryable = retryable

    def as_contract(self) -> dict[str, object]:
        return {
            "error_class": self.error_class,
            "error_code": self.error_code,
            "safe_message": self.safe_message,
            "retryable": self.retryable,
        }


@dataclass(frozen=True, slots=True)
class TimelineFacts:
    frame_count: int
    first_pts: int | None
    last_pts: int | None
    pts_missing: bool
    pts_non_monotonic: bool
    variable_intervals: bool


class MediaProber:
    def __init__(self, ffprobe_path: str, ffmpeg_path: str, timeout_seconds: int) -> None:
        self._ffprobe_path = ffprobe_path
        self._ffmpeg_path = ffmpeg_path
        self._timeout_seconds = timeout_seconds

    def probe(self, input_path: Path) -> dict[str, object]:
        metadata = self._read_metadata(input_path)
        streams = metadata.get("streams")
        media_format = metadata.get("format")
        if not isinstance(streams, list) or not isinstance(media_format, dict):
            raise _input_failure(
                "MEDIA_CONTAINER_UNSUPPORTED", "Media container metadata is invalid"
            )
        video_streams = [stream for stream in streams if _is_stream_type(stream, "video")]
        audio_streams = [stream for stream in streams if _is_stream_type(stream, "audio")]
        if not video_streams:
            raise _input_failure("MEDIA_VIDEO_STREAM_MISSING", "Media input has no video stream")
        primary = video_streams[0]
        timeline = self._read_timeline(input_path)
        self._verify_full_decode(input_path)
        try:
            time_base = _required_fraction(primary.get("time_base"), "time_base")
            nominal_rate = _optional_fraction(primary.get("r_frame_rate"))
            average_rate = _optional_fraction(primary.get("avg_frame_rate"))
            sample_aspect_ratio = _optional_fraction(
                primary.get("sample_aspect_ratio"), separator=":"
            )
            width = _positive_integer(primary.get("width"), "width")
            height = _positive_integer(primary.get("height"), "height")
            primary_index = _non_negative_integer(primary.get("index"), "stream index")
            codec_name = _required_string(primary.get("codec_name"), "video codec")
            container_format = _container_format(media_format.get("format_name"))
            duration = _seconds_to_microseconds(media_format.get("duration"), required=True)
        except (TypeError, ValueError) as exc:
            raise _input_failure(
                "MEDIA_TIMELINE_INVALID", "Required media facts are invalid"
            ) from exc

        variable_frame_rate = timeline.variable_intervals or (
            nominal_rate is not None and average_rate is not None and nominal_rate != average_rate
        )
        warnings: set[str] = set()
        if timeline.pts_missing:
            warnings.add("PTS_MISSING")
        if timeline.pts_non_monotonic:
            warnings.add("PTS_NON_MONOTONIC")
        if timeline.frame_count == 0:
            warnings.add("FRAME_COUNT_UNAVAILABLE")
        if variable_frame_rate:
            warnings.add("VFR_DETECTED")
        if audio_streams:
            warnings.add("AUDIO_PRESENT")

        return {
            "container_format": container_format,
            "duration_microseconds": duration,
            "container_start_time_microseconds": _seconds_to_microseconds(
                media_format.get("start_time"), required=False
            ),
            "video_stream_count": len(video_streams),
            "primary_video_stream_index": primary_index,
            "video_codec": codec_name,
            "video_profile": _optional_limited_string(primary.get("profile")),
            "width": width,
            "height": height,
            "pixel_format": _optional_limited_string(primary.get("pix_fmt")),
            "color_space": _optional_limited_string(primary.get("color_space")),
            "primary_video_bitrate_bps": _optional_non_negative_integer(primary.get("bit_rate")),
            "has_b_frames": _optional_b_frame_flag(primary.get("has_b_frames")),
            "stream_start_time_microseconds": _seconds_to_microseconds(
                primary.get("start_time"), required=False
            ),
            "time_base_numerator": time_base.numerator,
            "time_base_denominator": time_base.denominator,
            "first_pts": timeline.first_pts,
            "last_pts": timeline.last_pts,
            "nominal_frame_rate_numerator": _fraction_numerator(nominal_rate),
            "nominal_frame_rate_denominator": _fraction_denominator(nominal_rate),
            "average_frame_rate_numerator": _fraction_numerator(average_rate),
            "average_frame_rate_denominator": _fraction_denominator(average_rate),
            "frame_count": timeline.frame_count if timeline.frame_count > 0 else None,
            "variable_frame_rate": variable_frame_rate,
            "audio_stream_count": len(audio_streams),
            "sample_aspect_ratio_numerator": _fraction_numerator(sample_aspect_ratio),
            "sample_aspect_ratio_denominator": _fraction_denominator(sample_aspect_ratio),
            "decode_verified": True,
            "input_digest_verified": True,
            "warning_codes": [code for code in WARNING_ORDER if code in warnings],
        }

    def _read_metadata(self, input_path: Path) -> dict[str, Any]:
        completed = _run_bounded(
            [
                self._ffprobe_path,
                "-v",
                "error",
                "-show_format",
                "-show_streams",
                "-of",
                "json",
                str(input_path),
            ],
            timeout_seconds=self._timeout_seconds,
        )
        if completed.returncode != 0:
            raise _input_failure("MEDIA_CONTAINER_UNSUPPORTED", "Media container could not be read")
        try:
            value = json.loads(completed.stdout)
        except json.JSONDecodeError as exc:
            raise _input_failure(
                "MEDIA_CONTAINER_UNSUPPORTED", "Media metadata is invalid"
            ) from exc
        if not isinstance(value, dict):
            raise _input_failure("MEDIA_CONTAINER_UNSUPPORTED", "Media metadata is invalid")
        return value

    def _read_timeline(self, input_path: Path) -> TimelineFacts:
        command = [
            self._ffprobe_path,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=pts",
            "-of",
            "csv=p=0",
            str(input_path),
        ]
        started = time.monotonic()
        try:
            process = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except OSError as exc:
            raise _internal_failure("FFprobe executable is unavailable") from exc
        timer = threading.Timer(self._timeout_seconds, process.kill)
        timer.start()
        frame_count = 0
        first_pts: int | None = None
        last_pts: int | None = None
        previous_pts: int | None = None
        previous_delta: int | None = None
        pts_missing = False
        pts_non_monotonic = False
        variable_intervals = False
        try:
            if process.stdout is None:
                raise _internal_failure("FFprobe timeline output is unavailable")
            for line in process.stdout:
                frame_count += 1
                candidate = line.strip()
                if candidate in {"", "N/A"}:
                    pts_missing = True
                    continue
                try:
                    pts = int(candidate.split(",", maxsplit=1)[0])
                except ValueError:
                    pts_missing = True
                    continue
                if first_pts is None:
                    first_pts = pts
                if previous_pts is not None:
                    delta = pts - previous_pts
                    if delta <= 0:
                        pts_non_monotonic = True
                    if previous_delta is not None and delta != previous_delta:
                        variable_intervals = True
                    previous_delta = delta
                previous_pts = pts
                last_pts = pts
            return_code = process.wait()
        finally:
            timer.cancel()
            if process.stdout is not None:
                process.stdout.close()
        if time.monotonic() - started >= self._timeout_seconds:
            raise _timeout_failure()
        if return_code != 0:
            raise _input_failure("MEDIA_TIMELINE_INVALID", "Video timeline could not be read")
        return TimelineFacts(
            frame_count=frame_count,
            first_pts=first_pts,
            last_pts=last_pts,
            pts_missing=pts_missing,
            pts_non_monotonic=pts_non_monotonic,
            variable_intervals=variable_intervals,
        )

    def _verify_full_decode(self, input_path: Path) -> None:
        completed = _run_bounded(
            [
                self._ffmpeg_path,
                "-v",
                "error",
                "-nostdin",
                "-i",
                str(input_path),
                "-map",
                "0:v:0",
                "-f",
                "null",
                "-",
            ],
            timeout_seconds=self._timeout_seconds,
        )
        if completed.returncode != 0:
            raise _input_failure(
                "MEDIA_DECODE_FAILED", "Video stream failed full decode verification"
            )


def _run_bounded(command: list[str], timeout_seconds: int) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise _timeout_failure() from exc
    except OSError as exc:
        raise _internal_failure("FFmpeg executable is unavailable") from exc


def _is_stream_type(value: object, codec_type: str) -> bool:
    return isinstance(value, dict) and value.get("codec_type") == codec_type


def _required_fraction(value: object, label: str, separator: str = "/") -> Fraction:
    fraction = _optional_fraction(value, separator)
    if fraction is None or fraction <= 0:
        raise ValueError(f"{label} is required")
    return fraction


def _optional_fraction(value: object, separator: str = "/") -> Fraction | None:
    if not isinstance(value, str) or value in {"", "N/A", "0/0", "0:0"}:
        return None
    parts = value.split(separator)
    if len(parts) != 2:
        return None
    try:
        numerator = int(parts[0])
        denominator = int(parts[1])
    except ValueError:
        return None
    if numerator < 0 or denominator <= 0:
        return None
    return Fraction(numerator, denominator)


def _seconds_to_microseconds(value: object, *, required: bool) -> int | None:
    if value in {None, "", "N/A"}:
        if required:
            raise ValueError("duration is required")
        return None
    try:
        microseconds = (Decimal(str(value)) * Decimal(1_000_000)).quantize(
            Decimal(1), rounding=ROUND_HALF_UP
        )
    except InvalidOperation as exc:
        raise ValueError("invalid seconds value") from exc
    result = int(microseconds)
    if required and result < 0:
        raise ValueError("duration must not be negative")
    return result


def _positive_integer(value: object, label: str) -> int:
    parsed = _integer(value, label)
    if parsed < 1:
        raise ValueError(f"{label} must be positive")
    return parsed


def _non_negative_integer(value: object, label: str) -> int:
    parsed = _integer(value, label)
    if parsed < 0:
        raise ValueError(f"{label} must not be negative")
    return parsed


def _integer(value: object, label: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    try:
        return int(str(value))
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} must be an integer") from exc


def _optional_non_negative_integer(value: object) -> int | None:
    if value in {None, "", "N/A"}:
        return None
    parsed = _integer(value, "optional integer")
    return parsed if parsed >= 0 else None


def _required_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 64:
        raise ValueError(f"{label} is required")
    return value


def _optional_limited_string(value: object) -> str | None:
    return value if isinstance(value, str) and 0 < len(value) <= 64 else None


def _container_format(value: object) -> str:
    candidate = _required_string(value, "container format").split(",", maxsplit=1)[0]
    return candidate[:64]


def _optional_b_frame_flag(value: object) -> bool | None:
    if value in {None, "", "N/A"}:
        return None
    return _non_negative_integer(value, "has_b_frames") > 0


def _fraction_numerator(value: Fraction | None) -> int | None:
    return value.numerator if value is not None else None


def _fraction_denominator(value: Fraction | None) -> int | None:
    return value.denominator if value is not None else None


def _input_failure(error_code: str, message: str) -> ProbeFailure:
    return ProbeFailure("NON_RETRYABLE_INPUT", error_code, message, retryable=False)


def _timeout_failure() -> ProbeFailure:
    return ProbeFailure(
        "RETRYABLE_RESOURCE",
        "MEDIA_PROBE_TIMEOUT",
        "Media probe exceeded its approved execution time",
        retryable=True,
    )


def _internal_failure(message: str) -> ProbeFailure:
    return ProbeFailure("INTERNAL_BUG", "MEDIA_PROBE_INTERNAL", message, retryable=True)
