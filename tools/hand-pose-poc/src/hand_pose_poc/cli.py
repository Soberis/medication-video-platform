from __future__ import annotations

import argparse
import importlib.metadata
import json
import logging
import sys
import time
from collections.abc import Sequence
from dataclasses import asdict
from pathlib import Path

from .digest import sha256_file
from .landmarker import LandmarkerConfig, MediaPipeHandDetector
from .pipeline import PipelineConfig, process_video, statistics_dict

LOGGER = logging.getLogger("hand-pose-poc")


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        return _run(args)
    except (OSError, RuntimeError, ValueError) as exception:
        LOGGER.error("%s", exception)
        return 1


def _run(args: argparse.Namespace) -> int:
    input_path = args.input.resolve()
    model_path = args.model.resolve()
    output_dir = args.output_dir.resolve()
    if not input_path.is_file():
        raise ValueError("The input MP4 does not exist.")
    if input_path.suffix.lower() != ".mp4":
        raise ValueError("Phase A accepts MP4 input only.")
    if not model_path.is_file():
        raise ValueError("The offline Hand Landmarker model does not exist.")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("The output directory must be empty to avoid overwriting evidence.")

    landmarker_config = LandmarkerConfig(
        num_hands=args.num_hands,
        min_detection_confidence=args.min_detection_confidence,
        min_presence_confidence=args.min_presence_confidence,
        min_tracking_confidence=args.min_tracking_confidence,
        include_world_landmarks=args.include_world_landmarks,
        swap_handedness=args.swap_handedness,
    )
    landmarker_config.validate()
    model_sha256 = sha256_file(model_path)
    LOGGER.info("Loaded offline model sha256=%s", model_sha256)
    started = time.perf_counter()
    with MediaPipeHandDetector(model_path, landmarker_config) as detector:
        video, statistics = process_video(
            input_path,
            output_dir,
            detector,
            PipelineConfig(args.sample_fps, args.allow_fps_timestamp_fallback),
        )
    elapsed_seconds = time.perf_counter() - started
    throughput = statistics.inference_frames / elapsed_seconds if elapsed_seconds > 0 else 0.0
    summary = {
        "schema_version": "hand-pose-poc-summary.v1",
        "algorithm": "mediapipe-hand-landmarker",
        "model_sha256": model_sha256,
        "software": {
            "hand_pose_poc": importlib.metadata.version("hand-pose-poc"),
            "mediapipe": importlib.metadata.version("mediapipe"),
            "av": importlib.metadata.version("av"),
        },
        "input": asdict(video),
        "config": {
            **asdict(landmarker_config),
            "sample_fps": args.sample_fps,
            "allow_fps_timestamp_fallback": args.allow_fps_timestamp_fallback,
        },
        "statistics": statistics_dict(statistics),
        "timestamp": {
            "primary_source": "container_pts",
            "fallback_frames": statistics.fallback_timestamp_frames,
        },
        "performance": {
            "processing_seconds": round(elapsed_seconds, 6),
            "average_inference_frames_per_second": round(throughput, 6),
        },
    }
    summary_partial = output_dir / "run_summary.json.partial"
    summary_partial.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    summary_partial.replace(output_dir / "run_summary.json")
    LOGGER.info(
        "Completed %d decoded frames and %d inference frames",
        statistics.decoded_frames,
        statistics.inference_frames,
    )
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hand-pose-poc",
        description="Render offline MediaPipe hand landmarks over an Ego MP4.",
    )
    parser.add_argument("--input", type=Path, required=True, help="Local MP4 path")
    parser.add_argument("--model", type=Path, required=True, help="Offline .task model path")
    parser.add_argument(
        "--output-dir", type=Path, required=True, help="New or empty output directory"
    )
    parser.add_argument("--num-hands", type=int, default=2, choices=(1, 2))
    parser.add_argument("--min-detection-confidence", type=_unit_float, default=0.5)
    parser.add_argument("--min-presence-confidence", type=_unit_float, default=0.5)
    parser.add_argument("--min-tracking-confidence", type=_unit_float, default=0.5)
    parser.add_argument("--sample-fps", type=int, choices=range(1, 16))
    parser.add_argument("--include-world-landmarks", action="store_true")
    parser.add_argument("--swap-handedness", action="store_true")
    parser.add_argument("--allow-fps-timestamp-fallback", action="store_true")
    return parser


def _unit_float(value: str) -> float:
    parsed = float(value)
    if not 0.0 <= parsed <= 1.0:
        raise argparse.ArgumentTypeError("must be between 0 and 1")
    return parsed


if __name__ == "__main__":
    sys.exit(main())
