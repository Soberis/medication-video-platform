# Project Agent Rules

## Scope

- 本文件适用于 `medication-video-platform` 整个仓库。
- 子目录如有更近的 `AGENTS.md`、`DESIGN.md` 或 README，优先遵守更近的项目规则。
- 默认使用中文沟通；代码标识符、API 字段和事件名称使用英文。

## Sources Of Truth

这不是每次任务的全量阅读清单；按改动类型读取相关入口，无关分支直接跳过：

- 需求、验收或导出语义：先读 [需求入口](docs/requirements/README.md) 指向的 V1.36 Consolidated Baseline；当前产品语义为 V1.36，V1.28 是上一正式运行时基线，D1–D5 是实施前决策门禁。历史 V1.x 只在迁移、兼容性或审计需要时读取，不从历史推断当前范围。
- UI/UX：读取 `docs/DESIGN_SYSTEM.md`；只有需要领域交互、状态或前端验收规则时再读 `docs/architecture/web-ux-and-frontend-guidelines.md`。
- 架构决策、API、事件、Schema 或数据库：分别读取相关 `docs/adr/`、`contracts/`、迁移和专题文档。
- 真实代码、数据库迁移和实际测试结果用于核对实现事实；发现冲突时说明影响，不自行猜测或扩大范围。

只有用户确认改变需求基线、公开契约或验收语义时，才新增版本化需求基线；普通 bugfix、局部 UI、文档勘误和指令维护不要求新增业务版本。`docs/DESIGN_SYSTEM.md` 是唯一视觉权威，`web-ux-and-frontend-guidelines.md` 只补充领域 UX、实现与验收规则，均不覆盖业务需求和正式契约。

UI 改动复用 `docs/DESIGN_SYSTEM.md` 的视觉语言、Token 和现有组件；确需改变时先修订设计系统。设计技能的审美建议和默认 `DESIGN.md` 路径不覆盖此权威。

## Frontend Design Identity

V1.30：Video-first Quiet Professional Workspace，Neutral Light + Medical Blue（Twenty workspace + Frame.io video-first detail）。实施以设计系统为准，用户验收后基线才能成为 CURRENT。

## Architecture

- P0 采用一个 Java 21、Spring Boot、Spring Modulith Core API，不提前拆微服务。
- 核心领域模块为 `iamproject`、`assetcapture`、`mediaprivacy`、`annotationreview`、`qualitycleaning`、`datasetexport`、`aicandidate`、`governance`。
- `integration` 是 CVAT、S3、RabbitMQ、OIDC 等端口和适配器层，不拥有业务真值。
- Python Worker 只处理媒体和 AI 作业，不直接写业务表；当前唯一 `human-demonstration-hdf5.v2` 由 Core API 校验后导出，旧扁平展开表与 CSV 为历史格式。
- AI 输出进入 `CandidateAnnotation`，手部检测进入 `HandPoseCandidate`；双合格时由 Core API 自动执行乐观核准（以 `AUTO_SYSTEM` 系统身份生成首份 Revision、签署 APPROVED 并标记 COMPLETED，直接准入导出；审计字段不得写成真人或伪造管理员上下文）。各机位均为等权客观观测，手部与动作自动检测，多视角实事求是一一对应；自动核准初始 correspondence 为单机位 `UNCERTAIN`（须带原因），不做自动跨机位时间对齐，跨机位对应在人工【重新编辑】时确认；HDF5 关联当前已批准人工/系统核准区间与同源、provenance 有效的各机位手部样本，不人为设立主副机位。自动核准由完成事件（AFTER_COMMIT）与分页 reconcile 兜底触发，单事务原子、幂等；已存在人工任务、任意 Revision 或非 `AUTO_SYSTEM` 创建者时不得覆写。
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

接入事实按下列顺序建立。V1.36 目标为媒体就绪后默认自动生成候选与手部检测，双合格时系统在后台执行乐观自动核准（生成首份 Revision 并标记 APPROVED 与 COMPLETED，直接准入导出）；人工仅负责异常管理，抽检发现瑕疵时可随时在工作台一键【重新编辑】打回为草稿微调并重新提交。AI 不依赖人工任务、负责人或人工质量；所有 ACTIVE 项目适用。已有任务使用绑定 schema，无任务时冻结已发布全局 schema，不等待 MediaPipe。真实 Worker 联调单独验收。

