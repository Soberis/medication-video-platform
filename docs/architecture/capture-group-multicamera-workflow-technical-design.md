# CaptureGroup 多机位接收与数据闭环最小技术设计（V1.25）

> 状态：V1.25 当前多机位实现设计 — 目标与实现证据见追踪矩阵
> 本文定义已批准的 V1.25 目标、边界与实施顺序；不替代代码、迁移、OpenAPI、事件、Worker 和运行时的实际证据。当前实现状态以[当前需求追踪矩阵](requirements-traceability.md)、冻结记录和实际测试结果为准，真实 UAT 仍须单独执行。
> V1.25 是当前正式需求基线；V1.24 代码和单视频事实仍是迁移起点与历史兼容参考。

本文基于 [V1.25 需求基线](../requirements/baselines/V1.25/配药视频数据管理平台需求文档_V1.25.md)、[V1.24 前一正式基线](../requirements/baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)、[V1.24 手部组件参考](video-hand-pose-mvp-technical-design.md)、[external-video-ingest-v1 契约](../../contracts/schemas/external-video-ingest-v1.schema.json) 和 [最小训练数据导出说明](../../tools/minimal-export/README.md)。V1.22 多视角、CaptureSync 和复杂数据集文档仍是历史/延后设计。

## 1. 设计结论

一个真实配药事件对应一个 `CaptureGroup`。在现有模型足够表达该事件时，唯一组身份直接复用
`CaptureSession.capture_session_id`（对外可命名为 `capture_group_id`），不创建第二个 group ID 或
第二个分组真值。`capture_group_key`/manifest 是显式归组证据，不由文件名、顺序、接收时间或时间邻近推断。

```text
Project
└─ CaptureGroup = CaptureSession (capture_session_id)
   ├─ CameraSetSnapshot（冻结预期机位）
   ├─ CaptureGroupView = CameraSetSnapshotMember + CameraStream + ClipAsset
   └─ CaptureGroupQualityReview（最小持久人工组质量事实）
```

V1.25 目标主链：

```text
显式组事实
→ 每路接收与 Media Probe；成功视图再运行 MediaPipe
→ 自动事实 + 人工查看提示
→ 逐视图 QualityReview
→ 收件关闭后持久化 CaptureGroupQualityReview
→ 一个属于 CaptureGroup 的 AnnotationTask
→ 选择 primary/anchor view 作为正式时间基准
→ 一条属于 CaptureGroup 的 AnnotationRevision 修订链
→ 人工 Review
→ 事件级 Cleaning
→ 保留组/视图关系的可用训练数据
```

主/锚点视图只定义时间轴，不拥有配药事件或业务标注。

## 2. 事实复用、所有权与边界

| 业务概念 | 现有事实/最小新增事实 | 所有权与语义 |
| --- | --- | --- |
| CaptureGroup 身份 | `CaptureSession.capture_session_id` | `assetcapture`；一个真实事件的权威 ID |
| 预期机位 | `CameraSetSnapshot` / snapshot members | `assetcapture`；创建时冻结，缺号不重排 |
| 视图 | `CameraStream` + `ClipAsset` 的读取投影 | `assetcapture`；每路独立媒体和检测事实 |
| 逐视图质量 | 现有 `QualityReview` | `qualitycleaning`；`QUALIFIED/NEEDS_REVIEW/REJECTED` |
| 组质量 | `CaptureGroupQualityReview`（唯一必要的新业务事实） | `qualitycleaning`；人工、追加写入、可审计 |
| 组任务/修订 | 现有任务/修订表增加组关系与主视图引用 | `annotationreview`；组拥有一个任务和一条不可变追加修订链 |
| 候选 | `CandidateAnnotation` | `aicandidate`；辅助观测，不是 Ground Truth |
| Review/Cleaning | 现有 `AnnotationReview`、`CleaningRun/Result` 的组上下文 | 各自模块保持所有权和强制闸门 |

跨模块只通过公开应用服务、只读查询或版本化领域事件；不得跨模块注入 Repository 或直接写表。
`CaptureGroupQualityReview` 只引用组/视图的只读事实快照，不复制视频、候选或标注真值。

## 3. 收件完整性与媒体质量分离

组接收状态只描述预期文件是否已收到并最终确认：

| 状态 | 语义 |
| --- | --- |
| `RECEIVING` | 预期上传集合尚未最终确认 |
| `COMPLETE` | 所有预期相机文件都已收到并最终确认 |
| `INCOMPLETE` | 收件已最终确认，但至少一个预期相机文件缺失 |

