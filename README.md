# 配药视频数据管理平台

对接医院边端采集系统接收固定舱内相机视频，在云端完成视频/数据管理、MediaPipe 手部预标注、Ego 配药动作标注与数据清洗；手部预标注是当前优先交付方向。当前产品基线见
[V1.17 需求文档](./docs/requirements/baselines/V1.17/配药视频数据管理平台需求文档_V1.17.md) 与
[MVP 技术设计](./docs/architecture/video-hand-pose-mvp-technical-design.md)。

## 当前阶段

V1.17 已将项目的首要矛盾收敛为“完整保留云端数据生产链，同时不让用户操作内部技术概念”。医院边端设备负责
采集、缓存和上传策略，平台仅负责对接接收与云端处理。日常前端目标为“视频管理、数据管理、手部检测、Ego 标注
与清洗、上传接入、设置”；视频库使用服务端分页，并支持时间、相机、来源、检测、标注和清洗状态筛选。MediaPipe
只产生手部候选，不能代替人工标注或清洗结论；数据集构建与导出保留为清洗通过后的后续演进能力。
以下内容是已有实现清单和历史演进记录，不代表当前继续扩展的产品范围。

Sprint 0 已完成，上传/资产和最小人工审核、标注、管理员复核、后置清洗已形成可运行切片；Sprint 3 已交付可解释的数据集准入、`DRAFT` 草稿、可复现划分、不可变构建配置、显式启动、关系快照封印、冻结前技术校验、管理员冻结审批，以及 Step-only 训练包的申请、管理员审批、后台生成、服务端校验和短期下载授权。V1.11 允许拥有 ACTIVE 项目 `ADMIN` Membership 的 `ROLE_ADMIN` 自审/自批，但同人决定必须显示为 `SELF_ADMIN_APPROVAL`；`ROLE_MEMBER` 不获得管理员命令权限。`DatasetVersion` 当前可从 `DRAFT → BUILDING → VALIDATING → FROZEN`，`DatasetExport` 可从 `REQUESTED → QUEUED → RUNNING → AVAILABLE/FAILED`，可重试错误经 `RETRY_WAIT` 创建新 attempt。

