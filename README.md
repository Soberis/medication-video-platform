# Medication Video Platform

护士配药多相机视频数据管理平台，用于视频入库、资产管理、标注复核、清洗和数据集导出。

## 当前阶段

Sprint 0 已完成，正在交付 P0 数据审核、标注与清洗闭环。

- Core API 是 Java 21、Spring Boot 和 Spring Modulith 模块化单体；
- Web 已支持授权项目、Session、视频资产、文件夹筛选和平台本地上传；
- PostgreSQL、S3 兼容对象存储、RabbitMQ 和 Media Worker 已用于本地合成数据闭环；
- Redis、CVAT、Export Worker、外部 IdP 集成和 AI 插件尚未启用；
- 已完成项目内视频人工审核播放：ACTIVE 项目 ADMIN/MEMBER 可在媒体检测通过后显式申请短期只读地址，签发会审计且 URL 不落库；P0 不再运行独立隐私评估或隔离子系统，意外敏感内容按质量不合格处理并由管理员软删除。
- 项目管理员可以修改项目名称或归档项目；归档从日常列表移除并停止后续访问，但不物理删除视频、对象存储对象或审计。视频删除同样是可审计软删除；物理销毁不属于 P0 用户功能。
- 已完成最小人工质量判断、标注、独立复核与返工：项目成员可对已入库视频记录合格、不合格或需复核及固定原因；管理员只能为合格视频创建并分配一个标注任务；受分配成员可提交时间段标注与可选说明；另一名项目管理员可记录一次通过/不通过决定。不通过会把任务退回受分配成员，下一次提交产生新 revision；所有提交和复核都保留审计。对象框标注尚未启用。

详细完成度、缺口和验收证据见[需求追踪矩阵](./docs/architecture/requirements-traceability.md)。

## 架构

```text
Vue 3 Web
    ↓ REST / OpenAPI
Java 21 + Spring Boot + Spring Modulith Core API
    ├── PostgreSQL
    ├── S3 Compatible Object Storage
    └── Outbox → RabbitMQ → Python Media Worker
```

核心业务保持一个 Core API。只有耗时、跨进程或需要重试的任务才进入 Worker；其他基础设施按阶段启用。

## 目录

```text
apps/web/                       Vue 管理端
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

Docker 只运行 PostgreSQL、RabbitMQ 和 S3；启动脚本以 Spring `local` profile 启动 Core API、Media Worker 和 Web，并统一记录后台进程与日志。

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

在 macOS / Linux 上启动全部本地服务：

```bash
cd /path/to/medication-video-platform
./scripts/check-environment.sh
./scripts/start-local.sh
```

在 Windows PowerShell 上使用：

```powershell
cd C:\Dev\Projects\medication-video-platform
.\scripts\check-environment.ps1
.\scripts\start-local.ps1
```

在启动前，于未提交的 `.env` 中设置 `PLATFORM_BOOTSTRAP_ADMIN_USERNAME` 和至少 12 位的 `PLATFORM_BOOTSTRAP_ADMIN_PASSWORD`，首次启动会创建该管理员账号；之后使用 Web 登录页登录。`.env.example` 提供本地默认值，`.env` 只需写入要覆盖的值。启动脚本会等待 Core API 健康后启动 Media Worker，再启动或确认 Web，默认最长等待 180 秒。这样平台本地上传的视频会继续完成媒体探测，而不会长期停留在“已上传，待入库”。Web 默认地址为 `http://localhost:5173/login`，Core API 健康检查为 `http://localhost:8080/actuator/health`。后台日志位于 `logs/local/`。应用不持久化密码或会话令牌；认证服务暂不可用时，登录页只在当前页面内保留密码输入以便重试，刷新或离开页面即清除。

macOS / Linux 常用命令：

```bash
./scripts/status-local.sh
./scripts/stop-local.sh
./scripts/stop-local.sh --keep-infrastructure
./scripts/start-local.sh --skip-worker
```

Windows PowerShell 常用命令：

```powershell
.\scripts\status-local.ps1              # 查看容器、端口、后台进程和日志位置
.\scripts\stop-local.ps1                # 停止脚本启动的后端/Worker 和本地容器，保留数据卷
.\scripts\stop-local.ps1 -KeepInfrastructure
.\scripts\start-local.ps1 -SkipWorker   # 仅做 API/Web 元数据开发时显式跳过 Worker
```

如果 8080 或 5173 是此前手工启动的进程，先用状态脚本确认；只有明确要结束它时，macOS / Linux 才执行 `./scripts/stop-local.sh --include-listening-apps`，Windows 才执行 `.\scripts\stop-local.ps1 -IncludeListeningApps`。

## 验证

```powershell
.\scripts\quality-gate.ps1 -Strict
pnpm run api:test
```

macOS / Linux 可用 `pwsh ./scripts/quality-gate.ps1 -Strict` 运行同一质量门禁；`api:test` 需要本地基础设施和 Core API 已启动，并且只能使用合成开发身份和合成视频。

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
