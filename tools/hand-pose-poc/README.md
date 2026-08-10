# Hand Pose PoC

隔离的 Phase A 工具：

```text
本地 Ego MP4 + 离线 MediaPipe Hand Landmarker 模型
→ hand_pose_overlay.mp4 + hand_landmarks.jsonl + run_summary.json
```

它用于验证截图风格的双手关键点、骨架和 `Left/Right + handedness_score`。它不连接平台、不写
正式候选或标注，也不证明模型已经适合真实配药场景。完整边界见
[V1.13 需求基线](../../docs/requirements/baselines/V1.13/配药视频数据管理平台需求文档_V1.13.md)
和 [PoC 技术设计](../../docs/architecture/hand-pose-poc.md)。

## 环境与模型

- Python 3.12；
- uv；
- 本地 MP4；
- 预先审批并离线准备的 Hand Landmarker `.task` 文件。

模型、视频和输出均被仓库根 `.gitignore` 排除。工具不会下载模型，也不要把模型放入 Git。
MediaPipe 官方说明输入在设备本地处理，但运行时可能发送性能/使用指标；因此 PoC 必须在防火墙、
容器或隔离网络层阻断公网出站，并把断网运行和无连接尝试作为验收证据。

```powershell
cd tools/hand-pose-poc
uv sync --frozen
uv run hand-pose-poc `
  --input $env:HAND_POSE_INPUT_PATH `
  --model $env:HAND_POSE_MODEL_PATH `
  --output-dir $env:HAND_POSE_OUTPUT_DIR
```

默认对每个解码帧推理，最多两只手，三个 MediaPipe 阈值均为 `0.5`。实验参数：

```text
--sample-fps 1..15
--min-detection-confidence 0..1
--min-presence-confidence 0..1
--min-tracking-confidence 0..1
--swap-handedness
--include-world-landmarks
--allow-fps-timestamp-fallback
```

`--sample-fps` 只影响实验推理帧；未推理帧保持原画面并在 JSONL 中明确标记。默认要求容器 PTS
严格递增；只有人工检查视频后才应允许 FPS 回退。Overlay 保持原始分辨率，但第一版不复制音轨。

## 输出语义

- `hand_pose_overlay.mp4`：关键点、骨架和 handedness score；
- `hand_landmarks.jsonl`：每个解码帧一行，未检测到手为 `hands=[]`；
- `run_summary.json`：模型摘要、版本、配置、时间戳来源、计数和吞吐。

`handedness_score` 不是 bbox 或关键点置信度。`world_landmarks` 默认不输出；即使开启，也不是统一
相机/房间坐标系下的真实三维手。

## 验证

```powershell
uv run ruff format --check .
uv run ruff check .
uv run mypy
uv run pytest
```

自动测试不使用真实视频或模型。最终效果必须用合成或明确授权且隔离的 Ego Golden Set 人工验收，
重点观察双手遮挡、持药瓶/针筒、快速运动、手部出画、近距离大手和左右手交换。

## 非目标

不做动作/物体/SOP 识别、实时摄像头、永久 track ID、跨相机 ReID、三维重建、数据库、RabbitMQ、
CandidateAnnotation 或正式 AnnotationRevision。Phase A 验收后先停，由人工决定 GO、调参、替换或停用。
