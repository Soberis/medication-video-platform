# 固定多相机配药事件质量评审、标注与清洗用户流程

> 状态：P1 用户流程草案 / 未实施
>
> 本文只定义未来多相机工作流的用户体验和信息边界，不新增 React 页面、后端接口、数据库、状态机或
> 导航入口，也不改变当前 V1.24 P0 单视频闭环。示例使用 3–5 路固定相机；实际相机数量仍由服务端
> 已确认的采集归组事实动态决定。

本文遵循[设计系统](../DESIGN_SYSTEM.md)的 Quiet Professional Workspace 方向和[前端 UX 规范](./web-ux-and-frontend-guidelines.md)。
`CaptureGroup` 是面向操作人员的“一次配药事件”概念；实现时只能映射服务端明确的
`CaptureSession` / `capture_group_key`，不得由前端按文件名、上传顺序、时间邻近或相机号猜测归组。

## 1. 主操作旅程

```text
CaptureGroup 列表
    ↓
CaptureGroup 详情
    ↓
自动质量事实与每路提示
    ↓
人工查看各相机画面并提交逐视频 QualityReview
    ↓
QUALIFIED 视图继续；REJECTED 视图保留并停止下游处理
    ↓
组级质量结果（QUALIFIED / QUALIFIED_WITH_EXCLUSIONS / NEEDS_REVIEW / REJECTED）
    ↓
选择一个锚点视图
    ↓
锚点 `QUALIFIED` 自动创建并分配唯一 `AnnotationTask`
    ↓
锚点上的人工 AnnotationRevision
    ↓
人工 Review
    ↓
Cleaning
    ↓
可用数据
```

工作流的核心对象是“一个配药事件及其视图集合”，不是五个互相独立的训练样本。每一步都保留
`CaptureGroup` 身份和每路视图的处理结果，便于追溯、重看和后续按事件分组切分数据。

## 2. CaptureGroup 列表

列表是找工作和理解阻断原因的入口，不是统计仪表盘。使用现有工作台的 `PageHeader → Toolbar → Content`
层级，保持紧凑表格，不增加大面积 KPI 卡片。

建议列：

| 列 | 表达内容 |
| --- | --- |
| 配药事件 | `CaptureGroup A001`、采集时间和短 ID；完整 ID 可复制或展开查看 |
| 视图收件情况 | 预期相机数 / 已最终确认相机数；明确“缺失”而不是显示为失败 |
| 相机视图摘要 | `QUALIFIED`、`REJECTED`、`NEEDS_REVIEW`、待处理、缺失的紧凑文字计数 |
| 组质量状态 | 四种 canonical group quality 状态及一句阻断原因 |
| 下一步 | 质量评审、继续标注、查看 Review 或处理 Cleaning |

只显示服务端已经支持的筛选，例如项目上下文、采集时间和组状态。列表不预加载所有视频，也不在浏览器
拼接多页视频推断组状态。缺路相机仍保留其预期编号，例如“相机 5 缺失”，不能把相机 6 重编号为 5。

## 3. CaptureGroup 详情

详情页使用“主工作区 + Inspector”，让用户先看懂整个事件，再处理一条相机视图。

```text
PageHeader
├─ CaptureGroup A001
├─ 组质量：NEEDS_REVIEW（需复核） / QUALIFIED（可进入标注） /
│          QUALIFIED_WITH_EXCLUSIONS（保留部分视图后可进入标注） / REJECTED（证据不足）
├─ 收件：INCOMPLETE · 预期 5 路 · 已最终确认 4 路 · 缺失 1 路
└─ 刷新；当前唯一下一步

紧凑相机矩阵
├─ Cam1  QualityReview.QUALIFIED
├─ Cam2  自动提示待人工确认
├─ Cam3  QualityReview.QUALIFIED
├─ Cam4  QualityReview.NEEDS_REVIEW
└─ Cam5  缺失

工作区
├─ 主区：当前相机视频、媒体事实、手部证据、自动提示和人工动作
└─ Inspector：视图清单、组级质量状态、QUALIFIED 视图、下游下一步
```

