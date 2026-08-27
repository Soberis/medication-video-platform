# CaptureGroup 多机位接收与数据闭环最小技术设计（草案）

> 状态：DRAFT / FUTURE ADDITIVE
> 本文是面向后续版本的技术设计，不授权当前 V1.24 的代码、数据库迁移、OpenAPI、Worker 或运行时行为变更。
> V1.24 仍以单视频闭环为唯一正式基线；本文只定义最小的多机位增量方向。

本文基于 [V1.24 需求基线](../requirements/baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)、[当前手部数据闭环设计](video-hand-pose-mvp-technical-design.md)、[external-video-ingest-v1 契约](../../contracts/schemas/external-video-ingest-v1.schema.json) 和 [最小训练数据导出说明](../../tools/minimal-export/README.md)。已有 V1.22 多视角、CaptureSync 和同步训练数据文档仍是历史草案或延后设计，不在本文重新启用。

## 1. 设计结论

最小方案不创建第二套 CaptureGroup、Camera 或 Segment 实体：

~~~
Project
└─ CaptureGroup = 现有 CaptureSession（capture_session_id）
   └─ CameraSetSnapshot
      └─ Camera / View = 快照成员 + CameraStream
         └─ ClipAsset
~~~

CaptureGroup 是一次明确声明的配药事件的逻辑读取名称，真值锚点仍是现有 CaptureSession。上游提供的 capture_group_key（例如 external-video-ingest-v1 manifest 中的键）只作为显式归组证据，通过已有 client_file_id 等接入事实关联；不能由文件名、上传顺序、服务器接收时间或时间接近度推断归组。

后续多机位流程只增加组级读取和受控的组上下文，不替换当前逐视频的媒体、质量、标注、Review、Cleaning 和导出事实：

~~~
显式分组事实
→ CaptureSession / CameraSetSnapshot / CameraStream / ClipAsset
→ 每路 Media Probe
→ 每路 MediaPipe Candidate
→ 自动证据建议
→ 人工视图质量与排除
→ 一个锚点视图上的正式 AnnotationRevision
→ Review
→ Cleaning
→ 当前可用数据资格投影
~~~

## 2. 现有模型复用关系

| 业务概念 | 复用的现有事实 | 最小设计含义 |
| --- | --- | --- |
| 一次采集事件 | CaptureSession / capture_session_id | 直接作为 CaptureGroup 的实现锚点，不新建 group 表 |
| 预期相机集合 | CameraSetConfiguration、CameraSetSnapshot | 在事件创建时冻结预期机位和配置版本 |
| 相机成员 | CameraSetSnapshotMember | 保存本次事件中预期的稳定相机成员、编号、角色和安装信息 |
| 相机视图 | CameraStream + snapshot member | 一个机位在本次事件中的接收和媒体绑定事实 |
| 视频资产 | ClipAsset | 不可变媒体身份、摘要、入库状态和软删除事实 |
| 手部候选 | CandidateAnnotation 及其观测 | MediaPipe 辅助几何；不产生正式动作标签 |
| 人工质量 | QualityReview（按 ClipAsset） | 继续保存人工质量结论，不能由自动证据替代 |
| 正式动作标注 | AnnotationTask、AnnotationRevision | 复用现有区间标注和冻结 schema；第一版以一个锚点视图承载 |
| 人工复核 | AnnotationReview | APPROVED 仍是正式 Review 闸门 |
| 清洗 | CleaningRun、CleaningResult | PASS / SUCCEEDED 仍是清洗闸门 |
| 可用数据 | 现有训练数据资格投影和 minimal-export | 不创建 DatasetVersion，不改造当前 Parquet 契约 |

当前这些对象的所有权和跨模块访问规则保持不变。CaptureGroup 状态、组质量和视图汇总在第一版应是只读投影或查询结果，不成为一套与现有事实竞争的新真值。

## 3. 最小组接收状态

组的预期机位必须来自冻结的 CameraSetSnapshot 或显式上游 manifest 的 expected_camera_numbers。received_camera_count 只表示当前已观测到的技术计数，不单独证明组已经结束。

未来组读取语义定义为：

