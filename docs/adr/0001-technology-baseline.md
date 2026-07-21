# ADR-0001：技术基线与构建边界

- 状态：Accepted
- 日期：2026-07-14

## 背景

平台需要处理多相机大视频、复杂状态流转、权限审计、异步媒体任务、人工标注和后续可替换 AI 插件。P0 的目标是完成数据闭环，不以微服务或 AI 推理作为前置条件。

## 决策

1. Web 使用 Vue 3、TypeScript、Vite、Element Plus、Pinia 和 Vue Router。
2. Core API 使用 Java 21、Spring Boot 4.1.0 和 Spring Modulith 2.1.0，构建方式为 Maven Wrapper。
3. Core API 是一个 Spring Boot 应用，项目 Java 根包冻结为 `com.medicationvideo.platform`，八个领域模块按 Java 包和表所有权隔离。
4. 外部系统通过 `integration` 端口和适配器接入；MVP 不创建独立适配器服务。
5. 数据库使用 PostgreSQL 18 系列或目标部署批准的 17 系列，结构变更只通过 Flyway。
6. 大文件使用 S3 Compatible API；业务层不依赖 MinIO 专有 API。
7. 异步任务使用 Transactional Outbox 和 RabbitMQ；Redis 只用于缓存、短锁、限流和短期进度。
8. 媒体、导出和 AI 使用 Python 3.12 Linux Worker；依赖由 uv 和 lockfile 管理。
9. Object 标注优先集成 CVAT，session 级 Step 使用平台多路时间轴。
10. API 使用 OpenAPI 3.1，事件使用版本化 JSON Schema，Bruno 只作为本地调试和可执行请求集合。
11. P0 使用 Docker Compose；达到明确的 HA、GPU 或多院区扩容门槛后才评估 Kubernetes。
12. MediaPipe 使用 Tasks API 并作为 P1 插件；输出只能是候选标注。

## 版本原则

- 文档固定兼容系列，构建和部署固定准确补丁版本及镜像摘要。
- 禁止 `latest` 镜像、未锁定依赖和运行时模型下载。
- 当前开发工具基线：Java 21、Node.js 22.22.2、pnpm 11.11.0、Python 3.12。

## 影响

- P0 的部署和运维复杂度低于微服务方案。
- Java 与 Python 之间必须通过版本化消息/API 契约协作，不共享内部 Repository。
- 如果未来拆服务，必须以现有模块边界、数据所有权和独立扩容证据为依据。

## 尚待冻结

- PostgreSQL 17/18 的目标部署批准版本与高可用方案。
- S3 兼容对象存储的实际产品及许可证结论。
- 外部 IdP 是否需要以及接入方式（如未来出现统一身份集成需求）。
