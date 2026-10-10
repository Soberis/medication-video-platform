#!/usr/bin/env python3
"""
tools/inspect_truth.py

真值检验与多模态对齐质检脚本 (Truth Inspection & Multimodal Verification)
1. 从 HDF5 导出文件中提取演示样本 (Demo) 的动作标签与手部骨骼张量
2. 通过官方 Core API 获取安全回放流，抽取动作关键帧
3. 将 MediaPipe 21 点手部 3D 骨骼关节与骨骼连线精确叠画在视频画面上
4. 生成质检快照图，评估轨迹连续性、遮挡率与动作边界真实度
"""

import os
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

import json
import requests
import h5py
import numpy as np
import cv2

PROJECT_ID = "944a19eb-03b1-4b55-98fb-f8e108e23d92"
CLIP_ASSET_ID = "bafcf62f-fb2b-42a9-bb05-be24be1f03b2" # BV18gKB6rEZU
DEMO_NAME = "demo_000000"
HDF5_PATH = os.environ.get(
    "HDF5_PATH",
    "exports/hdf5/medication_demo_v2.hdf5"
    if os.path.exists("exports/hdf5/medication_demo_v2.hdf5")
    else "exports/medication_demo_v2.hdf5",
)
OUTPUT_DIR = os.environ.get("OUTPUT_DIR", "exports/inspection")
ADMIN_TOKEN = "synthetic-local-admin-bearer-token-do-not-reuse"

# MediaPipe 21 手部关键点连线拓扑
HAND_CONNECTIONS = [
    # 拇指
    (0, 1), (1, 2), (2, 3), (3, 4),
    # 食指
    (0, 5), (5, 6), (6, 7), (7, 8),
    # 中指
    (0, 9), (9, 10), (10, 11), (11, 12),
    # 无名指
    (0, 13), (13, 14), (14, 15), (15, 16),
    # 小指
    (0, 17), (17, 18), (18, 19), (19, 20),
    # 掌心横向基底连接
    (5, 9), (9, 13), (13, 17)
]

def get_playback_url():
    url = f"http://localhost:8080/api/v1/projects/{PROJECT_ID}/clip-assets/{CLIP_ASSET_ID}/playback-grants"
    headers = {
        "Authorization": f"Bearer {ADMIN_TOKEN}",
        "Accept": "application/json"
    }
    resp = requests.post(url, headers=headers)
    if not resp.ok:
        raise RuntimeError(f"获取回放授权失败 ({resp.status_code}): {resp.text}")
    data = resp.json()
    media_url = data["media_url"]
    # 容器外部访问修正: 如果返回 192.168.1.7:19000 或 localhost:19000
    media_url = media_url.replace("192.168.1.7:19000", "localhost:19000")
    return media_url

def download_video(media_url, save_path):
    if os.path.exists(save_path) and os.path.getsize(save_path) > 1000:
        print(f"[1/4] 本地已存在视频缓存: {save_path}")
        return
    print(f"[1/4] 正在从对象存储下载原始视频...")
    resp = requests.get(media_url, stream=True)
    if not resp.ok:
        raise RuntimeError(f"下载视频失败 ({resp.status_code})")
    with open(save_path, "wb") as f:
        for chunk in resp.iter_content(chunk_size=1024 * 1024):
            f.write(chunk)
    print(f"      视频下载完成 ({os.path.getsize(save_path) / 1024 / 1024:.2f} MB)")

