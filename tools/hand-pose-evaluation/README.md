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
样本版本和样本摘要至少提供一个；两者都提供时，报告会同时保留。

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
