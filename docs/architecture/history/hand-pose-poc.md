# Ego Hand Pose PoC 技术设计

> 版本：TD-HP-POC-V2
> 状态：Phase A 实施基线
> 对齐需求：V1.15 FR-042A
> 日期：2026-08-10

> 历史状态：SUPERSEDED。`Ego` 为历史内部命名；当前产品语言和实施规则以 [V1.18 当前技术设计](../video-hand-pose-mvp-technical-design.md) 为准。

## 1. 目标和边界

本设计只回答一个问题：MediaPipe Hand Landmarker 在第一人称配药操作视频中，能否稳定生成接近
参考截图的双手骨架候选，并形成可量化的试点证据。

Phase A 是仓库内独立工具，不调用平台服务：

```text
本地 MP4 + 本地 .task 模型
→ PyAV 解码和 PTS
→ RGB ndarray
→ MediaPipe HandLandmarker VIDEO
→ 结果校验
├─→ Pillow Overlay → PyAV MP4
├─→ frame JSONL
└─→ run summary
```

不新增数据库表、消息队列、正式 API 或 Worker。现有 AIJob 基础切片保持原状但不作为 Phase A
依赖，也不证明算法效果。

## 2. 技术选择

- Python 3.12、uv 和独立 lockfile；
- `mediapipe==1.0.0`，使用 Tasks API，不使用 Legacy `mp.solutions.hands`；
- PyAV 读取原始 PTS 并编码 MP4，避免把 `frame_index / fps` 当作唯一时间事实；
- NumPy 传递 RGB 帧；Pillow 只负责离屏绘图，不依赖 GUI；
- 模型从绝对路径加载并计算 SHA-256，不下载、不复制到输出目录。

MediaPipe 官方要求 `detect_for_video` 在 VIDEO mode 下接收单调递增的毫秒时间戳；输入图像支持
RGB/RGBA。三个阈值分别是 detection、hand presence 和 tracking 阈值，不能与返回结果里的
handedness category score 混为一谈。

MediaPipe 当前发行说明区分“输入不离开设备”和性能/使用指标。Phase A 必须由操作系统、容器或
隔离网络阻断公网出站，并在验收时观察 DNS/连接尝试；不能把 Python 代码没有显式 HTTP 客户端
当作零出站证据。

## 3. 时间轴

每个解码帧保存：

- `frame_index`：解码顺序；
- `source_timestamp_ms`：容器 PTS 换算值；
- `timestamp_ms`：减去首帧 PTS 后传入 MediaPipe 的非负时间；
- `timestamp_source`：`container_pts` 或 `nominal_fps_fallback`。

默认要求容器 PTS 存在且换算后的 `timestamp_ms` 严格递增。缺失、重复或倒退立即失败。只有
`--allow-fps-timestamp-fallback` 才允许用标称平均 FPS 生成时间戳，并在日志与 summary 中标记；
不做静默加一毫秒修正。

`--sample-fps` 未设置时每帧推理。设置 1–15 后，以时间戳门控推理，仍逐帧写 JSONL 和 Overlay；
未推理帧保持原画面并写 `inference_performed=false`，不沿用上一帧骨架，避免制造虚假的跟踪事实。

## 4. 结果模型

每个推理帧最多两只手。结果列表按 MediaPipe 同一索引对齐：

```json
{
  "frame_index": 123,
  "source_timestamp_ms": 4100,
  "timestamp_ms": 4067,
  "timestamp_source": "container_pts",
  "inference_performed": true,
  "image_width": 1920,
  "image_height": 1080,
  "hands": [
    {
      "hand_index": 0,
      "handedness": "Left",
      "handedness_score": 0.976,
      "valid_landmark_count": 21,
      "bbox_normalized": {"xmin": 0.1, "ymin": 0.42, "xmax": 0.39, "ymax": 0.91},
      "landmarks": [{"id": 0, "name": "WRIST", "x": 0.123, "y": 0.456, "z": -0.012}]
    }
  ]
}
```

二维关键点必须恰好 21 个且顺序为 0–20。bbox 是有限 x/y 点的最小包络并裁剪到 `[0,1]`；
坐标越界不使绘图崩溃。`world_landmarks` 默认省略，显式开启时只作为调试数据。

## 5. handedness 与 Overlay

Overlay 使用固定拓扑，颜色只用于区分候选：Left 为青色、Right 为品红、Unknown 为黄色。标签只写
`Left 0.97` 这类 handedness score，不显示“bbox confidence”。

不同相机链路可能镜像图像，左右手语义不能靠截图猜测。默认原样保存模型输出；
`--swap-handedness` 仅显式交换 Left/Right，并把配置写入 summary。最终选择由带左右手真值的
Golden Set 决定。

## 6. 输出与失败

成功时以 `.partial` 临时名写入并发布目标目录中的三个文件。第一版 Overlay 不复制音轨，`run_summary.json` 记录
`audio_preserved=false`。以下情况返回非零退出码且不伪造成功 summary：模型/视频不存在、模型摘要
失败、无视频流、PTS 非法、参数越界、MediaPipe 初始化/推理失败、编码器不可用或输出不可写。

日志按固定帧间隔报告进度，不逐帧输出，不打印视频内容、输入绝对路径、模型路径或身份信息。

## 7. 测试和验收证据

自动测试覆盖关键点编号、骨架端点、bbox、像素坐标、时间戳、采样门控、0/2 手 JSON、模型摘要、
离屏渲染和合成 MP4 管线。自动测试只证明工具行为，不证明模型在 Ego 场景的准确率。

人工试点按 V1.13 分层样本与统计口径执行。没有获批模型和 Ego Golden Set 时，交付状态只能是
“工具就绪，效果待验收”，不能写“达到截图效果”。

## 8. Phase B 预留

Phase B 复用已批准的 `AIJob → CandidateAnnotation → Human Review` 边界，但不会直接复用 PoC
文件布局作为生产契约。Dense landmark 是否进对象存储、结果 Schema、lease/result API、资产准入和
网络策略都要在 GO 决策后单独冻结；在第二个真实用例出现前不抽象通用 AI 框架。

## 9. Golden Set 前的实现收口

在使用获批 Golden Set 前，必须处理下列不改变识别算法的工具问题：

1. NaN/Inf landmark 仅跳过对应点与连接线，其余有效点继续渲染和序列化；
2. PTS 回退必须与已存在的时间原点连续，或在混合 PTS 时稳定失败，不能产生隐式倒退；
3. 失败后的 `.partial` 产物必须有安全清理/恢复路径；
4. `duration_ms` 和端到端吞吐指标必须如实命名；
5. 为上述边界增加合成自动测试。

这些是 Phase A 证据质量要求，不是绕过 Golden Set 或书面 `GO` 的理由。

## 10. 与 Phase B 的边界

现有平台代码可能已经含有 AIJob、Worker、候选回传或候选 Overlay 的实现草稿，但它们不属于本 PoC
运行链路，也不构成启用授权。书面 `GO` 后的输入准入、profile、结果、Outbox、存储、人工审核与验收
规范统一见 [Ego Hand Pose Candidate Phase B 技术设计](./hand-pose-candidate-phase-b.md)。
