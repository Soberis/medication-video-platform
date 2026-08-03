# 数据集导出跨进程验收

## 1. 目的与边界

本手册验证已实现的 P0 `p0-dataset-export-v2` 最小闭环：Core API 创建项目和合成采集数据，并显式发起每段视频的媒体检测；Media Worker 完成技术检测，两个独立的合成身份完成标注、冻结和导出审批，Outbox 经 RabbitMQ 交给 Export Worker，最后由 Core API 登记 ZIP 并签发一次短期下载授权。

它只使用运行时生成的合成 MP4，只允许连接 `localhost`/loopback 且 `DATA_PROFILE=synthetic`。工具不接受视频路径、不读取真实数据、不输出或持久化任何预签名 URL、lease token 或账号密码。

这是一项本地验收，不是生产 UAT、性能压测、弱网演练或医院环境验收。

## 2. 人工审批与前置条件

运行会创建一个新的本地合成项目、两个 CaptureSession、不可变 DatasetVersion 和一个导出包；这些事实用于保留审计证据，工具不会自动删除或覆盖它们。因此，每次执行前需要由环境负责人明确批准。

1. 本机 Docker Desktop、Node.js 22、pnpm 11、Python 3.12、uv、ffmpeg 可用；
2. 未提交的 `.env` 只含本地合成配置，或直接使用仓库 `.env.example`；
3. `DATA_PROFILE=synthetic`，Core API 使用 `local` profile；
4. Core API、RabbitMQ、Media Worker 和 Export Worker 已启动且健康；
5. 运行环境不含真实患者视频、医院凭证或生产对象存储配置。

可用以下命令启动本地依赖：

```powershell
.\scripts\start-local.ps1 -Worker
```

macOS / Linux 使用等价命令：

```bash
./scripts/start-local.sh --worker
```

## 3. 执行

在环境负责人确认后，显式打开一次性开关：

```powershell
$env:RUN_DATASET_EXPORT_ACCEPTANCE='true'
pnpm run dataset-export:acceptance
```

macOS / Linux 可在单条命令中设置同一个一次性开关：

```bash
RUN_DATASET_EXPORT_ACCEPTANCE=true pnpm run dataset-export:acceptance
```

工具自行从未提交的仓库根 `.env` 或 `.env.example` 读取本地合成令牌；进程环境变量优先。它拒绝非 loopback 地址、非 `synthetic` 数据配置、缺少两个本地身份或未设置显式开关的执行。

每个媒体入库最多等待 120 秒，Export Worker 最多等待 180 秒。成功时最后一行以 `ACCEPTANCE_RESULT` 开头，包含合成项目/版本/导出 ID、两个独立审批、Worker attempt、已登记产物的大小与 SHA-256，以及短期下载后复核的 SHA-256；不包含下载 URL。

## 4. 通过条件

- 两个 CaptureSession 均经媒体检测、人工质量、独立标注复核和 post-clean 后变为 eligible；
- DatasetVersion 依次通过 `DRAFT → BUILDING → VALIDATING → FROZEN`；
- 导出申请人与批准人为不同的 ACTIVE 项目管理员；
- 导出首次 attempt 从 `QUEUED` 进入 `AVAILABLE`，并登记一个非空 ZIP 产物；
- 仅通过 Core API 取得的短期下载内容大小和 SHA-256 与登记产物一致；
- 命令退出码为 0，Core API、RabbitMQ、Media Worker 与 Export Worker 日志无未解释错误。

失败时不要手工修改数据库、伪造结果或重用下载 URL。保留脱敏错误码、运行时间和合成项目 ID，再按同一提交定位故障；成功输出应与提交 SHA 一起记录为本地验收证据。

## 5. 可重试失败恢复演练

以下演练只用于已经批准的本地合成环境。它会停止并重启**受本地脚本管理且仍在运行的 Export Worker**，并创建新的合成项目、Session、DatasetVersion 和导出审计事实；开始前必须人工确认没有 `QUEUED`、`RUNNING` 或 `RETRY_WAIT` 导出，RabbitMQ 中没有遗留导出消息，也没有手工启动或由其他终端管理的 Export Worker。故障注入会作用于新 Worker 收到的第一个首轮任务，不满足这些隔离条件可能影响其他本地导出。它不会修改 Core API、数据库或对象存储配置，也不会访问真实视频。

