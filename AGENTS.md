# Project Agent Rules

## Scope

- 本文件适用于 `medication-video-platform` 整个仓库。
- 子目录如有更近的 `AGENTS.md`、`DESIGN.md` 或 README，优先遵守更近的项目规则。
- 默认使用中文沟通；代码标识符、API 字段和事件名称使用英文。

## Sources Of Truth

以下是需求冲突的裁决顺序，不是每次任务的全量阅读清单；按本次改动涉及的业务、UI、契约或迁移读取相关材料：

1. 用户在当前对话中明确提出的最新业务变更；它优先于任何历史需求或技术文档，必须先形成新的版本化需求基线。纯指令维护、文档勘误和不改变业务语义的修复无需新增业务版本。
2. `docs/requirements/baselines/V1.30/配药视频数据处理平台需求文档_V1.30.md`：当前 DRAFT 需求基线，定义浅色专业工作区与视频优先体验重构；界面方向冲突以 V1.30 为准，实施前同步设计系统。本版本继承 `docs/requirements/baselines/V1.29/配药视频数据处理平台需求文档_V1.29.md` 的未变更业务边界；V1.29 为前序 DRAFT，收敛为数据组、媒体检查、MediaPipe machine evidence、可选人工标签与导出；它优先定义后续变更方向，但在冻结前不表示 runtime 已改变或可删除。
3. `docs/requirements/baselines/V1.28/配药视频数据管理平台需求文档_V1.28.md` 与 `docs/architecture/v1.28-single-export-consolidation.md`：上一正式运行时基线和导出边界；V1.30 及其继承的 V1.29 与其冲突处以最新 DRAFT 的后续变更方向为准，具体删除项必须等待后续代码审计和批准。
4. `docs/architecture/v1.27-hand-detection-policy.md`、`docs/architecture/v1.27-ai-evidence-audit.md` 与 `docs/architecture/v1.27-training-traceability.md`：上一版本的手部检测和证据审计参考，其中检测事实边界仍适用，事件 manifest 部分不再是当前产品。`docs/requirements/baselines/V1.27/配药视频数据管理平台需求文档_V1.27.md`、`docs/requirements/baselines/V1.26/配药视频数据管理平台需求文档_V1.26.md`、`docs/architecture/v1.26-medication-event-workbench-design.md`、V1.25 CaptureGroup 文档和 V1.24 文档均为历史/迁移参考，不构成当前兼容性承诺。
5. `docs/adr/`：已经冻结的技术决策。
6. `contracts/`：OpenAPI、事件和 JSON Schema 契约。
7. 真实代码、数据库迁移和测试结果。

发现冲突时按上述优先级处理并说明影响，不自行猜测或扩大范围。前端界面任务必须先读取 `docs/DESIGN_SYSTEM.md`，并同时读取 `docs/architecture/web-ux-and-frontend-guidelines.md`；前者是唯一视觉权威，后者只补充领域 UX、实现与验收规则，均不覆盖业务需求和正式契约。

所有 UI 工作必须遵守 `docs/DESIGN_SYSTEM.md`。不得引入新的视觉语言、任意设计 Token 或一次性页面样式；确需改变时，应有意识地先修订设计系统。

## Frontend Design Identity

V1.30 implementation follows the revised `docs/DESIGN_SYSTEM.md`; user acceptance is still required before the baseline becomes CURRENT.

Frontend UI work follows this design identity:

Video-first Quiet Professional Workspace

with Neutral Light + Medical Blue visual theme (Twenty workspace + Frame.io video-first detail).

## Architecture

- P0 采用一个 Java 21、Spring Boot、Spring Modulith Core API，不提前拆微服务。
- 核心领域模块为 `iamproject`、`assetcapture`、`mediaprivacy`、`annotationreview`、`qualitycleaning`、`datasetexport`、`aicandidate`、`governance`。
- `integration` 是 CVAT、S3、RabbitMQ、OIDC 等端口和适配器层，不拥有业务真值。
- Python Worker 只处理媒体和 AI 作业；当前 `hand-landmarks.v1` 由 Core API 同步只读导出，不直接写正式标注或冻结数据集。
- AI 输出只能进入 `CandidateAnnotation`；`hand-landmarks.v1` 可在校验当前 Candidate evidence provenance 后独立导出检测事实，但不得把它写成正式标注、人工质量、Review 或 Cleaning 结论。
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
External Ingest / Upload
→ CaptureGroup
→ CameraStream / ClipAsset
→ Media Probe
→ MediaPipe Candidate
→ Automated / Human Quality
→ Optional Annotation / Review
→ Export
```

MediaPipe 手部预标注是数据处理主链的辅助能力；它只能产生 `CandidateAnnotation`，不得改写人工质量、正式标注、Review 或 Cleaning。人工质量、Annotation、Review 和 Cleaning 仅在项目需要 human ground truth 时使用，不是每个 CaptureGroup 或 `hand-landmarks.v1` 的强制流程。独立的 `hand-landmarks.v1` 只导出经过 provenance 校验的当前检测事实，不以人工链作为前置条件。handedness 仅为辅助元数据，不得作为标注、复核、Cleaning 或导出准入条件。

## MVP Simplicity

- 文档可以描述完整演进路径；代码只实现当前纵向闭环所必需的最小能力。
- 保留已经批准的领域边界、权限、审计、可追溯性和数据完整性；不要为了“简化”删除这些事实约束。
- 不提前创建空代码、队列、Worker、事件、数据库表或通用框架；第二个真实用例出现前，不抽象通用能力。
- 能在一个 Core API 数据库事务内可靠完成的操作，不引入消息、缓存或额外进程。耗时、跨进程或需要重试的工作才建立异步作业。
- 每次优先交付一个可运行、可测试、可回退的纵向切片；完成后再选择下一项，不并行铺开多个未闭环模块。
- UI 优先使用浏览器和现有组件库的原生能力；没有明确需求时，不引入新的设计系统、状态管理、播放器框架或动画库。
- V1.29 DRAFT 不恢复未接线的 CaptureSync runtime，不新增 `CaptureGroupView`、持久化 `next_action` / `blockers` 或通用工作流引擎；不恢复独立单视频任务、单视频 Cleaning、旧事件 manifest 或 `training-data.v2` 产品路径。V1.22 Draft、3D、标定和 DatasetVersion 仍为历史/延后方向。CaptureSession、CameraSet、CameraStream 和 DeclaredSyncStatus 等接入事实仍必须保留；现有 runtime 的具体收缩或删除必须等待后续审计和批准。

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

按受影响组件运行相关检查；行为变更仍须增加相应测试，API 和事件变更仍须完成契约检查。复用现有测试框架和 fixtures，不限制测试数量。确认使用合成数据且与生产隔离后，本地验证和本次变更导致的失败修复可连续执行，无需逐步确认。

纯指令或文档修改检查内容一致性、引用和 diff；无需为此运行整套应用测试。相关检查通过即结束验证；新改动、失败或未解决风险才触发扩大或重跑。需要完整质量门禁时使用以下入口。

macOS / Linux：

```bash
./scripts/check-environment.sh
./scripts/quality-gate.sh --strict
```

Windows PowerShell 7 可运行：

```powershell
.\scripts\check-environment.ps1
.\scripts\quality-gate.ps1 -Strict
```

完整质量门禁覆盖：

- Java：编译、测试、格式检查、Spring Modulith 边界验证和 Flyway 测试。
- React：lint、type-check、unit test 和 build。
- Python：lockfile 检查、Ruff、mypy 和 pytest。
- 契约：OpenAPI、事件 Schema 和兼容性检查。
- 系统：合同、集成、E2E 和安全测试。
