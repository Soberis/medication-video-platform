# Architecture Documents

本索引汇总当前配药视频数据管理平台的技术基线、兼容性专题设计和 Web 设计简报。V1.17 起，
日常产品以“边端对接接收、视频/数据管理、手部预标注、Ego 标注复核、数据清洗”的云端闭环为目标；手部预标注优先建设，数据集与训练导出在清洗通过后继续按阶段演进。

## 文档导航

| 文档 | 负责内容 |
| --- | --- |
| [配药视频数据管理平台：手部预标注优先技术设计](./video-hand-pose-mvp-technical-design.md) | 边端对接边界、云端数据闭环、最小 API/UI、迁移切片与验收 |
| [配药平台技术设计](./配药平台技术设计.md) | 总体范围、技术选型、模块边界和实施顺序 |
| [需求追踪矩阵](./requirements-traceability.md) | FR/NFR 到模块、契约、表和验收证据 |
| [Ego Hand Pose PoC](./hand-pose-poc.md) | Phase A 本地推理、PTS、Overlay、JSONL 和效果评估边界 |
| [Ego Hand Pose Candidate Phase B](./hand-pose-candidate-phase-b.md) | GO 后的 Ego 准入、冻结 profile、帧级结果、Worker、审核与验收门禁 |
| [舱内多机位 Hand Pose Analysis 编排](./hand-pose-analysis-orchestration.md) | V1.16 自动媒体检查、单一分析入口、持久化意图、策略和 Skeleton Overlay 设计 |
| [领域模型与数据所有权](./domain-model-and-data-ownership.md) | 聚合、表所有权、跨模块引用和事务边界 |
| [状态机与领域守卫](./state-machines.md) | 状态迁移、守卫、角色、幂等和事件 |
| [异步作业协议](./asynchronous-job-protocol.md) | Outbox/Inbox、Worker 回传、租约、重试、取消和 DLQ |
| [时间基准与多相机同步](./timebase-and-multicamera-sync.md) | PTS、VFR、offset、drift、抽帧和同步验收 |
| [安全、隐私与保留销毁](./security-privacy-retention.md) | 分级、授权、密钥、审计、保留和销毁 |
| [部署、容量与灾难恢复](./deployment-capacity-and-dr.md) | 拓扑、容量、性能、备份、RPO/RTO 和恢复演练 |
| [Web 前端与用户体验设计规范](./web-ux-and-frontend-guidelines.md) | 信息架构、页面状态、组件与状态管理启用门槛、可访问性和前端验收 |
| [设计简报](../design.md) | 管理端视觉方向、信息层级、交互语言与视觉非目标 |

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
