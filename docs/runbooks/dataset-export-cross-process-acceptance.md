# 数据集导出跨进程验收

## 1. 目的与边界

本手册验证已实现的 P0 `p0-dataset-export-v2` 最小闭环：Core API 创建项目和合成采集数据，并显式发起每段视频的媒体检测；Media Worker 完成技术检测；一个 `ROLE_ADMIN` + ACTIVE 项目 `ADMIN` Membership 的合成管理员完成标注、自审、冻结和导出审批，另一个 `ROLE_MEMBER` 合成身份只用于管理员命令的无副作用反向验证；Outbox 经 RabbitMQ 交给独立身份的 Export Worker，最后由 Core API 登记 ZIP 并签发一次短期下载授权。

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

工具自行从未提交的仓库根 `.env` 或 `.env.example` 读取本地合成令牌；进程环境变量优先。`DEV_ADMIN_BEARER_TOKEN` 必须对应合成管理员，`DEV_BEARER_TOKEN` 必须保持 `ROLE_MEMBER`，不得在验收过程中提权。工具拒绝非 loopback 地址、非 `synthetic` 数据配置、缺少这两个本地身份或未设置显式开关的执行。

每个媒体入库最多等待 120 秒，Export Worker 最多等待 180 秒。成功时最后一行以 `ACCEPTANCE_RESULT` 开头，Schema 为 `dataset-export-cross-process-acceptance-v2`；结果包含合成项目/版本/导出 ID、`approval_mode=SELF_ADMIN_APPROVAL`、`self_admin_approval` 中的管理员及三组申请/批准 actor、`member_permission_checks` 中三项无状态变化拒绝、Worker attempt、已登记产物的大小与 SHA-256，以及短期下载后复核的 SHA-256；不包含下载 URL。历史双管理员 v1 证据只证明当时的 V1.10 流程，不能替代本命令或正式 UAT 的 V1.11 单管理员证据。

## 4. 通过条件

- 两个 CaptureSession 均经媒体检测、人工质量、复核和 post-clean 后变为 eligible；其中一条由同一合格管理员提交并复核，提交者与复核者 actor 相同，另一条保留 `ROLE_MEMBER` 提交、管理员复核路径；
- DatasetVersion 依次通过 `DRAFT → BUILDING → VALIDATING → FROZEN`；
- 冻结请求/决定和导出请求/决定的申请人与批准人均为同一合格管理员，审批模式为 `SELF_ADMIN_APPROVAL`；
- `ROLE_MEMBER` 对复核、冻结、导出批准的请求均返回 `403 + PROJECT_ACCESS_DENIED`，且脚本读取的聚焦业务状态前后不变；
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
- 大数据集吞吐、并发导出、限带宽或断网；
- 正式 UAT、性能 SLO 与生产发布审批。

## 8. 2026-07-31 本地合成基线结果

第 8～12 节按原样保留既有执行证据，不代表当前 V1.11 规则。其中 JSON 的
`schema_version=dataset-export-cross-process-acceptance-v1` 和 `independent_approvals=true` 明确属于
V1.10 双管理员历史输出；不得改写历史事实，也不得将其计作 `SELF_ADMIN_APPROVAL` 验收通过。

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

这证明了 Core API、Outbox、RabbitMQ、两个 Worker、审批和短期下载校验的本地合成实现闭环；历史执行使用 V1.10 双管理员事实，不能替代 V1.11 单管理员正式 UAT、生产对象存储演练或性能验证。

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

## 12. Export DLQ 管理员受控重放（2026-08-04 隔离本机合成验收通过）

本节提供本地合成演练的可审计入口；2026-08-04 已在隔离 Windows loopback 环境完成一次真实 RabbitMQ DLQ 重放，结果见第 12.5 节。
它使用 `pnpm run dataset-export:dlq-prepare` 与 `pnpm run dataset-export:dlq-replay` 两个独立模式，要求同时设置普通验收授权和独立的
`RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE=true`。工具只允许 loopback Core API、RabbitMQ
AMQP 和 Management API，且强制 `DATA_PROFILE=synthetic`；不读取数据库，不接受原始任务 body，
也不会把 lease token、签名 URL、对象位置或其他敏感字段提交给重放 API。
准备模式复用 `DEV_PROJECT_ID` 指向的本地合成引导项目，而不是新建项目：受控重放接口按当前本地
Bearer 的项目上下文授权。它仍会在该项目下新建合成会话、DatasetVersion 和导出。

### 12.1 前置隔离与故障注入

