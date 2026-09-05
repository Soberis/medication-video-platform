# MediaPipe Hand-Landmarker Scientific Benchmark

这是独立的离线评估工具，不读取视频、不加载模型、不访问数据库或 Worker，也不修改生产 profile。它把一组带人工参考的帧级 JSON/JSONL/CSV 记录变成可复现的 JSON 报告。输入契约见 [`schema/input.schema.json`](schema/input.schema.json)。

## 输入

JSON 使用以下形状：

```json
{
  "format": "mediapipe-hand-landmarker-benchmark-input.v1",
  "provenance": {
    "model_sha256": "<64 hex>",
    "profile": "approved-profile-id",
    "profile_sha256": "<64 hex>",
    "fps": 10,
    "threshold": 0.5,
    "dataset_version": "authorized-v1",
    "dataset_digest": "<64 hex>",
    "evaluation_code_version": "hand-landmarker-evaluator.v1",
    "timestamp": "2026-09-04T00:00:00Z",
    "dataset_kind": "AUTHORIZED_EXTERNAL",
    "authorization_reference": "approval-record-123"
  },
  "frames": [
    {
      "frame_id": "frame-1",
      "timestamp_ms": 0,
      "groups": {"occlusion": "none", "fast_motion": false, "view": "front", "frame_edge": "none"},
      "ground_truth_hands": [{
        "hand_id": "hand-1",
        "handedness": "RIGHT",
        "normalization_scale": 0.4,
        "landmarks": [{"x": 0.1, "y": 0.2, "z": 0.0}]
      }],
      "predicted_hands": []
    }
  ]
}
```

`landmarks` 必须恰好 21 个点；点可以写成 `{x,y,z}` 或 `[x,y,z]`。参考手的 `normalization_scale` 是正数，用于归一化 2-D 图像坐标误差。每帧最多两只手，`hand_id` 用于稳定匹配；缺少对应检测时只计入 detection failure，不进入 localization 分母。`groups` 可按 `occlusion`、`fast_motion`、`two_hands`、`view`、`frame_edge` 及未来扩展字段分组。

JSONL 第一行是 `{"type":"provenance", ...}`，后续每行是 `{"type":"frame", ...}`。CSV 每行重复 provenance 列，帧数组写成 JSON 字符串；表头必须与工具定义完全一致。

缺失、无效或包含路径/URL/密钥/患者身份的 provenance 会失败。仓库内只提交合成 fixture；授权真实数据、模型和中间文件必须在仓库外隔离目录提供，报告只保存 digest 和安全的批准记录引用。

## 指标

- `HAND_PRESENT_RECALL`：有参考手的帧中检测到至少一只手的比例。
- `NO_HAND_FALSE_POSITIVE_RATE`：无参考手的帧中产生检测的比例。
- `PCK@0.05`、`PCK@0.10`：匹配手的 21 个点中，2-D 归一化误差不超过阈值的比例。
- `NORMALIZED_MEAN_ERROR`、`MEDIAN_NORMALIZED_ERROR`：匹配点误差的均值和中位数。
- `PER_LANDMARK_ERROR`：按 0–20 landmark index 的均值误差。
- `HANDEDNESS_ACCURACY`：仅在匹配且参考为 `LEFT`/`RIGHT` 时比较；`UNKNOWN` 不进入分母。
- `TEMPORAL_JITTER`：相邻帧同一 `hand_id` 的预测位移与参考位移之差，按参考 scale 归一化后的均值。

检测失败和关键点定位误差在报告的 `counts` 中分别记录；无可用分母时指标为 `null`，不是 0。输出也包含每个 group 的同样指标与分母。

## CLI

```powershell
uv lock --check --project evaluation/hand-landmarker
uv run --project evaluation/hand-landmarker python evaluation/hand-landmarker/evaluator.py `
  evaluation/hand-landmarker/fixtures/synthetic_benchmark.json `
  --output .runtime/hand-landmarker-report.json
```

比较 5/10/15 FPS 与 0.3/0.5/0.7 threshold 的九个输入：

```powershell
uv run --project evaluation/hand-landmarker python evaluation/hand-landmarker/compare_profiles.py `
  eval-fps5-t03.json eval-fps5-t05.json eval-fps5-t07.json `
  eval-fps10-t03.json eval-fps10-t05.json eval-fps10-t07.json `
  eval-fps15-t03.json eval-fps15-t05.json eval-fps15-t07.json `
  --output .runtime/profile-comparison.json
```

比较器要求九种组合恰好各出现一次，并要求 model SHA、dataset version/digest 相同；它只比较输入报告，绝不自动调整生产参数。

合成报告的 `conclusion` 固定标为 `SYNTHETIC_FIXTURE_ONLY`，不得冒充生产、真实数据或算法效果结论。授权真实数据报告也只给出可复现测量，任何算法/生产结论都需要 benchmark owner 的人工复核。
