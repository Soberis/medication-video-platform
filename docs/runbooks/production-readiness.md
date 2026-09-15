# V1.28 部署就绪检查与部署边界

## 目的与结论边界

本手册描述仓库当前实际提供的运行时拓扑、可重复的本地 synthetic smoke、配置与秘密边界，以及上线前必须由目标环境责任人补齐的证据。它不把本地 Docker Compose 当作生产部署，也不授权使用真实患者数据、生产凭据或生产队列。

当前仓库可以重复验证 V1.28 CaptureGroup 的合成闭环；旧单视频和 `training-data.v2` 仅作为历史运行/迁移事实保留。真实生产发布目前仍应标为 `NOT READY`，除非目标环境另行提供并演练生产制品监督、TLS/入口保护、数据库与对象存储备份恢复、值班/告警和批准的 RPO/RTO。

本轮是部署与 smoke 就绪检查，不是 UAT，也不改变产品业务语义。

当前产品范围以 [V1.28 正式需求基线](../requirements/baselines/V1.28/配药视频数据管理平台需求文档_V1.28.md) 和
[V1.28 单一导出边界设计](../history/architecture/v1.28-single-export-consolidation.md) 为准。

## 运行时拓扑（以当前代码为准）

| 组件 | 完整路径是否需要 | 当前实现与本地启动方式 | 启动/事实边界 |
| --- | --- | --- | --- |
| PostgreSQL | 是 | Compose `postgres`，宿主机默认 `localhost:15432` | Core API 的权威关系型事实库；Flyway 当前迁移序列到 V75，不能用手工 SQL 代替发布迁移 |
| S3-compatible object storage | 是 | Compose `s3`（SeaweedFS，仅本地），S3 API 默认 `http://localhost:9000` | 保存原始媒体及派生对象；应用只依赖 S3 API，不依赖 SeaweedFS 专有接口 |
| RabbitMQ | 是（完整异步路径） | Compose `rabbitmq`，AMQP 默认 `localhost:5672`，管理界面 `localhost:15672` | 只负责投递；Outbox、job、attempt、lease 和 DLQ 业务事实仍由 Core API/数据库拥有 |
| Core API | 是 | `services/core-api/mvnw ... spring-boot:run`，默认 `http://localhost:8080` | 启动后通过 `/actuator/health` 检查；承载同步查询、Review/Cleaning 和当前 hand-landmarks read-only export |
| Media Worker | 是（上传/Media Probe 路径） | `uv run --directory workers/media-worker media-worker` | 不写数据库；使用 Core API 的短期授权和 RabbitMQ；需要 Python 3.12、FFmpeg、FFprobe、批准的软件摘要 |
| AI Worker | 可选 | `uv run --directory workers/ai-worker ai-worker`；`AI_HAND_POSE_ENABLED=true` 且模型配置完整时启动 | 仅产生 Candidate；需要外部批准模型文件、SHA-256、profile digest 和唯一 token 来源；AI 未启用时不阻断基础 CaptureGroup smoke |
| Web | 用户 smoke 需要 | `apps/web` 下 `pnpm dev`（`pnpm run web:dev`） | 当前仓库提供前端源代码和 Vite 启动方式，不提供受支持的生产 Web supervisor/镜像 |
| model/profile files | 按路径需要 | Media Probe profile 在 `contracts/profiles/`；CaptureGroup Cleaning 规则集由 Core API 固定；AI 模型在仓库外 | profile、规则集和模型摘要必须与 Core API、Worker、job 及契约一致；不把模型文件提交到 Git |

V1.28 的 hand-landmarks 导出由 Core API 同步生成；仓库没有当前在用的 Export Worker，也不创建 `DatasetVersion`、`CaptureSync` 或通用 workflow engine。

## 最小部署就绪清单

目标环境负责人逐项保存证据；本清单的勾选不能由本地 synthetic 结果代替。

