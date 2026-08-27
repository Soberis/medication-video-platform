# 固定多相机配药事件质量评审、标注与清洗用户流程

> 状态：P1 用户流程草案 / 未实施
>
> 本文只描述未来多相机工作流的用户体验和信息边界，不新增当前 React 页面、后端接口、数据库、状态机或导航入口，也不改变 V1.24 P0 单视频闭环。示例使用 3–5 路固定相机；实际数量由服务端明确的归组事实动态决定。

`CaptureGroup` 表示一个真实配药事件，身份默认复用 `CaptureSession.capture_session_id`；前端只能读取服务端明确的组和视图，不能按文件名、上传顺序、接收时间或时间接近度猜测归组。

## 1. 主操作旅程

```text
CaptureGroup 列表
    ↓
CaptureGroup 详情
    ↓
每路自动事实与人工查看提示
    ↓
人工提交逐视图 QualityReview
    ↓
人工提交并保存 CaptureGroupQualityReview
    ↓
一个属于 CaptureGroup 的 AnnotationTask
    ↓
选择 primary/anchor view 作为正式时间基准
    ↓
一个属于 CaptureGroup 的 AnnotationRevision
    ↓
人工 Review
    ↓
事件级 Cleaning
    ↓
可用训练数据
```

这不是五个独立训练样本。每一步都保留组身份、视图身份和审计事实；主/锚点视图只提供时间轴，
不是业务事件或标注的 owner。

## 2. CaptureGroup 列表

列表用于找工作和理解阻断原因，不做统计大屏。沿用现有 `PageHeader → Toolbar → Content` 布局，保持紧凑表格。

建议列：

| 列 | 表达内容 |
| --- | --- |
| 配药事件 | `CaptureGroup A001`、采集时间、短 ID；完整 ID 可复制/展开 |
| 收件 | 预期相机数 / 已最终确认数；`RECEIVING`、`COMPLETE` 或 `INCOMPLETE` |
| 视图摘要 | `QUALIFIED`、`NEEDS_REVIEW`、`REJECTED`、待处理、缺失的文字计数 |
| 组质量 | 持久化的 `QUALIFIED`、`QUALIFIED_WITH_EXCLUSIONS`、`NEEDS_REVIEW` 或 `REJECTED` |
| 下一步 | 质量评审、标注、Review 或 Cleaning |

只显示服务端已支持的项目、时间和组状态筛选；浏览器不拼接多页视频推断组状态。缺少相机 2 时仍显示“相机 2 缺失”，不能重编号其他相机。

## 3. CaptureGroup 详情

```text
PageHeader
├─ CaptureGroup A001
├─ 组质量：NEEDS_REVIEW / QUALIFIED / QUALIFIED_WITH_EXCLUSIONS / REJECTED
├─ 收件：INCOMPLETE · 预期 5 路 · 已最终确认 4 路 · 缺失 1 路
└─ 刷新；显示当前唯一下一步

相机矩阵
├─ Cam1  QualityReview.QUALIFIED
├─ Cam2  Media Probe 失败 → 仍保留视图
├─ Cam3  QualityReview.QUALIFIED
├─ Cam4  QualityReview.NEEDS_REVIEW
└─ Cam5  缺失

工作区
├─ 主区：当前视图、媒体事实、手部证据、提示和人工动作
└─ Inspector：组质量事实、视图清单、主视图、下游下一步
```

### 3.1 当前视图

主区一次聚焦一条视图，避免变成视频编辑器：

1. 遵守现有授权，用户点击后播放；不自动播放、不预加载未请求的视频。
2. 显示媒体状态、时长、分辨率和稳定相机编号。
3. 显示 MediaPipe 候选证据（检测/无手、候选数量、覆盖等已存在事实）；候选不是动作标签或质量结论。
4. 显示自动事实和提示，如“媒体不可读”“长时间未检测到手部”“候选不可用”“时间证据不足”；正式动作区间前不说“关键动作被遮挡”。
5. 提供三个逐视图人工操作：`确认合格（QUALIFIED）`、`需复核（NEEDS_REVIEW）`、`拒绝（REJECTED）`。