| 状态 | 必要条件 |
| --- | --- |
| RECEIVING（接收中） | CaptureGroup 身份和预期机位已确定，但尚未收到可信的组结束事实，或仍有预期机位没有唯一绑定且已入库的 ClipAsset |
| COMPLETE（完整） | 已收到并接受上游/边端的显式组完成事实，且每个预期机位都有唯一 CameraStream，所绑定的 ClipAsset 均未删除并处于 INGESTED |
| INCOMPLETE（不完整） | 已收到显式组结束事实，并明确记录一个或多个预期机位缺失、不可用或未能入库；缺失编号和原因必须保留 |

显式结束事实可以来自版本化 manifest 的完成标记、边端发送的组完成消息或等价的不可变接收事实。当前 external-video-ingest-v1 已有预期相机声明，但如果运行环境没有组完成标记，就不能安全地区分“还在接收”和“已不完整”；后续实现前需要先补充契约语义。本文不修改该契约。

超时最多只能产生 stale / needs attention 这样的运维提示，不能单独把组判为 INCOMPLETE。迟到视频必须使用原有明确的分组键和受控接入路径，不能静默并入其他组，也不能覆盖已经审查的事实。当前 CaptureSession 的 OPEN、ASSEMBLING、COMPLETE、INCOMPLETE、CLOSED 存储状态保持不变；上述三态是未来组视图语义，不是本轮状态机修改。

接收闭合（`COMPLETE` / `INCOMPLETE`）只是接收事实，不是质量放行结论；一个已明确闭合但缺路的
组，在满足最低有效视图和覆盖规则并完成人工确认后，仍可投影为 `USABLE_WITH_EXCLUSIONS`。
反过来，`COMPLETE` 也不代表组已自动可用。

## 4. 同步只表达证据可信度

本文不承诺帧级或像素级同步，也不引入 CaptureSync、校准或跨相机帧匹配。组级同步证据只使用已有事实：

- CameraStream 的 record_start_utc、time_offset_ms、sync_group_id 和 declared_sync_status；
- external manifest 的 captured_at、timezone 及其来源；
- 每路 Media Probe 的媒体时间戳、时长和解码事实；
- 接收顺序和服务器时间只能作为审计事实，不能替代采集时间。

未来只读汇总可使用以下三档：

| 评估 | 含义 |
| --- | --- |
| RELIABLE（可靠） | 来源明确提供共同时间基准或可验证映射，相关流的时间证据一致；仍不等于已经完成帧对齐 |
| APPROXIMATE（近似） | 只有采集时间、起始时间或偏移量等证据，能够辅助人工对照但存在不确定误差 |
| UNRELIABLE（不可靠） | 时间缺失、互相冲突、来源不明，或无法解释偏移关系 |

declared_sync_status 是来源声明，不应被平台直接提升为验证后的同步结论。没有可靠证据时，标注工作台使用锚点视频自己的时间轴；其他视频只能作为未对齐参考，不自动推算 offset、不插帧、不建立共享播放位置。

## 5. 自动质量证据与建议

每路视图独立汇总以下证据：

1. ClipAsset 的摘要、未删除状态、INGESTED 状态和接入完整性；
2. Media Probe 的可读取性、解码、时长、分辨率等媒体事实；
3. MediaPipe 的运行状态、候选数量、覆盖统计和检测结果；
4. CameraStream 与 CaptureGroup、CameraSetSnapshot 的显式绑定；
5. 组的缺失机位和同步证据质量。

系统可以输出 LIKELY_USABLE、NEEDS_REVIEW、LIKELY_UNUSABLE 三类建议及可解释原因。建议只服务于排序、提示和人工决策，不写入 QualityReview，也不能自动放行或拒绝组。

特别是 MediaPipe NO_HAND_DETECTED、候选数量为零、handedness 方向错误或 AI 运行失败，都只是证据。它们可以促使人工查看，但不能直接证明视频不可用、不能创建正式标签，也不能阻断其他视图继续处理。

组级建议只汇总“缺哪些视图、哪些视图存在技术问题、证据是否足够”，不得把已有视图数量大于零解释成质量通过。最低有效视图数量和必要覆盖应来自已批准的 CameraSet / 项目规则；规则缺失时只能建议人工确认。

## 6. 人工质量与排除

### 6.1 视图状态

未来产品可显示三种视图语义：

