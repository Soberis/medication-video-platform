# 当前需求追踪矩阵（V1.25）

> 当前需求基线：[V1.25 多机位 CaptureGroup 工作流](../requirements/baselines/V1.25/配药视频数据管理平台需求文档_V1.25.md)<br>
> 当前技术设计：[CaptureGroup 多机位接收与数据闭环最小技术设计](./capture-group-multicamera-workflow-technical-design.md)<br>
> V1.24 实现/历史参考：[V1.24 需求基线](../requirements/baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md) 与 [V1.24 手部组件设计](./video-hand-pose-mvp-technical-design.md)<br>
> 历史实现与 UAT 记录：[历史需求追踪矩阵](./requirements-traceability-history.md)<br>
> 最后更新：2026-08-27

本文件区分 CURRENT REQUIREMENTS 与 CURRENT IMPLEMENTATION STATUS。V1.25 是当前正式需求基线，批准设计但尚未完整实现的条目统一使用 `D1`；V1.24/V1.21/V1.18 行保留已有运行时与证据状态。状态含义：`D1` 为已设计未实施；`PI` 为已有部分实现但当前验收未闭合；`I` 为实现与自动测试通过；`UAT` 为经批准的验收完成。代码或接口存在不等于 `UAT`。

V1.24-compatible 运行时继续保留 V1.23 的 V1.21 / V1.18 FR 事实链，将 handedness 保持为辅助元数据，并在 Cleaning PASS 后提供独立最小 Parquet 导出。每个成功入库的视频自动提交一个幂等 MediaPipe Candidate 作业；人工质量结论新建或变更为 `QUALIFIED` 时，在同一事务创建且分配唯一 `AnnotationTask`，但不替代人工 revision、Review、Cleaning 或管理员导出确认。CaptureSync / V1.19 多机位同步当前不属于已实现 V1.24-compatible runtime；其 Core API、外围同步 artifact worker、消息/Frame schema 和 G0 tooling 已在独立零生产者批次退役，CaptureSession、CameraSet、CameraStream 和 DeclaredSyncStatus 等接入事实仍保留。V1.22 多视角方向继续为 DRAFT / DEFERRED；未来能力不得回写当前运行时。DatasetVersion/复杂 Export、LeRobot 和 AI Evaluation 平台化均为 DEFERRED；相关旧代码和公开 API 已从当前 active surface 退役，历史 Flyway 文件和版本化文档只作为演进记录，不代表当前产品入口。V18-FR-006 的离线手部评估证据工具已实现并通过合成校验，但尚无授权样本实测，因此保持 `PI`，不代表效果已验收。