缺失视图显示缺失原因和影响，不显示黑色视频框。检测中、检测失败和实际缺路使用不同文字，不能只靠颜色。

### 3.2 相机矩阵与 Inspector

每行显示相机编号、文件是否最终接收、逐视频 `QualityReview`、自动事实提示、简短理由和是否参与下游；缺失行无播放按钮但可查看接入事实。

Inspector 只回答：

- 哪些视图为 `QualityReview.QUALIFIED`？
- 当前持久化组质量为什么停在这里？
- 下一步是逐视图质量、组质量、标注、Review 还是 Cleaning？

完整 SHA、编码参数、上传和审计详情渐进披露，不与主操作竞争。

## 4. 状态和提示

自动层与人工层必须分开：

| 层次 | 状态/提示 | 含义 |
| --- | --- | --- |
| 组收件事实 | `RECEIVING` / `COMPLETE` / `INCOMPLETE` | 只描述预期文件是否收到并最终确认，不描述媒体/人工质量 |
| 技术事实 | 缺失、待检测、检测中、检测失败、可查看 | 接收和媒体处理事实，不是人工质量结论 |
| 自动提示 | 媒体不可读、手部覆盖偏低、长时间未检测到手部、候选不可用、MediaPipe 失败、时间证据不足 | facts + human-attention hints；不自动改变下游 |
| 逐视图人工质量 | `QUALIFIED` / `NEEDS_REVIEW` / `REJECTED` | 人工确认、暂缓或拒绝该视图 |
| 组人工质量 | `QUALIFIED` / `QUALIFIED_WITH_EXCLUSIONS` / `NEEDS_REVIEW` / `REJECTED` | 持久化的人类组决定 |

五路文件都已收到并最终确认时，即使 Cam2 Media Probe 失败，组收件仍为 `COMPLETE`，Cam2 单独显示媒体失败；只有实际缺路才是 `INCOMPLETE`。`NO_HAND_DETECTED`、候选为零或读取失败只是证据，不证明真实手部不存在，也不阻断人工质量或标注入口。

V1.24 `CaptureSession.assembly_status` 和 `quality_status` 仍按既有技术语义展示；它们不是本流程的组 receipt，也不是人工组质量结论。

组质量至少需要一条逐视图 `QualityReview.QUALIFIED` 才能为 `QUALIFIED` 或 `QUALIFIED_WITH_EXCLUSIONS`；没有就只能保持 `NEEDS_REVIEW` 或由人判为 `REJECTED`。组质量写入 `CaptureGroupQualityReview`，不只是前端临时计数。

## 5. 组质量操作

服务端展示客观事实，用户提交正式组决定：

| 结果 | UI 语义 |
| --- | --- |
| `QUALIFIED` | 确认事件可用，且没有影响目标用途的相关缺失/拒绝视图 |
| `QUALIFIED_WITH_EXCLUSIONS` | 确认缺失/拒绝视图后剩余视图仍足以支持事件 |
| `NEEDS_REVIEW` | 组决定、接收、视图或时间证据尚未完成 |
| `REJECTED` | 确认剩余视觉证据不足或事件不可用 |

提交时记录组 ID、结果、理由、审核人、时间、版本及本次依据的视图集合/输入摘要。自动提示不能代替或覆盖人工决定。

## 6. 一个组任务和一份组修订

V1.25 多机位路径不为同一事件创建 Cam1、Cam2、Cam3 等重复任务：

```text
CaptureGroup
→ 一个 AnnotationTask（属于组）
→ 选择 primary/anchor view（只确定正式时间基准）
→ 一个 AnnotationRevision（属于组）
```

组质量为 `QUALIFIED` 或 `QUALIFIED_WITH_EXCLUSIONS` 后，系统按待冻结的版本化契约幂等创建并
分配恰好一个组任务；重复事件不重复创建，`NEEDS_REVIEW`、`REJECTED` 和候选结果不创建任务。
任务的权限、事务和审计仍必须保留。

主视图不是业务 owner；其他逐视图 `QUALIFIED` 只提供支持画面和候选证据。正式 action label 和 `[start_ms, end_ms)` 区间只来自人工 AnnotationRevision，Candidate/MediaPipe 不能生成、修改或通过 Review/Cleaning。

