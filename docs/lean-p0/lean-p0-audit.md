# LEAN-P0 只读减法审计（Audit）

> 版本：LEAN-P0-AUDIT.1
> 日期：2026-08-13
> 性质：**只读审计**，不落任何代码改动；产出分类清单供评审，评审通过后再单独执行禁用/删除。
> 对齐：[AGENTS.md](../../AGENTS.md) Sources of Truth、MVP Simplicity、Delivery Order

> 执行说明：本文的 Git 状态是审计发生时的历史快照，不代表当前工作区。已批准结论以 [V1.20 LEAN-P0 正式基线](../requirements/baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md) 为准。

## 0. Audit Snapshot（可复现快照）

```text
HEAD:
80c7832edb8c92f4da3ee5ea4c128cbb96fca83c

Working tree:
DIRTY

git diff SHA256 (tracked files only):
151f26bdac0c8bf74ec9e9bb642f8cdc883af51f0504117dac442bb953ab9ce4

Changed files (git status --short):
```

| 状态 | 文件 |
| --- | --- |
| M | `contracts/openapi/openapi.yaml` |
| M | `services/platform-api/pom.xml` |
| M | `assetcapture/CaptureSyncExecutionService.java` |
| M | `assetcapture/CaptureSyncInputAssemblyService.java` |
| M | `assetcapture/CaptureSyncJobService.java` |
| M | `assetcapture/CaptureSyncProvenanceQueryService.java` |
| M | `assetcapture/CaptureSyncQueryService.java` |
| M | `assetcapture/CaptureSyncResponses.java` |
| M | `assetcapture/CaptureSyncRevision.java` |
| M | `assetcapture/CaptureSyncStoragePort.java` |
| M | `assetcapture/CaptureSyncWorkerSecurityConfiguration.java` |
| M | `assetcapture/LocalCaptureSyncStorage.java` |
| M | `assetcapture/SynchronizedFrameQueryPort.java` |
| M | `test/.../CaptureSyncArtifactPersistenceContractTests.java` |
| M | `test/.../CaptureSyncExecutionContractTests.java` |
| M | `test/.../CaptureSyncJobModelTests.java` |
| M | `test/.../CaptureSyncPersistenceContractTests.java` |
| M | `test/.../CaptureSyncQueryContractTests.java` |
| M | `test/.../support/CaptureSyncTestSupport.java` |
| D | `assetcapture/ManifestSynchronizedFrameQueryPort.java` |
| ?? | `assetcapture/CaptureSyncWorkerAuthenticationFilter.java` |
| ?? | `assetcapture/ParquetSynchronizedFrameQueryPort.java` |
| ?? | `test/.../CaptureSyncManifestDigestTests.java` |
| ?? | `test/.../LocalCaptureSyncStorageTests.java` |
| ?? | `docs/lean-p0/` |

> **快照事实**：全部未提交改动都落在 V1.19 同步链（S4 provenance / S5 Parquet 查询收尾）。
> 本审计只记录该状态，不提交、不推送、不改动这些文件。

## 1. 判定矩阵

### 1.1 Runtime

| 值 | 定义 |
| --- | --- |
| `REQUIRED` | P0 闭环或其可靠运行所必需 |
| `FROZEN` | 代码/兼容接口保留，但**退出当前 P0 支持路径**，不继续开发 |
| `DEAD` | 确认零引用且不承担兼容/迁移责任，可作为删除候选 |

> `FROZEN` 语义澄清：它表示"退出 P0 支持路径、不再开发、不再给产品入口"，**不等于** endpoint/Bean 已在运行时禁用。档位①（入口隐藏）下，Bean 仍在、契约仍在、表仍在，只是 Worker 不默认启动、前端不渲染入口。

### 1.2 Surface

| 值 | 定义 |
| --- | --- |
| `USER` | 正常产品用户可见、可操作 |
| `INTERNAL` | Worker、服务间、管理员运维接口 |
| `NONE` | 纯内部领域事实，无产品入口 |

### 1.3 P0 Dependency