| 视图状态 | 与当前 QualityReview 的最小关系 |
| --- | --- |
| USABLE（可用） | 由人工确认；仅当该决定同时符合单视频语义时才可映射现有 `QUALIFIED`，不代表整组通过 |
| NEEDS_REVIEW（需复核） | 保持现有 NEEDS_REVIEW，不进入正式放行 |
| EXCLUDED（排除） | 未来组范围的纳入决定：该视图不参与本组下游但保留 ClipAsset；不能隐式映射为 V1.24 单视频 `QualityReview.REJECTED` |

EXCLUDED 是未来组范围的数据使用决定，不是删除，也不是把现有单视频 `QualityReview` 改写为
`REJECTED`。被排除视频、CameraStream、原因、操作人和版本仍保留，具备重新评审和审计条件。
不能把技术 `quality_status=REJECT`、`NO_HAND_DETECTED` 或自动建议直接当成人工排除；若需要
组范围的视图纳入决定，必须先冻结版本化契约，现有 V1.24 单视频质量语义保持不变。

如果未来需要表达“该资产在一般单视频流程中合格，但在本组中暂不采用”而不复用 REJECTED，必须另行冻结一个组范围的视图纳入决定；不能在实现时悄悄改变现有 QualityReview 的含义。第一版不提前创建第二套质量实体。

### 6.2 组状态

组状态由服务端根据预期机位、显式接收闭合、人工视图决定和最低有效视图规则投影，不由前端自行计数：

| 组状态 | 推导条件 |
| --- | --- |
| USABLE（可用） | 组已完整闭合，所需视图均为人工 USABLE，且没有未解决的缺失、排除或覆盖问题 |
| USABLE_WITH_EXCLUSIONS（排除部分视图后可用） | 组已明确闭合，至少一个视图人工 USABLE，存在明确缺失/排除/技术不可用视图，但仍满足已批准的最低有效视图和覆盖规则 |
| NEEDS_REVIEW（需复核） | 存在 NEEDS_REVIEW、未解决缺失、组未闭合或时间/覆盖证据不足 |
| REJECTED（拒绝） | 人工确认没有足够有效视图支撑目标动作，或所有可用视图都被人工排除；不能由 NO_HAND_DETECTED、单路缺失或自动建议单独触发 |

REJECTED 在最小方案中是组结果投影，不是把某一路的 QualityReview 误当成组结论。若未来需要独立的组级人工拒绝理由，再做单独契约设计。

现有 `CaptureSession.quality_status` 仍只是技术汇总证据，不是组级人工质量或训练资格；单路
stream 的 `REJECT` 只能保留为该视图事实和建议，组是否拒绝仍由剩余视图、最低规则和人工确认决定。

## 7. 正式动作标注：复用区间模型和一个锚点

现有 AnnotationRevision 的 segments 已经使用 [start_ms, end_ms) 区间和人工 label，足以表达第一版的一次逻辑动作，不需要新建 Segment 实体。

最小多机位路径如下：

1. 在组中明确选择一个 annotation anchor view，优先使用 CameraSet 已冻结的主视图角色；没有主视图时由有权限的人员选择并记录；
2. 在该锚点 ClipAsset 上沿用现有 AnnotationTask 和 AnnotationRevision，revision 的区间与标签代表 CaptureGroup 的逻辑动作；
3. 其他人工 USABLE 视图只作为同一事件的证据上下文，按 capture_session_id 关联并提供切换/对照，不重复创建同一动作标签；
4. MediaPipe Candidate 只提供每路手部几何和时间证据，正式 action label 始终来自人工 AnnotationRevision；
5. 锚点被排除后，不修改旧 revision，也不把它偷偷转移到另一视频；组进入 NEEDS_REVIEW，重新选择锚点后创建新的受控任务/revision。

这里有一个必须保留的兼容性约束：V1.24 的 QUALIFIED → AnnotationTask 自动衔接是按 ClipAsset 创建任务。如果多机位实现把每一路都直接 QUALIFIED，就会产生多个逐视频任务，不能声称已经得到一个组任务。因此，未来组路径必须在正式质量/任务衔接前确定锚点，或增加一个仅负责把现有自动衔接路由到锚点的、版本化的 group annotation intent。这个前置契约未在本文启用，也不能通过修改当前 V1.24 规则来解决。

如果多个视频没有可靠或近似的共同时间依据，AnnotationRevision 仍以锚点时间轴为正式事实，其他视频标记为参考而非已对齐证据。不要为了“多机位”预先建设多路独立编辑器或共享时间轴。

