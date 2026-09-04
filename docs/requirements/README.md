# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前正式基线

- [V1.28 配药视频数据管理平台需求基线（单一 CaptureGroup 路径与唯一手部检测导出）](./baselines/V1.28/配药视频数据管理平台需求文档_V1.28.md)

V1.28 是当前正式业务需求基线：项目只保留一个面向用户的“配药事件”入口，以配药事件（`CaptureGroup`）为工作对象；Local Upload 和 External Ingest 都进入同一事件路径，`N=1` 只是一路 `CameraStream` 视图。事件工作台统一承载媒体事实、逐视图质量、事件质量、唯一组任务、Revision、Review 和确定性 Cleaning；唯一公开导出是按当前 CameraStream/ClipAsset 和 MediaPipe Candidate 生成的 `hand-landmarks.v1` detection evidence CSV，支持项目范围或单一 CaptureGroup 范围。旧事件资格 manifest 出口已从当前运行时、公开契约、Web 和合成工具删除；历史代码、迁移和文档只保留为审计参考。真实 UAT、生产和医院部署证据仍待完成。

## 前一正式版本

- [V1.27 配药视频数据管理平台需求基线（单一 CaptureGroup 路径）](./baselines/V1.27/配药视频数据管理平台需求文档_V1.27.md)：前一正式基线；V1.28 仅继承其中仍适用的领域事实，不继承其旧事件 manifest 兼容性承诺。

- [V1.26 配药视频数据管理平台需求基线（统一视频管理、极简工作台与可证自动化）](./baselines/V1.26/配药视频数据管理平台需求文档_V1.26.md)：历史前序基线；V1.27 仅继承其中仍适用的领域事实，不继承其单视频兼容性承诺。

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