声明数组只表示上游上传意图，不证明字节、`ClipAsset` 或 `CameraStream` 已收到；预期机位以冻结
`CameraSetSnapshot`/批准声明为准，已收到机位以实际绑定的 `ClipAsset`/`CameraStream` 为准。
V2 manifest 只允许在 `ACCEPTED` 且尚未绑定时追加声明；一旦 `BOUND`，声明行不可变。完成/不完整
必须来自显式版本化 manifest 完成标记或专用 receipt-finalize command，并由上述实际接收事实计算；
任意超时最多是运维提示，不能独自决定 `INCOMPLETE`。当前 external-video-ingest-v1 没有组完成语义，
该契约必须先增版，本文不改它。

收件事实一旦表示五路文件均已收到并最终确认，之后 Cam2 Media Probe 失败仍保持：

```text
receipt = COMPLETE
Cam2 media = failed
```

只有实际缺失预期文件才产生 `INCOMPLETE`。Media Probe、MediaPipe、逐视图 QualityReview 和组质量
永远不能回写 receipt 状态。当前 V1.24 `CaptureSession.assembly_status` 与 `quality_status`
继续保持各自的技术投影语义，不冒充新的组 receipt，也不冒充 `CaptureGroupQualityReview`。

## 4. 每路处理与自动证据

每个 `CaptureGroupView` 独立执行：

```text
接收/完整性
→ Media Probe
├─ INGESTED → MediaPipe Candidate
└─ MEDIA_FAILED（媒体 Probe 失败）→ 停止该视图 AI 处理，等待人工/恢复
```

`MEDIA_FAILED` 是本文的 V1.25 逻辑分支名；具体 API/状态枚举另行版本化，不改当前 V1.24
既有 `INGEST_FAILED` 等运行时状态。一个视图失败不影响其他视图继续处理。

可保留的事实包括媒体可读性、解码、时长、编码/分辨率、处理状态、手部检测/无手、含手帧数、无手帧数、
候选数、landmark 有效性、confidence、时间证据、输入摘要和模型/profile/run provenance。自动层只输出
事实和“建议人工查看”提示，不建立独立自动质量状态机或可配置最低视角/覆盖规则引擎。

`NO_HAND_DETECTED`、候选为零、候选读取失败、长时间未检测到手和 MediaPipe 失败都是证据，不证明真实手部
不存在。正式人工动作区间产生前，自动层不能断言“关键配药动作被遮挡”，也不能写 QualityReview、组质量、
正式标签、Review/Cleaning 通过或删除数据。

## 5. 逐视图质量与组质量

### 5.1 逐视图 QualityReview

复用现有 `QualityReview`：

```text
QUALIFIED     人工确认该视图可继续下游
NEEDS_REVIEW  暂不能可靠决定，保持阻断
REJECTED      该视图不参与下游，但保留视频、Candidate、绑定、原因和审计
```

不新增持久化 `EXCLUDED` 状态。未来若出现“单独视频合格、只在某组排除”的真实用例，再另行冻结
组范围纳入事实，当前属于 SHOULD/DEFER；`REJECTED` 永远不等于删除。

### 5.2 持久化 CaptureGroupQualityReview

组质量是业务事实，不能只是前端聚合。`qualitycleaning` 应拥有一个最小、追加写入的
`CaptureGroupQualityReview`（具体表名和 API 在实现前冻结），至少保存：

```text
capture_group_id（默认 capture_session_id）
result
reason
reviewer
revision/version
decided_at
本次决定所依据的视图集合或输入版本摘要
```

唯一 group vocabulary：

| 结果 | 语义 |
| --- | --- |
| `QUALIFIED` | 人工确认事件有足够可靠信息进入人工标注，至少一条视图 `QualityReview.QUALIFIED`，且没有阻断标注的未解决问题 |
| `QUALIFIED_WITH_EXCLUSIONS` | 人工确认缺失/拒绝视图后，剩余视图仍足以进入人工标注；至少一条视图 `QUALIFIED` |
| `NEEDS_REVIEW` | 标注准入未完成，或接收、视图、时间证据仍有疑问 |
| `REJECTED` | 人工确认剩余视觉证据不足以进入人工标注，或事件不可用 |