### 3.1 当前相机视图

主区一次聚焦一条视图，避免把页面变成视频编辑器。顺序固定为：

1. 按现有授权规则点击后播放；不自动播放、不预加载未请求的视频；
2. 播放器旁显示基础媒体事实，如媒体状态、时长、分辨率和相机编号；
3. 显示已存在的 MediaPipe 手部候选证据，例如检测状态、候选数量和覆盖情况；候选是辅助证据，不是
   正式动作标签或质量结论；
4. 显示自动事实及其理由，例如“画面大段不可读，需人工查看”“长时间未检测到手部，需人工查看”或“时间证据不足，需人工查看”；
   在正式动作区间产生前，不把“关键动作被遮挡”作为自动结论；
5. 提供三个清晰的人工操作，并映射到现有结论：`确认合格（QUALIFIED）`、
   `拒绝（REJECTED）`、`需复核（NEEDS_REVIEW）`。

缺失视图显示缺失原因和后续影响，不显示黑色视频框。技术检测失败、尚未检测和实际缺路必须使用不同的
文字状态。状态不能只靠颜色表达。

### 3.2 相机矩阵与 Inspector

相机矩阵是详情页的快速导航，不重复完整技术报告。每行显示：相机编号、视频是否已最终接收、逐视频
`QualityReview`、自动事实提示、简短理由和是否进入下游。点击行切换主区视图；缺失行可查看接入事实但没有播放按钮。

Inspector 只回答三个问题：

- 哪些视图为 `QualityReview.QUALIFIED` 并可以继续？
- 当前组为什么停在这里？
- 下一步是质量评审、标注、Review 还是 Cleaning？

完整 SHA、编码参数、上传事实和审计详情使用渐进披露，不与相机矩阵和主操作竞争。

## 4. 视图质量状态

自动提示和人工结论必须分成两层，不能把模型提示直接显示成“已通过”。

| 层次 | 状态 | 含义 |
| --- | --- | --- |
| 组接收事实 | `RECEIVING` / `COMPLETE` / `INCOMPLETE` | 只描述预期相机文件是否已收到并最终确认，不描述 Media Probe 或质量结果 |
| 接入/技术事实 | 缺失 | 预期相机没有对应视频；不是删除，也不是媒体检测失败 |
| 接入/技术事实 | 待检测 / 检测中 / 检测失败 / 可查看 | 服务端媒体检查事实；不替代人工质量判断 |
| 自动提示 | 媒体不可读 / 手部覆盖偏低 / 长时间未检测到手部 / 时间证据不足 / MediaPipe 失败 / 无法判断 | 根据可解释的媒体和候选事实给出的提示，不改变下游状态；正式动作区间前不声称关键动作被遮挡 |
| 人工结论 | 待处理 | 尚未确认该视图 |
| 人工结论 | `QualityReview.QUALIFIED`（确认合格） | 人工确认该视频可继续进入当前下游 |
| 人工结论 | `QualityReview.REJECTED`（拒绝） | 该视频不进入下游，但继续保存和审计；不是删除 |
| 人工结论 | `QualityReview.NEEDS_REVIEW`（需复核） | 当前无法作出可靠结论，组或视图保持阻断 |

`RECEIVING` 表示预期文件尚未全部接收/最终确认；`COMPLETE` 表示所有预期相机文件均已接收并最终确认；
`INCOMPLETE` 仅表示接收已最终确认但实际缺少至少一个预期文件。文件收到后即使 Media Probe 失败，
仍保持收件 `COMPLETE`，只把媒体失败作为独立事实展示。`NO_HAND_DETECTED`、候选为零或 Candidate
读取失败仅表示检测证据缺失/失败，不证明真实手部不存在，也不替代人工结论。

