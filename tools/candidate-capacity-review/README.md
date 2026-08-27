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

`scenarios/measured-template.json` 是空白测量模板：所有观测输入和决策边界都保持
`PENDING`/`null`，不含生产规模或容量数字。复制后只填写有来源的值，将对应状态改为
`MEASURED`，并保留非空 `note` 说明样本范围、测量方法或待补证据原因。

## Scenario schema

顶层 `schema_version` 必须为 `candidate-capacity-review.v1`。所有容量或查询输入都使用：

```json
{
  "status": "MEASURED | ASSUMED | PENDING",
  "value": "实际值；PENDING 时必须为 null",
  "note": "必填：来源、范围、测量方法或为何仍是假设"
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
| `estimated_candidate_row_bytes` | 一个候选行及其基本字段的实际或估算字节数；实际基准标记为 `MEASURED` |
| `index_overhead_ratio` | 索引及相关开销 / 原始行存储，范围 0 到 1 |
| `growth_factor` | 规划放大倍数，至少为 1；用于保留量和存储估算 |
| `expected_query_patterns` | 预期热查询、长窗口查询或批量读取模式 |

`retention_days` 必须显式提供且至少包含 `30`、`90`、`365`；工具不会为缺失的保留期套用默认值。
`decision_criteria.required_measured_inputs` 必须列出全部容量证据输入，工具也会固定检查全部这些输入
是否为 `MEASURED`，不能通过缩短列表绕过 FPS、最大手数或增长因子。决策边界中的 PostgreSQL 行数/存储
阈值和两种方案的查询模式可以明确写为 `null` 表示待补；这会使对应决策 Gate 保持 `PENDING`，不会被
工具填入默认值。分区/索引可行性、运维复杂度和对象工件取回权衡继续使用带状态的证据对象。

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

## 证据采集清单

建议使用同一个明确的授权或合成观察窗口填写模板，并在每个 `MEASURED` 值的 `note` 中记录样本范围和方法：

| 证据 | 最小记录内容 |
| --- | --- |
| 平均/P95 视频时长 | 单路视频样本范围、样本数、平均值和 P95；P95 不能小于平均值 |
| 平均相机数/日量 | session 窗口、相机路数统计、日量单位及 `video_count_basis` |
| 推理 FPS/每帧最大手数 | 实际目标 profile、版本和抽样 run summary |
| 候选密度 | 候选数、推理帧数、手槽位定义及计算结果 |
| 单行大小/索引开销 | 代表性 PostgreSQL 表、分区/索引配置、测量方法和原始大小 |
| 增长因子 | 规划窗口、基准来源和放大计算；没有依据时保持 `PENDING` |
| 查询模式 | 热查询、时间窗口/批量范围、返回规模及 P50/P95/P99 |
| PostgreSQL 可行性 | 分区、索引、保留期基准结果；结论证据写入 criteria `note` |
| 运维复杂度/S3 取回权衡 | 操作步骤、监控/恢复负担，以及 JSONL/Parquet 长窗口或范围读取结果 |

`decision_criteria` 中的阈值和两种方案查询模式若尚未批准，可以写 `null`；工具会输出
`decision_criteria_configuration: PENDING`，不会用示例数字替代。只有全部容量输入和决策 criteria 证据
均为 `MEASURED`，且对应 Gate 通过时，报告才可能从 `DECISION PENDING` 进入某个方案；本工具不自动开启
FR-007 或 FR-008。
