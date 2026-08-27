# 离线手部关键点评估证据工具

`hand-pose-evaluation` 是 V18-FR-006 所需的最小离线证据能力。它只读取操作人员明确提供的本地 JSON
证据文件，并生成可追溯的规范化 JSON 和 Markdown 摘要。

它不是运行时功能，不是 AI Evaluation 平台，也不是产品 API。工具不会：

- 查询数据库、调用医院系统或访问 Core API；
- 下载模型、读取视频、上传证据或修改平台状态；
- 启动 Worker、队列或评估服务；
- 生成模型二进制、患者身份、医院账号、令牌、预签名 URL 或原始视频内容。

## 证据边界

格式版本固定为 `hand-pose-evaluation.v1`，定义见 [`evidence.schema.json`](./evidence.schema.json)。每条记录必须绑定：

- 已授权且隔离的评估样本版本和/或 SHA-256；
- `model_sha256` 和 `profile_sha256`；
- 参数调整说明；
- 七个冻结指标；
- 三个 P0 不变量的显式证据记录。

`sample_authorization.status` 必须为 `AUTHORIZED`，且必须带有不包含路径、URL 或敏感身份信息的证据引用或说明。
证据 JSON 必须显式包含 `sample_version` 和 `sample_sha256` 两个字段，其中一个可以为 `null`，但至少一个必须实际提供；两者都提供时，报告会同时保留。

## 指标语义

七个指标是：

1. `HAND_FRAME_RECALL`
2. `NO_HAND_FRAME_FALSE_POSITIVE_RATE`
3. `LANDMARK_21_VALID_RATE`
4. `HANDEDNESS_ACCURACY`
5. `FAST_MOTION_OCCLUSION_USEFULNESS`
6. `OVERLAY_TIME_ERROR`
7. `PROCESSING_TIME_PER_MINUTE`

每个指标必须是 `PENDING` 或 `RECORDED`。`PENDING` 的 `value` 必须为 `null`，不表示 0；`RECORDED` 必须是有限非负数。
前五个比例指标必须位于 `0..1`。没有授权评估样本或没有实际证据时，应保持 `PENDING`，不能填入推测值。

P0 不变量为：

- `WORKER_INTERRUPTION_RECOVERY`：Worker 中断后能够恢复或安全重试；
- `ORIGINAL_VIDEO_UNMODIFIED`：评估过程不会改写原视频；
- `CANDIDATE_NOT_FORMAL_ANNOTATION`：AI `CandidateAnnotation` 不会自动变成正式人工标注。

不变量可以是 `PASS` 或 `PENDING`。`PASS` 必须带 `evidence.reference` 或 `evidence.description`，不能用没有证据的断言冒充通过。

## 参数调整

`parameter_adjustment_note` 记录本次评估使用的 ROI、采样率、阈值或其他参数调整。没有调整时也要明确写出“未调整”或等价说明；
更换模型时应使用新的评估记录和新的模型 SHA-256，不覆盖旧记录。

## 授权真实样本操作清单

当前仓库没有授权真实手部视频。本清单只说明未来如何执行，不产生评估结果；
`tests/fixtures/synthetic_evidence.json` 只能验证证据格式，不能作为真实样本或算法效果证据。

### 1. 准备隔离样本

- 取得批准记录和一个仅供本次评估使用的隔离样本；样本、模型和中间文件都不得放入仓库、日志或报告输出目录。
- 为本次运行创建唯一的 `evaluation_id`。
- 从批准记录取得 `sample_version`；对实际样本文件计算 SHA-256，推荐同时填写版本和哈希：

  ```powershell
  $samplePath = 'authorized-evaluation/sample.mp4'
  $sampleSha256 = (Get-FileHash -LiteralPath $samplePath -Algorithm SHA256).Hash.ToLowerInvariant()
  $sampleSha256
  ```

- 证据 JSON 的 `sample_authorization.status` 必须为 `AUTHORIZED`，`evidence` 只填写安全的批准记录引用或说明，不能写本地路径、URL、患者或医院身份信息。
- 证据 JSON 只保存 `sample_version` 和/或 `sample_sha256`，不保存原始视频路径或视频内容。

### 2. 固定模型、profile 和参数

- 使用批准的、与本次运行一致的模型文件，计算其 SHA-256，并确认它等于 Worker 配置和作业中冻结的 `model_sha256`：

  ```powershell
  $modelPath = 'approved-model/hand_landmarker.task'
  $modelSha256 = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant()
  $modelSha256
  ```

- 从同一批准 Worker 构建或运行记录取得 `profile_sha256`，不得手工猜测或替换。当前 profile 的 provenance 为
  `algorithm_key=MEDIAPIPE_HAND_LANDMARKER`、`algorithm_version=v1`，并包含采样率、最大手数、阈值和
  handedness/timestamp/bounding-box policy；profile SHA 必须与该 profile 完全匹配。若 Worker 环境已就绪，可只读取得冻结值：

  ```powershell
  uv run --project workers/ai-worker python -c "from medication_ai_worker.landmarker import PROFILE_SHA256; print(PROFILE_SHA256)"
  ```