开始前由环境负责人确认：本机没有其他 `QUEUED`、`RUNNING` 或 `RETRY_WAIT` 导出；导出业务队列和
死信队列没有遗留演练消息；没有第二个手工 Export Worker；所有输入均为工具生成的合成视频。
不要运行 `rabbitmqctl purge_queue`，也不要删除队列、交换机、vhost 或 Volume。

为让真实 Export Worker 产生带有可信 `message_id`、`export_id` 和 `attempt` 的
`DATASET_EXPORT_JOB_ACQUIRE_REJECTED` 诊断，使用一个仅存在于本地临时文件中的无效合成身份。
`EXPORT_WORKER_BEARER_TOKEN_FILE` 必须是绝对路径；Worker 每次调用 Core API 前重新读取该文件。
不要把 token 内容写入命令输出、日志或验收证据。

PowerShell 示例（在独立终端中运行，并由本地进程主管理 Export Worker 的 worker-only 停止/启动；
不要用会同时停止 Core API 的整机 stop 命令）：

```powershell
$ErrorActionPreference = 'Stop'
$tokenFile = Join-Path $env:TEMP "dataset-export-dlq-token-$([guid]::NewGuid()).txt"
$validToken = $env:EXPORT_WORKER_BEARER_TOKEN
if ([string]::IsNullOrWhiteSpace($validToken) -or $validToken.Length -lt 32) {
    throw 'Load the approved synthetic export-worker token into the process without printing it.'
}
[IO.File]::WriteAllText($tokenFile, ('x' * 64), [Text.UTF8Encoding]::new($false))
$env:DATA_PROFILE = 'synthetic'
$env:PLATFORM_API_BASE_URL = 'http://localhost:8080'
$env:RABBITMQ_URL = 'amqp://medication_local:change-me-local-only@127.0.0.1:5672/medication'
$env:EXPORT_WORKER_BEARER_TOKEN_FILE = $tokenFile
Remove-Item Env:EXPORT_WORKER_BEARER_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE -ErrorAction SilentlyContinue
Remove-Item Env:EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT -ErrorAction SilentlyContinue
Remove-Item Env:RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE -ErrorAction SilentlyContinue
Remove-Item Env:EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT -ErrorAction SilentlyContinue
uv run --directory workers/export-worker export-worker
```

在另一个已配置合格管理员本地令牌的终端运行 DLQ 准备模式：

```powershell
$env:DATA_PROFILE = 'synthetic'
$env:RUN_DATASET_EXPORT_ACCEPTANCE = 'true'
$env:RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE = 'true'
# 必须与本地 Bearer 的当前项目相同；.env 的默认值是合成引导项目。
$env:DEV_PROJECT_ID = '00000000-0000-0000-0000-000000000001'
pnpm run dataset-export:dlq-prepare
```

工具在首次导出进入 `QUEUED` 后会输出一行 `ACCEPTANCE_SETUP`，其中只有
`project_id`、`dataset_version_id`、`dataset_export_id` 和 `first_attempt`，然后以退出码 0 正常结束；
它不会启动等待循环，也不需要 Ctrl+C。无效身份造成的
`dataset-export-dead-letter.v1` 诊断应随后进入
`platform.dataset-export.dead-letter.v1`；不要等待普通模式超时，也不要重用其他项目的 ID。
保留该行和脱敏错误码作为证据。

停止无效 Worker，仍保留 Core API、RabbitMQ 和对象存储运行。把临时文件原子替换为有效的本地
Export Worker token（不打印文件内容），然后以同一 `EXPORT_WORKER_BEARER_TOKEN_FILE` 启动一个
正常 Worker。下面的 PowerShell 片段把原子替换、工具执行和恢复放在同一控制窗口；
`Start-Process` 只启动临时 Worker，工具本身不管理进程，worker-only 停止/启动仍由环境负责人确认：

