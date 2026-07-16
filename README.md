# Medication Video Platform

配药舱多相机视频数据管理平台，用于视频入库、资产与元数据管理、标注复核、前后置清洗、数据集版本化和标准化导出。

## Current Status

当前已完成 **Sprint 0 最小工程与契约底座**，正在进行 **Sprint 1：P0 上传与资产**。

- Core API、八个领域模块和一个集成模块的边界声明已建立，Java 21 下的模块测试、启动测试和打包已通过。
- Vue 3 管理端已启用 `/sessions`、`/sessions/:id`、`/assets`、`/assets/:id` 和 `/uploads`。默认入口按一次护士配药操作展示多相机 Session，再下钻到单路视频资产；Element Plus 2.14.3 只用于当前真实列表、筛选和详情，Pinia 仍未注册或建立 Store。
- OpenAPI 3.1 和 Redocly lint 已建立；当前契约覆盖 UploadBatch 初始化、不可变文件清单注册、S3 Multipart Session、短期 Part 上传授权、服务端 Part 确认、恢复协调、单文件完成与最终完整性校验、批次完成与最小 RAW ClipAsset 绑定、项目隔离的批次/文件只读进度查询、服务器侧 CameraChannel/CameraSet 配置，以及从已验证 METADATA 对象原子创建 CaptureSession/CameraSetSnapshot/CameraStream。
- Sprint 0 严格质量门禁已通过；Python Worker/Plugin 按后续阶段 deferred。
- 上传控制面、最小相机配置、Session 组装、项目隔离的 Session/ClipAsset 只读目录和 Flyway V1～V9 已建立；批次只有在成功签发首个 Part 授权后才进入 `UPLOADING`，Part 只有经对象存储实际状态和大小核实后才进入 `UPLOADED`，文件只有在 Multipart Complete、最终大小和完整文件 SHA-256 全部通过后才进入 `UPLOADED`，Complete Batch 为每个 VIDEO 文件建立唯一的最小 RAW ClipAsset。CameraChannel 使用平台生成的不可变 ID，CameraSet 以不可变版本保存服务器配置真值；Session 入库重新核验 METADATA 大小/SHA-256，并在同一事务中冻结配置和绑定全部视频。目录查询使用认证项目范围和不透明 cursor，不返回对象存储定位或预览凭据。
- 本地 PostgreSQL 和 S3 兼容对象存储已由两服务 Compose 启动并通过健康与连通性检查；RabbitMQ、Redis 和 CVAT 尚未启用。
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

`check-environment.ps1` 检查本机工具链。默认质量门禁用于仓库基础检查；`-Strict` 用于完整检查当前已激活组件，Java/Web 门禁跳过，或缺少 OpenAPI、Bruno Collection、PostgreSQL/S3 Compose 骨架都会失败。`pnpm run api:test` 使用 Bruno CLI 对正在运行的本地 API 执行健康检查、批次幂等、文件清单、Multipart 会话、Part 授权、Part 进度、缺失 Part 拒绝、恢复协调、Complete File/Complete Batch/Session Ingest 失败守卫、批次/文件只读进度查询，以及 CameraChannel/CameraSet 配置，需要先启动 PostgreSQL、S3 和后端 `local` profile，并通过进程环境或从集合 `.env.example` 复制出的未提交 `.env` 提供与后端一致的 `DEV_BEARER_TOKEN`。Node 启动器只向 Bruno 映射运行时令牌，不把令牌写入集合。Python Worker/Plugin 属于后续交付阶段，未创建时标记为 deferred，不阻断当前门禁。

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

下一步进入媒体探测与入库报告切片：先冻结 MediaReport/作业状态和错误契约，再按第一个真实耗时任务启用 RabbitMQ 与最小 Media Worker；隐私授权和可播放派生资产稳定前不加入视频播放器。CameraChannel/CameraSet 的管理接口只建立并返回需要部署到 RK3588 的服务器配置标识，不连接、探测或控制边端硬件。大文件仍由 RK3588 边端程序自动调用上传协议并直传对象存储，Vue 不承担 P0 浏览器上传器职责。Pinia 到 OIDC、项目和权限上下文需要跨页面共享时再注册。