- Core API 是 Java 21、Spring Boot 和 Spring Modulith 模块化单体；
- Web 已支持授权项目、Session、视频资产、文件夹筛选和平台本地上传；
- PostgreSQL、S3 兼容对象存储、RabbitMQ、Media Worker 和 Export Worker 已用于本地合成数据闭环；
- Redis、CVAT、外部 IdP 集成和 AI 插件尚未启用；
- V1.16 在 V1.15 的“先 PoC、Golden Set 与书面 GO，后候选审核”门禁上，定义每路上传后的自动媒体技术检查、CaptureSession 一键批量检测与可选项目级自动分析；输入是获批的舱内固定/多机位相机而非佩戴式 Ego 相机。相机数量由每次采集配置动态决定，系统不得写死为 3 路；每路独立产生候选并在对应原视频上显示 Skeleton Overlay。`tools/hand-pose-poc` 可离线输出截图风格的骨架 Overlay、逐帧 JSONL 和运行摘要。现有 AIJob/Worker/候选页面仍仅为未获 GO 的实现草稿，机器结果不能自动成为正式标注。
- 已完成项目内视频人工查看：ACTIVE 项目 ADMIN/MEMBER 可在上传完整性校验完成后显式申请短期只读地址，媒体检测不阻断查看；签发会审计且 URL 不落库。P0 不再运行独立隐私评估或隔离子系统，意外敏感内容按质量不合格处理并由管理员软删除。
- 项目管理员可以修改项目名称或归档项目；归档从日常列表移除并停止后续访问，但不物理删除视频、对象存储对象或审计。视频删除同样是可审计软删除；物理销毁不属于 P0 用户功能。
- 已完成最小人工质量判断、主 Step 标注、管理员复核与返工：项目成员可对已入库视频记录合格、不合格或需复核及固定原因；拥有 ACTIVE 项目 `ADMIN` Membership 的 `ROLE_ADMIN` 可为合格视频创建并分配任务，也可记录一次通过/不通过决定；同人复核必须记录为 `SELF_ADMIN_APPROVAL`。不通过会把任务退回受分配成员，下一次提交产生新 revision；所有提交和复核都保留审计。任务列表在同一次服务端一致读中返回当前可见范围的待提交、待修改、待复核和已完成数量，不由浏览器拼接统计。对象框与 COCO 已按 V1.10 后置到 P1。
- 媒体探测结果会投影为 CameraStream/CaptureSession 的入库数量、组装状态和技术检测状态；配药操作详情展示训练数据准备度及阻断证据。项目管理员可为当前符合条件的 CaptureSession 显式创建 `DRAFT` DatasetVersion，并固定成员与证据快照、划分和构建配置。管理员开始构建、封印关系快照、运行冻结前技术校验并批准 `PASS` 时均重新校验当前证据；V1.11 允许同一合格管理员完成申请和冻结，但必须照实写入 `SELF_ADMIN_APPROVAL`。证据变化或 `FAIL` 都必须新建 DatasetVersion；冻结不创建导出文件、不授权导出，也不启动 Worker。
- 项目管理员可对 `FROZEN` 版本提交唯一的 `MODEL_TRAINING` 导出申请，明确用途和未来到期时间；合格管理员批准时，服务端在同一事务重验冻结证据并记录审批、首次 attempt、Outbox 与审计。申请人与批准人为同一账号时必须显示 `SELF_ADMIN_APPROVAL`。Export Worker 只通过独立机器身份和 attempt 租约读取固定输入，生成确定性 ZIP；Core API 校验文件集合、输入与输出摘要和批准的软件摘要后才登记 `ExportArtifact`。失联 Worker 的过期租约会在 grace period 后自动失效并按上限创建新 attempt，临时输出随旧 attempt 清理。管理员显式下载时才签发 5 分钟地址，URL 不落库。
- 新构建规范绑定 Step-only `p0-dataset-export-v2`。历史 v1 事实保持可读和可校验，但因仓库没有真实 Object/COCO 与 `videos/clean/` 生产者，当前不会把原视频伪装成 clean 或生成空 COCO，v1 执行仍是 V1.10 的已知缺口。
- 项目成员可按需执行无副作用的导出审批准备检查；它重新核对管理员角色、项目 Membership、用途授权有效期、冻结状态及全部当前证据，返回稳定阻断码。检查不写审批决定、不入队，也不能替代批准命令中的事务内复核。

详细完成度、缺口和验收证据见[需求追踪矩阵](./docs/architecture/requirements-traceability.md)。

## 架构

```text
React Web
    ↓ REST / OpenAPI
Java 21 + Spring Boot + Spring Modulith Core API
    ├── PostgreSQL
    ├── S3 Compatible Object Storage
    └── Outbox → RabbitMQ → Python Media / Export Worker
```

核心业务保持一个 Core API。只有耗时、跨进程或需要重试的任务才进入 Worker；其他基础设施按阶段启用。

## 目录

```text
apps/web/                      React 管理端
services/platform-api/         Core API
workers/                       按阶段启用的异步 Worker
plugins/                       P1 AI 插件
contracts/                     OpenAPI、事件和 JSON Schema
api-tests/bruno/               真实服务核心场景
infra/docker-compose/          本地基础设施
docs/                          需求、设计、ADR 和运行手册
```

## 本地开发

要求 Java 21、Node.js 22、pnpm 11、Python 3.12、uv 和 Docker Desktop。准确依赖版本由构建文件和 lockfile 冻结。

Docker 只运行 PostgreSQL、RabbitMQ 和 S3；启动脚本以 Spring `local` profile 启动 Core API 并记录后台进程与日志。前端始终由 pnpm 单独启动；Media Worker 和 Export Worker 仅在需要媒体或导出流程时显式启动。

macOS 首次准备（已安装 Homebrew）：

```bash
brew install openjdk@21 node@22 pnpm uv ffmpeg
brew install --cask docker
export PATH="$(brew --prefix openjdk@21)/bin:$(brew --prefix node@22)/bin:$PATH"
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
uv python install 3.12
open -a Docker
```

`PATH` 和 `JAVA_HOME` 两行需要写入 `~/.zprofile` 才会在新终端持续生效。Docker Desktop 首次打开后，等待菜单栏显示引擎已启动，再执行项目脚本。