先确认未提交的本地 `.env` 若覆盖了 Export Worker 两个摘要，已与当前 `.env.example` 一致。环境负责人再手动执行：

```powershell
.\scripts\start-local.ps1 -Worker -RestartExportWorker -ExportWorkerSyntheticFailFirstAttempt
$env:RUN_DATASET_EXPORT_ACCEPTANCE='true'
pnpm run dataset-export:recovery-acceptance
.\scripts\start-local.ps1 -Worker -RestartExportWorker
```

macOS / Linux 使用等价的受控重启和故障注入入口：

```bash
./scripts/start-local.sh --worker --restart-export-worker --export-worker-synthetic-fail-first-attempt
cleanup_export_worker() {
  ./scripts/start-local.sh --worker --restart-export-worker
}
trap cleanup_export_worker EXIT
RUN_DATASET_EXPORT_ACCEPTANCE=true pnpm run dataset-export:recovery-acceptance
cleanup_export_worker
trap - EXIT
```

第一个命令只让新启动的 Export Worker 对一个首轮 attempt 人为返回一次已有的、可重试的 `PACKAGE_BUILD_FAILED` 结果；开关要求 `DATA_PROFILE=synthetic`、两项显式验收授权以及 loopback Core API/RabbitMQ，普通启动会强制关闭。恢复命令必须在输出中记录 `RETRY_WAIT`，并以第 2 次 attempt 的 `AVAILABLE`、产物登记和下载 SHA-256 一致结束。最后一个命令重启为无注入的正常 Worker。

本机合成恢复结果见第 9～11 节；每次复验仍须保留命令输出和对应提交。失败时保留脱敏错误码和合成项目 ID，不要手改数据库、重复使用授权 URL 或在非合成环境尝试。

## 6. Worker 租约失联恢复演练

以下演练只用于已经批准的本地合成环境。它会重启受本地脚本管理的 Core API 和
Export Worker，并临时使用 10 秒租约、2 秒心跳、2 秒 grace period 和 1 秒恢复轮询；
脚本进程结束后这些短时参数不会写回仓库或生产环境。开始前必须确认没有其他本地导出、
没有手工启动的 Worker，也没有真实视频、医院凭证或非 loopback 依赖。

故障模式默认关闭，并且同时要求 `DATA_PROFILE=synthetic`、loopback Core API/RabbitMQ、
`RUN_DATASET_EXPORT_ACCEPTANCE=true` 和独立的
`RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE=true`。它只让真实 Export Worker 在首个
attempt 已 acquire、完成临时 ZIP 上传后停止续租并跳过结果提交；不写数据库状态、不输出
lease token、预签名 URL、对象 key 或账号密码。恢复由 Core API 的现有租约回收逻辑完成。

PowerShell：

```powershell
.\scripts\start-local.ps1 -Worker -RestartApi -RestartExportWorker -ExportWorkerSyntheticLeaseLossFirstAttempt
$env:RUN_DATASET_EXPORT_ACCEPTANCE='true'
$env:RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE='true'
pnpm run dataset-export:lease-loss-acceptance
.\scripts\start-local.ps1 -Worker -RestartApi -RestartExportWorker
```

macOS / Linux：

```bash
./scripts/start-local.sh --worker --restart-api --restart-export-worker --export-worker-synthetic-lease-loss-first-attempt
cleanup_export_worker() {
  ./scripts/start-local.sh --worker --restart-api --restart-export-worker
}
trap cleanup_export_worker EXIT
RUN_DATASET_EXPORT_ACCEPTANCE=true RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE=true \
  pnpm run dataset-export:lease-loss-acceptance
cleanup_export_worker
trap - EXIT
```

通过条件：最终由第 2 个 attempt 变为 `AVAILABLE`，并且只有一个已登记产物，下载后的
SHA-256 与登记值一致。`RETRY_WAIT` 可能短于验收工具的轮询间隔，因此若输出中观察到该
状态只作为辅助信息，不作为通过前提。现有
`DatasetExportRecoveryServiceTests` 继续断言首个 attempt 为 `LOST`、错误/审计 reason 为
`WORKER_LEASE_LOST` 并调用旧输出清理；本公共数据集版本查询只返回
当前 attempt，因此验收工具不会伪造或猜测历史 attempt 字段。若需要留存完整证据，应同时
保存该测试结果和本次脱敏命令输出。