## 8. Review、Cleaning 与最小导出

现有闸门保持权威：

~~~
人工 AnnotationRevision
→ AnnotationReview = APPROVED
→ CleaningRun / CleaningResult = PASS（或现有 SUCCEEDED 语义）
→ 当前训练数据资格
~~~

组视图不能绕过 Review 或 Cleaning。排除视图在任何未来组级投影中都必须被过滤；保留视图可以继续作为事件证据。若剩余有效视图满足最低规则，组可为 USABLE_WITH_EXCLUSIONS；若锚点被排除，则必须重新标注，不得仅凭其他视图自动复用旧 revision。

当前 CleaningRun 和 minimal-export 仍是按 ClipAsset / revision 组织的 V1.24 能力，本文不把它们伪装成已经支持组级清洗，也不修改 training-data.v2 / training_data.parquet。未来实现若要输出多视图数据，至少必须保留并传递 capture_session_id（组身份）、camera_stream_id 或 camera_channel_id（视图身份），并只投影人工纳入且通过现有 Review/Cleaning 闸门的视图；具体 Parquet 字段和导出契约另行设计。

因此，单视频 CaptureGroup 可以直接走当前导出路径；多视图组在没有组范围资格投影前，只能继续使用现有的逐视频路径，不能把多个 ClipAsset 的候选行拼接后宣称为组级可用数据。

## 9. 相机身份与配置

- camera_number 是面向操作者的显示编号，按当前约定由 camera_index + 1 展示；它不是跨项目稳定身份，也不能因缺路而给其他相机重新编号；
- camera_channel_id 是已有的项目范围稳定来源相机身份，优先复用；
- camera_role、mount_position、layout_version 和配置版本已经能够表达本次事件的必要安装/布局上下文，并通过 CameraSetSnapshot 冻结；
- 第一版不建立相机资产管理系统。只有在真实部署证明现有安装事实不足时，才评估一个可选的 placement/version 元数据扩展，且应复用现有 CameraSet 版本而不是再造相机实体。

## 10. 单视频兼容性

平台本地上传已经以一个 CameraSet、一个 snapshot member、一个 CameraStream 和一个 ClipAsset 表达 expected camera count = 1；外部 manifest 只声明一路时也具有同样结构。将其读取为只有一个 View 的 CaptureGroup 后：

- 现有视频管理和授权播放不变；
- 现有 Media Probe、MediaPipe Candidate、QualityReview、AnnotationTask/Revision、Review、Cleaning 和 minimal-export 不变；
- 不增加组级任务、不引入同步要求、不改变现有状态或权限。

多机位是对这条单视频路径增加事件上下文和视图集合，而不是替换它。

## 11. 实施前需要冻结的最小契约

真正开始编码前，只需先冻结以下三件事：

1. **组完成事实**：沿用现有 manifest / edge 接收链路，补充可审计的完成或不完整声明；没有它就不能可靠产生 COMPLETE / INCOMPLETE；
2. **组读取投影**：以 capture_session_id 汇总预期相机、CameraStream、ClipAsset、候选和每路人工结果，不创建第二个 CaptureGroup 真值；
3. **锚点任务规则**：明确多机位组只有一个正式 AnnotationTask/Revision 锚点，避免逐 ClipAsset 自动任务衔接被误解释为组任务。

视图排除原因、最低有效视图/覆盖规则以及同步证据的取值来源也应随上述契约版本化。完成这些冻结前，不应新增页面、迁移、Worker 或导出字段。

## 12. 明确延后

本设计不包含：

- 3D、三角测量、标定平台、跨相机追踪或 Re-identification；
- 帧级同步、自动对齐、插帧、共享时间轴和 CaptureSync runtime；
- AI 动作识别、Candidate 自动转 Ground Truth 或自动质量结论；
- 通用多视图标注平台、复杂视频编辑器、片段级剪切/遮罩；
- 新消息总线、队列、服务、工作流引擎或数据库表；
- DatasetVersion、Dataset Platform、页面内训练/验证/测试切分和 Parquet 重设计；若未来发生 split，
  必须以 `CaptureGroup` 为最小防泄漏单位；
- 以删除代替质量排除，或因单个坏视图自动拒绝整个 CaptureGroup。
