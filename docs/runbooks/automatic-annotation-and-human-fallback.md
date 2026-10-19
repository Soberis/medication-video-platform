# 自动标注、乐观自动核准与异常重做

状态：V1.36 当前运行手册。媒体就绪后，MediaPipe 手部检测与 Action Candidate 自动派发；双合格时由 Core API 以系统身份 `AUTO_SYSTEM` 乐观自动核准，直接取得 `human-demonstration-hdf5.v2` 导出准入；人工只做异常抽检与一键【重新编辑】。策略见 [ADR-0006](../adr/0006-optimistic-auto-approval-and-exception-rework.md)。真实 Worker 推理、候选质量和生产运行仍须分别验收，不能从本文推断已上线。

## 正常路径

1. 视频完成接收和 Media Probe 后，后台扫描会为 ACTIVE 项目的有效视频自动并发派发 MediaPipe 手部检测与 Action Candidate；不要求人工任务、负责人、人工质量或打开页面。
2. MediaPipe 与 Action Candidate 独立处理，各机位等权客观观测、无主副机位。已有人工任务使用绑定 schema；无任务时使用派发时冻结的全局已发布 schema。
3. **双合格乐观自动核准**：某 CaptureGroup 所有预期机位的 Media Probe 通过、MediaPipe 检出有效手部骨骼、Action Candidate 推理成功且产出匹配合法 Schema 的候选时，Core API 在一个事务内自动准备 CaptureGroup AnnotationTask，冻结初始整组 Revision（版本 1，`annotation-document.v2`），记录 AnnotationReview（`decision=APPROVED`、`reviewed_by=AUTO_SYSTEM`），任务进入 `COMPLETED`，直接获得 `human-demonstration-hdf5.v2` 导出准入。全程无需人工逐个打开或点击确认。
4. 自动核准初始跨机位 correspondence 一律记录为单机位 `UNCERTAIN` 并给出原因；系统不做自动跨机位时间对齐，跨机位核对在人工【重新编辑】时完成。
5. **异常重新编辑**：抽检发现时序偏差、动作切分不准或漏动时，可随时点击【重新编辑】把任务打回可编辑草稿（`DRAFT`），保留已确认片段供微调；人工显式提交后生成新 Revision（版本 2）并重新完成核准。
6. 从项目列表选择记录导出；后端复验当前批准 Revision 与媒体/MediaPipe 证据。历史 Cleaning 只读展示，不再产生新的 Cleaning 事实或 recheck 结果。

保存草稿、AI 成功、打开页面都不等于最终数据；但双合格时由系统自动核准，不再走“人工显式提交、管理员人工复核”的旧前置。AI 失败、无有效手部或候选为空时绝不自动核准，保持待人工状态。

## 异常处理

| 情况 | 处理 |
| --- | --- |
| 没有动作候选 | 不触发自动核准；显示有效空结果，允许从零标注，不推断没有动作 |
| MediaPipe 成功无手 | 不触发自动核准；保留成功状态；区间内无样本时最终导出拒绝，编辑与提交仍可进行 |
| 手部/动作任务失败或未送达（如 `AI_WORKER_MESSAGE_UNDELIVERED`） | 不自动核准，进入异常/待人工；经既有 retry/attempt 或受控恢复后重跑，禁止以失败结果自动核准 |
| 查询失败 | 重试读取，不能把 job 改为 FAILED 或显示有效空结果 |
| 部分窗口失败 / 非法标签 / 越界 | 不初始化完整草稿、不自动核准；保留错误原因，允许重试或人工标注 |
| confidence 缺失 | 不伪造数值，不单独判失败；有值也不证明标签正确 |
| 候选晚到或人工已编辑 | 保留人工内容，提示新候选，禁止静默覆盖 |
| 已存在人工任务 / 任意 Revision / 创建者非 `AUTO_SYSTEM` | 自动核准幂等不覆写，直接交人工流程 |
| 租约失效 / Worker 重启 | 使用既有 retry、attempt、inbox/outbox；拒绝旧执行覆盖 |
| 抽检发现瑕疵 | 一键【重新编辑】打回 `DRAFT`，微调后显式提交生成 Revision 2 并重新核准 |
| 导出失效 | 显示具体原因，保留选择；整批拒绝，不静默跳过 |

## 触发、幂等与审计

- 自动核准由 Action Candidate 与 MediaPipe 完成事件（事务提交后 `AFTER_COMMIT`）和定时分页 reconcile 扫描共同触发，二者互为兜底；定时 reconcile 由独立的 `AutomaticOptimisticApprovalScheduler` 承载，仅在 `platform.annotation.auto-approval.enabled=true`（缺省开启）时装配。
- 单次 adopt 在一个数据库事务内原子完成（准备任务、冻结 Revision、签署 Review），任一步失败整体回滚，不产生半成品。
- 重复事件或重复 reconcile 必须幂等：已存在人工任务、任意 Revision 或任务创建者非 `AUTO_SYSTEM` 时不覆写。
- 自动核准全程以系统身份 `AUTO_SYSTEM` 记录审计字段，不伪造真人管理员或其安全上下文。测试 profile 显式关闭调度，reconcile 由测试手动触发，避免后台线程污染夹具。

## 验证与观测

使用合成数据测试：双合格自动核准（任务 `COMPLETED`、Revision 1、Review `APPROVED`、`reviewed_by=AUTO_SYSTEM`、取得导出准入）、重复事件与重复 reconcile 幂等（只有一份 Revision）、无手 / 候选失败 / 任务未送达不自动核准、重新编辑打回 `DRAFT` 后显式提交生成 Revision 2、schema 变化、重复回传和未核准不可导出。真实标签质量另用获准的独立样本评估。

记录自动核准比例、异常转人工比例、人工重新编辑比例、人工修改耗时、候选质量和失败原因。无需新增批准策略或逐字段摘要；复用视频/模型身份、schema、job/attempt 和 `AUTO_SYSTEM` 审计。