失败时不要手工修改数据库、重用授权 URL 或再次使用故障 Worker；先执行最后的正常重启，
再保留脱敏日志和合成项目 ID 定位问题。未经明确批准不要运行这组会改变本地服务状态的命令。

## 7. 尚未覆盖

- 生产身份、生产 S3、医院网络和真实视频；
- 不可重试失败后的人工恢复和 DLQ 演练；
- 大数据集吞吐、并发导出、限带宽或断网；
- 正式 UAT、性能 SLO 与生产发布审批。

## 8. 2026-07-31 本地合成基线结果

本次在 Windows 本机 loopback、Core API `local` profile、Docker Compose 的 PostgreSQL 18.4、SeaweedFS 4.29 和 RabbitMQ 4.3.2，以及 Node.js 22.22.2、Media Worker、Export Worker 全部运行时执行。输入为工具即时生成的两段合成 MP4；未使用真实视频、生产凭证或外部对象存储。

最终运行结果：

```json
{
  "schema_version": "dataset-export-cross-process-acceptance-v1",
  "project_id": "029ccab4-b13d-4b15-832e-e6c93b1d0f95",
  "dataset_version_id": "83ca646f-762b-48df-967d-67c8da10df6f",
  "dataset_export_id": "cbf3b148-1f32-47cd-abb1-f9680d42cf8d",
  "capture_session_count": 2,
  "independent_approvals": true,
  "worker_attempt": 1,
  "export_status": "AVAILABLE",
  "artifact_size_bytes": 381064,
  "artifact_sha256": "b53abbf36a572d237a6d320d5c10b8034a873f1a3db5cc887ff26b8d8770c865",
  "downloaded_sha256": "b53abbf36a572d237a6d320d5c10b8034a873f1a3db5cc887ff26b8d8770c865",
  "duration_ms": 115263
}
```

这证明了 Core API、Outbox、RabbitMQ、两个 Worker、独立审批和短期下载校验的本地合成实现闭环；它不替代正式 UAT、生产对象存储演练或性能验证。

## 9. 2026-07-31 本地合成恢复结果

本次基于提交 `58aa365`，在第 8 节相同的本机合成环境中执行第 5 节命令。执行前确认进行中的导出数为 0；结束后已将 Export Worker 重启为无故障注入的正常模式。

最终运行结果：

```json
{
  "schema_version": "dataset-export-cross-process-acceptance-v1",
  "project_id": "16de8ed2-7c3f-4b4b-8e99-1e8e467f9d50",
  "dataset_version_id": "e3e881c7-c02c-4092-a5d5-57904b24e1f7",
  "dataset_export_id": "6dcf9812-6ea5-4855-92eb-4bf833ccb2a9",
  "capture_session_count": 2,
  "independent_approvals": true,
  "recovery_mode": true,
  "observed_export_statuses": [
    "QUEUED",
    "RUNNING",
    "RETRY_WAIT",
    "AVAILABLE"
  ],
  "worker_attempt": 2,
  "export_status": "AVAILABLE",
  "artifact_size_bytes": 381064,
  "artifact_sha256": "5955f1ee816bba299d3098ff9c718915db858dc381c64cc2c1ddce7c01924388",
  "downloaded_sha256": "5955f1ee816bba299d3098ff9c718915db858dc381c64cc2c1ddce7c01924388",
  "duration_ms": 176329
}
```

数据库复核显示首轮 attempt 为 `FAILED/PACKAGE_BUILD_FAILED`，第 2 轮为 `SUCCEEDED`，导出终态为 `AVAILABLE`。这证明可重试构建失败能够经 Core API 的有上限策略生成新 attempt，并由真实 Worker 完成恢复；它不证明失联租约、DLQ、并发、生产网络或正式 UAT 已通过。

## 10. 2026-07-31 macOS 本地合成恢复结果

