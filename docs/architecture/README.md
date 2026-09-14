# Architecture Documents

本索引汇总配药视频数据处理平台的当前规范与历史参考。当前唯一需求入口是 V1.35 Consolidated Baseline：它冻结自包含的产品边界、CaptureGroup 主链、AI/人工事实隔离和唯一 HDF5 导出语义；D1–D5 是实施前决策门禁。V1.28 仍是上一正式运行时基线，V1.35 文档收敛不表示 runtime 已全部实现或已授权删除。Candidate、权限、审计和 provenance 边界继续有效。

## 当前规范

| 文档 | 负责内容 |
| --- | --- |
| [V1.35 Consolidated Baseline（当前唯一需求）](../requirements/baselines/V1.35/配药视频数据处理平台需求文档_V1.35.md) | 当前唯一完整产品需求；D1–D5 为实施前决策门禁 |
| [动作候选技术设计（支持性 DRAFT）](./v1.34-action-annotation.md) | 解释动作候选、Worker、时间映射和人工编辑的实现边界；不得覆盖 V1.35 |
| [领域模型与数据所有权](./domain-model-and-data-ownership.md) | 聚合、表所有权、跨模块引用和事务边界 |
| [状态机与领域守卫](./state-machines.md) | 状态迁移、守卫、角色、幂等和事件 |
| [异步作业协议](./asynchronous-job-protocol.md) | Outbox/Inbox、Worker 回传、租约、重试、取消和 DLQ |
| [安全、隐私与保留销毁](./security-privacy-retention.md) | 分级、授权、密钥、审计、保留和销毁 |
| [部署、容量与灾难恢复](./deployment-capacity-and-dr.md) | 拓扑、容量、性能、备份、RPO/RTO 和恢复演练 |
| [前端设计系统](../DESIGN_SYSTEM.md) | 唯一前端视觉权威、Token、AppShell、共享布局和通用交互语言 |
| [Web 前端与用户体验设计规范](./web-ux-and-frontend-guidelines.md) | 信息架构、页面状态、组件与状态管理启用门槛、可访问性和前端验收 |
| [当前需求追踪与实现证据](./requirements-traceability.md) | V1.35 需求对应的实现/审计证据；未验收条目不得视为完成 |

## 历史参考（按需读取）

历史架构和迁移资料不得覆盖当前规范；仅在迁移、兼容性或审计任务需要时读取[历史索引](./history-index.md)。

## Active / History 清理清单

[Platform 文档 Active / History 清理清单](../audits/v1.35-platform-document-active-history-inventory.md) 记录当前文档分类、历史化建议和仍需决策的提案。本阶段只更新入口与清单，不移动或删除任何文档。

## 维护规则

- 当前业务需求唯一以 `docs/requirements/baselines/V1.35/配药视频数据处理平台需求文档_V1.35.md` 为完整入口；本目录不复制需求正文。
- 总体技术设计只保存跨领域决策；详细规则进入对应专题文档。
- `docs/DESIGN_SYSTEM.md` 是唯一前端视觉权威；Web 领域 UX、实现、状态和验收规则由前端体验规范补充，后者不得另建视觉语言。
- 已批准技术决策进入 `docs/adr/`；详细设计不得覆盖 ADR。
- API、事件和结构化数据契约进入 `contracts/`，不能只保存在 Markdown 示例中。
- 数据库结构只通过 `services/core-api/src/main/resources/db/migration/` 维护。
- 架构变化同步更新需求追踪、ADR、契约、实现和测试。
- 待确认值明确标记 `PENDING`，不得在代码中伪装成批准常量。
- 不创建同名副本或 `final-final` 文档；当前版本由本 README 和根 README 指向。