收件是组质量的前置条件：`receipt=RECEIVING` 时预期上传集合尚未关闭，组可保持
`NEEDS_REVIEW`，但不得提交或最终化为 `QUALIFIED` / `QUALIFIED_WITH_EXCLUSIONS`；只有
`receipt=COMPLETE` 或 `INCOMPLETE` 后，人工才能提交最终的 `CaptureGroupQualityReview` 结果。
超时不能单独关闭 receipt。预期五路仅收到三路时，即使已收到视图均为 `QualityReview.QUALIFIED`，
组仍不能成为 `QUALIFIED`；收件明确为 `INCOMPLETE` 且剩余视图足够时，才可由人决定
`QUALIFIED_WITH_EXCLUSIONS`。

零条 `QualityReview.QUALIFIED` 视图时，组不能是 `QUALIFIED` 或 `QUALIFIED_WITH_EXCLUSIONS`。
`CaptureGroupQualityReview` 只表示标注准入，不表示 Review、Cleaning 或训练导出已通过。第一版
由服务端呈现客观事实、由人作组决定，不写死最低相机数或覆盖阈值；医院/项目政策留待后续。

## 6. 组拥有的任务/修订与主视图时间基准

V1.25 多机位路径的正式模型是：

```text
CaptureGroup
→ 一个 AnnotationTask（业务归属为组）
→ 一条不可变、追加写入的 AnnotationRevision 修订链（业务归属为组）
```

只有 receipt 已关闭为 `COMPLETE` 或 `INCOMPLETE`，且人工组质量为 `QUALIFIED` 或
`QUALIFIED_WITH_EXCLUSIONS` 时，才按待冻结的 V1.25 契约幂等创建并分配恰好一个组任务。
提交该有效组质量结论的 ACTIVE 项目成员就是第一版任务负责人：其 actor ID 同时写入
`AnnotationTask.assignee_actor_id` 和 `created_by`。提交者必须通过现有项目成员、ACTIVE 和授权校验。
重复事件返回原任务，不创建或重新分配；已有组任务不得被静默替换或改派。`NEEDS_REVIEW`、
`REJECTED` 和任何 Candidate/MediaPipe 结果都不能触发任务。任务创建/分配必须保留现有权限、事务
和追加式审计边界，不引入调度、负载均衡、轮询、队列或技能路由。

同一动作不得按相机重复创建任务或标签。选择 `primary/anchor view` 只决定正式 `[start_ms, end_ms)`
时间基准；主视图不是组或标注的 owner。其他 `QualityReview.QUALIFIED` 视图是支持/参考证据，不创建第二份动作真值。
动作标签和时间段始终来自人工、不可变且追加写入的 `AnnotationRevision`，不是 Candidate、MediaPipe 或自动建议。

组质量结论、组任务工作上下文和每条 `AnnotationRevision` 都记录创建/提交时确认的 View Set 与
输入版本摘要；后续迟到视图或视图质量变化不得静默改写已有事实。若要纳入新视图或新质量，必须
重新提交 `CaptureGroupQualityReview`，并在同一组的唯一任务下按新输入版本追加修订；既有修订的
主视图和时间基准保持不变。

当前 V1.24 单视频规则 `ClipAsset.QUALIFIED → 一个视频 AnnotationTask → 人工 AnnotationRevision`
继续有效，但只适用于冻结单视频路径。若现有数据库/API不能表达 V1.25 组归属，实施时最小增量方向是：

1. 在 `annotation_task` 与 `annotation_revision` 增加可选 `capture_group_id`（旧 V1.24 行为空）；
2. 增加显式 `primary_view/anchor_view` 引用，组行的 `clip_asset_id` 若因兼容必须保留，只作技术参考，不作为 owner；
3. 为每个项目的组任务建立唯一约束，并明确组任务的权限、事件、审计和 Review/Cleaning 引用。

具体字段、迁移和契约必须单独版本化；本设计不实施。

## 7. 同步与相机身份

同步只表示证据可信度：

```text
RELIABLE / APPROXIMATE / UNRELIABLE
```

`RELIABLE` 也不等于校准后的逐帧同步。无可靠证据时使用主视图时间轴，其他视图仅作视觉参考；不伪造
offset、不插帧、不自动对齐、不引入 CaptureSync。`camera_number` 是事件内稳定显示编号，
`camera_channel_id` 等来源身份在可用时保留，缺路不重编号。

同一 CaptureGroup 的事件身份不等于各机位拥有精确共享的时间戳。
主视图上的 `[start_ms, end_ms)` 是正式动作区间的唯一时间基准，不得直接复制给支持视图。只有同步证据为
`RELIABLE` 且存在已验证、带版本的 primary→supporting 映射时，才可按该映射推导支持视图区间，并保留
映射 provenance/version；`APPROXIMATE` 仅供人工对照，不能静默生成精确动作时间；`UNRELIABLE` 或无映射
只能作为事件级支持证据，不产生伪造的 action-aligned 时间戳。Annotation、Review、Cleaning 和未来导出
都必须遵守此边界。

