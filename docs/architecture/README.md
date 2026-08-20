# Architecture Documents

本索引汇总配药视频数据管理平台的当前规范与历史参考。当前范围以 V1.24 FLOW-FIRST P0 自动任务衔接为准：平台对接边端接收，在云端完成视频管理、自动单视频手部预标注、人工质量判断、合格后自动任务分配、配药动作标注复核、数据清洗和管理员确认的最小 `training-data.v2` preview/download。手部预标注与自动任务创建都不替代人工标注、复核和清洗；CaptureSession、CameraSet、CameraStream 和 DeclaredSyncStatus 等接入事实保留，CaptureSync Core API runtime 已按独立 Physical Reduction 批次退役，未来多视角同步仍由 V1.22 DRAFT / DEFERRED 和相关专题文档承载。DatasetVersion/legacy Export 与 AiEvaluation 的代码和 API 已退役，其未使用的数据库对象已由 V65/V66 前向迁移物理退役，历史 Flyway 文件和设计文档仅保留演进记录。

## 当前规范

| 文档 | 负责内容 |
| --- | --- |
| [配药视频数据管理平台：手部预标注优先技术设计](./video-hand-pose-mvp-technical-design.md) | 当前边端对接边界、云端主链、状态、容量和验收 |
| [当前需求追踪矩阵](./requirements-traceability.md) | V1.24 当前 FR 到模块、契约、测试和状态 |
| [领域模型与数据所有权](./domain-model-and-data-ownership.md) | 聚合、表所有权、跨模块引用和事务边界 |
| [状态机与领域守卫](./state-machines.md) | 状态迁移、守卫、角色、幂等和事件 |
| [异步作业协议](./asynchronous-job-protocol.md) | Outbox/Inbox、Worker 回传、租约、重试、取消和 DLQ |
| [时间基准与多相机同步](./timebase-and-multicamera-sync.md) | 未来多视角同步约束参考；V1.24 不交付 CaptureSync runtime，V1.22 继续 DEFERRED |
| [安全、隐私与保留销毁](./security-privacy-retention.md) | 分级、授权、密钥、审计、保留和销毁 |
| [部署、容量与灾难恢复](./deployment-capacity-and-dr.md) | 拓扑、容量、性能、备份、RPO/RTO 和恢复演练 |
| [前端设计系统](../DESIGN_SYSTEM.md) | 唯一前端视觉权威、Token、AppShell、共享布局和通用交互语言 |
| [Web 前端与用户体验设计规范](./web-ux-and-frontend-guidelines.md) | 信息架构、页面状态、组件与状态管理启用门槛、可访问性和前端验收 |

## 历史参考（不得覆盖当前规范）

| 文档 | 历史用途 |
| --- | --- |
| [历史需求追踪矩阵](./requirements-traceability-history.md) | V1.17 及以前的实施记录、证据和历史结论 |
| [配药平台技术设计](./配药平台技术设计.md) | 历史总体设计参考；当前范围以 V1.24 当前规范为准 |
| [Hand Pose PoC](./history/hand-pose-poc.md) | 历史本地推理参考；不定义当前产品语言 |
| [Hand Pose Candidate Phase B](./history/hand-pose-candidate-phase-b.md) | 历史候选机制设计；不定义当前范围 |
| [舱内多机位 Hand Pose Analysis 编排](./history/hand-pose-analysis-orchestration.md) | V1.16 历史编排参考；当前范围以 V1.24 为准 |

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