```powershell
$replacement = "$tokenFile.next"
$workerProcess = $null
try {
    [IO.File]::WriteAllText($replacement, $validToken, [Text.UTF8Encoding]::new($false))
    [IO.File]::Replace($replacement, $tokenFile, $null) # NTFS 同卷原子替换，不输出内容
    $workerProcess = Start-Process -FilePath 'uv' `
        -ArgumentList @('run', '--directory', 'workers/export-worker', 'export-worker') `
        -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput 'logs/local/dlq-replay-export-worker.log' `
        -RedirectStandardError 'logs/local/dlq-replay-export-worker.error.log'

    $env:RUN_DATASET_EXPORT_ACCEPTANCE = 'true'
    $env:RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE = 'true'
    $env:DATASET_EXPORT_DLQ_REPLAY_PROJECT_ID = '<ACCEPTANCE_SETUP.project_id>'
    $env:DATASET_EXPORT_DLQ_REPLAY_DATASET_VERSION_ID = '<ACCEPTANCE_SETUP.dataset_version_id>'
    $env:DATASET_EXPORT_DLQ_REPLAY_EXPORT_ID = '<ACCEPTANCE_SETUP.dataset_export_id>'
    $env:DATASET_EXPORT_DLQ_REPLAY_REASON_CODE = 'AUTHORIZATION_FIXED'
    $env:DATASET_EXPORT_DLQ_REPLAY_CHANGE_REFERENCE = 'local-dlq-replay-20260803'
    $env:DATASET_EXPORT_DLQ_REPLAY_IDEMPOTENCY_KEY = 'local-dlq-replay-command-20260803'
    pnpm run dataset-export:dlq-replay
}
finally {
    if ($null -ne $workerProcess -and -not $workerProcess.HasExited) {
        taskkill.exe /PID $workerProcess.Id /T /F | Out-Null
    }
    Remove-Item -LiteralPath $replacement, $tokenFile -Force -ErrorAction SilentlyContinue
    Remove-Item Env:EXPORT_WORKER_BEARER_TOKEN_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:EXPORT_WORKER_BEARER_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE -ErrorAction SilentlyContinue
    Remove-Item Env:RUN_DATASET_EXPORT_ACCEPTANCE -ErrorAction SilentlyContinue
    Remove-Item Env:RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE -ErrorAction SilentlyContinue
    Remove-Item Env:EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT -ErrorAction SilentlyContinue
    Remove-Item Env:RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE -ErrorAction SilentlyContinue
    Remove-Item Env:EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT -ErrorAction SilentlyContinue
    .\scripts\start-local.ps1 -Worker -RestartExportWorker
}
```

`finally` 必须执行，即使 `pnpm run dataset-export:dlq-replay` 失败；它不删除 RabbitMQ
队列或数据库事实，只关闭临时 Worker、删除临时 token 文件并恢复受脚本管理的正常 Worker。

### 12.2 读取诊断并调用管理员重放

从 `ACCEPTANCE_SETUP` 设置项目和 DatasetVersion ID，并显式提供管理员原因与变更引用。以下值
只作为示例，不能复制到生产：

```powershell
$env:RUN_DATASET_EXPORT_ACCEPTANCE = 'true'
$env:RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE = 'true'
$env:DATASET_EXPORT_DLQ_REPLAY_PROJECT_ID = '<ACCEPTANCE_SETUP.project_id>'
$env:DATASET_EXPORT_DLQ_REPLAY_DATASET_VERSION_ID = '<ACCEPTANCE_SETUP.dataset_version_id>'
$env:DATASET_EXPORT_DLQ_REPLAY_EXPORT_ID = '<ACCEPTANCE_SETUP.dataset_export_id>'
$env:DATASET_EXPORT_DLQ_REPLAY_REASON_CODE = 'AUTHORIZATION_FIXED'
$env:DATASET_EXPORT_DLQ_REPLAY_CHANGE_REFERENCE = 'local-dlq-replay-20260803'
$env:DATASET_EXPORT_DLQ_REPLAY_IDEMPOTENCY_KEY = 'local-dlq-replay-command-20260803'
pnpm run dataset-export:dlq-replay
```

工具通过 RabbitMQ Management API 使用 `ack_requeue_true` 读取**且保留**一条诊断；它拒绝多条
消息、非预期 exchange/routing-key、非 `application/json`、未知字段、`CONTRACT_INVALID`、
缺少 `message_id/export_id/attempt`、错误 body SHA-256 或不匹配 Worker UUIDv5 的
`dead_letter_id`。提交 Core API 的 JSON 仅包含 `dead_letter_id`、`source_message_id`、
`source_attempt`、稳定 `dead_letter_reason`、诊断 `body_sha256`、管理员原因和变更引用。
本次故障注入和重放固定使用 `DATASET_EXPORT_JOB_ACQUIRE_REJECTED`；工具在发起 POST 前会拒绝
其他 DLQ reason。`AUTHORIZATION_FIXED` 只是管理员对修复原因的分类，不是 DLQ reason。

重放前工具会先只读查询指定 DatasetVersion，确认其 export ID 与 `DATASET_EXPORT_DLQ_REPLAY_EXPORT_ID`
和诊断一致、状态仍为 `QUEUED/RUNNING`、current attempt 为 1 且没有 artifact，随后才允许 POST。
重放后工具只验证 replay response 的 `new_attempt=2`，以及当前 attempt=`2/SUCCEEDED`、导出最终为
`AVAILABLE`、只登记一个非空且格式正确的 artifact。公共查询不暴露历史 attempt，因此
`DLQ_REPLAY_RESULT`/`DLQ_REPLAY_EVIDENCE` 将旧 attempt 标为
`NOT_OBSERVED_BY_PUBLIC_API`；旧 attempt=`FAILED`、新 message/capability、Outbox 和审计记录必须
在验收后用独立只读 SQL/队列证据确认，未确认时不得声称通过。输出仍不包含原始 RabbitMQ payload、
lease token、签名 URL、凭据或对象 key，只保留 artifact 大小和 SHA-256 作为非敏感完整性证据。

### 12.3 验收后只读 SQL/队列证据

工具不会执行 SQL，也不会把 lease token 写入输出。`DLQ_REPLAY_RESULT` 通过后，仍需由独立的
数据库只读账号使用同一个 `<dataset_export_id>` 复核以下事实；任一查询未执行或断言不成立，
都不得把本节标为通过：

```sql
-- 1) 两个 attempt：首轮必须是人工停发后的 FAILED/acquire-rejected，重放轮必须成功。
SELECT attempt, status, error_code, message_id, lease_token_hash
FROM dataset_export_attempt
WHERE dataset_export_id = '<dataset_export_id>'
ORDER BY attempt;

