# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前正式基线

- [V1.25 配药视频数据管理平台需求基线（多机位 CaptureGroup 工作流）](./baselines/V1.25/配药视频数据管理平台需求文档_V1.25.md)

V1.25 是当前正式业务需求基线：一个真实配药事件对应一个 `CaptureGroup`，支持动态 `N` 路固定机位，分离收件、媒体和人工质量事实；组质量通过后自动创建一个组 AnnotationTask 和一条追加修订链，以主视图作为正式时间基准，保留 Review、事件级 Cleaning、权限、审计、来源追踪和按组防泄漏规则。V1.25 实现已冻结，自动化回归与本地 synthetic service E2E 已通过；真实 UAT、生产和医院部署证据仍待完成。V1.24-compatible 历史单视频路径继续保留。V1.22 继续为 DRAFT / DEFERRED，不进入正式继承链。

## 前一正式版本

- [V1.24 配药视频数据管理平台需求基线（FLOW-FIRST P0 自动任务衔接）](./baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)：前一正式基线、当前 V1.24-compatible 运行时和历史行为参考；其单视频任务/标注/清洗/导出所有权仅被 V1.25 对新 CaptureGroup 工作流替代。

- [V1.23 配药视频数据管理平台需求基线（FLOW-FIRST P0 Physical Reduction）](./baselines/V1.23/配药视频数据管理平台需求文档_V1.23.md)：V1.24 的正式继承基线。

- [V1.21 配药视频数据管理平台需求基线（FLOW-FIRST MINIMAL EXPORT）](./baselines/V1.21/配药视频数据管理平台需求文档_V1.21.md)：V1.23 的正式继承基线。

- [V1.20](./baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md)：LEAN-P0 单视频闭环基础；已由 V1.21 的流程优先与最小导出条款替代为继承基线。
- [V1.18](./baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md)：历史继承基础。
- [V1.17](./baselines/V1.17/配药视频数据管理平台需求文档_V1.17.md)：方向校正版本；已被 V1.18 的范围与语义冻结条款替代。
- V1.19（DRAFT）：见 [drafts/V1.19](./drafts/V1.19/)，当前 DEFERRED。

## 归档与完整性

所有历史基线保存在 [`baselines/`](./baselines/)；当前与前一版本的摘要由 [requirements-manifest.json](./requirements-manifest.json) 记录。更新基线时必须重新计算摘要、更新 manifest，并同步 `AGENTS.md`、当前技术设计和当前追踪矩阵。