| 需求 | 优先级 | Owner module | 契约/读模型 | 最小验证 | 实现/证据状态 |
| --- | --- | --- | --- | --- | --- |
| V18-FR-001 边端接收 | P0 | `assetcapture` | `external-video-ingest-v1`、multipart、接收回执 | 显式 group、SHA、重复上传、损坏媒体；平台不控制边端 | I |
| V18-FR-002 视频与数据管理 | P0 | `assetcapture` | `VideoSummary`、`DataManagementSummary`、稳定 cursor | 1,000 条合成数据分页、筛选、统计跳转一致 | I |
| V18-FR-003 单视频手部预标注 | P0 | `aicandidate`、`integration` | 单视频 job、candidate、run summary、Overlay | 含手/无手、幂等、租约恢复、候选隔离；handedness 不作 Gate | I |
| V18-FR-004 配药动作标注与复核 | P0 | `annotationreview` | schema、任务、revision、review | 候选辅助；退回产生新 revision；历史不覆盖 | I |
| V18-FR-005 数据清洗与可用数据 | P0 | `qualitycleaning` | rule run/result、可用数据投影 | 媒体/元数据/标注完整性问题可追溯 | I |
| V18-FR-006 算法效果评估 | P0 | `aicandidate`、`governance` | profile、summary、离线评估证据 | 授权样本指标与参数调整可追溯；不新增评估平台 | PI |
| V18-FR-007 显式批量预标注 | P1 | `aicandidate` | batch request/result | 容量评审、逐项语义和幂等边界冻结后才启用 | D1 |
| V18-FR-008 自动预标注策略 | P1/P2 | `aicandidate`、`governance` | policy snapshot | 相机/模型/资源审批后才启用 | D1 |
| V18-FR-009 测试数据彻底清理 | P1 | `assetcapture`、`governance` | purge request/audit | 仅 `SYNTHETIC_TEST` 经确认删除 | D1 |
| V21-FR-001 最小训练数据导出 | P0 | `tools/minimal-export`（只读投影） | `training-data.v2` Parquet | 当前 Cleaning PASS + 人工标签/动作区间 + Candidate 21 点；非 PASS 拒绝；可回读；默认无 MP4 | I |
| V24-FR-001 质量合格自动任务衔接 | P0 | `qualitycleaning`、`annotationreview` | `QualityReviewQualifiedEvent`、`AnnotationTask`、AuditEvent | 合格事务内唯一自动分配、同结论幂等、非合格不创建、人工 revision/Review/Cleaning 不可绕过 | I |
| V25-FR-001 CaptureGroup 身份与收件闭环 | P0 | `assetcapture` | `CaptureSession` / `CameraSetSnapshot` / 组 receipt 读模型 | 显式归组、动态 N、N=1 特例、缺路不重编号；`RECEIVING` / `COMPLETE` / `INCOMPLETE` 与版本化完成信号 | I |
| V25-FR-002 逐视图媒体与候选处理 | P0 | `assetcapture`、`aicandidate` | per-view Media Probe / MediaPipe Candidate / `QualityReview` | Media Probe 失败只停止该视图；成功视图独立产生 Candidate；Candidate 不成为 Ground Truth；视图质量三态 | I |
| V25-FR-003 组质量人工准入 | P0 | `qualitycleaning` | `CaptureGroupQualityReview` | 持久四态、收件关闭前不得最终 `QUALIFIED`/`QUALIFIED_WITH_EXCLUSIONS`、零合格视图不得放行；无自动规则引擎 | I |
| V25-FR-004 单组任务与修订链 | P0 | `annotationreview`、`qualitycleaning` | 组 `AnnotationTask` / append-only `AnnotationRevision` / AuditEvent | 每组恰好一个任务；有效组质量提交者写入 `assignee_actor_id` 与 `created_by`；幂等不重建/不改派；View Set/input version 可追溯 | I |
| V25-FR-005 主视图时间基准与映射安全 | P0 | `annotationreview` | primary/anchor view、同步证据和映射 provenance | 主视图正式时间轴；仅已验证 `RELIABLE` 映射可推导支持区间；`APPROXIMATE`/`UNRELIABLE` 不伪造对齐时间戳 | I |
| V25-FR-006 人工 Review 与事件级 Cleaning | P0 | `annotationreview`、`qualitycleaning` | 组 Review / Cleaning 读写模型 | Review、Cleaning 均为人工闸门；拒绝/缺失视图保留并不参与下游；Cleaning 以组事实和正式 revision 为准 | I |
| V25-FR-007 下游追溯、分割与版本化导出方向 | P0 | `qualitycleaning`、`datasetexport`、`governance` | group/view provenance、`training-data.v3` | `REJECTED` 不等于删除；同组视图不跨 train/validation/test；导出保留组/视图关系，未实现不标 UAT | I |

实现、OpenAPI、事件、数据库或测试的每次变更必须更新本表相应行；被 V1.21 替代或明确 DEFERRED 的范围、历史证据和旧版本结论只记录在历史矩阵或对应草案，不能反向定义当前范围。

2026-08-14 集成证据：视频播放器共同容器全屏包含 `<video>` 与 Skeleton Overlay，播放器限制在笔记本工作台上限；Web 全量 128 tests 和 build 已单独通过。异常 handedness 降级为 `UNKNOWN` 并保留结构有效 Candidate；AI Worker 44 tests、Candidate→人工 AnnotationRevision→Review→Cleaning→可用性 51 个 Java 契约测试通过。`tools/minimal-export` 3 tests 通过，并用本地 synthetic 数据实际导出 1 个 eligible 视频、2,100 条 Candidate 中落入人工区间的 9 行；PyArrow 回读成功，未标注的 2,091 行被排除，默认未输出 MP4。全仓 Strict Gate 由本次最终集成结果单独记录，不以定向测试替代。

2026-08-11 自动验收证据：`quality-gate.ps1 -Strict` 全部通过；真实本地合成系统验证了显式多相机缺路映射、1,000 条元数据分页/筛选/汇总、候选—Revision—Review—Cleaning 链路、原视频 SHA-256 不变和项目隔离。`VideoSummaryContractTests.acceptsItsServerCursorOnTheNextPage` 覆盖服务端 cursor 与时间筛选，`VideoManagementView.spec.tsx` 覆盖汇总失败时列表仍可用。V18-FR-003 的授权含手 MP4 尚未提供，因此保留 `PI`；本矩阵没有据此标记 `UAT`。