| 值 | 定义 |
| --- | --- |
| `DIRECT` | P0 主链直接依赖 |
| `TRANSITIVE` | 支持 P0 正确运行，但用户主链不直接操作 |
| `NONE` | P0 不依赖 |

### 1.4 Residual Surface（FROZEN 使用）

| 值 | 定义 |
| --- | --- |
| `CALLABLE` | API/Bean 仍存在，可直接调用 |
| `NOT_STARTED` | 代码存在，但对应 Worker/进程不默认启动 |
| `NONE` | 产品入口已消失 |

### 1.5 三条硬规则

1. 标 `DEAD` 必须给出零引用证据，否则一律降级 `FROZEN`。
2. 每个 `FROZEN` 项默认落档位①（入口隐藏 / 不默认启动），不动 Spring 扫描、不动 Flyway。
3. 本审计只读；`D` 层本轮不列未经零引用证明的 `DEAD` 项。

## 2. 四层分类总览

| 层 | 定义 | 数量级 |
| --- | --- | --- |
| A 活跃开发 | P0 闭环必需 | ~22 Controller、~9 View |
| B 隐藏运行 | 保证系统正确，非产品 | CaptureSession/Camera 链、Audit、Job 运维 |
| C 冻结停用 | 退出 P0 支持路径，不再修/增强/给入口 | CaptureSync、DatasetVersion/Export、AI Eval、多相机播放 |
| D 删除候选 | 零引用或被取代的产品面 | 本轮无 DEAD 结论；仅记录 LEAN-P0 CUT 项（见 §6） |

## 3. A 层 — P0 活跃开发（REQUIRED）

### 3.1 后端 Controller（22）

| 模块 | Controller | Surface | P0 Dependency | 说明 |
| --- | --- | --- | --- | --- |
| iamproject | PlatformAuthenticationController | USER | DIRECT | 登录/会话/登出 |
| iamproject | PlatformAccountManagementController | INTERNAL | TRANSITIVE | 管理员账号 |
| iamproject | ProjectController | USER | DIRECT | 项目 + 成员管理 |
| assetcapture | AssetCatalogController | USER | DIRECT | `/videos` `/clip-assets` `/video-data/summary`（核心）；`/capture-sessions` `/capture-event-logs` 属 B 层路径 |
| assetcapture | ClipAssetPlaybackController | USER | DIRECT | 播放授权 |
| assetcapture | ClipAssetDeletionController | USER | DIRECT | 删除（保留人工评审约束） |
| assetcapture | UploadBatchController | INTERNAL | TRANSITIVE | 边端上传 |
| assetcapture | UploadFileController | INTERNAL | TRANSITIVE | 边端上传 |
| assetcapture | UploadMultipartController | INTERNAL | TRANSITIVE | 边端分片上传 |
| assetcapture | ExternalVideoIngestManifestController | INTERNAL | TRANSITIVE | 边端 manifest 接入 |
| assetcapture | CameraConfigurationController | NONE | TRANSITIVE | 相机配置（接入侧事实） |
| mediaprivacy | MediaJobController | INTERNAL | TRANSITIVE | 媒体探测触发 |
| mediaprivacy | MediaReportController | INTERNAL | TRANSITIVE | 媒体报告 |
| mediaprivacy | MediaWorkerController | INTERNAL | TRANSITIVE | worker 提交结果 |
| mediaprivacy | ClipAssetMediaReportController | USER | DIRECT | 视频详情显示媒体报告 |
| mediaprivacy | MediaJobDeliveryRecoveryController | INTERNAL | TRANSITIVE | 幂等/恢复运维 |
| mediaprivacy | MediaJobReplayController | INTERNAL | TRANSITIVE | 重试运维 |
| aicandidate | HandPoseJobController | USER | DIRECT | 手部检测触发/查询/重试 |
| aicandidate | AiWorkerController | INTERNAL | TRANSITIVE | AI worker 提交（**不可砍**） |
| annotationreview | AnnotationTaskController | USER | DIRECT | 标注 + 复核 + 清洗后复查 |
| annotationreview | AnnotationLabelSchemaController | USER | DIRECT | 标签 schema |
| qualitycleaning | QualityReviewController | USER | DIRECT | 清洗 PASS/FAIL |

