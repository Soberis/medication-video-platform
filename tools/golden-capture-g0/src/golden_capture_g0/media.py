from __future__ import annotations

import hashlib
import json
import subprocess
from fractions import Fraction
from pathlib import Path
from typing import Any, cast

from golden_capture_g0.model import CaptureSpec, ProbedStream, SourceFrame, StreamSpec
from golden_capture_g0.rational import round_half_even

NANOSECONDS_PER_SECOND = 1_000_000_000


def generate_capture_media(
    capture: CaptureSpec,
    output_dir: Path,
    *,
    ffmpeg_path: str = "ffmpeg",
) -> dict[int, Path]:
    case_dir = output_dir / capture.name
    case_dir.mkdir(parents=True, exist_ok=True)
    result: dict[int, Path] = {}
    for stream in capture.streams:
        path = case_dir / stream.file_name
        _generate_stream(stream, capture.duration_seconds, path, ffmpeg_path)
        result[stream.camera_number] = path
    return result


def probe_capture_media(
    capture: CaptureSpec,
    paths: dict[int, Path],
    *,
    ffprobe_path: str = "ffprobe",
) -> dict[int, ProbedStream]:
    return {
        stream.camera_number: probe_stream(stream, paths[stream.camera_number], ffprobe_path)
        for stream in capture.streams
    }


def probe_stream(spec: StreamSpec, path: Path, ffprobe_path: str = "ffprobe") -> ProbedStream:
    metadata = _run_json(
        [
            ffprobe_path,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_streams",
            "-of",
            "json",
            str(path),
        ]
    )
    stream_values = cast(list[dict[str, Any]], metadata.get("streams", []))
    if len(stream_values) != 1:
        raise RuntimeError(f"expected one video stream in {path}")
    stream_metadata = stream_values[0]
    time_base = _fraction(stream_metadata.get("time_base"), required=True)
    if time_base is None:
        raise RuntimeError(f"missing time base in {path}")
    frames_payload = _run_json(
        [
            ffprobe_path,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=pts",
            "-of",
            "json",
            str(path),
        ]
    )
    frame_values = cast(list[dict[str, Any]], frames_payload.get("frames", []))
    if not frame_values:
        raise RuntimeError(f"no video frames found in {path}")
    raw_pts_values = [_required_int(frame.get("pts"), "frame PTS") for frame in frame_values]
    pts_origin = raw_pts_values[0]
    ordinal_by_pts: dict[int, int] = {}
    frames: list[SourceFrame] = []
    for index, raw_pts in enumerate(raw_pts_values):
        ordinal = ordinal_by_pts.get(raw_pts, 0)
        ordinal_by_pts[raw_pts] = ordinal + 1
        source_pts_ns = round_half_even(
            Fraction(raw_pts - pts_origin) * time_base * NANOSECONDS_PER_SECOND
        )
        invalid_reason = (
            "SYNTHETIC_DECODE_VALIDATION_FAILED"
            if index in spec.invalid_source_frame_indices
            else None
        )
        frames.append(SourceFrame(index, raw_pts, source_pts_ns, ordinal, invalid_reason))
    return ProbedStream(
        spec=spec,
        path=path,
        size_bytes=path.stat().st_size,
        sha256=sha256_file(path),
        time_base=time_base,
        pts_origin=pts_origin,
        nominal_rate=_fraction(stream_metadata.get("r_frame_rate"), required=False),
        average_rate=_fraction(stream_metadata.get("avg_frame_rate"), required=False),
        frames=tuple(frames),
    )


def verify_full_decode(path: Path, ffmpeg_path: str = "ffmpeg") -> None:
    _run(
        [
            ffmpeg_path,
            "-v",
            "error",
            "-nostdin",
            "-i",
            str(path),
            "-map",
            "0:v:0",
            "-f",
            "null",
            "-",
        ]
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _generate_stream(
    stream: StreamSpec,
    duration_seconds: Fraction,
    output_path: Path,
    ffmpeg_path: str,
) -> None:
    rate = _fraction_text(stream.source_rate)
    duration = _decimal_text(duration_seconds)
    offset = _decimal_text(stream.pts_offset_seconds)
    filters: list[str] = []
    if stream.pattern == "GAP":
        filters.append(r"select=not(between(n\,30\,34))")
    elif stream.pattern == "VFR":
        filters.append(r"select=not(eq(mod(n\,5)\,0))")
    if stream.pts_offset_seconds:
        filters.append(f"setpts=PTS+{offset}/TB")
    filters.append("format=yuv420p")
    command = [
        ffmpeg_path,
        "-v",
        "error",
        "-nostdin",
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"testsrc2=size=160x96:rate={rate}:duration={duration}",
        "-vf",
        ",".join(filters),
        "-fps_mode",
        "vfr",
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "ultrafast",
        "-crf",
        "30",
        "-pix_fmt",
        "yuv420p",
        "-threads",
        "1",
        "-g",
        "250",
        "-video_track_timescale",
        "90000",
        "-map_metadata",
        "-1",
        "-metadata",
        "creation_time=2026-08-12T00:00:00Z",
        "-fflags",
        "+bitexact",
        str(output_path),
    ]
    _run(command)


def _run_json(command: list[str]) -> dict[str, Any]:
    completed = _run(command, capture_output=True)
    value = json.loads(completed.stdout)
    if not isinstance(value, dict):
        raise RuntimeError("media command returned a non-object JSON value")
    return cast(dict[str, Any], value)


def _run(command: list[str], *, capture_output: bool = False) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        command,
        capture_output=capture_output,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        safe_error = completed.stderr.strip().splitlines()[-1:] if completed.stderr else []
        raise RuntimeError(f"media command failed ({completed.returncode}): {safe_error}")
    return completed


def _fraction(value: object, *, required: bool) -> Fraction | None:
    if not isinstance(value, str) or value in {"", "N/A", "0/0"}:
        if required:
            raise RuntimeError("required rational media value is missing")
        return None
    try:
        result = Fraction(value)
    except (ValueError, ZeroDivisionError) as exc:
        if required:
            raise RuntimeError("required rational media value is invalid") from exc
        return None
    return result if result >= 0 else None


def _required_int(value: object, label: str) -> int:
    try:
        return int(str(value))
    except (TypeError, ValueError) as exc:
        raise RuntimeError(f"{label} is missing or invalid") from exc


def _fraction_text(value: Fraction) -> str:
    return f"{value.numerator}/{value.denominator}"


def _decimal_text(value: Fraction) -> str:
    return format(float(value), ".9f").rstrip("0").rstrip(".") or "0"
