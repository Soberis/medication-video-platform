# CandidateAnnotation capacity review

这是一个只读、离线、可复现的开发工具，用于在决定 P1 批量手部预标注前形成容量证据。它不连接
PostgreSQL、S3、RabbitMQ、Worker 或任何生产系统，也不修改平台数据。

容量评审是 V1.18 进入 P1 前的门禁。它帮助后续明确比较：

- A：PostgreSQL 保存经过分区、索引和保留策略验证的 dense `CandidateAnnotation`；
- B：PostgreSQL 保存 job/summary/index 事实，帧级 dense 结果保存为版本化 S3 JSONL/Parquet 工件。

工具本身不会启用 P1 批量 API、自动策略、dense-result migration、S3 runtime、后台任务、数据库 schema
或产品 UI。证据不足时报告必须保持 `DECISION PENDING`。

## 运行

要求 Python 3.12 和 uv。工具没有运行时依赖，开发依赖由 `uv.lock` 固定：

```powershell
uv sync --project tools/candidate-capacity-review
uv run --project tools/candidate-capacity-review `
  python tools/candidate-capacity-review/candidate_capacity_review.py `
  --input tools/candidate-capacity-review/scenarios/synthetic-pending.json `
  --json-output .\reports\candidate-capacity-review.json `
  --markdown-output .\reports\candidate-capacity-review.md
```

输出包括机器可读 JSON 和简洁 Markdown；输入只是一份显式、版本化 scenario 文件。

## Scenario schema

顶层 `schema_version` 必须为 `candidate-capacity-review.v1`。所有容量或查询输入都使用：

```json
{
  "status": "MEASURED | ASSUMED | PENDING",
  "value": "实际值；PENDING 时必须为 null",
  "note": "来源、范围或为何仍是假设"
}
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `average_video_duration_minutes` | 单路相机视频的平均时长 |
| `p95_video_duration_minutes` | 单路相机视频时长 P95 |
| `average_camera_count` | 每个采集 session 的平均固定相机路数 |
| `videos_per_day` | 日采集量；由 `video_count_basis` 说明单位 |
| `video_count_basis` | `camera_video` 表示已是单路视频数；`capture_session` 表示需乘相机数 |
| `inference_fps` | 每路视频的 AI 推理采样率 |
| `max_hands_per_frame` | 每个推理帧最多保留的手数 |
| `observed_or_assumed_candidate_density` | 实际候选数 / 理论帧-手槽位，范围 0 到 1 |
| `estimated_candidate_row_bytes` | 一个候选行及其基本字段的估算字节数 |
| `index_overhead_ratio` | 索引及相关开销 / 原始行存储，范围 0 到 1 |
| `growth_factor` | 规划放大倍数，至少为 1；用于保留量和存储估算 |
| `expected_query_patterns` | 预期热查询、长窗口查询或批量读取模式 |

`retention_days` 必须至少包含 `30`、`90`、`365`。`decision_criteria` 配置 PostgreSQL 行数/存储阈值、
两种方案的查询模式，以及分区/索引可行性、运维复杂度和对象工件取回权衡的证据状态。决策需要足够的
`MEASURED` 输入；只有假设值时不会自动选择 A 或 B。

## 公式

理论基线严格复现 V1.18：

```text
theoretical_candidates_per_minute
  = inference_fps × 60 × max_hands_per_frame
```

因此 `10 FPS × 60 × 2 = 1,200` 条候选/分钟。其余计算为：

```text
daily_camera_video_assets
  = videos_per_day                         if basis == camera_video
  = videos_per_day × average_camera_count  if basis == capture_session

average_case_rows_per_video
  = average_duration_minutes × theoretical_candidates_per_minute × candidate_density

p95_duration_rows_per_video
  = p95_duration_minutes × theoretical_candidates_per_minute × candidate_density

theoretical_upper_bound_rows_per_video
  = average_duration_minutes × theoretical_candidates_per_minute

retained_rows
  = rows_per_day × retention_days × growth_factor

raw_storage_bytes
  = retained_rows × estimated_candidate_row_bytes

storage_bytes_including_index_overhead
  = raw_storage_bytes × (1 + index_overhead_ratio)
```

所有估算行数和字节数向上取整，避免容量规划因小数被低估。PENDING 输入会在相关结果中保留为 null，
而不被工具偷偷替换成默认生产数字。

## 如何解释

报告分为 `FACTS`、`ASSUMPTIONS`、`CALCULATED RESULTS`、`PENDING EVIDENCE` 和 `DECISION GATES`。
固定理论公式属于事实；标为 `ASSUMED` 的数值只代表当前场景假设；`MEASURED` 只表示输入文件明确携带
的实测证据，不会由工具推断。决策默认使用最大保留期的 P95-duration case 检查配置阈值。

这不是成本平台，也不是生产容量承诺。进入 P1 前仍需补充授权或合成测量：时长分布、相机路数、日量、
候选密度、单行/索引实测大小、30/90/365 日保留需求、典型查询的 P50/P95/P99、PostgreSQL 分区与索引
可行性、运维复杂度，以及 S3 JSONL/Parquet 长窗口和范围读取的实际权衡。工具输出不能单独开启 P1。