-- 2) 手工重放只允许一条，且绑定固定 DLQ reason 和 attempt 1 -> 2。
SELECT dead_letter_id, source_message_id, source_attempt, new_attempt,
       dead_letter_reason, operator_reason_code, change_reference
FROM dataset_export_manual_replay
WHERE dataset_export_id = '<dataset_export_id>';

-- 3) Artifact 只有一个；不要选择 storage_bucket/object_key 等对象位置。
SELECT count(*) AS artifact_count,
       count(*) FILTER (WHERE size_bytes > 0 AND sha256 ~ '^[a-f0-9]{64}$') AS valid_artifact_count
FROM export_artifact
WHERE dataset_export_id = '<dataset_export_id>'
  AND artifact_role = 'DATASET_PACKAGE';

-- 4) 两个不同 message_id 的导出作业事件已进入 Outbox；只统计是否仍有未脱敏 lease token，
--    不读取或打印 token 内容。
SELECT count(*) AS job_event_count,
       count(DISTINCT payload_json ->> 'message_id') AS distinct_message_ids,
       count(*) FILTER (WHERE jsonb_exists(payload_json, 'lease_token'))
         AS unredacted_lease_token_events
FROM outbox_event
WHERE aggregate_id = '<dataset_export_id>'
  AND schema_version = 'dataset-export-job.v1';

-- 5) 一条成功的手工重放审计，reason 和 details 均与请求一致。
SELECT count(*) AS replay_audit_count,
       max(outcome) AS outcome,
       max(reason_code) AS operator_reason_code,
       max(details_json ->> 'dead_letter_reason') AS dead_letter_reason,
       max(details_json ->> 'source_attempt') AS source_attempt,
       max(details_json ->> 'new_attempt') AS new_attempt
FROM audit_event
WHERE action = 'DATASET_EXPORT_MANUAL_REPLAY'
  AND object_type = 'DatasetExport'
  AND object_id = '<dataset_export_id>';