```text
External Ingest / Upload
→ CaptureGroup
→ CameraStream / ClipAsset
→ Media Probe
   ├─ MediaPipe detection evidence (all cameras)
   └─ action candidate
          ↓ (手部与动作双合格)
     乐观自动核准 (Revision 1, APPROVED) → HDF5 导出准入
          ↕ (抽检异常一键重新编辑)
     人工草稿 (DRAFT) → 显式重新提交 (Revision 2, APPROVED)
```

MediaPipe 手部预标注是数据处理主链的辅助能力；手部骨骼与动作候选均为自动并发检测生成，并与物理动作事实一一对应。若 AI 检测失败或未检出手部样本，系统不触发自动核准，保持待人工介入状态。人工提交与复核涵盖组内各机位的动作对应与手部质量核验，不再只针对单一机位。当前 HDF5 配套导出必须同时满足全机位对应确认、MediaPipe 与既有准入，不恢复独立 CSV。handedness 仅为辅助元数据，不得作为标注、复核、Cleaning 或导出准入条件。

## MVP Simplicity

- 文档可以描述完整演进路径；代码只实现当前纵向闭环所必需的最小能力。
- 保留已经批准的领域边界、权限、审计、可追溯性和数据完整性；不要为了“简化”删除这些事实约束。
- 不提前创建空代码、队列、Worker、事件、数据库表或通用框架；第二个真实用例出现前，不抽象通用能力。
- 能在一个 Core API 数据库事务内可靠完成的操作，不引入消息、缓存或额外进程。耗时、跨进程或需要重试的工作才建立异步作业。
- 默认优先交付一个可运行、可测试、可回退的纵向切片；用户明确要求的多个独立变更仍可在同一任务内完成，不因首个切片完成而提前结束。
- UI 优先使用浏览器和现有组件库的原生能力；没有明确需求时，不引入新的设计系统、状态管理、播放器框架或动画库。
- 产品界面不放置直连数据库的开发期运维操作（批量清理、批量重试、唤醒分析、废弃整组等）；这些能力仅保留在 vite dev 中间件与 `tools/` 命令行脚本，供隔离的开发/合成数据环境使用。若要在产品界面内“舍弃/删除整个采集组”，必须新增正式组删除 API（权限、审计、级联、S3 清理），不得复用 dev 直连脚本。
- V1.36 Consolidated Baseline 不恢复未接线的 CaptureSync runtime，不新增持久化工作流投影、`next_action` / `blockers` 或通用工作流引擎；现役 `CaptureGroupView` 只读模型与前端派生提示保留。不恢复独立单视频任务、单视频 Cleaning、旧事件 manifest 或 `training-data.v2` 产品路径。V1.22 Draft、3D、标定和 DatasetVersion 仍为历史/延后方向。CaptureSession、CameraSet、CameraStream 和 DeclaredSyncStatus 等接入事实仍必须保留；runtime 收缩按对应 D1–D5 决策及任务授权执行，各项决策不构成全部工作的统一阻塞条件。

## Data And Safety

- Codex、开发环境、测试环境和 CI 不得接触真实患者视频、身份映射、医院账号、生产密钥和生产数据库。
- 测试默认使用合成数据；脱敏样本也必须获得明确授权并隔离管理。
- 禁止把令牌、密码、私钥、预签名 URL、真实视频和模型文件提交到仓库。
- 涉及删除、数据放行、隐私审批、签名 URL、AI 转正式标签、数据集冻结和导出时必须人工评审。
- 上述评审边界不阻塞已授权的代码实现、只读审计和隔离合成数据测试；先完成这些准备并给出可评审结果，实际受控操作仍须满足人工评审要求。
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
- 对已有测试的超时、重试或跳过规则，只有在保留原断言且能证明是环境时序问题时才调整；不得借此掩盖失败。

## Verification

按受影响组件运行相关检查；行为变更必须增加相应测试，验证可观察行为和业务约束，避免仅重复实现。API 和事件变更仍须完成契约检查。复用现有测试框架和 fixtures，按风险选择范围，不设固定测试数量。确认使用合成数据且与生产隔离后，本地验证和本次变更导致的失败修复可连续执行，无需逐步确认。无法验证时说明原因和可执行命令。

纯指令或文档修改检查内容一致性、引用和 diff；无需为此运行整套应用测试。相关检查通过即结束验证；新改动、失败或未解决风险才触发扩大或重跑。需要完整质量门禁时，按当前系统运行仓库 README 指向的 `scripts/quality-gate.ps1 -Strict` 或 `scripts/quality-gate.sh --strict`；不要在本文件复制门禁清单。