- 在 `parameter_adjustment_note` 中记录实际使用的参数。没有调整时写明“未调整，使用冻结 P0 profile”；有调整时写清原值、新值和原因。
  更换模型或 profile 必须创建新的 `evaluation_id`，不能覆盖旧证据。

### 3. 填写冻结指标和不变量

七项指标必须各出现一次，并填写 `status` 与 `value`：

| 指标 | 单位 | 记录要求 |
| --- | --- | --- |
| `HAND_FRAME_RECALL` | rate | 按冻结评估方法记录实际测量值 |
| `NO_HAND_FRAME_FALSE_POSITIVE_RATE` | rate | 按冻结评估方法记录实际测量值 |
| `LANDMARK_21_VALID_RATE` | rate | 按冻结评估方法记录实际测量值 |
| `HANDEDNESS_ACCURACY` | rate | 必须有授权人工参考；Candidate 的 handedness 只是辅助元数据 |
| `FAST_MOTION_OCCLUSION_USEFULNESS` | rate | 按冻结评估方法记录实际测量值 |
| `OVERLAY_TIME_ERROR` | milliseconds | 记录实际时间误差 |
| `PROCESSING_TIME_PER_MINUTE` | seconds_per_video_minute | 记录实际处理耗时 |

前五项 rate 必须在 `0..1`，其他已记录值必须是有限非负数。没有真实测量依据时保持
`PENDING` 且 `value: null`，不得用 0、估计值或合成 fixture 的值代替。
本工具只校验和整理操作员提供的值，不读取视频、不运行模型，也不计算指标；若尚无批准的测量记录或计算依据，不能标记为
`RECORDED`。

三个 P0 不变量也必须各出现一次：

- `WORKER_INTERRUPTION_RECOVERY`：提供实际中断后恢复或安全重试的证据；
- `ORIGINAL_VIDEO_UNMODIFIED`：提供原视频评估前后完整性校验的证据；
- `CANDIDATE_NOT_FORMAL_ANNOTATION`：提供 Candidate 未自动成为正式人工标注的证据。

只有有实际证据时才能标记 `PASS` 并填写 `evidence.reference` 或 `evidence.description`，否则保持
`PENDING`。Candidate 几何和 handedness 不能代替人工 Ground Truth，也不能改变人工标注、Review 或 Cleaning 语义。

### 4. 校验、规范化并生成报告

将新的证据 JSON 和输出目录放在批准的隔离位置，然后执行：

```powershell
$evidencePath = 'authorized-evaluation/hand-pose-evidence.json'
$outputDir = 'authorized-evaluation/validated-output'

uv lock --check --project tools/hand-pose-evaluation
uv run --project tools/hand-pose-evaluation `
  python tools/hand-pose-evaluation/hand_pose_evaluation.py `
  validate $evidencePath
uv run --project tools/hand-pose-evaluation `
  python tools/hand-pose-evaluation/hand_pose_evaluation.py `
  normalize $evidencePath --output "$outputDir\normalized-evidence.json"
uv run --project tools/hand-pose-evaluation `
  python tools/hand-pose-evaluation/hand_pose_evaluation.py `
  report $evidencePath --output $outputDir
```

确认 `validate` 成功、规范化 JSON 与报告可回读，且输出中没有样本路径、原始视频或模型文件。
工具成功校验只表示证据格式和约束合法，不表示算法效果通过；在授权审查和真实指标证据完成前，V18-FR-006 继续保持 `PI`。

## 使用

工具采用仓库统一的 Python 3.12 + uv 约定：

```powershell
uv lock --check --project tools/hand-pose-evaluation
uv run --project tools/hand-pose-evaluation `
  python tools/hand-pose-evaluation/hand_pose_evaluation.py `
  validate tools/hand-pose-evaluation/tests/fixtures/synthetic_evidence.json
```

规范化为确定性 JSON：

```powershell
uv run --project tools/hand-pose-evaluation `
  python tools/hand-pose-evaluation/hand_pose_evaluation.py `
  normalize evidence.json --output normalized-evidence.json
```

生成机器可读 JSON 和人类可读 Markdown：

```powershell
uv run --project tools/hand-pose-evaluation `
  python tools/hand-pose-evaluation/hand_pose_evaluation.py `
  report evidence.json --output .\evaluation-report
```

`validate`、`normalize` 和 `report` 在证据不合法时均以非零状态退出。生成报告只包含格式允许的摘要字段，
不包含视频、模型文件或样本路径。合成 fixture 仅展示证据格式和校验行为，不代表任何真实算法准确率。