| 类别 | 就绪条件 |
| --- | --- |
| Configuration | `[ ]` 记录 Git/build、Flyway 版本、配置版本、环境标识和目标 `DEV_PROJECT_ID`/项目 ID；`[ ]` 数据库、S3、RabbitMQ、Core API、Worker、Web 端点均为该环境的显式配置 |
| Secrets | `[ ]` 生产秘密来自受控密钥来源或绝对路径文件；`[ ]` 未进 Git、镜像、命令行历史、日志、工单和导出；`[ ]` 会话 Cookie 使用 `Secure`，Worker token 使用短期 JWT |
| Database | `[ ]` PostgreSQL 可用；`[ ]` 仅运行匹配制品的 Flyway 迁移；`[ ]` 迁移后核对关键事实和权限；`[ ]` 已完成隔离恢复演练 |
| Object storage | `[ ]` bucket、区域、TLS、path-style、最小权限正确；`[ ]` 可写入并读回合成/批准样本；`[ ]` 已有 raw/variant/report/export 对象备份与抽样恢复证据 |
| RabbitMQ | `[ ]` broker、vhost、TLS、服务身份和权限正确；`[ ]` publisher confirm、消费者、ready/unacked、重试和 DLQ 有验证/责任人；`[ ]` 不把 RabbitMQ 当最终状态库 |
| Workers | `[ ]` Media Worker 能消费并完成 Media Probe；`[ ]` AI Worker 若启用则模型/profile SHA 校验通过；`[ ]` Worker 身份只具备需要的项目/能力；`[ ]` restart、退避和磁盘清理由批准 supervisor 提供 |
| Web | `[ ]` Web 构建制品与 API base URL 匹配；`[ ]` 入口 TLS、Cookie、CORS/代理边界和管理面网络已核对；`[ ]` 目标浏览器 smoke 可登录并读取项目/事件 |
| Health | `[ ]` Compose/平台容器健康；`[ ]` `/actuator/health` 为 `UP`；`[ ]` smoke 逐项读通 API、媒体、质量、任务和 hand-landmarks preview；`[ ]` 已知健康检查不覆盖的依赖有额外核对 |
| Backup/restore | `[ ]` PostgreSQL 指定时间/隔离恢复通过；`[ ]` S3 对象版本、大小、SHA-256 和可读性抽样通过；`[ ]` 恢复后 schema、权限、审计、Revision、Cleaning 和 hand-landmarks provenance 可对账 |
| Logging | `[ ]` API/Worker/平台日志可访问；`[ ]` 有 request ID、错误升级路径和 DLQ 处理人；`[ ]` 日志不包含 token、签名 URL、对象 key、原始视频或患者标识 |

## 发布前证据

以下命令只使用合成数据或静态配置，不要把输出中的令牌、密码、预签名 URL 或视频内容留在工单中：

```powershell
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1 -Strict
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml config --quiet
```

质量门禁应覆盖 Java 编译/测试/边界校验、React lint/typecheck/unit test/build、Worker 的锁文件/格式/lint/typecheck/test、契约和合成上传测试。质量门禁失败时停止发布，不要通过跳过测试或改写本地数据库来“修复”。

Node 运行时必须保持在仓库声明的 >=22.12.0 <23 范围内；同时核对 node --version 与 pnpm exec node --version，避免系统 Node 和 pnpm 自带运行时不一致。媒体 Worker 源码摘要变化后，重新计算并同步 `MEDIA_WORKER_APPROVED_SOFTWARE_DIGEST`；摘要不一致时应让结果进入受控拒绝/死信，不得通过把 Worker 配置成“批准值”来绕过校验。

## 环境与秘密

