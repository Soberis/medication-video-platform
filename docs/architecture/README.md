# Architecture Documents

本索引汇总配药视频数据管理平台的当前规范与历史参考。当前需求范围以 V1.28 为准：产品只有一条以 `CaptureGroup` 为对象的业务路径，Local Upload 和 External Ingest 都通过 `CameraStream / ClipAsset` 进入事件工作台，`N=1` 只是一路视图；唯一公开导出是按当前 CameraStream 生成的 `hand-landmarks.v1` MediaPipe detection evidence CSV，支持项目或单一 CaptureGroup 范围，不依赖人工质量、Annotation、Review 或 Cleaning。V1.27 及更早版本的事件事实、人工质量、单组任务与 Revision、人工 Review、确定性 Cleaning、权限、审计和 provenance 中仍适用的部分继续保留；旧事件资格 manifest、单视频工作流和 `training-data.v2` 不再是当前产品承诺。Candidate、权限、审计和 provenance 边界继续有效；CaptureSession、CameraSet、CameraStream 和 DeclaredSyncStatus 等接入事实保留，CaptureSync Core API runtime 已退役，V1.22 多视角及 3D/标定/数据集平台仍为历史/延后方向。

## 当前规范

| 文档 | 负责内容 |
| --- | --- |
| [V1.28 单一导出边界设计](./v1.28-single-export-consolidation.md) | 当前唯一导出边界；继承 V1.27 的 CaptureGroup 主链和 N=1 统一模型 |
| [V1.27 AI evidence pipeline audit](./v1.27-ai-evidence-audit.md) | 上一版本的 MediaPipe/Candidate 证据审计，当前仍适用于检测事实部分 |
| [V1.27 training traceability audit](./v1.27-training-traceability.md) | 上一版本的 provenance 审计；事件 manifest 部分仅作历史记录 |
| [V1.27 hand detection policy](./v1.27-hand-detection-policy.md) | 逐视图检测状态、人工决策边界、审计与保留要求 |
| [CaptureGroup 多机位接收与数据闭环最小技术设计](./capture-group-multicamera-workflow-technical-design.md) | V1.25 历史领域参考；仅承接 V1.27 仍适用的所有权与实现事实 |
| [固定多相机配药事件质量评审、标注与清洗用户流程](./multi-camera-quality-review-workflow.md) | V1.25 历史质量、Review 与 Cleaning 语义参考 |
| [配药视频数据管理平台：手部预标注组件技术设计](./video-hand-pose-mvp-technical-design.md) | V1.24/V1.25 历史组件参考；当前产品路径由 V1.28 定义 |
| [当前需求追踪矩阵](./requirements-traceability.md) | V1.28 当前需求到模块、契约、测试和状态；历史版本证据单独标识 |
| [领域模型与数据所有权](./domain-model-and-data-ownership.md) | 聚合、表所有权、跨模块引用和事务边界 |
| [状态机与领域守卫](./state-machines.md) | 状态迁移、守卫、角色、幂等和事件 |
| [异步作业协议](./asynchronous-job-protocol.md) | Outbox/Inbox、Worker 回传、租约、重试、取消和 DLQ |
| [时间基准与多相机同步](./timebase-and-multicamera-sync.md) | V1.25 时间映射约束参考；不交付 CaptureSync runtime，V1.22 继续 DEFERRED |
| [安全、隐私与保留销毁](./security-privacy-retention.md) | 分级、授权、密钥、审计、保留和销毁 |
| [部署、容量与灾难恢复](./deployment-capacity-and-dr.md) | 拓扑、容量、性能、备份、RPO/RTO 和恢复演练 |
| [前端设计系统](../DESIGN_SYSTEM.md) | 唯一前端视觉权威、Token、AppShell、共享布局和通用交互语言 |
| [Web 前端与用户体验设计规范](./web-ux-and-frontend-guidelines.md) | 信息架构、页面状态、组件与状态管理启用门槛、可访问性和前端验收 |

## 历史参考（不得覆盖当前规范）

| 文档 | 历史用途 |
| --- | --- |
| [历史需求追踪矩阵](./requirements-traceability-history.md) | V1.17 及以前的实施记录、证据和历史结论 |
| [V1.26 极简配药事件工作台设计](./v1.26-medication-event-workbench-design.md) | V1.26 历史产品 IA 和工作台设计；当前范围以 V1.28 当前规范为准 |
| [配药平台技术设计](./配药平台技术设计.md) | 历史总体设计参考；当前范围以 V1.28 当前规范为准 |
| [Hand Pose PoC](./history/hand-pose-poc.md) | 历史本地推理参考；不定义当前产品语言 |
| [Hand Pose Candidate Phase B](./history/hand-pose-candidate-phase-b.md) | 历史候选机制设计；不定义当前范围 |
| [舱内多机位 Hand Pose Analysis 编排](./history/hand-pose-analysis-orchestration.md) | V1.16 历史编排参考；当前范围以 V1.28 为准 |

## 维护规则

- 业务需求基线位于 `docs/requirements/`，本目录不复制需求正文。
- 总体技术设计只保存跨领域决策；详细规则进入对应专题文档。
- `docs/DESIGN_SYSTEM.md` 是唯一前端视觉权威；Web 领域 UX、实现、状态和验收规则由前端体验规范补充，后者不得另建视觉语言。
- 已批准技术决策进入 `docs/adr/`；详细设计不得覆盖 ADR。
- API、事件和结构化数据契约进入 `contracts/`，不能只保存在 Markdown 示例中。
- 数据库结构只通过 `services/core-api/src/main/resources/db/migration/` 维护。
- 架构变化同步更新需求追踪、ADR、契约、实现和测试。
- 待确认值明确标记 `PENDING`，不得在代码中伪装成批准常量。
- 不创建同名副本或 `final-final` 文档；当前版本由本 README 和根 README 指向。
