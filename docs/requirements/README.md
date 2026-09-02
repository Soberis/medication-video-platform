# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前正式基线

- [V1.27 配药视频数据管理平台需求基线（单一 CaptureGroup 产品路径、training-data.v3 与 hand-landmarks.v1）](./baselines/V1.27/配药视频数据管理平台需求文档_V1.27.md)

V1.27 是当前正式业务需求基线：项目只保留一个面向用户的“视频管理”入口，以配药事件（`CaptureGroup`）为工作对象；Local Upload 和 External Ingest 都进入同一事件路径，`N=1` 只是一路 `CameraStream` 视图。事件工作台统一承载媒体事实、逐视图质量、事件质量、唯一组任务、Revision、Review 和确定性 Cleaning；`training-data.v3` 是 CaptureGroup eligibility / provenance event manifest，实际 ML feature export 定义为 Primary View-only 的 `hand-landmarks.v1`。当前实现只有前者，尚无实际 training consumer 读取后者的证据。`ClipAsset` 仍保留为物理视频证据对象，但不再是独立业务工作流对象。V1.24/V1.26 单视频路径、单视频任务/清洗和 `training-data.v2` 已从当前产品范围退役；历史代码、数据和迁移不因本索引删除。真实 UAT、生产和医院部署证据仍待完成。

## 前一正式版本

- [V1.26 配药视频数据管理平台需求基线（统一视频管理、极简工作台与可证自动化）](./baselines/V1.26/配药视频数据管理平台需求文档_V1.26.md)：前一正式基线；V1.27 仅继承其中仍适用的领域事实，不继承其单视频兼容性承诺。

- [V1.25 配药视频数据管理平台需求基线（多机位 CaptureGroup 工作流）](./baselines/V1.25/配药视频数据管理平台需求文档_V1.25.md)：历史前序基线，保留 V1.27 继承的 CaptureGroup 领域参考。

- [V1.24 配药视频数据管理平台需求基线（FLOW-FIRST P0 自动任务衔接）](./baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)：V1.24-compatible 运行时和历史行为参考。

- [V1.23 配药视频数据管理平台需求基线（FLOW-FIRST P0 Physical Reduction）](./baselines/V1.23/配药视频数据管理平台需求文档_V1.23.md)：V1.24 的正式继承基线。

- [V1.21 配药视频数据管理平台需求基线（FLOW-FIRST MINIMAL EXPORT）](./baselines/V1.21/配药视频数据管理平台需求文档_V1.21.md)：V1.23 的正式继承基线。

- [V1.20](./baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md)：LEAN-P0 单视频闭环基础；已由 V1.21 的流程优先与最小导出条款替代为继承基线。
- [V1.18](./baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md)：历史继承基础。
- [V1.17](./baselines/V1.17/配药视频数据管理平台需求文档_V1.17.md)：方向校正版本；已被 V1.18 的范围与语义冻结条款替代。
- V1.19（DRAFT）：见 [drafts/V1.19](./drafts/V1.19/)，当前 DEFERRED。

## 归档与完整性

所有历史基线保存在 [`baselines/`](./baselines/)；当前与前一版本的摘要由 [requirements-manifest.json](./requirements-manifest.json) 记录。更新基线时必须重新计算摘要、更新 manifest，并同步 `AGENTS.md`、当前技术设计和当前追踪矩阵。