## 8. Review、事件级 Cleaning 与导出方向

- Review 以组任务的正式 `AnnotationRevision` 为对象，审阅组身份、主视图、参与合格视图、拒绝/缺失视图、动作区间和相关候选证据；Review 必须由人通过。
- `CaptureGroupQualityReview` 是标注准入；Cleaning 是训练数据准入。只有正式修订和人工 Review 通过后，Cleaning 才在配药事件级检查组质量事实、参与视图、媒体完整性和拒绝/缺失原因。Cam2 `REJECTED` 时，Cam1/Cam3/Cam4 可在组为 `QUALIFIED_WITH_EXCLUSIONS` 且人工确认后参与；Cam2 保留但不下游。
- 主视图区间是正式时间基准；支持视图只有在已验证的 `RELIABLE` 映射下才可推导对应区间并保留 provenance/version。`APPROXIMATE`、`UNRELIABLE` 或无映射的支持视图仅作事件级证据，Review/Cleaning 和未来导出不得为其伪造动作对齐时间戳。
- 现有 V1.24 `training-data.v2` / `training_data.parquet` 继续服务单视频历史路径。V1.25 使用已冻结的最小 `training-data.v3` 组 manifest，保留组 ID、修订 ID、主视图、参与视频/相机、逐视图质量、组质量、Candidate provenance 和 Cleaning provenance；它不是 DatasetVersion 平台。
- 默认不复制 MP4，不创建 DatasetVersion 或数据集平台。所有同一 CaptureGroup 的训练行必须进入同一 train/validation/test split。

## 9. 与 V1.24 的兼容与实施前置

一机位 CaptureGroup 仍是有效特例。V1.25 生效后，新接入事件无论 `N=1` 还是 `N>1` 都统一
以 CaptureGroup 进入本流程，`N=1` 只是一路视图；不按摄像头数量维护两条新流程。现有 V1.24
单视频 Media Probe、MediaPipe Candidate、QualityReview、AnnotationTask/Revision、Review、Cleaning、权限和导出仅作为历史数据/兼容迁移路径保留，不改其运行语义。

当前实现所依据的最小契约为：

1. 组完成/不完整的版本化接收信号；
2. `capture_session_id` 到 `capture_group_id` 的只读映射和视图集合查询；
3. `CaptureGroupQualityReview` 的字段、权限、审计和版本；
4. 组 AnnotationTask/Revision 的唯一性、主视图引用及 Review/Cleaning 关系；
5. 多视图导出的版本化字段和按组 split 规则。

在这些契约冻结前，不新增页面、迁移、Worker、Queue、API 或导出实现。

## 10. MVP 边界

### MUST

- 显式 CaptureGroup 身份（默认复用 `capture_session_id`）和动态 `N` 路视图；
- RECEIVING / COMPLETE / INCOMPLETE 收件事实及版本化组完成信号；
- 每路独立 Media Probe/MediaPipe 证据和复用的逐视图 QualityReview；
- 可审计的四态 `CaptureGroupQualityReview`；
- 一个组 AnnotationTask、一条不可变追加的 AnnotationRevision 修订链、主视图时间基准；
- 组上下文 Review、事件级 Cleaning、下游组/视图追溯和按组防泄漏 split。

### SHOULD

- 更丰富的多视图对照和同步证据展示；
- 组范围“单视频合格但本组排除”的独立纳入事实；
- 更多可解释的自动提示与时间证据。

### DEFER

- 片段级排除/剪切、可配置质量规则平台、最低相机数策略平台；
- 帧级同步、自动对齐、标定、三角测量、3D、跨相机追踪、AI 动作识别；
- DatasetVersion、复杂导出/训练平台、批量 Worker/Queue、通用工作流引擎和微服务拆分。

## 11. 明确的历史边界

V1.22 三维多视角、CaptureSync runtime、相机标定/三角测量、跨相机 ReID/手部身份、3D 重建、
DatasetVersion/复杂导出、通用 AI Evaluation、工作流引擎和微服务扩张继续是历史/延后方向，不约束
V1.25，也不因本文恢复。

本文不承载 V1.25 生产代码、数据库、契约、Worker 或运行时实现；这些工件的当前状态以仓库实际内容和冻结记录为准。V1.25 CaptureGroup 路径已经有自动化与本地 synthetic 证据，但不因此替代真实 UAT、生产部署或医院验收。
