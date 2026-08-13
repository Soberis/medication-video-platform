# Architecture Documents

本索引汇总配药视频数据管理平台的当前规范与历史参考。当前范围以 V1.20 LEAN-P0 为准：平台对接边端接收，在云端完成视频管理、单视频手部预标注、配药动作标注复核和数据清洗。手部预标注优先建设，但不替代人工标注和清洗；V1.19 多相机同步与数据集产品能力为 DEFERRED。

## 当前规范

| 文档 | 负责内容 |
| --- | --- |
| [配药视频数据管理平台：手部预标注优先技术设计](./video-hand-pose-mvp-technical-design.md) | 当前边端对接边界、云端主链、状态、容量和验收 |
| [当前需求追踪矩阵](./requirements-traceability.md) | V1.20 当前 FR 到模块、契约、测试和状态 |
| [领域模型与数据所有权](./domain-model-and-data-ownership.md) | 聚合、表所有权、跨模块引用和事务边界 |
| [状态机与领域守卫](./state-machines.md) | 状态迁移、守卫、角色、幂等和事件 |
| [异步作业协议](./asynchronous-job-protocol.md) | Outbox/Inbox、Worker 回传、租约、重试、取消和 DLQ |
| [时间基准与多相机同步](./timebase-and-multicamera-sync.md) | 历史同步约束参考；V1.20 不交付同步产品能力 |
| [安全、隐私与保留销毁](./security-privacy-retention.md) | 分级、授权、密钥、审计、保留和销毁 |
| [部署、容量与灾难恢复](./deployment-capacity-and-dr.md) | 拓扑、容量、性能、备份、RPO/RTO 和恢复演练 |
| [Web 前端与用户体验设计规范](./web-ux-and-frontend-guidelines.md) | 信息架构、页面状态、组件与状态管理启用门槛、可访问性和前端验收 |
| [设计简报](../design.md) | 管理端视觉方向、信息层级、交互语言与视觉非目标 |

## 历史参考（不得覆盖当前规范）

| 文档 | 历史用途 |
| --- | --- |
| [历史需求追踪矩阵](./requirements-traceability-history.md) | V1.17 及以前的实施记录、证据和历史结论 |
| [配药平台技术设计](./配药平台技术设计.md) | 历史总体设计参考；当前范围以 V1.20 为准 |
| [Hand Pose PoC](./history/hand-pose-poc.md) | 历史本地推理参考；不定义当前产品语言 |
| [Hand Pose Candidate Phase B](./history/hand-pose-candidate-phase-b.md) | 历史候选机制设计；不定义当前范围 |
| [舱内多机位 Hand Pose Analysis 编排](./history/hand-pose-analysis-orchestration.md) | V1.16 历史编排参考；单路/批量范围以 V1.20 为准 |

## 维护规则

- 业务需求基线位于 `docs/requirements/`，本目录不复制需求正文。
- 总体技术设计只保存跨领域决策；详细规则进入对应专题文档。
- `docs/design.md` 只定义视觉方向和信息表达；Web 实现、状态和验收规则由前端体验规范负责。
- 已批准技术决策进入 `docs/adr/`；详细设计不得覆盖 ADR。
- API、事件和结构化数据契约进入 `contracts/`，不能只保存在 Markdown 示例中。
- 数据库结构只通过 `services/platform-api/src/main/resources/db/migration/` 维护。
- 架构变化同步更新需求追踪、ADR、契约、实现和测试。
- 待确认值明确标记 `PENDING`，不得在代码中伪装成批准常量。
- 不创建同名副本或 `final-final` 文档；当前版本由本 README 和根 README 指向。