### 3.2 前端 View / 组件（REQUIRED/USER）

| 文件 | 说明 |
| --- | --- |
| `views/LoginView.tsx` | 登录 |
| `views/ProjectListView.tsx` | 项目列表 |
| `views/VideoManagementView.tsx` | 数据管理主页（视频列表/筛选） |
| `views/AssetDetailView.tsx` | 视频详情（融合检测；folder 赋值 UI 属 LEAN-P0 CUT，见 §6） |
| `views/AnnotationTaskListView.tsx` | 数据标注（含复核/清洗后复查动作） |
| `components/HandPoseOverlay.tsx` | 21 点骨架 overlay（核心） |
| `components/AssetActivityCard.tsx` | 视频活动/检测状态 |
| `components/AssetDetailSections.tsx` | 视频详情分区（folder 部分移除） |
| `components/PromptDialog.tsx` | 通用对话框 |

### 3.3 前端 API 层（REQUIRED）

| 文件 | 保留函数（节选） |
| --- | --- |
| `api/auth.ts` | 全部 |
| `api/admin.ts` | 全部 |
| `api/annotation.ts` | listAnnotationTasks / submitAnnotationRevision / reviewAnnotationRevision / recheckAnnotationPostCleaning |
| `api/quality.ts` | getQualityReview / recordQualityReview |
| `api/catalog.ts` | listClipAssets / listVideoSummaries / getDataManagementSummary / getClipAsset / getClipAssetMediaReport / deleteClipAsset / authorizeClipAssetPlayback / requestHandPoseCandidates / getLatestHandPoseCandidates / listCaptureEventLogs（B 层） |
| `api/uploads.ts` | 上传进度查询 |

### 3.4 Worker（REQUIRED）

| Worker | 说明 |
| --- | --- |
| `workers/media-worker` | ffprobe / 视频入库 / 媒体探测 |
| `workers/ai-worker` | MediaPipe Hand Landmarker |

### 3.5 后端非 Controller（REQUIRED）

| 模块 | 文件/能力 | Surface | P0 Dependency |
| --- | --- | --- | --- |
| governance | AuditEvent / AuditEventRecorder / AuditEventQueryService / AuditActorType / AuditOutcome | NONE | TRANSITIVE（审计保留，**不砍**） |
| governance | ApprovalRequest / ApprovalDecision（通用审批机制） | NONE | TRANSITIVE |
| assetcapture | CaptureSession / CameraStream / CameraChannel / CameraSetSnapshot | NONE | TRANSITIVE（接入侧事实） |
| aicandidate | AIJob / CandidateAnnotation（model SHA / profile / input digest 溯源保留） | NONE | DIRECT |

## 4. B 层 — 隐藏但运行

| 能力 | Runtime | Surface | P0 Dependency | Residual | 说明 |
| --- | --- | --- | --- | --- | --- |
| CaptureSession 链（Session/CameraStream/CameraChannel/Snapshot） | REQUIRED | NONE | TRANSITIVE | NONE | 边端接入容器，**非产品模块**，不做 Session 列表/详情/同步播放 |
| Audit / Event Log 底层 | REQUIRED | NONE | TRANSITIVE | NONE | 谁上传/触发 AI/标注/复核/清洗，可追溯 |
| Job/Attempt + RabbitMQ delivery/recovery | REQUIRED | NONE | TRANSITIVE | NONE | 异步幂等运维 |
| 采集事件日志 UI（`CaptureEventLogListView`） | REQUIRED | INTERNAL | TRANSITIVE | CALLABLE | 页面仅管理员隐藏入口，底层保留 |

## 5. C 层 — 冻结停用（FROZEN，档位①）

### 5.1 后端 Controller / 模块