本次以 `16df94d` 为代码基线，并叠加本轮尚未提交的 POSIX 启动脚本改动，在 Apple Silicon macOS 本机 loopback 环境执行。运行时为 Java 21.0.12、Node.js 22.23.1、pnpm 11.11.0、uv 管理的 Python 3.12.13，以及 Docker Compose 的 PostgreSQL 18.4、SeaweedFS 4.29 和 RabbitMQ 4.3.2。执行前数据库中没有 `QUEUED`、`RUNNING` 或 `RETRY_WAIT` 导出，导出队列没有 ready/unacknowledged 消息且只有一个受脚本管理的 Export Worker；输入仍为工具即时生成的两段合成 MP4。

最终运行结果：

```json
{
  "schema_version": "dataset-export-cross-process-acceptance-v1",
  "project_id": "eb60c16d-b132-4e6d-bc4b-b0b1b49c706e",
  "dataset_version_id": "1a89a379-a6d4-4543-b977-c01f016d690d",
  "dataset_export_id": "b973bd97-2d06-414d-8ac7-85734152ade8",
  "capture_session_count": 2,
  "independent_approvals": true,
  "recovery_mode": true,
  "observed_export_statuses": [
    "QUEUED",
    "RETRY_WAIT",
    "AVAILABLE"
  ],
  "worker_attempt": 2,
  "export_status": "AVAILABLE",
  "artifact_size_bytes": 380894,
  "artifact_sha256": "a4ac92220229678f0940577f63de8a5aafed3237ba44fa79a9f25dc6b707bb3e",
  "downloaded_sha256": "a4ac92220229678f0940577f63de8a5aafed3237ba44fa79a9f25dc6b707bb3e",
  "duration_ms": 11958
}
```

数据库复核显示 attempt 1 为 `FAILED/PACKAGE_BUILD_FAILED`，attempt 2 为 `SUCCEEDED`，导出终态为 `AVAILABLE`。结束后已受控重启正常 Export Worker，并直接确认 `EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT=false`、`RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE=false`；导出和媒体队列均为 0 条 ready/unacknowledged 消息且各有一个消费者。该结果补齐 macOS 的可重试构建失败恢复证据，但仍不替代失联租约、DLQ、并发或正式 UAT。

## 11. 2026-08-03 Windows 本地合成租约失联恢复结果

本次在 Windows 本机 loopback 合成环境执行一次租约失联验收，基线为 `main/1761744d21824a3a05c26a59d9af25ea97d55717`。执行前确认工作区干净、没有 `QUEUED`、`RUNNING` 或 `RETRY_WAIT` 导出，导出队列没有 ready/unacknowledged 消息；运行时只使用本地 PostgreSQL、SeaweedFS、RabbitMQ、Core API、Media Worker 和 Export Worker，未使用真实视频、生产凭证或外部服务。

本次合成证据：

```text
project_id: 3d70fe12-6a2d-449a-8e2d-ed9fe0a04bbf
dataset_version_id: bf1d9779-adbb-43e7-80fe-2705f5244f7f
dataset_export_id: a79e29dc-fd71-4620-b987-4f980421cd03
export_status: AVAILABLE
active_export_count: 0
attempt_1: LOST / WORKER_LEASE_LOST
attempt_2: SUCCEEDED
artifact_count: 1
artifact_size_bytes: > 0
artifact_sha256: ea9087841b80eb52a3ffce76ea44155500883f9c2a3a8fa89e0fe5d7ae6b7706
acceptance_exit_code: 0
acceptance_command_reruns: 0
```

审计依次记录 `DATASET_EXPORT_RETRY_SCHEDULED/WORKER_LEASE_LOST` 和
`DATASET_EXPORT_AVAILABLE/EXPORT_PACKAGE_VERIFIED`。恢复后的 API 为 UP，Export queue
为 ready `0`、unacknowledged `0`、consumer `1`；正常租约参数恢复为
`lease_duration=PT2H`、`heartbeat_interval=PT5M`、`grace=PT5M`、`recovery_poll_delay=PT30S`，故障注入关闭。数据库、审计和 Artifact 登记由独立只读查询复核；
验收工具 stdout 未被当前工具层保留，因此本节不声称直接复核下载响应中的 SHA 字段。

该结果补齐本地合成环境的 Worker 失联租约恢复证据；DLQ、并发、大数据集、生产网络和正式 UAT
仍按第 7 节保持未覆盖。
