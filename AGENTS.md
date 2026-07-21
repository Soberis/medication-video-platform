# Project Agent Rules

## Scope

- 本文件适用于 `medication-video-platform` 整个仓库。
- 子目录如有更近的 `AGENTS.md`、`DESIGN.md` 或 README，优先遵守更近的项目规则。
- 默认使用中文沟通；代码标识符、API 字段和事件名称使用英文。

## Sources Of Truth

按以下顺序理解需求和实现：

1. 用户在当前对话中明确提出的最新业务变更；它优先于任何历史需求或技术文档，必须先形成新的版本化需求基线。
2. `docs/requirements/baselines/V1.3/配药视频数据管理平台需求文档_V1.3.md`：当前业务需求基线；未变更条款继承 V1.2/V1.1.13。
3. `docs/architecture/配药平台技术设计.md`：总体架构、技术选型、模块边界和实施顺序。
4. `docs/adr/`：已经冻结的技术决策。
5. `docs/architecture/` 中由主技术设计链接的专题详细设计。
6. `contracts/`：OpenAPI、事件和 JSON Schema 契约。
7. 真实代码、数据库迁移和测试结果。

发现冲突时按上述优先级处理并说明影响，不自行猜测或扩大范围。前端界面任务还应读取 `docs/design.md` 和 `docs/architecture/web-ux-and-frontend-guidelines.md`；它们只约束设计与实现方式，不覆盖业务需求和正式契约。

## Architecture

- P0 采用一个 Java 21、Spring Boot、Spring Modulith Core API，不提前拆微服务。
- 核心领域模块为 `iamproject`、`assetcapture`、`mediaprivacy`、`annotationreview`、`qualitycleaning`、`datasetexport`、`aicandidate`、`governance`。
- `integration` 是 CVAT、S3、RabbitMQ、OIDC 等端口和适配器层，不拥有业务真值。
- Python Worker 只处理媒体、导出和 AI 作业，不直接写正式标注或冻结数据集。
- AI 输出只能进入 `CandidateAnnotation`，经人工确认、正式修订、复核和清洗后才能进入数据集。
- Redis 不作为事实数据库；RabbitMQ 不保存最终业务状态；对象存储不承载关系型业务状态。

## Module Boundaries

- 模块只能写自己拥有的表。
- 禁止跨模块 Repository 注入和跨模块直接更新表。
- 跨模块写入通过公开应用服务或版本化领域事件。
- 跨模块读取通过公开查询接口或明确的只读投影。
- 每个 API 变更同步更新 OpenAPI 和契约测试。
- 每个事件变更同步更新 JSON Schema 和兼容性测试。
- Flyway SQL 只允许存放在 `services/platform-api/src/main/resources/db/migration/`。

## Delivery Order

严格遵循：

```text
仓库骨架和质量门禁
→ P0 上传与资产
→ P0 隐私、标注和清洗
→ P0 数据集与导出
→ P0 集成测试和 UAT
→ AI Plugin SDK
→ Mock Plugin
→ MediaPipe 试点
→ 对照评估和启停决策
```

MediaPipe 不进入 P0 关键路径。

## MVP Simplicity

- 文档可以描述完整演进路径；代码只实现当前纵向闭环所必需的最小能力。
- 保留已经批准的领域边界、权限、审计、可追溯性和数据完整性；不要为了“简化”删除这些事实约束。
- 不提前创建空代码、队列、Worker、事件、数据库表或通用框架；第二个真实用例出现前，不抽象通用能力。
- 能在一个 Core API 数据库事务内可靠完成的操作，不引入消息、缓存或额外进程。耗时、跨进程或需要重试的工作才建立异步作业。
- 每次优先交付一个可运行、可测试、可回退的纵向切片；完成后再选择下一项，不并行铺开多个未闭环模块。
- UI 优先使用浏览器和现有组件库的原生能力；没有明确需求时，不引入新的设计系统、状态管理、播放器框架或动画库。

## Data And Safety

- Codex、开发环境、测试环境和 CI 不得接触真实患者视频、身份映射、医院账号、生产密钥和生产数据库。
- 测试默认使用合成数据；脱敏样本也必须获得明确授权并隔离管理。
- 禁止把令牌、密码、私钥、预签名 URL、真实视频和模型文件提交到仓库。
- 涉及删除、数据放行、隐私审批、签名 URL、AI 转正式标签、数据集冻结和导出时必须人工评审。
- 不自动触发相机、机器人、PLC、外部医院系统或其他真实硬件/服务状态变化。

## Versions And Dependencies

- Java 21；Spring Boot 使用技术设计批准的 4.1 系列准确补丁版本。
- Node.js 22；pnpm 11；Python 3.12。
- Java 使用 Maven Wrapper，前端使用 pnpm lockfile，Python 使用 uv 和 lockfile。
- 容器镜像和运行时依赖不得使用 `latest`。
- 不在运行时自动下载模型；模型必须有 SHA-256 和审批记录。

## Change Rules

- 先理解再修改，保持最小改动，匹配现有结构和风格。
- 未经用户明确要求，不执行 Git 提交、推送、合并、分支删除或远程状态变更。
- 不回滚无关的用户改动；遇到重叠修改先说明。
- 不编造测试、CI、部署、硬件或外部服务结果。
- 行为变更必须增加相应测试；无法验证时说明原因和可执行命令。
- 对已有测试的超时、重试或跳过规则，只有在保留原断言且能证明是环境时序问题时才调整；不得借此掩盖失败。

## Verification

阶段 0 可运行：

```powershell
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1
```

组件工程生成后，质量门禁应覆盖：

- Java：编译、测试、格式检查、Spring Modulith 边界验证和 Flyway 测试。
- Vue：lint、type-check、unit test 和 build。
- Python：lockfile 检查、Ruff、mypy 和 pytest。
- 契约：OpenAPI、事件 Schema 和兼容性检查。
- 系统：合同、集成、E2E 和安全测试。