人工选择与自动提示不一致时，必须填写固定原因或简短说明。服务端应记录原提示、人工结论、原因、操作人、
时间和当时使用的证据/版本；页面只展示已确认的审计事实，不自行拼接历史。

收件状态与媒体/质量状态严格分开：五路文件都已收到并最终确认时，即使 Cam2 的 Media Probe 失败，
组收件仍为 `COMPLETE`，Cam2 另显示媒体失败；只有实际缺少预期相机文件时，最终收件才为 `INCOMPLETE`。
Candidate 缺失或读取失败只显示为技术事实/人工查看提示，不阻断人工 `QualityReview` 或标注入口。

## 5. 组质量行为

组状态由服务端根据预期视图、人工视图结论、媒体事实和当前工作流结果投影。前端不以“可用视图数量大于零”
自行推断组已合格。

组质量状态使用以下 canonical server vocabulary，括号内仅为 UI 文案：

| 组质量状态 | UI 文案、进入条件和下一步 |
| --- | --- |
| `QUALIFIED` | 可进入标注；人工确认组可用，至少一条视图为 `QualityReview.QUALIFIED`，且没有未解决的阻断问题 |
| `QUALIFIED_WITH_EXCLUSIONS` | 保留部分视图后可进入标注；人工确认组可用，至少一条视图为 `QualityReview.QUALIFIED`，其他视图可为缺失、媒体失败或 `REJECTED` |
| `NEEDS_REVIEW` | 待质量评审/需复核；逐视图质量、接收事实、时间证据或人工组判断尚未完成 |
| `REJECTED` | 证据不足；人工确认没有足够的 `QualityReview.QUALIFIED` 视图支持事件，或事件本身不可用 |

组质量四态确定后，`标注中`、`待 Review`、`Cleaning`、`可用数据` 等是既有后续工作流事实，
不是另一套组质量状态；它们继续复用 V1.24 的服务端结果。

第一版不建设可配置的最低视角/覆盖规则引擎；服务端展示接收、媒体、候选和逐视频 `QualityReview` 事实，
由人确认组质量。没有任何 `QualityReview.QUALIFIED` 视图时，不得将组标为 `QUALIFIED` 或
`QUALIFIED_WITH_EXCLUSIONS`。额外的医院/项目政策留待后续决定。

这里的“可进入标注”是未来 `CaptureGroup` 的服务端投影状态，不等同于单个视频的
`QualityReview.QUALIFIED`。V1.24 的继承桥必须保留：锚点视频的 `QUALIFIED` 自动创建并分配唯一
`AnnotationTask`，再由受分配人提交 `AnnotationRevision`；正式进入组标注前必须先冻结锚点路由，
不能让每个视图重复触发任务。

例：初始评审可以显示为：

```text
Cam1  QualityReview.QUALIFIED
Cam2  自动提示：需人工查看
Cam3  QualityReview.QUALIFIED
Cam4  QualityReview.NEEDS_REVIEW
Cam5  缺失
组质量：NEEDS_REVIEW（待质量评审）
```

人工确认后，如果结果为：

```text
Cam1  QUALIFIED  → 进入下游
Cam2  REJECTED   → 不进入下游，继续保存和审计
Cam3  QUALIFIED  → 进入下游
Cam4  QUALIFIED  → 进入下游
Cam5  缺失       → 保留缺路事实
组质量：QUALIFIED_WITH_EXCLUSIONS（保留部分视图后可进入标注）
```

单个坏相机不应自动否定整个组；只有人工确认没有足够的 `QualityReview.QUALIFIED` 视图支持事件时，
才将组判为 `REJECTED`。

## 6. REJECTED 行为与删除边界

第一版复用逐 `ClipAsset` 的 `QualityReview.REJECTED`，不新增持久化 `EXCLUDED` 状态：

