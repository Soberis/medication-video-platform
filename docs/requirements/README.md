# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前正式基线

- [V1.26 配药视频数据管理平台需求基线（统一视频管理、极简工作台与可证自动化）](./baselines/V1.26/配药视频数据管理平台需求文档_V1.26.md)

V1.26 是当前正式业务需求基线：项目只保留一个面向用户的“视频管理”入口，以配药事件（`CaptureGroup`）为工作对象；事件工作台采用 current-task-first 信息层级、六阶段业务流程、唯一 `Current Action` 和服务端成功后的安全连续处理。平台本地 `N=1` 上传使用已完成批次作为显式收件完成事实。V1.25 的多机位事实模型、人工质量、组任务与修订链、人工 Review、确定性 Cleaning、权限、审计和 provenance 继续继承；外部或多机位收件仍不得按超时或数量推断完成。界面自动化只减少找路，不自动提交专业结论；真实 UAT、生产和医院部署证据仍待完成。

## 前一正式版本

- [V1.25 配药视频数据管理平台需求基线（多机位 CaptureGroup 工作流）](./baselines/V1.25/配药视频数据管理平台需求文档_V1.25.md)：前一正式基线，继续定义 V1.26 继承的 CaptureGroup 领域闭环。

- [V1.24 配药视频数据管理平台需求基线（FLOW-FIRST P0 自动任务衔接）](./baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)：V1.24-compatible 运行时和历史行为参考。

- [V1.23 配药视频数据管理平台需求基线（FLOW-FIRST P0 Physical Reduction）](./baselines/V1.23/配药视频数据管理平台需求文档_V1.23.md)：V1.24 的正式继承基线。

- [V1.21 配药视频数据管理平台需求基线（FLOW-FIRST MINIMAL EXPORT）](./baselines/V1.21/配药视频数据管理平台需求文档_V1.21.md)：V1.23 的正式继承基线。

- [V1.20](./baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md)：LEAN-P0 单视频闭环基础；已由 V1.21 的流程优先与最小导出条款替代为继承基线。
- [V1.18](./baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md)：历史继承基础。
- [V1.17](./baselines/V1.17/配药视频数据管理平台需求文档_V1.17.md)：方向校正版本；已被 V1.18 的范围与语义冻结条款替代。
- V1.19（DRAFT）：见 [drafts/V1.19](./drafts/V1.19/)，当前 DEFERRED。

## 归档与完整性

所有历史基线保存在 [`baselines/`](./baselines/)；当前与前一版本的摘要由 [requirements-manifest.json](./requirements-manifest.json) 记录。更新基线时必须重新计算摘要、更新 manifest，并同步 `AGENTS.md`、当前技术设计和当前追踪矩阵。
