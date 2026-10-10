#!/usr/bin/env python3
"""
tools/preflight_hand_check.py

多源网络视频导入前置手部准入门禁探针 (Pre-flight Hand Gate Probe)
--------------------------------------------------------------------
在批量导入/上传视频到平台之前，快速探测视频关键帧是否存在手部目标。
- 极速短路：只要探测到有效手部立即退出并放行（耗时约 200ms - 800ms）；
- 严格拦截：若在多个抽样关键帧中均未检出有效手部，则退出码为 1（拦截拒收）。
"""

import argparse
import json
import os
import sys
from pathlib import Path

DEFAULT_MODEL_PATH = Path(r"D:\medication-video-data\hand-pose\models\hand_landmarker.task")

def run_preflight_check(video_path: Path, model_path: Path, sample_ratios: list[float], min_confidence: float) -> dict:
    import av
    import mediapipe as mp

    if not video_path.is_file():
        return {"ok": False, "error": f"视频文件不存在: {video_path}"}
    if not model_path.is_file():
        return {"ok": False, "error": f"MediaPipe 模型文件不存在: {model_path}"}

    options = mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path)),
        running_mode=mp.tasks.vision.RunningMode.IMAGE,
        num_hands=2,
        min_hand_detection_confidence=min_confidence,
        min_hand_presence_confidence=min_confidence,
    )

    try:
        container = av.open(str(video_path))
        stream = container.streams.video[0]
        total_frames = stream.frames or 0
        duration_sec = float(stream.duration * stream.time_base) if stream.duration else 0.0

        sample_pts = []
        if total_frames > 10:
            sample_pts = [int(total_frames * r) for r in sample_ratios]
        elif duration_sec > 1.0:
            time_base = float(stream.time_base)
            sample_pts = [int((duration_sec * r) / time_base) for r in sample_ratios]
        else:
            sample_pts = [5, 15, 30]

        detected_count = 0
        sampled_count = 0

        with mp.tasks.vision.HandLandmarker.create_from_options(options) as landmarker:
            idx = 0
            for frame in container.decode(stream):
                target_matched = False
                if total_frames > 10:
                    target_matched = idx in sample_pts
                else:
                    target_matched = any(abs(frame.pts - p) < 500 for p in sample_pts) if frame.pts else (idx in [10, 30, 50])

                if target_matched:
                    sampled_count += 1
                    img = frame.to_ndarray(format="rgb24")
                    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=img)
                    res = landmarker.detect(mp_image)
                    if res.hand_landmarks:
                        detected_count += 1
                        # 极速短路优化：一旦发现手部，立即判定合格放行
                        return {
                            "ok": True,
                            "has_hands": True,
                            "video_path": str(video_path),
                            "detected_count": detected_count,
                            "sampled_count": sampled_count,
                            "early_exit": True,
                        }

                idx += 1
                if sample_pts and idx > max(sample_pts) + 10:
                    break

        return {
            "ok": True,
            "has_hands": detected_count > 0,
            "video_path": str(video_path),
            "detected_count": detected_count,
            "sampled_count": sampled_count,
            "early_exit": False,
        }

    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def main():
    parser = argparse.ArgumentParser(description="Pre-flight Hand Gate Probe")
    parser.add_argument("video_path", type=Path, help="Path to video file")
    parser.add_argument("--model-path", type=Path, default=DEFAULT_MODEL_PATH, help="Path to hand_landmarker.task")
    parser.add_argument("--confidence", type=float, default=0.4, help="Min hand detection confidence")
    parser.add_argument("--samples", type=str, default="0.15,0.35,0.55,0.75", help="Comma-separated sample ratios")
    args = parser.parse_args()

    ratios = [float(x.strip()) for x in args.samples.split(",") if x.strip()]
    result = run_preflight_check(args.video_path, args.model_path, ratios, args.confidence)

    print(json.dumps(result, ensure_ascii=False))

    if result.get("ok") and result.get("has_hands"):
        sys.exit(0)
    else:
        sys.exit(1)


if __name__ == "__main__":
    main()
