# 当前需求追踪矩阵

> 当前需求基线：[V1.18](../requirements/baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md)<br>
> 当前技术设计：[手部预标注优先技术设计](./video-hand-pose-mvp-technical-design.md)<br>
> 历史实现与 UAT 记录：[历史需求追踪矩阵](./requirements-traceability-history.md)<br>
> 最后更新：2026-08-10

本文件只追踪当前开发主线。状态含义：`D1` 为已设计未实施；`PI` 为已有部分实现但当前验收未闭合；`I` 为实现与自动测试通过；`UAT` 为经批准的验收完成。代码或接口存在不等于 `UAT`。

| 需求 | 优先级 | Owner module | 契约/读模型 | 最小验证 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| V18-FR-001 边端接收 | P0 | `assetcapture` | `external-video-ingest-v1`、multipart、接收回执 | 显式 group、SHA、重复上传、损坏媒体；平台不控制边端 | PI |
| V18-FR-002 视频与数据管理 | P0 | `assetcapture` | `VideoSummary`、`DataManagementSummary`、稳定 cursor | 1,000 条合成数据分页、筛选、统计跳转一致 | PI |
| V18-FR-003 单视频手部预标注 | P0 | `aicandidate`、`integration` | 单视频 job、candidate、run summary、Overlay | 含手/无手、幂等、租约恢复、候选隔离 | PI |
| V18-FR-004 配药动作标注与复核 | P0 | `annotationreview` | schema、任务、revision、review | 候选辅助；退回产生新 revision；历史不覆盖 | PI |
| V18-FR-005 数据清洗与可用数据 | P0 | `qualitycleaning` | rule run/result、可用数据投影 | 媒体/元数据/标注完整性问题可追溯 | PI |
| V18-FR-006 算法效果评估 | P0 | `aicandidate`、`governance` | profile、summary、评估记录 | 授权样本指标、参数调整与审批可追溯 | D1 |
| V18-FR-007 显式批量预标注 | P1 | `aicandidate` | batch request/result | 容量评审、逐项语义和幂等边界冻结后才启用 | D1 |
| V18-FR-008 自动预标注策略 | P1/P2 | `aicandidate`、`governance` | policy snapshot | 相机/模型/资源审批后才启用 | D1 |
| V18-FR-009 测试数据彻底清理 | P1 | `assetcapture`、`governance` | purge request/audit | 仅 `SYNTHETIC_TEST` 经确认删除 | D1 |

实现、OpenAPI、事件、数据库或测试的每次变更必须更新本表相应行；被 V1.18 替代的范围、历史证据和旧版本结论只记录在历史矩阵，不能反向定义当前范围。
