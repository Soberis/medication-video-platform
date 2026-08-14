# 最小训练数据 Parquet 导出

这是一个独立、只读的本地工具：从现有 Core API PostgreSQL 读取 Cleaning PASS 的视频证据、人工正式 AnnotationRevision 和 MediaPipe Candidate，生成一个可离线读取的 `training_data.parquet`。

它不下载 MP4、不创建导出表、不修改平台数据库，不启动 Worker、队列或 Dataset/LeRobot 平台。

## 运行环境

```powershell
uv sync --project tools/minimal-export
```

默认只接受 `DATA_PROFILE=synthetic`。不要把真实患者数据、生产密钥或其他敏感数据放入仓库。

## 生成 Parquet

```powershell
uv run --project tools/minimal-export `
  python tools/minimal-export/export_training_data.py `
  --project-id $env:DEV_PROJECT_ID `
  --output .\training_data.parquet
```

每一行是一个 MediaPipe 手部候选在人工动作时间段内的观测，包含：

```text
video_id
timestamp_ms
action_label
hand_index
annotation_revision_id
candidate_annotation_id
handedness
handedness_score
handedness_is_ground_truth
landmark_00_x / landmark_00_y / landmark_00_z
...
landmark_20_x / landmark_20_y / landmark_20_z
```

`action_label` 只从 APPROVED 的人工 `AnnotationRevision.annotation_document.segments` 按 `[start_ms, end_ms)` 对齐；手部几何来自 `CandidateAnnotation`。`handedness` 和 `handedness_score` 仅作为 MediaPipe 辅助元数据，文件元数据明确 `handedness_is_ground_truth=false`。

导出前同时校验视频 `INGESTED`、质量 `QUALIFIED`、人工复核 `APPROVED`、Cleaning `PASS + SUCCEEDED`，并绑定当前 asset/revision/quality 的摘要。未通过 Cleaning 的记录不会进入 Parquet。视频原文件只通过 `video_id` 回查，不写入输出。

`--force` 只覆盖命令行明确指定的输出文件；`--allow-non-synthetic` 仅在明确授权的隔离环境中使用。

## 后续转换为 LeRobot

当前结构保持平台无关：`video_id + timestamp_ms` 用于回查原视频帧，63 个 landmark 列可映射为手部观测，`action_label` 可映射为任务/类别。后续转换器应位于平台外部，按训练任务补充 episode/frame 索引；不得伪造机器人 `action` 或 `observation.state`，也不需要修改平台核心模型。