def inspect_dataset():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    video_path = os.path.join(OUTPUT_DIR, "sample_bv18gkb6rezu.mp4")

    # 1. 下载视频
    media_url = get_playback_url()
    download_video(media_url, video_path)

    # 2. 读取 HDF5 数据
    print(f"[2/4] 读取 HDF5 数据集: {HDF5_PATH} -> {DEMO_NAME}...")
    with h5py.File(HDF5_PATH, "r") as f:
        demo = f["data"][DEMO_NAME]
        landmarks = np.array(demo["samples"]["landmarks"]) # (T, 2, 21, 3)
        dense_labels = [l.decode('utf-8') if isinstance(l, bytes) else str(l) for l in demo["samples"]["label"]]
        frame_timestamps = np.array(demo["samples"]["frame_timestamp_ms"])
        
        # 读取高层动作区间
        annot_labels = [l.decode('utf-8') if isinstance(l, bytes) else str(l) for l in demo["annotations"]["label"]]
        start_ms = np.array(demo["annotations"]["segment_start_ms"])
        end_ms = np.array(demo["annotations"]["segment_end_ms"])

    T, num_hands, num_pts, dims = landmarks.shape
    print(f"      总采样时间步 T: {T} 帧 (10 FPS)")
    print(f"      动作切片总数: {len(annot_labels)} 个动作片段")

    # 3. 计算客观真值统计指标
    # 判断哪一帧有手 (不是全 0)
    has_left = np.any(np.abs(landmarks[:, 0, :, :]) > 1e-5, axis=(1, 2))
    has_right = np.any(np.abs(landmarks[:, 1, :, :]) > 1e-5, axis=(1, 2))
    has_any_hand = has_left | has_right
    has_both_hands = has_left & has_right

    left_ratio = np.mean(has_left) * 100
    right_ratio = np.mean(has_right) * 100
    any_ratio = np.mean(has_any_hand) * 100
    both_ratio = np.mean(has_both_hands) * 100

    print("\n------------------- 数据质量与跟踪真值统计 -------------------")
    print(f"  • 双手任意一只手可见率: {any_ratio:.1f}% ({np.sum(has_any_hand)}/{T} 帧)")
    print(f"  • 双手同时操作可见率:   {both_ratio:.1f}% ({np.sum(has_both_hands)}/{T} 帧)")
    print(f"  • 左手单独检测率:       {left_ratio:.1f}%")
    print(f"  • 右手单独检测率:       {right_ratio:.1f}%")
    print("  • 识别出的核心动作分布:")
    from collections import Counter
    label_counts = Counter(annot_labels)
    for lbl, count in label_counts.most_common(6):
        print(f"      - {lbl}: {count} 次")

    # 4. 抽取关键帧并绘制骨骼叠加图
    print(f"\n[3/4] 抽取关键帧并渲染骨骼叠加图...")
    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total_video_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    video_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    video_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"      原始视频规格: {video_width}x{video_height} @ {fps:.1f} FPS, 共 {total_video_frames} 帧")

    # 挑选几个具有代表性的动作片段: 如抽药(draw_liquid)、插针(insert_needle)、开盖(open_container)
    target_actions = ["draw_liquid", "insert_needle", "open_container", "acquire_item"]
    selected_samples = []

    for idx, (lbl, s_ms, e_ms) in enumerate(zip(annot_labels, start_ms, end_ms)):
        if lbl in target_actions:
            mid_ms = (s_ms + e_ms) / 2
            # 找到最接近该时间戳的 HDF5 采样步
            step_idx = np.argmin(np.abs(frame_timestamps - mid_ms))
            if has_any_hand[step_idx]:
                selected_samples.append((idx, lbl, mid_ms, step_idx))
                target_actions.remove(lbl)
        if len(target_actions) == 0:
            break

    if len(selected_samples) < 4:
        for idx, (lbl, s_ms, e_ms) in enumerate(zip(annot_labels, start_ms, end_ms)):
            if not any(s[1] == lbl for s in selected_samples):
                mid_ms = (s_ms + e_ms) / 2
                step_idx = np.argmin(np.abs(frame_timestamps - mid_ms))
                if has_any_hand[step_idx]:
                    selected_samples.append((idx, lbl, mid_ms, step_idx))
            if len(selected_samples) >= 5:
                break

    # 渲染快照
    rendered_images = []
    for (act_idx, label, time_ms, t_idx) in selected_samples:
        # 定位视频帧
        cap.set(cv2.CAP_PROP_POS_MSEC, time_ms)
        ret, frame = cap.read()
        if not ret:
            continue

        h, w, _ = frame.shape
        # 复制底图
        overlay = frame.copy()

        # 绘制双手骨骼
        hand_colors = [
            (255, 128, 0),  # 左手 (蓝绿/橙色 BGR: 蓝色为主)
            (0, 215, 255)   # 右手 (金黄色 BGR: 亮黄)
        ]

        hand_names = ["Left", "Right"]
        for h_idx in range(2):
            pts = landmarks[t_idx, h_idx] # (21, 3)
            if np.all(np.abs(pts) < 1e-5):
                continue

            color = hand_colors[h_idx]

            # 画关节连接线
            for (p1, p2) in HAND_CONNECTIONS:
                x1, y1 = int(pts[p1, 0] * w), int(pts[p1, 1] * h)
                x2, y2 = int(pts[p2, 0] * w), int(pts[p2, 1] * h)
                if 0 <= x1 < w and 0 <= y1 < h and 0 <= x2 < w and 0 <= y2 < h:
                    cv2.line(overlay, (x1, y1), (x2, y2), color, 3, cv2.LINE_AA)

            # 画 21 个骨骼关键点
            for pt_i in range(21):
                px, py = int(pts[pt_i, 0] * w), int(pts[pt_i, 1] * h)
                if 0 <= px < w and 0 <= py < h:
                    # 指尖画大红点，关节画绿色小圆
                    pt_color = (0, 0, 255) if pt_i in [4, 8, 12, 16, 20] else (0, 255, 0)
                    cv2.circle(overlay, (px, py), 5 if pt_i in [4, 8, 12, 16, 20] else 3, pt_color, -1, cv2.LINE_AA)

        # 融合透明度
        alpha = 0.85
        cv2.addWeighted(overlay, alpha, frame, 1 - alpha, 0, frame)

        # 绘制半透明顶部信息栏
        cv2.rectangle(frame, (0, 0), (w, 80), (20, 20, 20), -1)
        # 写入动作名称与时间戳
        cv2.putText(frame, f"Action: [{label.upper()}]  |  Timestamp: {time_ms/1000:.2f}s (Step #{t_idx})", 
                    (20, 35), cv2.FONT_HERSHEY_SIMPLEX, 0.85, (0, 255, 255), 2, cv2.LINE_AA)
        cv2.putText(frame, f"Hands Tracked: Left={'Yes' if has_left[t_idx] else 'No'}, Right={'Yes' if has_right[t_idx] else 'No'}  |  HDF5 Tensor Aligned", 
                    (20, 65), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (200, 200, 200), 1, cv2.LINE_AA)

        out_img_name = f"truth_{label}_{int(time_ms)}ms.jpg"
        out_img_path = os.path.join(OUTPUT_DIR, out_img_name)
        cv2.imwrite(out_img_path, frame)
        rendered_images.append((out_img_path, label, time_ms))
        print(f"      已渲染动作真值快照: {out_img_name}")

    cap.release()
    print(f"[4/4] 质检完成！共输出 {len(rendered_images)} 张多模态对齐质检快照。")
    return rendered_images

if __name__ == "__main__":
    inspect_dataset()