- `REJECTED` 视图仍保留原视频、绑定关系、原因、操作人、时间和审计记录；
- `REJECTED` 视图不进入后续正式动作标注、Review、Cleaning 准入或训练数据投影；
- 组详情默认聚焦 `QualityReview.QUALIFIED` 视图，但允许有权限的用户查看 `REJECTED` 视图及其证据；
- 若后续需要重新评审，应使用受控的重新评审动作，不覆盖历史结论；
- 物理删除仍是独立的管理员/保留策略生命周期操作，使用现有确认机制，不作为质量评审的快捷动作。

## 7. 配药动作标注

### 7.1 进入方式

组质量为 `QUALIFIED` 或 `QUALIFIED_WITH_EXCLUSIONS` 且已选定锚点后，从详情进入带有 `CaptureGroup`
上下文的标注工作台。现有 Annotation Workbench 仍是正式标注编辑入口；CaptureGroup 详情不复制一套编辑器。

锚点视频的人工 `QualityReview.QUALIFIED` 继续触发 V1.24 的自动桥：自动创建并分配唯一
`AnnotationTask`，再由受分配人提交人工 `AnnotationRevision`。其他支持视图的 `QUALIFIED` 只提供证据，
不重复创建同一事件的任务；该锚点路由是 V1.25 实现前必须冻结的版本化契约，不改变 V1.24 单视频路径。

### 7.2 第一版交互

- 默认显示一个聚焦相机，提供相机切换；
- 可选显示少量缩略视图用于对照，不强制同时加载 3–5 路完整视频；
- 只有同步证据可信时，才提供共享逻辑播放位置；
- 同步证据不足、声明未验证或时间误差不确定时，显示明确警告，并允许逐路查看，不把多个时间轴伪装成
  已同步；
- 每路可以独立显示 MediaPipe 21 点/骨架 Overlay；`handedness` 和候选只作辅助信息；
- 对一次配药事件采用“多视图辅助的锚点标注（multi-view-assisted anchor annotation）”：正式动作标注和 AnnotationRevision 仍由锚点 ClipAsset
  所拥有，视图集合挂在事件上下文下，不要求为每路相机重复创建相同动作标签；这不是新的组拥有 revision；
- 标注人可以用不同视图确认同一时间段，但正式动作标签仍来自人工 AnnotationRevision，而不是 Candidate。

如果未来真实场景证明“同一事件内不同相机的动作边界必须不同”，再以明确用例和契约评估视图级补充标注；第一版
不预建五套独立标签编辑器。

## 8. Review

Review 以锚点 ClipAsset 所拥有的正式 AnnotationRevision 为对象，并带有 CaptureGroup 上下文，而不是
把每个相机当成独立动作样本。复核界面应保留：

- CaptureGroup 标识和 `QualityReview.QUALIFIED` / `REJECTED` 视图清单；
- 当前正式动作时间段和标签；
- 可切换的有效相机画面；
- 被 `REJECTED` 视图的只读原因和审计入口；
- Review 通过、退回或其他现有正式结果。

Review 不把自动提示、MediaPipe 候选或 handedness 当作人工通过依据，也不因存在一条 `REJECTED` 视图就自动退回整组。

## 9. Cleaning

Cleaning 要回答：“这个已标注的配药事件，是否真的可用于下游训练？”它应同时检查：

1. 正式 AnnotationRevision 是否存在且内容完整；
2. Review 是否已通过；
3. 是否有至少一条人工 `QualityReview.QUALIFIED` 视图，并由人工确认现有画面足以支撑所需动作；
4. 哪些视图为 `REJECTED`、缺失或仍有阻断，以及这些事实是否可追溯；
5. 参与视图的媒体完整性和必要技术事实；
6. 手部/画面证据是否足以辅助判断；候选仍是辅助证据，不能成为 Ground Truth。

建议结果语义：

- `PASS`：正式标签、Review、媒体和人工确认的视图事实均满足，可进入“可用数据”投影；
- `WARNING`：存在需要处理或明确批准例外的非致命问题，不应直接当作训练可用；
- `REJECT`：当前事件缺少足够有效视觉证据或存在实质完整性问题；保留组、视图和原因，不删除对象。

