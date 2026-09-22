# ADR 0005：取消人工质量关卡，组准入事实改为系统冻结

日期：2026-09-22
状态：已实施。
依据：[V1.35](../requirements/baselines/V1.35/配药视频数据处理平台需求文档_V1.35.md)、[ADR 0004](./0004-automatic-annotation-approval-policy.md)。

## 决策

人工全程只保留两个动作：

1. **机位处置**：提交 / 排除（排除必填原因，全程审计）。
2. **标注复核**：通过 / 退回。

取消作为人工步骤的「单机位质量评审」与「整组质量准入」。二者当前在界面上没有任何评审入口，实际由前端在首次提交时静默批量写入 `QUALIFIED`（`apps/web/src/features/capture-group/CaptureGroupAnnotationPanel.tsx:574` `bootstrapGroupQualityForAnnotation()`），既没有真正由人执行，又会在机位未处置完时阻塞标注——是纯粹为流程而流程的关卡。

组准入事实（现有 `CaptureGroupQualityQueryService.CurrentDecision.isAdmitting()`）语义重定义为**「输入快照已冻结，可进入标注 / 复核 / 导出」**，由 Core API 在首次准备人工任务时以 SYSTEM 身份自动产生并冻结，不再由前端在提交时 bootstrap。机位否决的唯一人工入口是机位 `EXCLUDED`（必填原因 + 审计），不再需要人工 `QualityReview REJECTED` 作为并行语义。

## 不改动的安全底线

- Media Probe 技术有效性仍是自动事实，探针不合格的视频进入不了可标注状态。
- 输入快照（`confirmedViewSet` / `inputVersion` / `confirmedViewSetSha256`）与逐机位漂移校验 `viewSetStillCurrent()` 全部保留。
- `AnnotationReview = APPROVED` 仍是标签质量硬关卡，保留。
- 导出侧 fail-closed 保留：`APPROVED_CAPTURE_GROUP_ANNOTATION_NOT_ELIGIBLE` 继续校验准入、输入版本、机位集合与 provenance。
- 历史 `CaptureGroupQualityReview` / `QualityReview` 记录只读保留，不删表、不删字段、不删迁移。

## 为什么「只删校验」是错的

导出准入（`ApprovedCaptureGroupAnnotationQueryService:114`）与复核上下文（`CaptureGroupAnnotationWorkflow:281`）都在读准入事实，并额外要求 `captureGroupQualityReviewId() != null`。若只删除前端的静默写入与后端门禁、不接管产生源，准入记录将永远为空，**导出与复核会 100% 失败**。正确手术是「换产生源，不换校验」：事实必须继续存在，只是由系统而非前端偷偷写。

同理，机位级 `QUALIFIED` 也是复核与导出的普遍前置事实（`CaptureGroupAnnotationWorkflow:303` 只有 `present && assetAvailable && INGESTED && QUALIFIED` 的机位才进 `qualifiedViews`；`viewSetStillCurrent:486` 逐机位比对质量决定）。系统冻结时必须一并产出机位级准入事实，判定依据为 `present && assetAvailable && mediaStatus = INGESTED && 非 EXCLUDED`。

## 与 V1.35 基线的解释关系

V1.35 §10.3 与 `V35-FR-007` 要求导出时「CaptureGroup 人工质量允许进入标注」/「组质量」有效。本决策**不取消这条事实**，只改变它的产生者：从人工评审步骤改为系统冻结。基线中「人工质量」的措辞按 §10.3 该条与 FR-007 的最小范围修订，并重算 `requirements-manifest.json` 摘要。

## 为什么不保留「系统自动写质量记录」

把前端那次静默写入搬到后端（系统身份补写机位级 + 组级 `QUALIFIED`）虽然不是不能做，但它只是把「为流程而流程」藏进服务端：机位「质量合格」本来就只是 `present && assetAvailable && INGESTED` 的副本，属于派生事实，不是独立事实。保留它意味着系统必须持续伪造 N 条记录去喂下游，正是本次要删掉的东西。

因此准入判断直接读客观事实 + 人工处置，不再有第二份「质量决定」事实层。

## 实施设计

1. **冻结点前移**：`CaptureGroupAnnotationWorkflow.assignForHumanLabel` 在准备任务时，按当前 facts 计算并冻结 `confirmedViewSet` / `inputVersion` / `confirmedViewSetSha256`，写入 `AnnotationTask`（该实体已有这三个字段）。快照计算逻辑仍归 `qualitycleaning` 所有，以公开方法暴露，annotationreview 只调用不复制。
2. **门禁换成漂移校验**：`CaptureGroupAnnotationWorkflow:94 / :180 / :281` 的 `isAdmitting()` 与 `captureGroupQualityReviewId() != null` 约束替换为「任务已冻结快照 + 当前 facts 与冻结快照一致」；输入漂移的拒绝能力与既有错误码保留。
3. **机位可复核条件去质量化**：`CaptureGroupAnnotationWorkflow:303` 与 `CaptureGroupReviewPanel.tsx:1060-1073` 中 `QUALIFIED` 子句改为客观事实（`present && assetAvailable && INGESTED`），机位否决由 camera view `EXCLUDED` 唯一表达。
4. **导出去质量化**：`ApprovedCaptureGroupAnnotationQueryService:114` 去掉 `quality == null || !quality.isAdmitting()`，改为与任务冻结快照比对，`APPROVED_CAPTURE_GROUP_ANNOTATION_NOT_ELIGIBLE` 及其余 provenance 校验不变。
5. **前端**：删除 `canBootstrapGroupQuality`（`CaptureGroupAnnotationPanel.tsx:300`）、`bootstrapGroupQualityForAnnotation()`（`:574`）、提交路径的 bootstrap 分支（`:648`）与橙色阻塞条（`:970`）；机位 `INGESTED` 后「提交标注」直接可点；`use-capture-group-view.ts:329/:334` 文案同步。
6. **不动的东西**：不新增表、不改迁移、不改 `confirmedViewSet` 结构（避免 digest 与 schema 版本连锁）、不删质量评审的只读查询面与历史记录。

