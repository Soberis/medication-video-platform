# 当前需求追踪矩阵

> 当前需求基线：[V1.20 LEAN-P0](../requirements/baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md)<br>
> 当前技术设计：[手部预标注优先技术设计](./video-hand-pose-mvp-technical-design.md)<br>
> 历史实现与 UAT 记录：[历史需求追踪矩阵](./requirements-traceability-history.md)<br>
> 最后更新：2026-08-13

本文件只追踪当前开发主线。状态含义：`D1` 为已设计未实施；`PI` 为已有部分实现但当前验收未闭合；`I` 为实现与自动测试通过；`UAT` 为经批准的验收完成。代码或接口存在不等于 `UAT`。

V1.20 继承下列 V1.18 FR 标识作为既有事实链，并将产品面收敛为单视频闭环。V1.19 同步、DatasetVersion/Export 和 AI Evaluation 平台化均为 DEFERRED；已有后端、契约、迁移和测试保留，不代表当前产品入口。

| 需求 | 优先级 | Owner module | 契约/读模型 | 最小验证 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| V18-FR-001 边端接收 | P0 | `assetcapture` | `external-video-ingest-v1`、multipart、接收回执 | 显式 group、SHA、重复上传、损坏媒体；平台不控制边端 | I |
| V18-FR-002 视频与数据管理 | P0 | `assetcapture` | `VideoSummary`、`DataManagementSummary`、稳定 cursor | 1,000 条合成数据分页、筛选、统计跳转一致 | I |
| V18-FR-003 单视频手部预标注 | P0 | `aicandidate`、`integration` | 单视频 job、candidate、run summary、Overlay | 含手/无手、幂等、租约恢复、候选隔离 | PI |
| V18-FR-004 配药动作标注与复核 | P0 | `annotationreview` | schema、任务、revision、review | 候选辅助；退回产生新 revision；历史不覆盖 | I |
| V18-FR-005 数据清洗与可用数据 | P0 | `qualitycleaning` | rule run/result、可用数据投影 | 媒体/元数据/标注完整性问题可追溯 | I |
| V18-FR-006 算法效果评估 | P0 | `aicandidate`、`governance` | profile、summary、朴素评估证据 | 授权样本指标与参数调整可追溯；不新增评估平台 | D1 |
| V18-FR-007 显式批量预标注 | P1 | `aicandidate` | batch request/result | 容量评审、逐项语义和幂等边界冻结后才启用 | D1 |
| V18-FR-008 自动预标注策略 | P1/P2 | `aicandidate`、`governance` | policy snapshot | 相机/模型/资源审批后才启用 | D1 |
| V18-FR-009 测试数据彻底清理 | P1 | `assetcapture`、`governance` | purge request/audit | 仅 `SYNTHETIC_TEST` 经确认删除 | D1 |

实现、OpenAPI、事件、数据库或测试的每次变更必须更新本表相应行；被 V1.20 替代或明确 DEFERRED 的范围、历史证据和旧版本结论只记录在历史矩阵或对应草案，不能反向定义当前范围。

2026-08-11 自动验收证据：`quality-gate.ps1 -Strict` 全部通过；真实本地合成系统验证了显式多相机缺路映射、1,000 条元数据分页/筛选/汇总、候选—Revision—Review—Cleaning 链路、原视频 SHA-256 不变和项目隔离。`VideoSummaryContractTests.acceptsItsServerCursorOnTheNextPage` 覆盖服务端 cursor 与时间筛选，`VideoManagementView.spec.tsx` 覆盖汇总失败时列表仍可用。V18-FR-003 的授权含手 MP4 尚未提供，因此保留 `PI`；本矩阵没有据此标记 `UAT`。

2026-08-12 收口证据：AI Worker 的 `test_landmarker.py` 定向测试 `25 passed`，覆盖 valid hand、valid no-hand、invalid frame、混合有效/无效手、超手数、非法 landmark/handedness，以及三态互斥统计；全仓 `quality-gate.ps1 -Strict` 实际通过（Java 325 tests、Web 121 tests、Python gates 全部通过，跳过 0）。真实本地跨进程合成无手视频 `52b3c43b-8b5e-492c-bf8d-92a98cbea0b3` 经 Core API、RabbitMQ、AI Worker 和 MediaPipe 得到 `SUCCEEDED / NO_HAND_DETECTED`，`total_video_frames=50`、`inference_frame_count=17`、`frames_with_hand=0`、`frames_without_hand=17`、候选数为 0，原视频 SHA-256 前后一致。受控真实 Retry job `9a057122-c93b-49e7-ba74-00e7891417ea` 验证 Attempt 1 `FAILED + retryable=true`、同一 job 的 Attempt 2 成功，重复相同幂等键仍为 2 次 Attempt。授权含手 MP4 仍未提供，因此 V18-FR-003 保持 `PI`，V18-FR-006 保持 `D1`；本轮没有据此标记 `UAT`。