单个相机的坏画面只影响该视图；若剩余 `QualityReview.QUALIFIED` 视图仍能观察完整配药动作，Cleaning
不应拒绝整个组。关键动作是否被遮挡必须在正式动作区间存在后由人工 Review/Cleaning 判断，不能由预检查自动断言。
只有在剩余视图不足以支撑正式动作判断时，才将组判为不可用。

## 10. 部分坏数据与时间段处理

第一版默认采用整路视图处理，并复用 `QualityReview.REJECTED`：

```text
坏视图 → `QualityReview.REJECTED` 并记录原因 → 其余 `QUALIFIED` 视图继续
```

不默认建设片段级清洗编辑器。只有出现一个明确且无法用现有 Annotation 时间段、Review 或 Cleaning 事实处理的
真实工作流，例如同一路视频只有中间一段损坏且保留前后段对组结论有实际价值，才单独评估一等片段排除、审计和
下游投影语义。没有该证据前，不增加时间轴剪切、遮罩、批量修复或派生视频编辑能力。

## 11. 数据集安全

页面不提供 train/validation/test 切分控件，也不创建 DatasetVersion 或数据集构建器。但所有进入后续投影的
记录必须保留不可变的 `CaptureGroup` 身份和事件关联：

- 数据切分的最小防泄漏单位应是 CaptureGroup/配药事件，而不是单路视频；
- 同一事件的所有相机不得被拆到不同 split；
- `QualityReview.REJECTED` 视图不能脱离原组作为独立样本重新进入下游；
- 导出或训练投影若需要 split，应由后续版本化规则在服务端/离线工具中按组执行，并保留规则版本。

## 12. 与当前工作区的衔接

本文不改变当前实现边界：

- `VideoManagementView` 继续是当前单路视频列表入口；本草案不新增 CaptureGroup 导航或列表路由；
- `AssetDetailView` 继续负责单个视频的授权播放、人工质量判断、手部候选和现有工作流事实；不在其中嵌入多相机
  组编辑器；
- 当前 `QualityReview` 是单个视频资产的人工质量结论。未来组视图结论不能把单视频 `QUALIFIED` 直接解释为整组
 通过，必须先有明确的组级查询/命令契约；
- 当前 Annotation Workbench 负责正式 AnnotationRevision、Review 和后置清洗事实。多相机第一版应增加组上下文和
 视图切换边界，而不是把编辑逻辑复制到详情页；
- 当前 post-cleaning 是按正式 revision 的受控结果。组级 Cleaning 只有在有效视图集合、`QualityReview` 事实和
  人工组判断具备正式契约后才能启用；不预建最低视角/覆盖规则引擎；
- MediaPipe Candidate 始终是辅助候选观测，不是人工标注、Review 或 Cleaning 的替代物。

## 13. 有意延后

- 当前 P0 的 CaptureGroup/Session 列表、详情和多路同步播放入口；
- 以图像内容、ReID、三维融合或 handedness 推断跨相机身份；
- 未经验证同步证据的共享时间轴、自动对齐和跨相机帧匹配；
- 复杂多路视频编辑器、剪切、遮罩和片段级排除；
- 批量质量评审、自动质量策略、批量预标注和新的后台队列；
- 在详情页编辑正式标注、重复创建每路动作标签或自动把 Candidate 转成 Ground Truth；
- DatasetVersion、数据集构建、LeRobot 和页面内数据切分控制；
- 用删除代替质量排除，或因单个坏相机自动拒绝整个事件。

未来实现前的最小前置条件是：冻结 CaptureGroup/视图集合查询事实、人工 `QualityReview` 与组判断的审计契约、
同步证据语义，以及 Annotation/Review/Cleaning 如何引用锚点和 `QualityReview.QUALIFIED` 视图的版本化契约。
本文不因这些前置条件存在而授权代码实现。