| 模块 | Controller / 文件 | Surface | P0 Dependency | Residual |
| --- | --- | --- | --- | --- |
| assetcapture | CaptureSyncController | NONE | NONE | CALLABLE |
| assetcapture | CaptureSyncWorkerController | NONE | NONE | CALLABLE |
| assetcapture | CaptureSync*（Job/Attempt/Revision/StreamRevision/Artifact + Engine/Service/Query + Parquet 读取） | NONE | NONE | CALLABLE |
| assetcapture | AssetFolderController / Service / Repository | NONE | NONE | CALLABLE（前端产品面移除后冻结） |
| aicandidate | AiEvaluationController / Service | NONE | NONE | CALLABLE |
| datasetexport | DatasetVersionController | NONE | NONE | CALLABLE |
| datasetexport | DatasetAdmissionPreviewController | NONE | NONE | CALLABLE |
| datasetexport | DatasetExportWorkerController | NONE | NONE | CALLABLE |
| datasetexport | DatasetExportManualReplayController | NONE | NONE | CALLABLE |
| datasetexport | 其余 60+ 文件（Build/Split/Export/Profile/Attempt 等） | NONE | NONE | CALLABLE |

> 冻结语义：Bean/契约/表保留，Worker 不默认启动，前端不渲染入口；不关闭 Spring Bean、不修改历史 Flyway、不 DROP 表。

### 5.2 前端 View / API（FROZEN）

| 文件 | Residual |
| --- | --- |
| `views/SessionListView.tsx` / `views/SessionDetailView.tsx` | 隐藏入口 |
| `components/SessionCameraPlayback.tsx` | 隐藏入口 |
| `views/DatasetVersionListView.tsx` / `views/DatasetVersionDetailView.tsx` | 隐藏入口 |
| `api/catalog.ts` 的 listCaptureSessions / getCaptureSession / listDatasetVersions / getDatasetVersion / *DatasetVersion* / *DatasetExport* / getCaptureSessionDatasetAdmissionPreview | 冻结 |

### 5.3 AI Evaluation 拆分结论

| 保留 | 冻结 |
| --- | --- |
| AIJob 上绑定 model SHA / profile / input digest / job-attempt 溯源 | AiEvaluationController / Service / DB / 页面 |
| 朴素评估：`evaluation/*.csv` / Markdown | 评估平台化 |

### 5.4 Worker / 契约 / 迁移

| 项 | 判定 | Residual |
| --- | --- | --- |
| `workers/export-worker` | FROZEN | NOT_STARTED（不默认启动） |
| `contracts/schemas/synchronized-frame-v1.schema.json` | FROZEN | CALLABLE（文件保留） |
| `contracts/events/dataset-export-*.json` | FROZEN | CALLABLE |
| `contracts/profiles/p0-dataset-export-*.json` | FROZEN | CALLABLE |
| Flyway `V62__create_capture_sync_core.sql` / `V63__create_capture_sync_artifact.sql` | FROZEN，**不 DROP、不修改**，第二阶段 forward migration | CALLABLE |
| `docs/architecture/multi-camera-synchronized-training-data-v1.19.md` | 标注 `DEFERRED`，不删 | — |
| `docs/requirements/drafts/V1.19/…` | 标注 `DEFERRED` | — |

## 6. D 层 — 删除候选

### 6.1 LEAN-P0 CUT（主动移除产品面，非 DEAD）

前端「文件夹」概念渗透进 P0 核心页，P0 主线不需要它。**这是主动移除产品面，不是零引用死代码，故不标 `DEAD`**：

| 文件 | 位置 | 处理 |
| --- | --- | --- |
| `views/AssetDetailView.tsx` | `listAssetFolders` / `assignClipAssetFolder` / folder 状态 | LEAN-P0 CUT：移除 folder 赋值 UI |
| `views/LocalUploadView.tsx` | `listAssetFolders` / `assetFolderId` | LEAN-P0 CUT：移除 folder 选择 |
| `api/projects.ts` | `AssetFolder` / `ClipAssetFolderAssignment` / `listAssetFolders` / `assignClipAssetFolder` / `removeClipAssetFolder` | LEAN-P0 CUT：移除定义与函数 |
| `api/local-upload.ts` | `assetFolderId` 字段 | LEAN-P0 CUT：移除字段 |
| `components/AssetDetailSections.tsx` | `folders: AssetFolder[]` | LEAN-P0 CUT：移除 folder 分区 |