V1.24 冻结单视频路径仍是 `ClipAsset.QUALIFIED → 一个视频 AnnotationTask → 人工 AnnotationRevision`，不回写历史行为。V1.25 需要在实现前冻结组任务/修订关系、主视图引用、权限和审计契约，当前工作台不提前伪装支持它。

## 7. 多视图查看与同步

同步只展示证据可信度：`RELIABLE`、`APPROXIMATE`、`UNRELIABLE`。`RELIABLE` 也不承诺校准后的逐帧同步。

第一版交互：

- 一个主聚焦相机，可切换其他已参与视图；
- 可选少量缩略图，不强制同时加载所有视频；
- 仅在证据支持时提供共享逻辑播放位置；
- 证据近似/不可靠时明确警告，按主视图时间轴标注，其他视图仅作参考；
- 不伪造 offset、不插帧、不自动对齐，不引入 CaptureSync、标定或三维视图；
- 每路可独立显示 MediaPipe 21 点 Overlay，handedness 只作辅助元数据。

## 8. Review、Cleaning 与数据去向

Review 以组任务的正式 `AnnotationRevision` 为对象，审阅组身份、主视图、参与的 `QUALIFIED` 视图、拒绝/缺失视图、动作区间和相关候选证据；Review 是人工闸门，不能由 AI 自动通过。

Cleaning 在配药事件级回答“该组标注是否适合训练”，检查正式修订、Review、`CaptureGroupQualityReview`、参与视图、媒体完整性及拒绝/缺失原因。示例：Cam1/Cam3/Cam4 `QUALIFIED`、Cam2 `REJECTED`，组为 `QUALIFIED_WITH_EXCLUSIONS`；Review 通过后 Cleaning 可使用 Cam1/Cam3/Cam4，Cam2 保留但不下游。

质量拒绝不等于删除：

```text
视图 REJECTED
→ 保留视频、Candidate、绑定、理由和审计
→ 不参与下游
```

不建设片段级清洗编辑器。现有 V1.24 `training-data.v2` / `training_data.parquet` 继续服务单视频历史路径；V1.25 多视图如需导出，应另行冻结版本化契约（可称 `training-data.v3`），保留组 ID、修订 ID、主视图、参与视频/相机、逐视图质量、组质量、候选和 Cleaning provenance。默认不复制 MP4，不创建 DatasetVersion。

任何 train/validation/test 切分都必须以 CaptureGroup 为最小防泄漏单位，同组所有视图和派生行进入同一 split。

## 9. 当前工作区边界

本草案不改变当前实现：

- 当前 `VideoManagementView`、`AssetDetailView` 和 Annotation Workbench 仍是 V1.24 单视频入口；不新增组页面或运行时。
- 当前代码的 `QualityReview`、AnnotationTask/Revision、Review、Cleaning、权限、项目隔离和导出契约保持不变。
- V1.25 组质量、组任务/修订和事件级 Cleaning 必须先完成版本化契约，再进入实现规划。
- 不新增 workflow engine、Worker、Queue、微服务、数据集平台、AI 动作识别或物理删除快捷路径。

## 10. V1.25 MVP 边界

### MUST

- 显式 CaptureGroup 身份和动态 N 路视图读取；
- 三态收件事实及版本化组完成信号；
- 每路独立 Media Probe/MediaPipe 证据和逐视图 QualityReview；
- 可审计的四态 `CaptureGroupQualityReview`；
- 一个组 AnnotationTask、一个组 AnnotationRevision、主视图时间基准；
- 组 Review、事件级 Cleaning、组/视图追溯和按组 split。

### SHOULD

- 更丰富的多视图对照和同步证据展示；
- “单视频合格但仅本组排除”的独立纳入事实；
- 更多可解释自动提示。

### DEFER

- 片段级排除/剪切、质量规则平台、最低相机数策略平台；
- 帧级同步、自动对齐、标定、三角测量、3D、跨相机追踪、AI 动作识别；
- DatasetVersion、复杂导出/训练平台、批量 Worker/Queue、工作流引擎和微服务拆分。

V1.22 三维/同步方向和历史复杂导出、评估、编排设计仅作追溯，不约束 V1.25。
