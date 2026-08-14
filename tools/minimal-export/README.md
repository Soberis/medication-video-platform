# 最小训练数据 Parquet 导出

这是一个独立、只读的本地工具：从现有 Core API PostgreSQL 的权威 `training_data_eligibility` 和
`training_data_candidate_observations` 视图读取 Cleaning PASS 的视频证据、人工正式 AnnotationRevision
和 MediaPipe Candidate，生成一个可离线读取的 `training_data.parquet`。Core API 同样使用这两个视图提供
项目级 preview/download；工具与 API 不各自维护资格 SQL。

它不下载 MP4、不创建导出表、不修改平台数据库，不启动 Worker、队列或 Dataset/LeRobot 平台。API 路径为：

```text
GET /api/v1/projects/{project_id}/training-data/preview
GET /api/v1/projects/{project_id}/training-data/download
```

两个 API 都是同步只读操作，仅允许平台 `ROLE_ADMIN` 且具有项目 `ADMIN` membership 的身份；下载响应为
`attachment; filename="training_data.parquet"`，并带 `Cache-Control: no-store`。preview 的
`training_row_count` 定义为 Parquet 行数：每一行是一个 Candidate 手部观测落在一个人工 APPROVED
动作区间 `[start_ms, end_ms)` 内，而不是视频数。默认不含 MP4。

## 运行环境

```powershell
uv sync --project tools/minimal-export
```

只接受显式设置的 `DATA_PROFILE=synthetic`。当前没有正式的非合成数据授权模型，因此缺失、空值或非合成
profile 都会 fail closed；不要把真实患者数据、生产密钥或其他敏感数据放入仓库。

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
confidence
hand_presence
hand_mask_available
handedness_is_ground_truth
algorithm_key
algorithm_version
model_sha256
profile_sha256
landmark_00_x / landmark_00_y / landmark_00_z
...
landmark_20_x / landmark_20_y / landmark_20_z
```

`action_label` 只从 APPROVED 的人工 `AnnotationRevision.annotation_document.segments` 按 `[start_ms, end_ms)` 对齐；手部几何来自 `CandidateAnnotation`。`hand_presence=true` 表示该行有一个候选手，`hand_mask_available=false` 表示当前没有像素 mask，不能补造第二只手或假的 landmark。`confidence` 当前同源于 `handedness_score`，仅为 Candidate 置信度元数据；`handedness` 和 `handedness_score` 不是正式标签，文件元数据明确 `handedness_is_ground_truth=false`。
`algorithm_key`、`algorithm_version`、`model_sha256` 和 `profile_sha256` 是每行必需的 Candidate provenance；文件元数据同时固定 `hand_geometry_is_ground_truth=false` 和坐标系 `normalized_image_xy_and_mediapipe_relative_z`；`confidence` 的来源固定记录为 `candidate_annotation.handedness_score`，不表示动作标签置信度。

导出前同时校验视频 `INGESTED`、质量 `QUALIFIED`、人工复核 `APPROVED`、Cleaning `PASS + SUCCEEDED`，并绑定当前 asset/revision/quality 的摘要。未通过 Cleaning 的记录不会进入 Parquet。视频原文件只通过 `video_id` 回查，不写入输出。

`--force` 只覆盖命令行明确指定的输出文件。工具会在 `DATA_PROFILE` 缺失、为空或不是显式 `synthetic` 时直接拒绝运行。

## 后续转换为 LeRobot

当前结构保持平台无关：`video_id + timestamp_ms` 用于回查原视频帧，63 个 landmark 列可映射为手部观测，`action_label` 可映射为任务/类别。后续转换器应位于平台外部，按训练任务补充 episode/frame 索引；不得伪造机器人 `action` 或 `observation.state`，也不需要修改平台核心模型。
