# 媒体就绪后的自动动作分析

依据：V1.35 §6.3 与 ADR 0004。机器预标注不需要负责人或人工任务；最终标签仍需显式人工提交、既有复核与导出准入。

## 当前实现

- `AutomaticActionSchedulingService` 默认开启，每 10 秒扫描最多 100 条媒体就绪视频，分页循环。扫描所有项目，逐条事务内检查项目 ACTIVE；归档项目不新建作业。
- `AssetCatalogQueryService` 提供所属项目、视频 ID 的只读投影，仅选择未删除、INGESTED、有 CameraStream 绑定的视频。不依赖人工质量或 MediaPipe。
- `ActionCandidateJobService.queueAutomatically` 在项目锁内去重，事务内写入 job、attempt、receipt 与 outbox。事务失败后下轮重试，某条失败不阻塞其他视频；重启后从头扫描，因此同时覆盖已有的未派发媒体。
- 每个视角单独分析；已有任务使用其冻结 schema，无任务则快照已发布全局 PRIMARY_STEP 标签。机器作业不创建 AnnotationTask、Revision、Review 或质量结论。
- 已有同源动作 job 不因扫描、创建任务或发布新标签再次创建；终态失败不会每轮自动花费新 attempt。既有 attempt 重试与授权重试 API 保留。
- 页面自动查询候选；无任务时读取最小 camera index，有人工主视图时读取该视图。未编辑草稿可初始化；读取故障与作业失败分开提示，不覆盖人工编辑。

## 运维

默认无需设置任何项目或负责人。全局暂停：`platform.ai.action.auto.enabled=false`；扫描间隔：`platform.ai.action.auto.poll-delay=PT10S`。运行现有 outbox relay、RabbitMQ 和 Action Worker 才能完成推理。

启用新版会为现有 ACTIVE 项目的尚未分析就绪视频排队，应预估积压与 Worker 容量；暂停调度不取消已经入队的作业。无需新增 migration 或消息字段，沿用 `ai-action-job.v1` 与既有 outbox、租约和结果契约。

## 验证边界

合成测试覆盖无人工任务派发、跨项目扫描、重复派发、归档阻断、标签冻结与候选不创建人工事实。真实 Worker 的接收、推理、回传及模型质量仍需在获授权的合成联调环境验收；本地测试不能替代它。
