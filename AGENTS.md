# Project Agent Rules

## Scope

- 本文件适用于 `medication-video-platform` 整个仓库。
- 子目录如有更近的 `AGENTS.md`、`DESIGN.md` 或 README，优先遵守更近的项目规则。
- 默认使用中文沟通；代码标识符、API 字段和事件名称使用英文。

## Sources Of Truth

按以下顺序理解需求和实现：

1. 用户在当前对话中明确提出的最新业务变更；它优先于任何历史需求或技术文档，必须先形成新的版本化需求基线。
2. `docs/requirements/baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md`：当前正式业务需求基线（FLOW-FIRST P0 自动任务衔接）；未覆盖条款继承 V1.23、V1.21、V1.20、V1.18 及其历史继承基线。V1.22 多视角草案继续为 DRAFT / DEFERRED，不覆盖 V1.24。
3. `docs/architecture/video-hand-pose-mvp-technical-design.md`：当前云端数据闭环、手部预标注、状态、容量和实施顺序设计。
4. `docs/adr/`：已经冻结的技术决策。
5. `docs/architecture/` 中标记为“当前规范”的专题详细设计。
6. `contracts/`：OpenAPI、事件和 JSON Schema 契约。
7. 真实代码、数据库迁移和测试结果。

发现冲突时按上述优先级处理并说明影响，不自行猜测或扩大范围。前端界面任务必须先读取 `docs/DESIGN_SYSTEM.md`，并同时读取 `docs/architecture/web-ux-and-frontend-guidelines.md`；前者是唯一视觉权威，后者只补充领域 UX、实现与验收规则，均不覆盖业务需求和正式契约。

所有 UI 工作必须遵守 `docs/DESIGN_SYSTEM.md`。不得引入新的视觉语言、任意设计 Token 或一次性页面样式；确需改变时，应有意识地先修订设计系统。

## 前端设计身份

所有前端 UI 工作必须遵循以下永久设计身份：

Linear 风格的安静专业工作区

视觉主题为石墨灰 + 医疗蓝。

修改前端 UI 前，必须阅读：

`docs/DESIGN_SYSTEM.md`

设计系统是以下内容的唯一事实来源：

- 布局；
- 颜色；
- 间距；
- 组件；
- 交互模式。

不得引入独立的页面级视觉风格。

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
- Flyway SQL 只允许存放在 `services/core-api/src/main/resources/db/migration/`。

## Delivery Order

严格遵循：

```text
边端接收对接
→ 视频与数据管理
→ P0 单视频 MediaPipe 手部预标注
→ 配药动作标注与复核
→ 数据清洗与可用数据
→ P0 最小 training_data.parquet 导出
→ P0 集成测试与授权样本算法评估
→ P1 显式批量预标注和项目自动策略
→ 后续数据集构建、导出与模型演进
```

MediaPipe 手部预标注是当前 P0 数据生产闭环的辅助能力；它只能产生 `CandidateAnnotation`，不得绕过人工确认、复核和清洗进入正式数据。handedness 仅为辅助元数据，不得作为标注、复核、Cleaning 或导出准入条件。

## MVP Simplicity

- 文档可以描述完整演进路径；代码只实现当前纵向闭环所必需的最小能力。
- 保留已经批准的领域边界、权限、审计、可追溯性和数据完整性；不要为了“简化”删除这些事实约束。
- 不提前创建空代码、队列、Worker、事件、数据库表或通用框架；第二个真实用例出现前，不抽象通用能力。
- 能在一个 Core API 数据库事务内可靠完成的操作，不引入消息、缓存或额外进程。耗时、跨进程或需要重试的工作才建立异步作业。
- 每次优先交付一个可运行、可测试、可回退的纵向切片；完成后再选择下一项，不并行铺开多个未闭环模块。
- UI 优先使用浏览器和现有组件库的原生能力；没有明确需求时，不引入新的设计系统、状态管理、播放器框架或动画库。
- 当前 P0 不保留未接线的 CaptureSync runtime，仅为了未来 V1.22 Draft 预留；CaptureSession、CameraSet、CameraStream 和 DeclaredSyncStatus 等接入事实仍必须保留。

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

阶段 0 在 macOS / Linux 可运行：

```bash
./scripts/check-environment.sh
./scripts/quality-gate.sh --strict
```

Windows PowerShell 7 可运行：

```powershell
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1 -Strict
```

组件工程生成后，质量门禁应覆盖：

- Java：编译、测试、格式检查、Spring Modulith 边界验证和 Flyway 测试。
- React：lint、type-check、unit test 和 build。
- Python：lockfile 检查、Ruff、mypy 和 pytest。
- 契约：OpenAPI、事件 Schema 和兼容性检查。
- 系统：合同、集成、E2E 和安全测试。
