# Medication Video Platform

配药舱多相机视频数据管理平台，用于视频入库、资产与元数据管理、标注复核、前后置清洗、数据集版本化和标准化导出。

## Current Status

当前已完成 **Sprint 0 最小工程与契约底座**，正在进行 **Sprint 1：P0 上传与资产**。

- Core API、八个领域模块和一个集成模块的边界声明已建立，Java 21 下的模块测试、启动测试和打包已通过。
- Vue 3 管理端已启用 `/sessions`、`/sessions/:id`、`/assets`、`/assets/:id` 和 `/uploads`。默认入口按一次护士配药操作展示多相机 Session，再下钻到单路视频资产；Element Plus 2.14.3 只用于当前真实列表、筛选和详情，Pinia 仍未注册或建立 Store。
- OpenAPI 3.1 和 Redocly lint 已建立；当前契约覆盖上传控制面、项目隔离的 Session/ClipAsset 查询，以及以 `QUEUED` 状态持久化的媒体探测作业。媒体探测使用服务器批准的 `media-probe-baseline-v1` 配方，任务消息和 Worker 结果分别由 `media-probe-job.v1`、`media-probe-result.v1` JSON Schema 约束。
- Sprint 0 严格质量门禁已通过；最小 Python 3.12 Media Worker 工程已建立并启用 uv lock、Ruff、mypy、pytest 门禁，AI Plugin 仍按后续阶段 deferred。
- 上传控制面、最小相机配置、Session 组装、项目隔离的 Session/ClipAsset 只读目录、MediaJob/MediaReport 契约和 Flyway V1～V12 已建立。创建媒体作业会在同一数据库事务内写入 attempt 和 Outbox，再由带 publisher confirm 的 relay 投递到单一媒体业务队列；消息不含对象存储位置或预签名 URL。Worker 已实现严格消息解析、manual ack、prefetch=1、短期输入授权客户端、流式大小/SHA-256 复核、FFprobe 事实提取和 FFmpeg 完整解码，但 Core API 租约/结果接口、Inbox 去重和 MediaReport 落库仍未实现，因此尚未形成运行闭环。
- 本地 PostgreSQL、S3 兼容对象存储和 RabbitMQ 已由三服务 Compose 启动并通过健康与连通性检查；RabbitMQ 当前仅建立一个媒体探测队列和一个 DLQ，Redis 和 CVAT 尚未启用。
- FR-001～FR-003 仍是部分实现：尚缺 Abort/过期清理、2GB 中断恢复验收、事件日志索引和媒体探测入库。FR-004 的 `capture-session-metadata-v1` 必填字段、服务器 CameraSet 对账和原子 Session 组装已达到实现态，但尚未进入 UAT。
- MediaPipe 属于 P1 试点，不进入 P0 关键路径。

## Architecture Summary

```text
Vue 3 Web
    ↓ REST / OpenAPI
Java 21 + Spring Boot + Spring Modulith Core API
    ├── PostgreSQL
    ├── S3 Compatible Object Storage
    ├── CVAT / OIDC adapters
    └── Transactional Outbox → RabbitMQ
                                  ↓
                         Python 3.12 Workers
```

核心平台是模块化单体。媒体处理、导出和 AI 以异步 Worker 运行；第三方工具通过适配器接入。

## Repository Layout

```text
apps/web/                       Vue 管理端
services/platform-api/         单个 Spring Boot/Modulith Core API
workers/                       媒体、导出和 AI Worker
plugins/                       AI Plugin SDK、Mock 和 MediaPipe 插件
contracts/                     OpenAPI、事件和 JSON Schema
api-tests/bruno/               本地优先的 API 调试集合
infra/docker-compose/          本地与 UAT 基础设施
tests/                         跨工程合同、集成、E2E 和安全测试
docs/                          ADR、架构说明和运行手册
```

## Required Toolchain

| Tool | Baseline |
|---|---|
| Java | 21 LTS |
| Node.js | 22.22.2 |
| pnpm | 11.11.0 |
| Python | 3.12 |
| Python manager | uv |
| Containers | Docker Desktop + WSL2 |

Spring Boot、PostgreSQL 和其他服务的准确补丁版本在构建文件或容器摘要中冻结，不使用 `latest`。

## Local Checks

在 PowerShell 中执行：

```powershell
cd C:\Dev\Projects\medication-video-platform
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1
.\scripts\quality-gate.ps1 -Strict
pnpm run api:test
```

`check-environment.ps1` 检查本机工具链。默认质量门禁用于仓库基础检查；`-Strict` 用于完整检查当前已激活组件。`pnpm run api:test` 使用 Bruno CLI 对正在运行的本地 API 执行上传、CameraSet、资产目录和 MediaJob 控制面场景，需要先启动 PostgreSQL、S3、RabbitMQ 和后端 `local` profile，并提供合成开发令牌。Node 启动器只向 Bruno 映射运行时令牌，不把令牌写入集合。Media Worker 的 Python 门禁已激活；Core API 内部租约/结果接口完成前不要启动常驻消费者。

## Data Safety

- 本仓库只允许合成测试数据。
- 不得提交真实患者视频、个人身份信息、生产 URL、账号、令牌、私钥和模型大文件。
- `.env.example` 只包含本地占位值；真实本地配置放在被忽略的 `.env` 中。
- AI 结果只能作为候选标注，不能自动成为训练真值。

## Source Documents

- [配药视频数据管理平台需求文档 V1.1.13](./docs/requirements/baselines/V1.1.13/配药视频数据管理平台需求文档_V1.1.13.docx)
- [配药平台技术设计](./docs/architecture/配药平台技术设计.md)
- [架构详细设计索引](./docs/architecture/README.md)
- [ADR-0001：技术基线与构建边界](./docs/adr/0001-technology-baseline.md)

## Next Step

`tools/synthetic-upload-client` 已建立并通过本地真实链路验证：运行时生成短小合成 MP4、METADATA 和事件日志，用与 RK3588 相同的 Upload/Multipart/Complete/CaptureSession 协议产生可重复的 CaptureSession 和 ClipAsset。Transactional Outbox、一个 RabbitMQ 媒体业务队列和 DLQ 已启用并完成真实发布验证；不连接业务数据库的最小 Media Worker 也已建立并通过合成 MP4 全流解码测试。下一步实现受租约保护的输入授权、结果接收、Inbox 去重、业务状态提交和 MediaReport 查询，再执行完整端到端验证。
