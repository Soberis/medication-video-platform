# ADR 0006：乐观自动核准与异常重做策略（Optimistic Auto-Approval & Exception-based Rework）

日期：2026-10-10  
状态：当前需求决策；取代 ADR 0004 必须人工显式提交整组 Revision 并由管理员复核的前置要求。  
依据：[V1.36](../requirements/baselines/V1.36/配药视频数据处理平台需求文档_V1.36.md)。

## 决策

在配药视频自动化生产主链中，系统采用**“默认自动核准合格数据，人工按需重新编辑处理异常”**的生产模式：

1. **乐观自动核准（Optimistic Auto-Approval）**：
   - 当 CaptureGroup 处于 ACTIVE 项目，且所有预期机位的 Media Probe 媒体检查通过、MediaPipe 手部骨骼检测成功（检出有效手部骨骼样本）、Action Candidate 模型推理成功且产出匹配合法 Schema 的动作候选时；
   - 系统自动准备 `AnnotationTask`，将当前动作候选自动冻结为不可变 `annotation-document.v2` 的初始整组 Revision（版本 1）；
   - 系统自动记录 AnnotationReview，`decision` 设为 `APPROVED`，`reviewed_by` 记录为 `AUTO_SYSTEM`；
   - 任务状态直接进入 `COMPLETED`，该 CaptureGroup 自动达到 HDF5 导出准入资格。整个过程无需人工逐个点击确认。

2. **异常介入与重新编辑（Exception-based Rework）**：
   - 质检人员或算法专家在工作台查看已完成视频时，若肉眼抽检发现时序偏差、动作切分不准或漏动，可随时点击【重新编辑】；
   - 重新编辑将任务打回为可编辑草稿（`DRAFT`），保留现有已确认动作片段供人工微调；
   - 人工微调完成后，显式点击提交，生成新 Revision（版本 2）并重新完成核准。

3. **失败与降级守卫**：
   - 若 Action Candidate 出现模型执行失败、超时或无有效候选，或 MediaPipe 未检出有效手部骨骼样本，系统**绝对禁止**触发乐观自动核准；
   - 此时保持未完成或异常警告状态，保留为待人工从零标注或补充处理。

4. **架构与模型兼容**：
   - 保留 `CandidateAnnotation` -> `AnnotationRevision` -> `AnnotationReview` -> `HDF5 Export` 的不可变领域实体与只读事实隔离边界；
   - 不增加额外的冗余状态枚举，复用 `APPROVED` 与 `COMPLETED` 语义；
   - 保留完整 provenance 追溯链，确保导出 HDF5 示教数据的版本历史清晰透明。

5. **实现约束与幂等守护**：
   - 自动核准由 Core API 内部的 `AutomaticOptimisticApprovalService` 承担，触发来源为 Action Candidate 与 MediaPipe 完成事件（事务提交后）以及定时分页 reconcile 扫描，二者互为兜底；
   - 定时 reconcile 由独立的 `AutomaticOptimisticApprovalScheduler` 承载，仅在 `platform.annotation.auto-approval.enabled=true`（缺省开启）时装配；`Service` 本身始终可装配，供事件、程序与测试直接调用。测试 profile 显式置为 `false`，使后台调度不与测试夹具/清理并发，reconcile 逻辑由测试手动触发验证；
   - 单次 adopt 在一个数据库事务内原子完成（准备任务、冻结 Revision、签署 Review），任何一步失败整体回滚，不产生半成品；
   - 重复事件或重复 reconcile 必须幂等：已存在人工任务、已有任意 Revision、或任务创建者非 `AUTO_SYSTEM` 时不覆写，直接交由人工流程；
   - 自动核准全程以系统身份 `AUTO_SYSTEM` 记录审计字段，不伪造真人管理员或其安全上下文；
   - 跨机位动作对应初始一律记录为单机位 `UNCERTAIN` correspondence 并给出原因（"单机位自动采纳，多机位对应待人工确认"），系统不做自动跨机位时间对齐，避免时间偏移错配；跨机位核对在人工【重新编辑】时完成。

## 运行与运维边界

- 自动核准是 Core API 内建能力，不依赖外部脚本或人工触发；历史的命令行“自动采纳/清理无效视频”脚本仅作为开发期运维工具，不属于产品功能。
- 产品界面不提供直连数据库的批量清理、批量重试或“唤醒分析”等运维操作；这些能力仅保留在开发服务器中间件与命令行工具中，供隔离的开发/合成数据环境使用。
- 若未来需要在产品界面内提供“舍弃/删除整个采集组”，必须新增正式的组删除 API（带权限、审计、级联与对象存储清理），不得复用开发期直连脚本。

## 依据与权衡

- **吞吐与人效**：在大规模离线视频处理场景下，人工逐个点开、逐个点击确认成为最大吞吐瓶颈；对于算法检测置信度高且手部完备的标准样本，默认自动核准可将人效释放数百倍。
- **管理例外**：质检人员的精力从“机械式给合格视频点确认按钮”转向“针对边缘案例与疑难样本的抽检与重新编辑”，符合“大道至简、全自动化”的工业实践。
- **安全与纠错闭环**：通过保留一键【重新编辑】与回退草稿能力，系统确保任何时候发现数据瑕疵均可无缝返工纠偏，不破坏最终模仿学习数据集的纯净度。

## 相关决策

- [ADR 0004](./0004-automatic-annotation-approval-policy.md)：已由本决策取代。
- [ADR 0005](./0005-system-frozen-capture-group-admission.md)：系统冻结 CaptureGroup 输入准入事实保持生效。