2026-08-12 收口证据：AI Worker 的 `test_landmarker.py` 定向测试 `25 passed`，覆盖 valid hand、valid no-hand、invalid frame、混合有效/无效手、超手数、非法 landmark/handedness，以及三态互斥统计；全仓 `quality-gate.ps1 -Strict` 实际通过（Java 325 tests、Web 121 tests、Python gates 全部通过，跳过 0）。真实本地跨进程合成无手视频 `52b3c43b-8b5e-492c-bf8d-92a98cbea0b3` 经 Core API、RabbitMQ、AI Worker 和 MediaPipe 得到 `SUCCEEDED / NO_HAND_DETECTED`，`total_video_frames=50`、`inference_frame_count=17`、`frames_with_hand=0`、`frames_without_hand=17`、候选数为 0，原视频 SHA-256 前后一致。受控真实 Retry job `9a057122-c93b-49e7-ba74-00e7891417ea` 验证 Attempt 1 `FAILED + retryable=true`、同一 job 的 Attempt 2 成功，重复相同幂等键仍为 2 次 Attempt。授权含手 MP4 仍未提供，因此 V18-FR-003 保持 `PI`，V18-FR-006 保持 `D1`；本轮没有据此标记 `UAT`。

2026-08-26 V18-FR-006 审计证据：仓库及可用本地输入中仅发现 `tools/hand-pose-evaluation/tests/fixtures/synthetic_evidence.json` 合成 fixture，未发现授权真实评估样本或可用于效果测量的授权记录。离线证据工具的 schema、normalizer、report 回归测试 `29 passed`，并通过 lock、Ruff、mypy；AI Worker `45 passed`、Media Worker `23 passed`，Core API 中模型/profile 完整性、候选隔离、租约恢复和结果契约定向测试 `37 passed`，媒体原文件保护与最小导出契约 `28 passed`。本次修复了缺失 sample version/SHA 字段被静默放行、不变量说明长度与 schema 不一致、以及 sample version 长度约束未在 schema 声明三个契约缺口；未改变运行时、指标定义或业务流程。由于尚无授权样本实测，七项指标和三项不变量没有新的真实评估记录，V18-FR-006 继续保持 `PI`，不标记为 `I` 或 `UAT`。

2026-08-27 Task C 自动化证据：`CaptureGroupQualityAnnotationContractTests` `10 passed` 覆盖组 Review 上下文、Review rejection 闸门、`QUALIFIED_WITH_EXCLUSIONS`、N=1/N=5、主视图区间、可靠映射和 v3 preview/download；`CaptureGroupCleaningServiceTests` `8 passed` 覆盖拒绝视图保留/排除及 `APPROXIMATE`/`UNRELIABLE` 不伪造支持区间；`TrainingDataV3ExportServiceTests` `3 passed` 覆盖 v3 readback 与 CaptureGroup split 防泄漏；既有 `MinimalTrainingDataExportServiceTests` `5 passed`、`TrainingDataExportControllerTests` `3 passed`、`ModularityTests` `2 passed`，并通过 `pnpm run contract:lint`。本轮仅将 V25-FR-005/006/007 标为 `I`；没有 UAT 证据，也未标记 `UAT`。

2026-08-27 Task E 最终集成证据：从 V1.25 基线提交 `37681a700636162bcfefb5cf868aa33a66ebe076` 创建最终集成分支，并按 A `cc7049c6b379475d18a1889ecce43073b1b0a1b9`、B `43467d2473e32162670b96ea3f8c8cdc0ed933ac`、C `4fba94795fb60f58318804d8da79e5f2833f88fd` 顺序 cherry-pick，分别得到 `a2ef7f0`、`6ce7d1b`、`59cf788`，无冲突。最终检查修复了 CaptureGroup 列表误用旧 media count 代替 V1.25 实际 receipt、前端在权限受限或任务列表失败时把未知任务状态显示为无任务、以及 `.env.example` 中过期的 Media Worker approved digest；保留 V1.24 单视频计数语义。未新增迁移，现有 Flyway V67-V70 与其余 70 个迁移通过验证；OpenAPI、JSON Schema/profile/digest/no-latest、Bruno 和 Docker Compose 检查通过，未改变事件 Schema。相关 Java 定向回归 `37 passed`（`17 + 12 + 8`），完整 Core API `verify` 为 `301 passed`、失败/错误/跳过均为 `0`；Web `26` 个测试文件、`183 passed`，format/lint/type-check/build 通过；Python 五个组件分别为 `45`、`23`、`10`、`29`、`19 passed`，合计 `126 passed`，合成上传 `7 passed`。上述均为自动化或合成证据；没有批准的真实 UAT、真实患者/授权样本、医院/硬件或生产服务验证，因此本矩阵不标记 `UAT`，V18-FR-006 继续保留既有外部证据缺口。