> AssetFolder 后端（Controller/Service/Repository/表）不删，走 C 层 FROZEN（档位①），符合"先死掉不 DROP"。

### 6.2 零引用死代码（DEAD）

| 候选 | 证据状态 |
| --- | --- |
| 历史 route 残留 / 重复 DTO / 被取代的兼容 UI / 无引用 helper / 死测试 fixture | **本轮未做全量引用闭包扫描，一律不标 `DEAD`**，降级为 `FROZEN` 或 `待确认` |

## 7. 关键发现

1. **AssetFolder 残留**：`e0bd519` 只删了独立文件夹页，但「文件夹」概念仍渗透在视频详情页和本地上传页，是 P0 应清理的产品面（§6.1）。
2. **复核/清洗无独立 View**：`annotation.ts` 已含 `reviewAnnotationRevision` / `recheckAnnotationPostCleaning`，`quality.ts` 已含 `recordQualityReview`，但前端无独立 Review/Cleaning 页，动作挂在标注流程里。与"四入口"目标一致，非减法重点，标注现状即可。
3. **AssetCatalogController 是混合体**：`/videos` `/clip-assets` 是 P0 核心，`/capture-sessions` `/capture-event-logs` 是 B 层内部路径，不能整 controller 砍，需路径级处理。
4. **CaptureEventLog 共识**：UI 隐藏（管理入口收敛），底层 Audit/EventLog 保留。
5. **CaptureSession 定位已拍板**：`REQUIRED / NONE / TRANSITIVE / HIDDEN`，是接入侧事实，不是产品模块，也不进单视频 MediaPipe 核心路径（`project_id + clip_asset_id` 驱动）。

## 8. 门禁影响

**预期影响：LOW，但必须由 Strict Gate 验证。**

即使只是"隐藏 route + 删导航 + 停止 export-worker 默认启动"，也可能影响 React route tests、App navigation tests、startup script tests、compose assertions、E2E。

**要求：** 执行后必须完整运行 `.\scripts\quality-gate.ps1 -Strict`；未经实际 Gate 通过，**不得写"零影响"**。这与"不编造测试结果"原则一致。

## 9. 建议执行顺序（收口）

```text
Step 1 — Freeze Baseline
基于本 Audit 创建新的版本化 LEAN-P0 需求基线。明确：
- V1.18 未变更的安全/权限/审计条款继续继承
- V1.19 多相机同步 = DEFERRED
- CaptureSession = REQUIRED / NONE / TRANSITIVE / HIDDEN
- 当前唯一交付路径 = 视频 → MediaPipe → 标注 → 复核 → 清洗

Step 2 — One Lean Cut
只执行一次最小产品面收敛：
- 隐藏 Session / Dataset / CaptureEventLog 产品入口
- 不默认启动 export-worker
- 移除 P0 页面中的 AssetFolder 用户操作
- 不关闭 Spring Bean
- 不修改历史 Flyway
- 不 DROP 表
- 不删除 Sync / Dataset / Evaluation backend code

Step 3 — Strict Gate
运行：.\scripts\quality-gate.ps1 -Strict
只有 Gate 通过才认定 LEAN-P0 CUT COMPLETE。

Step 4 — Stop Refactoring
减法立即结束。禁止继续：
- CaptureSync
- DatasetVersion
- Export
- AI Evaluation 平台
- 其他 P1/P2 重构

Step 5 — MediaPipe Demo
立即进入 P0 演示：
hand_landmarker.task
→ 上传合成测试视频
→ Media ingest
→ MediaPipe
→ HAND_DETECTED
→ Skeleton Overlay
→ 配药动作标注
```

> 一句话：**Audit → Baseline → 一刀减法 → Gate → 立刻拿成果。** 减法完成后暂停所有 V1.19/P1 开发，直到 P0 演示闭环真正跑出来。