- `infra/docker-compose/compose.yaml` 只用于本地基础设施；其中的 Postgres、RabbitMQ 和 SeaweedFS 凭据只能是本地占位值。
- 生产必须使用独立数据库、bucket、RabbitMQ vhost、服务身份和会话密钥，不能复用 Local/CI/UAT 的任何一项。
- 生产 Worker 使用文件或受控密钥系统提供的短期 JWT；不要把 bearer token 放进镜像、Git、命令行历史或日志。
- 生产会话 Cookie 必须启用 `Secure`，入口必须由 TLS 反向代理/WAF 终止并限制管理面网络。
- 生产配置必须显式提供非本机的数据库、对象存储和消息代理端点，并核对 bucket、凭据、TLS、最小权限和时钟同步。
- `DEV_PROJECT_ID` 必须同时匹配 Core API 启动配置、合成工具进程环境和目标项目。`LOCAL_SYNTHETIC_PROJECT_ENABLED=true` 只允许 local synthetic；其他环境关闭并使用显式创建、授权的项目。
- 非 synthetic 环境必须使用 HTTPS API/Worker 端点、AMQPS 和 `PLATFORM_SESSION_COOKIE_SECURE=true`；Worker 使用 `*_TOKEN_FILE` 或受控短期身份，不把 token 放在参数中。
- `AI_HAND_POSE_MODEL_PATH` 必须是仓库外的批准模型绝对路径，`AI_HAND_POSE_MODEL_SHA256` 必须等于该文件字节摘要，也必须匹配 job 冻结的 `model_sha256`。当前 AI Worker README 的本地批准示例摘要为 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`；这只是示例配置，不能证明目标机器已安装模型。
- 当前 profile/规则集/软件摘要基线如下。质量门禁对 JSON profile 先去掉 BOM 和结尾换行后计算 SHA-256，因此不能直接把带换行文件的 `Get-FileHash` 结果当作 profile digest：

  | 事实 | 值 |
  | --- | --- |
  | `media-probe-baseline-v1.json` normalized digest | `8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765` |
  | CaptureGroup Cleaning ruleset (`capture-group-event-eligibility`, `v1`) digest | `9b0f46e8e6a0a4a2f2d31ef6c2cb2b7d0b7fca0a20a7e6b3f6f4a9d4b7f0a61c` |
  | AI hand-pose profile digest (fixed time grid) | `239a403b289c6b379af660988516f752daf1899ce2a05613e333a67a6f95af09` |

- V1.30 采样以首帧 PTS 为起点，按固定 100ms 网格选取目标时刻及之后首个真实帧。24/25/30/60fps 不再因逐次累加实际帧间隔而降采样；稀疏源不补造帧。Core API 与 AI Worker 需一起切换新 profile。旧 `3a3be803…90626` 已完成证据保持原摘要，契约保留兼容；切换前排空旧作业，或按显式新作业重跑。新 Worker 不应把旧作业按新策略执行，也不得修改历史结果或 V55 迁移。
  | local Core API approved Media Worker software digest | `sha256:d7accc8bd9123226c273034cdb7b868e6815450ce1d307c8f55f01197463a252` |

  若 profile、Worker 源码、`uv.lock` 或批准配置变化，必须重新计算、审批并同步摘要；不能把运行中的摘要强行改成“批准值”来绕过校验。模型文件应使用 `Get-FileHash -Algorithm SHA256` 或等价工具核对。
- `.env.example` 是本地合成占位配置；`.env` 不应提交。`change-me-local-only`、`synthetic-local-*` 和 `*-do-not-reuse` 只能用于 local/test synthetic，不能用于医院、UAT 或生产。
- 当前 Compose 的 PostgreSQL、SeaweedFS 和 RabbitMQ image 均使用固定版本加 digest；配置扫描未发现 `:latest`。但 Core API、Web、Worker 尚无仓库内受支持的生产镜像/监督配置，生产制品仍需由部署方案提供不可变版本和摘要。

## 本地启动、状态与受控重启

这些步骤只适用于本地合成环境：

```powershell
.\scripts\check-environment.ps1
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml config --quiet
.\scripts\start-local.ps1
.\scripts\status-local.ps1
.\scripts\start-local.ps1 -RestartApi
```

```bash
./scripts/check-environment.sh
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml config --quiet
./scripts/start-local.sh
./scripts/status-local.sh
```

启动顺序为：Compose 等待 PostgreSQL、S3、RabbitMQ healthy → 校验三个宿主机端口 → Core API health → Media Worker → 配置完整时的 AI Worker → 手工启动 Web。Windows 和 Bash 启动脚本都会在端口未发布时只重建对应基础设施容器；Bash 端口检查是本次 readiness 修复项。

脚本记录 PID 并等待 Core API 健康，但它不是生产进程监督器。生产必须由批准的服务管理平台负责启动、优雅停止、退避重启、日志收集和资源限制；不要把 `start-local.ps1`、`start-local.sh` 或 `nohup` 直接当成生产 supervisor。

重启后至少核对：

1. `/actuator/health` 返回 `status=UP`；
2. Flyway 版本与发布制品匹配；
3. Outbox relay、业务队列、Worker consumer 和未完成 lease 能继续推进；
4. 日志没有认证失败、连接池耗尽、重复提交或 DLQ 增长。

`/actuator/health` 是基础入口，不代表 S3、RabbitMQ、Worker、队列或完整业务链路全部可用；当前没有额外的 Worker health endpoint。必须结合 `docker compose ps`、`status-local`、API/Worker 日志和下节 smoke 判断，不新增监控平台或健康服务。

## 备份与恢复

仓库目前没有生产备份脚本、WAL/PITR 配置或对象存储复制策略。目标环境必须使用数据库和对象存储产品提供方按批准 RPO/RTO 配置的能力，并保存独立的恢复演练记录；以下是最小核对模型，不是可直接对生产执行的破坏性命令。

### PostgreSQL

1. 使用批准身份和受控目标执行 `pg_dump --format=custom` 或等价的产品快照；备份文件进入受控位置，不进入 Git 或工单附件。
2. 恢复到隔离数据库/实例，用 `pg_restore --exit-on-error` 或产品等价操作；禁止先删除生产库来验证恢复。
3. 让匹配制品执行 Flyway，确认迁移历史到发布要求（当前仓库迁移最高为 V75），再对账 `capture_session`、`camera_stream`、`annotation_revision`、`annotation_review`、`capture_group_cleaning`、`audit_event`、`outbox_event` 和相关 job/attempt 的行数、ID/摘要及权限。若 V74 因遗留单视频行而 fail closed，先完成经批准的数据保留或删除处置，禁止把删除业务数据写入自动发布迁移。
4. 隔离恢复通过后，才由负责人决定是否切换；应用回滚不能覆盖 append-only Revision、Review、Cleaning、审计和 Outbox 事实。

### S3-compatible object storage

1. 使用供应商的版本化/备份能力覆盖 raw、variant、frame 和 media report 对象；数据库引用、对象 version、size 和 SHA-256 一起登记。当前 hand-landmarks.v1 是 Core API 同步返回，不生成持久化导出对象。
2. 恢复到隔离 bucket/prefix，不直接覆盖生产对象；使用批准身份读回抽样对象，核对 version、大小、SHA-256 和媒体可解码性。
3. 只有数据库事实与对象抽样都一致才可宣告恢复演练通过。Compose 的 `s3-data` volume 只代表本地开发数据，不能当生产备份。

停止本地服务时使用 `down` 会保留命名卷；不要使用 `down -v` 作为普通停止步骤。任何清除本地合成数据的操作都必须明确指定测试卷/目标并单独确认。

## 健康检查与可观测性

当前公开的健康入口是 `/actuator/health`，管理面默认不应向公网暴露。目标部署必须另外明确：

- liveness/readiness 分组和依赖失败时的摘流策略；
- API、数据库连接池、上传 complete/abort、Outbox、RabbitMQ ready/unacked/DLQ、Worker lease/重试/临时盘和对象存储容量指标；
- request ID、结构化日志、脱敏规则、告警 owner、升级路径和对应 runbook；
- 指标标签不得包含患者、操作者、对象 key、预签名 URL 或令牌。

## 最小 smoke（不是 UAT）

只使用合成数据和 local/test 身份。第一次运行从干净 checkout 开始，创建未跟踪的 `.env` 并按需填写本地 bootstrap 账号；不要在文档、Git 或命令行历史中放生产秘密。

```powershell
pnpm install --frozen-lockfile
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
.\scripts\check-environment.ps1
.\scripts\start-local.ps1
Set-Location apps\web
pnpm dev
```

API/合成服务 smoke 可在另一个 PowerShell 会话中设置 `.env.example` 中的 synthetic token（不使用真实 token），并显式启用 service E2E：

```powershell
$env:RUN_SYNTHETIC_CAPTURE_GROUP_E2E='true'
$env:DEV_BEARER_TOKEN='<local synthetic token>'
$env:DEV_ADMIN_BEARER_TOKEN='<local synthetic admin token>'
$env:DEV_PROJECT_ID='<same project id used by Core API>'
pnpm run synthetic-capture-group -- --scenario n1-complete
```

按以下顺序核对，证据只保留状态、ID、reason、版本和 request ID，不保存 token、预签名 URL 或视频画面：

1. 登录 Web，或使用 local bearer 进行 API smoke；确认 `POST /api/v1/auth/login`、`GET /api/v1/auth/session` 和项目请求不越权。
2. 读取 `GET /api/v1/projects` 和 `GET /api/v1/projects/{project_id}`，确认项目 ID 与工具/服务配置一致。
3. 读取事件列表：V1.28 使用 `GET /api/v1/projects/{project_id}/capture-groups`。旧 `/videos` 表面不属于当前业务验收；没有 AnnotationTask 的 CaptureGroup 也必须显示。
4. 打开 `GET /api/v1/projects/{project_id}/capture-groups/{capture_group_id}`，核对 Receipt、expected/present/missing camera numbers、CameraStream 和 CaptureSession 事实；检查 `RECEIVING`、`INCOMPLETE`、`REJECTED`、`QUALIFIED` 等状态没有被 task 过滤。
5. 读取 `GET /api/v1/projects/{project_id}/clip-assets/{clip_asset_id}/media-report`，必要时申请短期 playback grant；确认媒体可读，绝不把 grant URL 写入证据。
6. 分别读取 view quality `.../clip-assets/{clip_asset_id}/quality-review` 和 group quality `.../capture-groups/{capture_group_id}/quality-review`，确认两者不是同一事实。
7. 读取 `GET /api/v1/projects/{project_id}/annotation-tasks`，确认合格组至多进入一个组 task，task/revision/review 的当前状态可见；Review 只由人工管理员操作。
8. 在视频管理事件列表或事件详情调用 `hand-landmarks.v1` preview/download；核对导出范围、当前 CameraStream/ClipAsset、21 点样本、源时间和 Candidate/AI provenance。旧事件 manifest 与历史单视频导出不列为 V1.28 验收目标。

建议至少运行 `n1-complete`、`n5-complete`、`n5-missing-camera`、`n5-rejected-view` 和 `receiving-before-finalize`；`no-hand-detected`/`candidate-failure` 只有在 AI 模型和受控 fixture 就绪时才运行。`candidate-failure` 未运行不能解释为 UAT 通过。

## 常见部署故障、症状与恢复

| 故障 | 可观察症状 | 最小恢复动作 |
| --- | --- | --- |
| DB migration mismatch | Core API 日志出现 Flyway validate/migrate 错误；`/actuator/health` 不为 `UP`；应用不应继续接流量 | 停止发布，核对制品与数据库迁移版本，先保留/备份权威事实；使用匹配版本的向前兼容迁移，禁止手工改历史表或用破坏性 SQL“回滚” |
| S3 unavailable | upload complete、media report、playback 或对象读写失败；Media Worker 无法取得输入 | 检查 endpoint、bucket、TLS、path-style、服务身份和网络；恢复对象存储后重试受控 job，先核对幂等/对象版本，不盲目重传或删除 |
| RabbitMQ unavailable | Core API/Worker 日志出现 broker/vhost/TLS/认证连接错误；Outbox 未投递，job 卡在 QUEUED 或 consumer 无 ready | 检查 broker、vhost、权限、证书和端口；恢复后观察 ready/unacked、重试和 DLQ，确认 DB Outbox 事实后由 supervisor 受控重启 |
| Media Worker unavailable | Media Probe 长时间 QUEUED、lease 到期/重试或进入安全 DLQ；无存活 Worker 进程 | 查看 `logs/local/worker*.log` 或平台日志，验证 `uv sync --frozen`、FFmpeg/FFprobe、RabbitMQ、API URL、token file 和批准软件摘要，再由 supervisor 退避重启 |
| AI Worker unavailable | 启用 AI 时 Candidate 不推进；模型/队列读取或 Worker 身份错误；基础人工 CaptureGroup 路径仍可在不依赖 AI 时运行 | 检查是否确实需要 AI、模型路径存在、文件 SHA、profile digest、RabbitMQ 和唯一 token 来源；配置完整前不要把 Candidate 缺失当作人工 Review/Cleaning 通过 |
| Wrong model digest | AI Worker 启动配置拒绝，或 job/result 因模型 SHA 不匹配进入受控失败（例如 `AI_HAND_POSE_MODEL_UNSUPPORTED`） | 停止候选处理，计算实际批准模型 SHA，与配置和冻结 job 对齐；部署批准模型/制品，不把配置改成未经批准的摘要 |
| Wrong `DEV_PROJECT_ID` / local project | synthetic 工具得到 `PROJECT_NOT_FOUND`、空项目或权限错误；Web 看不到预期合成事件 | 比对 Core API `.env`、工具进程环境和项目初始化结果，统一到同一个 local synthetic project；不要把生产项目 ID 或 token 带入本地 |

## 发布、迁移与回滚

- 数据库只使用向前兼容的 expand/migrate/contract；迁移通过后再切换应用和 Worker。
- 发布前确认旧版本能够读取新 schema 和事件；不要用破坏性 SQL 作为即时回滚手段。
- 回滚应用时保留已经合法产生的不可变 revision、run、审计和导出事实；使用补偿状态处理错误结果。
- 每次发布记录 Git commit、构建制品摘要、迁移版本、配置版本和验证证据。

## 事故停止条件

出现以下任一情况立即停止放量并人工调查：

- 未登录请求可访问受保护 API，或 Worker 身份可跨项目/跨能力使用；
- 数据库提交与对象引用、SHA-256、审计或 Outbox 不一致；
- publisher confirm 失败但消息未重试，或 DLQ 含原始消息、签名 URL、lease token；
- 恢复后的 schema/权限、Cleaning 或 hand-landmarks provenance 无法对账；
- 健康检查为 UP 但关键依赖、队列或 Worker 已持续失败；
- hand-landmarks.v1 导出越权、跨项目，或其当前视图、ClipAsset、Candidate provenance 无法对账；
- 没有可验证的备份恢复证据、值班 owner 或回滚窗口。

## 日志、回滚和当前结论

本地脚本日志位于 `logs/local/api.log`、`worker*.log`、`ai-worker*.log` 及对应 `.error.log`；用 `status-local` 和服务日志确认进程、依赖、租约、重试和 DLQ。生产日志由批准 supervisor/平台采集，必须保留 request ID 和脱敏错误，不得记录 bearer token、预签名 URL、对象 key、原始 payload 或患者标识。

数据库发布使用向前兼容的 expand/migrate/contract；迁移完成并核对后再切换 API/Worker。回滚应用时保留合法产生的不可变 Revision、Review、Cleaning 和审计事实；hand-landmarks.v1 不持久化导出对象。不要用 `git reset`、破坏性 SQL 或覆盖对象来代替恢复流程。每次发布记录 Git/build 摘要、迁移版本、配置摘要、模型/profile 摘要和 smoke 证据。

- 本地 synthetic：具备 Compose、Core API、Media Worker、可选 AI Worker、Web 和合成验收入口；可按本手册执行 smoke。
- 生产部署：`NOT READY`，除非目标环境补齐生产制品监督、TLS/入口保护、秘密管理、PostgreSQL/S3 备份恢复、日志/告警 owner、RPO/RTO 和回滚演练证据。
- UAT：不由本手册或 synthetic smoke 宣告；按 V1.28 需求基线的验收条款由现场责任人形成独立记录并填写结果。
