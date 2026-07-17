# Medication Video Platform

配药舱多相机视频数据管理平台，用于视频入库、资产与元数据管理、标注复核、前后置清洗、数据集版本化和标准化导出。

## Current Status

当前已完成 **Sprint 0 最小工程与契约底座**，正在进行 **Sprint 1：P0 上传与资产**。

- Core API、八个领域模块和一个集成模块的边界声明已建立，Java 21 下的模块测试、启动测试和打包已通过。
- Vue 3 管理端已启用 `/sessions`、`/sessions/:id`、`/assets`、`/assets/:id` 和 `/uploads`。默认入口按一次护士配药操作展示多相机 Session，再下钻到单路视频资产；Element Plus 2.14.3 只用于当前真实列表、筛选和详情，Pinia 仍未注册或建立 Store。
- OpenAPI 3.1（当前 0.19.0）和 Redocly lint 已建立；当前契约覆盖上传控制面、批次终止与清理进度、项目隔离的 Session/ClipAsset/CaptureEventLog 查询、媒体探测作业、内部租约/心跳/结果提交、MediaReport 查询、管理员受控重放，以及人工 PrivacyAssessment 创建/查询与默认阻断的资产隐私投影。媒体探测使用服务器批准的 `media-probe-baseline-v1` 配方；人工评估使用固定摘要的 `privacy-assessment-policy-v1`；任务、Worker 结果和脱敏死信诊断分别由 `media-probe-job.v1`、`media-probe-result.v1`、`media-dead-letter.v1` JSON Schema 约束。
- Sprint 0 严格质量门禁已通过；最小 Python 3.12 Media Worker 工程已建立并启用 uv lock、Ruff、mypy、pytest 门禁，AI Plugin 仍按后续阶段 deferred。
- 上传控制面、最小相机配置、Session 组装、项目隔离的 Session/ClipAsset/CaptureEventLog 只读目录、MediaJob/MediaReport、人工 PrivacyAssessment、append-only AuditEvent、受控重放和 Flyway V1～V19 已建立。Complete Batch 在同一事务中为 VIDEO 建立 ClipAsset、为 EVENT_LOG 建立不可变对象索引；事件负载尚不解析。未完成批次可由发起人或项目管理员终止；过期判定、文件终态、清理任务和审计原子落库，再以租约和有限重试异步执行 Multipart Abort 或遗留对象删除。创建媒体作业会在同一数据库事务内写入 attempt 和 Outbox，再由带 publisher confirm 的 relay 投递到单一媒体业务队列；发布确认后 Outbox 会删除一次性 lease capability。Worker 使用独立身份换取短期输入 URL，处理期间按服务端节奏续租，流式复核大小/SHA-256，执行 FFprobe 与 FFmpeg 完整解码，再由 Core API 通过租约、软件摘要 allowlist 和 Inbox 幂等守卫提交状态与不可变 MediaReport。租约超过 grace period 后，平台会原子标记旧 attempt 为 `LOST`，以新 capability 和 Outbox 消息创建下一 attempt；有效的 retryable Worker 失败会进入 `RETRY_WAIT`，通过 Outbox 有限退避生成下一 attempt；达到上限后才终止业务 job。管理员可用独立身份从保留的脱敏诊断创建新 attempt，旧诊断不会被直接重投，审计表由数据库阻止更新和删除。本地合成视频真实队列闭环已验证。人工隐私评估绑定 INGESTED 资产摘要和策略摘要，未评估或任一结论均默认 BLOCKED；自动检测、脱敏、实际隔离和放行仍未实现。
- 本地 PostgreSQL、S3 兼容对象存储和 RabbitMQ 已由三服务 Compose 启动并通过健康与连通性检查；RabbitMQ 当前仅建立一个媒体探测队列和一个 DLQ。业务队列不使用会复制 lease capability 的 broker 原样死信，Worker 只在 publisher confirm 后写入脱敏诊断；Redis 和 CVAT 尚未启用。
- FR-001～FR-003 仍是部分实现：媒体探测入库、EVENT_LOG 对象索引、2 GiB 本机双进程中断恢复、Abort、过期清理代码路径和应用内清理积压判定已闭合，但尚缺弱网/并发、目标环境监控通知和 UAT 验收。FR-004 的 `capture-session-metadata-v1` 必填字段、服务器 CameraSet 对账和原子 Session 组装已达到实现态，但尚未进入 UAT。
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

`check-environment.ps1` 检查本机工具链。默认质量门禁用于仓库基础检查；`-Strict` 用于完整检查当前已激活组件。`pnpm run api:test` 使用 Bruno CLI 对正在运行的本地 API 执行上传、CameraSet、资产目录和 MediaJob 控制面场景，需要先启动 PostgreSQL、S3、RabbitMQ 和后端 `local` profile，并提供合成开发令牌。Node 启动器只向 Bruno 映射运行时令牌，不把令牌写入集合。Media Worker 只能使用独立服务令牌，且其当前软件摘要必须被平台准确批准；本地联调只允许合成视频。

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

首个隐私业务切片已建立：管理员可针对已 `INGESTED` 的合成 ClipAsset 记录版本化人工 `PrivacyAssessment`，固定人脸、姓名、病历号、屏幕、工牌、医嘱/标签文本、语音等 finding 类别，并得到放行复核、待脱敏或待隔离的后续动作要求；策略、输入摘要、操作者、请求和旧/新版本均可追溯。当前所有隐私投影仍默认 `BLOCKED`，`QUARANTINE_REQUIRED` 尚不等于实际隔离记录。下一步只做 `qualitycleaning` 所属的 Quarantine 最小编排与只读状态，再做 DeidentifyRun/AssetVariant，最后才做有职责分离的 PrivacyReleaseDecision；不先开发自动人脸检测或 MediaPipe。
