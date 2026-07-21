# Medication Video Platform

护士配药多相机视频数据管理平台，用于视频入库、资产管理、标注复核、清洗和数据集导出。

## 当前阶段

Sprint 0 已完成，正在交付 P0 上传与资产闭环。

- Core API 是 Java 21、Spring Boot 和 Spring Modulith 模块化单体；
- Web 已支持授权项目、Session、视频资产、文件夹筛选和平台本地上传；
- PostgreSQL、S3 兼容对象存储、RabbitMQ 和 Media Worker 已用于本地合成数据闭环；
- Redis、CVAT、真实 OIDC、Export Worker 和 AI 插件尚未启用；
- 下一纵向切片是可播放 Variant 与播放授权，随后实现视频软删除。

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

```powershell
cd C:\Dev\Projects\medication-video-platform
pnpm install --frozen-lockfile
.\scripts\start-local.ps1
```

Web 默认地址为 `http://localhost:5173/projects`，Core API 健康检查为 `http://localhost:8080/actuator/health`。本地日志位于 `logs/local/`。

## 验证

```powershell
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1 -Strict
pnpm run api:test
```

`api:test` 需要本地基础设施和 Core API 已启动，并且只能使用合成开发身份和合成视频。

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
