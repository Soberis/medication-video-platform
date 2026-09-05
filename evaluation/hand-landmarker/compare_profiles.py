#!/usr/bin/env python3
"""Compare offline benchmark reports across sampling/profile settings."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import cast

from evaluator import EvaluationError, evaluate_dataset, load_dataset

EXPECTED_FPS = frozenset({5.0, 10.0, 15.0})
EXPECTED_THRESHOLDS = frozenset({0.3, 0.5, 0.7})


def compare(paths: Sequence[str | Path]) -> dict[str, object]:
    """Evaluate a complete 3x3 FPS/threshold grid without changing settings."""

    if len(paths) != 9:
        raise EvaluationError("compare_profiles requires exactly nine inputs (3 FPS x 3 thresholds)")
    rows: list[dict[str, object]] = []
    seen: set[tuple[float, float]] = set()
    baseline_signature: tuple[object, ...] | None = None
    for path in paths:
        dataset = load_dataset(path)
        provenance = dataset.provenance
        fps = float(cast(float, provenance["fps"]))
        threshold = float(cast(float, provenance["threshold"]))
        key = (fps, threshold)
        if fps not in EXPECTED_FPS or threshold not in EXPECTED_THRESHOLDS:
            raise EvaluationError("inputs must use FPS 5/10/15 and thresholds 0.3/0.5/0.7")
        if key in seen:
            raise EvaluationError(f"duplicate profile combination: fps={fps}, threshold={threshold}")
        seen.add(key)
        signature = (
            provenance["model_sha256"],
            provenance["dataset_version"],
            provenance["dataset_digest"],
        )
        if baseline_signature is None:
            baseline_signature = signature
        elif signature != baseline_signature:
            raise EvaluationError("all profile inputs must share model SHA and dataset version/digest")
        rows.append(
            {
                "fps": fps,
                "threshold": threshold,
                "profile": provenance["profile"],
                "profile_sha256": provenance["profile_sha256"],
                "source_sha256": dataset.source_sha256,
                "metrics": evaluate_dataset(dataset)["metrics"],
            }
        )
    if seen != {(fps, threshold) for fps in EXPECTED_FPS for threshold in EXPECTED_THRESHOLDS}:
        raise EvaluationError("inputs must cover every FPS/threshold combination exactly once")
    rows.sort(key=lambda row: (float(cast(float, row["fps"])), float(cast(float, row["threshold"]))))
    return {
        "format": "mediapipe-hand-landmarker-profile-comparison.v1",
        "model_sha256": baseline_signature[0] if baseline_signature else None,
        "dataset_version": baseline_signature[1] if baseline_signature else None,
        "dataset_digest": baseline_signature[2] if baseline_signature else None,
        "combinations": rows,
        "note": "Offline comparison only; this tool never changes MediaPipe or production parameters.",
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compare MediaPipe benchmark FPS/threshold profiles")
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        report = compare(args.inputs)
        rendered = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        else:
            sys.stdout.write(rendered)
    except (EvaluationError, OSError) as error:
        print(f"PROFILE COMPARISON FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
