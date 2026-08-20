# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前

- [V1.24 配药视频数据管理平台需求基线（FLOW-FIRST P0 自动任务衔接）](./baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)

V1.24 是当前唯一正式业务需求基线：继承 V1.23 的 LEAN-P0 主线，并在人工质量结论变为 `QUALIFIED` 时，于同一事务自动创建且分配唯一 `AnnotationTask`。唯一主线为边端接收 → 视频管理 → 自动单视频 MediaPipe Candidate → 人工质量判断 → 自动任务分配 → 人工 AnnotationRevision → Review → Cleaning → 管理员确认的 `training_data.v2` / `training_data.parquet` 导出。V1.23 是上一正式基线；V1.22 继续为 DRAFT / DEFERRED，不进入正式继承链。

## 前一正式版本

- [V1.23 配药视频数据管理平台需求基线（FLOW-FIRST P0 Physical Reduction）](./baselines/V1.23/配药视频数据管理平台需求文档_V1.23.md)：V1.24 的正式继承基线。

- [V1.21 配药视频数据管理平台需求基线（FLOW-FIRST MINIMAL EXPORT）](./baselines/V1.21/配药视频数据管理平台需求文档_V1.21.md)：V1.23 的正式继承基线。

- [V1.20](./baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md)：LEAN-P0 单视频闭环基础；已由 V1.21 的流程优先与最小导出条款替代为继承基线。
- [V1.18](./baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md)：历史继承基础。
- [V1.17](./baselines/V1.17/配药视频数据管理平台需求文档_V1.17.md)：方向校正版本；已被 V1.18 的范围与语义冻结条款替代。
- V1.19（DRAFT）：见 [drafts/V1.19](./drafts/V1.19/)，当前 DEFERRED。

## 归档与完整性

所有历史基线保存在 [`baselines/`](./baselines/)；当前与前一版本的摘要由 [requirements-manifest.json](./requirements-manifest.json) 记录。更新基线时必须重新计算摘要、更新 manifest，并同步 `AGENTS.md`、当前技术设计和当前追踪矩阵。