首次安装前端依赖：

```bash
cd /path/to/medication-video-platform
pnpm install --frozen-lockfile
```

在 macOS / Linux 上启动本地基础设施和 Core API：

```bash
cd /path/to/medication-video-platform
./scripts/check-environment.sh
./scripts/start-local.sh
```

在 Windows PowerShell 7 上启动本地基础设施和 Core API：

```powershell
$repositoryRoot = git rev-parse --show-toplevel
Set-Location $repositoryRoot
.\scripts\check-environment.ps1
.\scripts\start-local.ps1
```

在仓库任意子目录中执行上述命令即可；脚本会基于自身位置定位仓库根目录，因此项目移动到其他磁盘或目录后无需修改脚本。

在启动前，于未提交的 `.env` 中设置 `PLATFORM_BOOTSTRAP_ADMIN_USERNAME` 和至少 12 位的 `PLATFORM_BOOTSTRAP_ADMIN_PASSWORD`，首次启动会创建该管理员账号；之后使用 Web 登录页登录。`.env.example` 提供本地默认值，`.env` 只需写入要覆盖的值。启动脚本默认最长等待 180 秒，直到 Core API 健康，并默认启动媒体与导出 Worker；当 `.env` 中已启用并配置手部检测时，也会自动启动 AI Worker。前端需由 pnpm 单独启动。Web 默认地址为 `http://localhost:5173/login`，Core API 健康检查为 `http://localhost:8080/actuator/health`。后台日志位于 `logs/local/`。应用不持久化密码或会话令牌；认证服务暂不可用时，登录页只在当前页面内保留密码输入以便重试，刷新或离开页面即清除。

macOS / Linux 常用命令：

```bash
./scripts/status-local.sh
./scripts/stop-local.sh
./scripts/stop-local.sh --keep-infrastructure
./scripts/start-local.sh
./scripts/start-local.sh --restart-api
./scripts/start-local.sh --restart-export-worker
pnpm run local-scripts:test
```

Windows PowerShell 7 常用命令：

```powershell
.\scripts\status-local.ps1              # 查看容器、端口、后台进程和日志位置
.\scripts\stop-local.ps1                # 停止脚本启动的后端/Worker 和本地容器，保留数据卷
.\scripts\stop-local.ps1 -KeepInfrastructure
.\scripts\start-local.ps1               # 默认启动媒体/导出 Worker；启用 AI 配置时也启动 AI Worker
.\scripts\start-local.ps1 -RestartApi   # 仅重启脚本启动的 Core API；保留前端、Worker 与容器
```

只启动前端开发服务器时，在仓库根目录运行：

```powershell
pnpm run web:dev
```

该命令复用 `apps/web` 的 Vite 配置，自动使用 `WEB_PORT`（默认 `5173`）并将 `/api` 代理到本地 Core API（默认 `8080`）。

如果 8080 或 5173 是此前手工启动的进程，先用状态脚本确认；只有明确要结束它时，macOS / Linux 才执行 `./scripts/stop-local.sh --include-listening-apps`，Windows 才执行 `.\scripts\stop-local.ps1 -IncludeListeningApps`。

## 验证

macOS / Linux：

```bash
./scripts/quality-gate.sh --strict
pnpm run api:test
```

Windows PowerShell 7：

```powershell
.\scripts\quality-gate.ps1 -Strict
pnpm run api:test
```

两套质量门禁覆盖同一组活动组件；macOS / Linux 不依赖 PowerShell。`api:test` 需要本地基础设施和 Core API 已启动，并且只能使用合成开发身份和合成视频。

## 数据安全

- 仓库、开发环境和 CI 只使用合成数据；
- 不提交真实视频、身份信息、生产地址、账号、令牌、私钥或预签名 URL；
- AI 输出只能作为候选标注，不能直接成为训练真值。

## 文档入口

- [需求基线索引](./docs/requirements/README.md)
- [总体技术设计](./docs/architecture/配药平台技术设计.md)
- [架构与专题设计索引](./docs/architecture/README.md)
- [Web 设计简报](./docs/design.md)
- [项目开发规则](./AGENTS.md)
