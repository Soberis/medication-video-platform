# 生产就绪检查与部署边界

## 目的与结论边界

本手册把仓库内可以重复执行的发布前检查、环境边界和必须由平台运维补齐的生产证据放在一起。
它不把本地 Docker Compose 演示环境当成生产部署，也不授权使用真实患者数据、生产凭据或生产队列。

当前仓库的本地合成闭环可运行，但生产就绪仍取决于以下未完成项：

- Core API、Web 和 Worker 尚无受支持的生产镜像/服务监督配置；
- PostgreSQL、对象存储和配置没有仓库内可执行的生产备份、保留和恢复流程；
- TLS 反向代理/WAF、告警、值班和 RPO/RTO 尚未在目标环境演练。

在这些证据完成前，发布结论只能是 `NOT READY` 或 `READY WITH P1 FOLLOW-UPS`，不能写成生产通过。

## 发布前证据

以下命令只使用合成数据或静态配置，不要把输出中的令牌、密码、预签名 URL 或视频内容留在工单中：

```powershell
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1 -Strict
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml config --quiet
```

质量门禁应覆盖 Java 编译/测试/边界校验、React lint/typecheck/unit test/build、Worker 的锁文件/格式/lint/typecheck/test、契约和合成上传测试。质量门禁失败时停止发布，不要通过跳过测试或改写本地数据库来“修复”。

Node 运行时必须保持在仓库声明的 >=22.12.0 <23 范围内；同时核对 node --version 与 pnpm exec node --version，避免系统 Node 和 pnpm 自带运行时不一致。媒体 Worker 源码摘要变化后，重新计算并同步 MEDIA_WORKER_APPROVED_SOFTWARE_DIGEST；摘要不一致时应让结果进入受控拒绝/死信，不得通过把 Worker 配置成“批准值”来绕过校验。

## 环境与秘密

- `infra/docker-compose/compose.yaml` 只用于本地基础设施；其中的 Postgres、RabbitMQ 和 SeaweedFS 凭据只能是本地占位值。
- 生产必须使用独立数据库、bucket、RabbitMQ vhost、服务身份和会话密钥，不能复用 Local/CI/UAT 的任何一项。
- 生产 Worker 使用文件或受控密钥系统提供的短期 JWT；不要把 bearer token 放进镜像、Git、命令行历史或日志。
- 生产会话 Cookie 必须启用 `Secure`，入口必须由 TLS 反向代理/WAF 终止并限制管理面网络。
- 生产配置必须显式提供非本机的数据库、对象存储和消息代理端点，并核对 bucket、凭据、TLS、最小权限和时钟同步。

## 本地启动、状态与受控重启

这些步骤只适用于本地合成环境：

```powershell
.\scripts\start-local.ps1
.\scripts\status-local.ps1
.\scripts\start-local.ps1 -RestartApi
```

脚本记录 PID 并等待 Core API 健康，但它不是生产进程监督器。生产必须由批准的服务管理平台负责启动、优雅停止、退避重启、日志收集和资源限制；不要把 `start-local.ps1` 或 `nohup` 直接当成生产 supervisor。

重启后至少核对：

1. `/actuator/health` 返回 `status=UP`；
2. Flyway 版本与发布制品匹配；
3. Outbox relay、业务队列、Worker consumer 和未完成 lease 能继续推进；
4. 日志没有认证失败、连接池耗尽、重复提交或 DLQ 增长。

## 备份与恢复

生产备份由数据库和对象存储产品提供方按批准的 RPO/RTO 实施。仓库目前没有生产备份脚本、WAL/PITR 配置或对象存储复制策略，因此上线前必须提交独立的恢复演练证据：

- PostgreSQL 指定时间恢复、Flyway 迁移版本核对、关键行数/摘要对账；
- raw、variant、frame、report 和 export 对象的版本/大小/SHA-256 抽样核验；
- 密钥引用恢复后能读取批准样本；
- RabbitMQ 拓扑可重建，DB 中已提交的作业和 Outbox 能恢复投递；
- 恢复后的权限、审计、AnnotationRevision、CleaningRun 和导出准入事实保持一致。

本轮在本地合成数据库完成了一次隔离演练：使用 `pg_dump` 创建临时归档，恢复到临时数据库，核对 Flyway 版本 `66` 和 49 张 public 表后删除临时数据库。该证据只证明本机合成数据库的可恢复性，不替代生产 PITR、对象备份或整站演练。

## 健康检查与可观测性

当前公开的健康入口是 `/actuator/health`，管理面默认不应向公网暴露。目标部署必须另外明确：

- liveness/readiness 分组和依赖失败时的摘流策略；
- API、数据库连接池、上传 complete/abort、Outbox、RabbitMQ ready/unacked/DLQ、Worker lease/重试/临时盘和对象存储容量指标；
- request ID、结构化日志、脱敏规则、告警 owner、升级路径和对应 runbook；
- 指标标签不得包含患者、操作者、对象 key、预签名 URL 或令牌。

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
- 恢复后的 schema/权限/清洗或导出准入事实无法对账；
- 健康检查为 UP 但关键依赖、队列或 Worker 已持续失败；
- 没有可验证的备份恢复证据、值班 owner 或回滚窗口。