```

断言为：attempt 查询恰好返回 attempt 1/2，状态分别为 `FAILED/SUCCEEDED`，attempt 1 的
`error_code=DATASET_EXPORT_JOB_ACQUIRE_REJECTED`，两个 `message_id` 和两个
`lease_token_hash` 均不同；重放查询恰好一行且 `source_attempt=1/new_attempt=2`、
`dead_letter_reason=DATASET_EXPORT_JOB_ACQUIRE_REJECTED`；Artifact 查询为
`artifact_count=valid_artifact_count=1`；Outbox 至少两条、至少两个不同 message ID、
`unredacted_lease_token_events=0`；审计恰好一条且 `outcome=SUCCEEDED`，其 reason、source/new
attempt 与重放一致。`lease_token_hash` 是不可逆摘要，严禁把 Worker token 作为 SQL 结果或日志
证据。

最后执行 RabbitMQ 只读状态检查（不 purge、不 ack、不 republish）：

```powershell
rabbitmqctl list_queues -p medication name messages_ready messages_unacknowledged consumers
```

确认导出业务队列和 DLQ 没有额外 ready/unacknowledged 演练消息，且 DLQ 原诊断仍被保留；该命令
只读队列元数据，不替代上面的数据库事实。

### 12.4 通过条件与恢复

- RabbitMQ 诊断只以 requeue 模式读取且仍保留在 DLQ；没有 purge/delete/republish 原始 body；
- 管理员重放响应的 `source_attempt` 与诊断一致，`new_attempt=2`，且 replay API 请求没有敏感字段；
- 工具观测到 replay response `new_attempt=2`，当前 attempt=`SUCCEEDED`，导出=`AVAILABLE`，artifact
  数量恰好为 1；
- 验收后独立只读 SQL/队列证据确认旧 attempt=`FAILED`、新 message ID/capability、Outbox 和审计；
- 成功或失败后，Export Worker 均恢复有效 token、正常配置和受本地脚本管理的进程；临时 token
  文件被删除，验收开关恢复为 `false`；
- 证据至少包括脱敏 `DLQ_REPLAY_RESULT`/`DLQ_REPLAY_EVIDENCE`、提交 SHA、命令退出码和恢复后
  `status-local`/RabbitMQ 只读状态。不要声称本节已执行，除非真实命令、日志和数据库只读复核均已保留。

### 12.5 2026-08-04 Windows 隔离本机合成结果

隔离 Docker Compose 环境使用 loopback PostgreSQL、SeaweedFS 和 RabbitMQ；Core API、Media Worker 与 Export Worker
均只处理合成输入。准备模式输出 `dataset_export_id=62b6db6c-9680-41cf-8e60-c1f5b0739707`，首轮 attempt=1。
无效 Worker 身份产生并保留一条 `DATASET_EXPORT_JOB_ACQUIRE_REJECTED` 诊断；管理员以
`AUTHORIZATION_FIXED` 和 `local-dlq-replay-20260804` 执行受控重放。工具最终输出 `DLQ_REPLAY_RESULT`：
`source_attempt=1`、`new_attempt=2`、导出=`AVAILABLE`、Artifact 数量=1、大小=381064、SHA-256=
`15e2941802ab7577f0057a7d88dc5c6f17d228ae61e53ee286f3dd8b2b0bc784`、退出码=0。

独立只读 SQL 复核 attempt 1=`FAILED/DATASET_EXPORT_JOB_ACQUIRE_REJECTED`、attempt 2=`SUCCEEDED`；两个
message ID 与 lease capability 摘要均不同。`dataset_export_manual_replay` 恰一行，绑定 attempt 1→2、同一
dead-letter reason、操作者原因和变更引用；Artifact 恰一条且有效；Outbox 有两条不同 message ID 的 job 事件且
没有明文 lease token；`DATASET_EXPORT_MANUAL_REPLAY` 审计恰一条并为 `SUCCEEDED`。RabbitMQ 只读检查显示
导出业务队列为 0，原 DLQ 诊断仍为 1。另有一条未消费的 `platform.media.probe.v1` 消息，不属于本次 export/DLQ
重放，故本节仅声明 Export DLQ 闭环通过，不声明隔离环境全部队列已清空。

## 13. 2026-08-09 macOS V1.11 单管理员本地合成结果

在 macOS 本地 loopback 合成环境中，PostgreSQL、SeaweedFS、RabbitMQ、Core API、Web、Media Worker
和 Export Worker 均健康。正常模式命令退出码为 0，输出
`schema_version=dataset-export-cross-process-acceptance-v2`：项目
`7327f006-c12a-4ec3-ae15-60f042f76ca6`、DatasetVersion
`8a916078-f4aa-4b1a-a166-4c91f6b2d167`、DatasetExport
`b2c26ce0-b4f5-4ee4-a56b-2fd1c6112213`。

`local-synthetic-admin` 同时是首条 AnnotationRevision 的提交者/复核者、冻结请求者/批准者和导出
请求者/批准者；`local-synthetic-uploader` 始终保持 ACTIVE `MEMBER`，其复核、冻结和导出批准命令
均得到 `403 + PROJECT_ACCESS_DENIED`，脚本核对的聚焦状态前后不变。导出首次 attempt 依次观测到
`QUEUED → RUNNING → AVAILABLE`；Artifact 大小为 380894 bytes，登记和下载 SHA-256 均为
`3b7c2dc3852aee2671e7ef9f41820a073758e66e6f421dac3b487dd375c1d314`。

该结果证明当前本地合成跨进程主链符合 V1.11 单管理员规则，不替代四档视口、页面/审计展示和批准
样本环境的正式 P0 UAT。