代价与前提：存量进行中任务的冻结快照由旧逻辑产生，升级后需重新准备任务；开发库无存量数据，不受影响。契约测试需同步 `CaptureGroupQualityAnnotationContractTests` 等断言。

## 实施结果（2026-09-17）

**新增（qualitycleaning）**

- `CaptureGroupInputFreeze`：纯投影。公开常量 `SCHEMA_VERSION` / `ANNOTATABLE` / `NOT_ANNOTATABLE`；`annotatable(CaptureGroupView)` 判定机位可标注；`of(CaptureGroupFacts)` 产出 `Snapshot(confirmedViewSet, inputVersion, viewSetSha256, receiptState, viewCount, annotatableViewCount)`，`admitting()` = 接收已定稿且至少一个可标注机位，`fullyAnnotatable()` = 全机位可标注。快照字段形状沿用 `capture-group-view-set.v1`，其中 `quality_review_decision` 改由探针事实推导取值（不新增字段、不改 digest 结构语义）。
- `CaptureGroupInputFreezeQueryService`：准入唯一边界。`get` / `getAdmittingById` / `lockAndGet` 只读计算；`freeze(projectId, captureGroupId, now)` 幂等冻结——输入未变复用现有凭据，输入已变则以 `SYSTEM` 身份写入 `capture_group_quality_review` 新修订（`resultFor` 映射 NEEDS_REVIEW / QUALIFIED / QUALIFIED_WITH_EXCLUSIONS），并记 `CAPTURE_GROUP_INPUT_FROZEN` 审计事件。

**删除**

- `CaptureGroupQualityQueryService`（含 `CurrentDecision.isAdmitting()`）——准入读取面已被冻结快照取代。

**改造**

- `CaptureGroupAnnotationWorkflow`：依赖换成 `CaptureGroupViewQueryService` + `CaptureGroupInputFreezeQueryService`；`assignForHumanLabel` 先 `freeze(...)`，`!snapshot.admitting()` 抛既有 `ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED`；`requireCurrentGroupInput` / `viewSetStillCurrent` / `requireCameraViewEligibleForSubmission` / `requireGroupReviewContext` 全部改为比对冻结快照与当前 facts，逐机位判定改用 `ANNOTATABLE` / `NOT_ANNOTATABLE`；`groupQualityNotAdmitting()` 更名 `groupNotAnnotatable()`（错误码不变）。
- `AnnotationTaskService`：列表投影与 `updateCameraView` 改读 `CaptureGroupInputFreezeQueryService`。
- `ApprovedCaptureGroupAnnotationQueryService`：导出 fail-closed 改为与冻结快照（`admitting()` + `inputVersion` + `confirmedViewSet` + 漂移校验）比对。
- `CaptureGroupQualityReviewService`：降级为**历史面**——保留组质量记录的读写与历史查询，落库时快照改由 `CaptureGroupInputFreeze.of(facts)` 计算，不再参与任何准入判断。记录页既有校验语义（`CAPTURE_GROUP_QUALITY_RECEIPT_NOT_FINALIZED` / `CONFLICT` / `NO_QUALIFIED_VIEW` / `VIEW_NOT_RESOLVED` / `INCOMPLETE_REQUIRES_EXCLUSIONS` / `QUALIFIED_HAS_EXCLUSIONS`）保持不变。
- `CaptureGroupQualityReviewController`：删除 `from(CurrentDecision)` 重载。

**前端**

- `CaptureGroupAnnotationPanel`：删除 bootstrap 质量逻辑、橙色阻塞条、跨机位「对应原因」必填（改为系统自动登记 `各路机位按独立时间轴送审，未确认跨机位对应。`），「提交复审」不再被未填原因阻塞。
- `CaptureGroupFocusedView` + `capture-group-detail-model`：机位标签页展示**各自**处置状态（`已提交 N 段` / `已排除` / `编辑中` / `待处理`），切换机位有可见差异。
- `use-capture-group-detail-data`：移除机位级与组级质量评审（`groupQuality` / 质量历史 / 可见性 / 错误态）的全部加载与状态，标注页不再请求质量评审端点。
- `CaptureGroupReviewPanel` / `CaptureGroupInspector` / `CaptureGroupDetailView`：删除「当前整体质量」事实块、人工标签质量事实卡片、质量错误 Alert 与质量驱动的置灰和导航；Inspector 的任务上下文链接与右侧面板选择只由任务状态（`captureGroupTaskDestination`）和客观机位处置决定，历史质量记录不再劫持导航；证据区标题、机位排除原因与 `api/annotation.ts` 错误文案统一改为客观的输入就绪 / 机位处置表述。
- 复核面板动作段区由「只受控 `open`、无 `onToggle`」的异常 `<details>` 改为常驻区块，修复「查看全部 N 段 / 收起动作段」折叠交互。
- 组件、契约与 API 文案测试同步更新；历史质量只读 client（`api/capture-groups.ts` / `api/quality.ts`）、fixtures 与无渲染引用的 CSS 死类按历史面保留，不再接入任何标注 UI。


## 验收

- 机位 `INGESTED` 后「提交标注」直接可点，全程无需任何质量确认动作。
- 全部预期机位处置完 → 可提交整组复核；复核通过 → 可导出。
- 探针失败视频不可提交；排除必填原因并留审计；输入漂移后提交 / 复核 / 导出按既有错误码拒绝。
- 历史质量评审记录仍可读、不可改。
