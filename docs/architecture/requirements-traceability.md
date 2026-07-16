# 需求追踪矩阵

> 文档状态：P0 设计基线（评审稿）  
> 需求基线：`docs/requirements/baselines/V1.1.13/配药视频数据管理平台需求文档_V1.1.13.docx`  
> 总体设计：`docs/architecture/配药平台技术设计.md`  
> 最后更新：2026-07-16

## 1. 目的

本文把需求编号映射到领域模块、计划契约、数据所有权和验收证据，防止“文档中提到”被误判为“已经实现”。

追踪顺序固定为：

```text
需求 FR/NFR
→ 领域能力和业务守卫
→ OpenAPI / 事件 / JSON Schema
→ 数据库迁移和约束
→ 模块测试 / 契约测试 / 集成测试 / E2E
→ UAT 证据
```

当前仓库已完成 Sprint 0，正在进行 Sprint 1 的上传与资产纵向闭环。下表中的契约面同时包含已实现和计划能力，必须结合“当前状态”和可执行证据判断，不能仅凭接口名称推断需求已经完成。

## 2. 状态定义

| 状态 | 含义 |
| --- | --- |
| `D1` | 已映射到详细设计，但尚无可执行契约和测试证据 |
| `C` | OpenAPI/Schema 已冻结且契约测试通过 |
| `I` | 实现和模块/集成测试通过 |
| `PI` | 需求只有部分能力达到 `C`/`I`，尚未覆盖完整验收范围 |
| `UAT` | 已取得经批准的 UAT 证据 |
| `PENDING` | 关键业务或环境参数尚未批准 |
| `DEFERRED` | 明确不属于 P0 |

任何条目只有达到 `UAT` 才能称为需求验收完成。代码存在、接口可调用或单次人工演示均不能替代验收证据。

## 3. 功能需求追踪

### 3.1 数据接收与资产

| 需求 | 优先级 | 领域模块 | 计划契约面 | 主要持久化 | 最小验证 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-001 批次接收视频、元数据和事件日志 | P0 | `assetcapture` | upload init/Complete File/Complete Batch；batch 查询；`UploadCompletedV1` | upload_batch、capture_event_log、clip_asset | 同批次成功/失败混合上传；明细与汇总一致 | PI |
| FR-002 大小、摘要、媒体可读性和属性校验 | P0 | `assetcapture`、`mediaprivacy` | Complete File；media probe job/result | clip_asset、media_job、media_report | 摘要冲突、损坏容器、不可解码流不得进入标注池 | PI |
| FR-003 分片上传和断点续传 | P0 | `assetcapture` | upload init/files/multipart/part authorization/confirmation/reconciliation/Complete File/Complete Batch/abort | upload_batch、upload_file、upload_part | 标准 S3 multipart 兼容性；2GB 合成文件中断续传；重复文件、重复 part 和重复 complete 幂等 | PI |
| FR-004 元数据必填校验 | P0 | `assetcapture` | `capture-session-metadata-v1`；CaptureSession ingest | capture_session、camera_set_snapshot、camera_stream | 缺字段阻断；服务器 CameraSet 对账；原子建档 | I |
| FR-005 上传批次批量状态变更 | P0 | `assetcapture` | batch command API | upload_batch、audit_event | 部分失败返回逐项结果，不允许静默跳过 | D1 |
| FR-006 资产列表和常用筛选 | P0 | `assetcapture`、只读投影 | asset/session query API | 资产检索投影 | 规定数据量与并发下第一页 P95 不超过 3 秒 | D1 |
| FR-007 资产详情、预览和状态历史 | P0 | `assetcapture`、`mediaprivacy`、`governance` | clip/session detail；preview grant | clip_asset、asset_variant、audit_event | 只能预览有权限的批准 variant；状态历史完整 | D1 |
| FR-008 裁剪、抽帧、预览和媒体预处理 | P0 | `mediaprivacy` | media job API；`MediaJobRequestedV1`/result | media_job、asset_variant、media_report | 派生资产可追溯到 raw 摘要、配方和软件摘要 | D1 |
| FR-009 批量业务标签 | P0 | `assetcapture` | asset tag command API | asset_tag、audit_event | 幂等添加/移除；跨项目和越权操作被拒绝 | D1 |
| FR-010 隔离区 | P0 | `qualitycleaning`、`governance` | quarantine command/query | quarantine_record、approval_request | 隔离数据默认不可标注、不可导出；解除需要批准 | D1 |

当前 `I`/`PI` 证据和边界：

- FR-001：UploadBatch 初始化、不可变 UploadFile 清单、Complete Batch、VIDEO 到最小 RAW ClipAsset 的幂等绑定、严格 METADATA 解析、CaptureSession/CameraSetSnapshot/CameraStream 原子组装、媒体探测入库、项目隔离的批次/文件只读进度查询和 Vue `/uploads` 只读进度页已有 OpenAPI、Flyway、合同测试与前端测试；EVENT_LOG 索引和 UAT 尚未闭合。
- FR-002：已校验声明大小、摘要格式、Part 范围、存储实际大小、最终对象大小和流式计算的完整文件 SHA-256；MediaJob、批准配方摘要、任务/结果/脱敏死信 Schema、Transactional Outbox、RabbitMQ 单一业务队列/DLQ 和最小 Media Worker 已有可执行证据。合成 MP4 已真实完成队列消费、独立摘要复核、FFprobe 事实提取、FFmpeg 完整解码、受租约保护的结果提交、Inbox 去重、状态提交和 MediaReport 查询；Worker 心跳、grace 后失联回收、retryable 结果的 `RETRY_WAIT`/Outbox 有限退避、新 attempt/新 capability、旧结果拒绝、max-attempts 终止、不复制 lease capability 的脱敏死信路径，以及独立管理员令牌、幂等受控重放和数据库追加式审计已有合同测试。2026-07-16 的本地合成演练进一步验证了真实 broker 上的错误身份拒绝、脱敏 DLQ、管理员重放、新 attempt/Outbox/审计和最终 MediaReport 闭环；损坏媒体矩阵和 UAT 尚未完成，因此仍为 `PI`。
- FR-003：Multipart Session、短期 Part 授权、对象存储侧 Part 确认、恢复协调、Part/文件/批次进度查询、幂等 Complete File/Complete Batch，以及发起人或项目管理员 Abort 已有实现。TTL 过期会原子写入批次/文件终态、追加式审计和持久化清理任务；清理使用 claim lease、有限重试，并区分 Multipart Abort 与已完成遗留对象删除。标准 S3/SeaweedFS 8 MiB 合成 Part 已完成真实 Multipart Complete、完整文件 SHA-256、对象删除和 Abort 重放兼容验证；2GB 中断恢复、清理积压告警和 UAT 环境验收尚未完成，因此仍为 `PI`。
- FR-004：`capture-session-metadata-v1` 严格要求 capture session、device、CameraSet、布局、同步组、设备时间/时区以及每路 VIDEO/CameraChannel/index/enabled/record start/offset/sync status；缺字段和未知字段被阻断，服务器配置与视频一一绑定均经合同测试。该条达到实现态 `I`，但尚无 20 组真实授权样例的 UAT 证据。
- FR-006/FR-007 当前完成的是第一段只读能力：项目隔离的 Session/ClipAsset 列表与详情、设备/批次/相机角色/拍摄时间/当前状态筛选、不透明 cursor、无对象存储信息的安全响应，以及 Vue 列表/详情。场景、科室、隐私、标注、清洗筛选、性能基准、预览 variant、状态历史和审计日志尚未完成，因此整条仍保持 `D1`，不能宣布需求验收通过。
- 当前可执行证据位于 OpenAPI、`capture-session-metadata-v1`、`media-probe-job-v1`、`media-probe-result-v1`、`media-dead-letter-v1`、批准的媒体探测 profile、Flyway V1～V17、资产/上传终止与清理/MediaJob/Outbox/Inbox/租约恢复/脱敏死信/受控重放/追加式审计合同测试、S3 与 RabbitMQ 兼容性验证、合成上传客户端、Media Worker、Vue 页面测试和 Bruno 控制面场景；合成客户端与 Worker 已真实完成 VIDEO/METADATA/EVENT_LOG 直传、Session 组装、Outbox 投递、队列消费、探测解码、结果入库和 MediaReport 查询，并完成一次真实 DLQ 到受控重放闭环，但这些证据不能替代 2GB/弱网/失联恢复性能测试、清理告警演练、损坏媒体矩阵或 UAT。

接口细化说明：需求基线中的 `/upload/chunk` 表示分片上传能力，实际契约采用 Part 授权、客户端预签名直传 S3、服务端确认/协调；需求基线中的 `/upload/complete` 分为单对象 Complete File 和批次清单 Complete Batch。该映射保持 FR-001～FR-003 的业务能力，同时避免 Core API 代理大视频字节。媒体探测、Abort 和过期清理代码路径已经闭合；EVENT_LOG 索引、2GB 中断恢复、清理运行告警和 UAT 环境验收仍未闭合，因此相关需求仍为 `PI`。

测试入口说明：正式生产入口仍是 RK3588 等边端自动上传。P0 已建立一个复用相同协议、仅处理合成数据的自动化上传客户端，作为开发、回归、CI 和批量造数证据；客户端稳定后可以增加仅在 local/dev/test 启用的 Web 受控手动导入，用于人工演示和交互检查。Web 测试入口不属于 2GB 生产上传验收证据，不能替代 FR-003 的边端中断恢复、容量和 UAT 验证；三种入口不得通过私有接口、直接插库或绕过后端权限和状态守卫。

### 3.2 标注与复核

| 需求 | 优先级 | 领域模块 | 计划契约面 | 主要持久化 | 最小验证 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-011 标注项目和标签版本 | P0 | `annotationreview` | annotation projects；label schemas | annotation_project、label_schema、label_definition | 同一 session 可进入不同项目；结果绑定不可变 schema digest | D1 |
| FR-012 任务拆分和分配 | P0 | `annotationreview` | annotation tasks/assignments | annotation_task、task_assignment | session Step 与 stream Object 范围不混淆；重复分配受唯一约束保护 | D1 |
| FR-013 标注任务状态管理 | P0 | `annotationreview` | task command API；task events | annotation_task | 只允许状态机定义的迁移；并发提交产生 409 而非覆盖 | D1 |
| FR-014 标注指南绑定 | P0 | `annotationreview` | guideline versions | annotation_guideline_version | 提交 revision 可追溯到实际使用的指南摘要 | D1 |
| FR-015 标注进度统计 | P0 | `annotationreview`、只读投影 | annotation progress query | annotation_progress_projection | 与任务事实表对账一致；统计不反向写业务表 | D1 |
| FR-016 基础复核 | P0 | `annotationreview` | review command API | review_record、annotation_revision | 复核人、时间、结论、理由完整；复核员不能静默改原 revision | D1 |
| FR-017 退回重标并保留历史 | P0 | `annotationreview` | rework command | annotation_task、annotation_revision、review_record | 返工创建新 revision；旧 revision 不可覆盖 | D1 |
| FR-018 基础一致性检查 | P0 | `annotationreview`、`qualitycleaning` | validation job/result | annotation_validation_result、cleaning_result | 时间越界、非法类别、隐私缺失等规则有稳定错误码 | D1 |
| FR-019 抽检比例和全检策略 | P1 | `annotationreview` | sampling policy | review_sampling_policy | 规则版本化并能解释任务为何被抽中 | DEFERRED |
| FR-020 冲突标注对比 | P1 | `annotationreview` | comparison query | annotation_comparison | 同源 revision 对齐且不混用不同 schema | DEFERRED |
| FR-021 标注质检报告 | P1 | `annotationreview` | quality report API | annotation_quality_report | 指标口径、分母、时间窗和 schema 版本明确 | DEFERRED |

### 3.3 清洗、隐私和数据集

| 需求 | 优先级 | 领域模块 | 计划契约面 | 主要持久化 | 最小验证 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-022 创建清洗任务 | P0 | `qualitycleaning` | cleaning runs | cleaning_run、cleaning_rule_set | 输入筛选条件冻结；重跑产生新 run | D1 |
| FR-023 视频质量自动检测 | P0 | `mediaprivacy`、`qualitycleaning` | media metrics/result | media_report、cleaning_result | 指标算法、阈值、软件摘要和输入摘要可追溯 | D1 |
| FR-024 完全重复和近重复检测 | P0 | `qualitycleaning` | duplicate detection job/result | duplicate_group、cleaning_result | SHA-256 完全重复与视频指纹近重复分开报告；不自动物理删除 | D1 |
| FR-025 隐私风险标记 | P0 | `mediaprivacy` | privacy assessment/review | privacy_assessment、deidentify_run | 人脸、屏幕、工牌、文本和语音风险有人工复核路径 | D1 |
| FR-026 清洗结果人工确认 | P0 | `qualitycleaning`、`governance` | cleaning review/override | cleaning_result、admission_override、audit_event | 自动 reject 不等于删除；override 有批准人、理由和版本范围 | D1 |
| FR-027 创建数据集版本 | P0 | `datasetexport` | dataset versions | dataset_version | 选择规则、标签/清洗规则、创建人和时间冻结 | D1 |
| FR-028 数据集准入 | P0 | `datasetexport` | dataset validation | dataset_member_session、dataset_member_stream | 未复核、未放行、警告未批准或摘要变化均阻断冻结 | D1 |
| FR-029 split 和防泄漏 | P0 | `datasetexport` | split policy/result | dataset_split_assignment | 同一 leakage group 不跨 split；冲突必须失败而非自动降级 | D1 |
| FR-030 MVP 必需导出 | P0 | `datasetexport` | export jobs | dataset_export、export_artifact | manifest、action segments、COCO、schema、split、README 和 SHA256SUMS 一致 | D1 |
| FR-031 数据集冻结 | P0 | `datasetexport`、`governance` | freeze command/approval | dataset_version、approval_request | FROZEN 后成员、revision、split 和规则不可修改 | D1 |
| FR-032 扩展导出格式 | P1/P2 | `datasetexport` | export profiles | export_profile | YOLO/CVAT/Parquet 等逐格式建立契约和 Golden 文件 | DEFERRED |

## 4. 非功能需求追踪

| 编号 | 基线要求 | 设计归属 | 验收方法 | 当前状态 |
| --- | --- | --- | --- | --- |
| NFR-PERF-001 | 单文件至少支持 2GB，上传可恢复 | `assetcapture`、对象存储 | 2GB/边界大小合成文件；断网、重传、重复 complete 和过期 upload 测试 | D1 |
| NFR-PERF-002 | 常用列表第一页 3 秒内返回 | 查询投影、PostgreSQL | 冻结数据规模、过滤组合和并发后统计服务端 P95；浏览器时间单列 | PENDING |
| NFR-PERF-003 | 脱敏视频和缩略图可预览 | `mediaprivacy`、Web | 指定浏览器矩阵下验证解码、首帧、seek 和多路播放 | PENDING |
| NFR-PERF-004 | CVAT 完成后五分钟内同步 | `integration`、`annotationreview` | 从 CVAT 完成时间到平台 revision 可见时间的 P95；故障重试单列 | D1 |
| NFR-OPS-001 | 清洗、导出等异步执行并可查进度 | 各作业所属模块 | 状态、心跳、失败原因、取消和重跑测试 | D1 |
| NFR-OPS-002 | 所有后台任务可重跑、可取消 | 作业协议 | 请求取消、运行中协作取消、完成后取消和重复取消 | D1 |
| NFR-SEC-001 | 标注员默认不能访问 raw | `iamproject`、`mediaprivacy` | RBAC+ABAC 正反向权限矩阵和预签名 URL 越权测试 | D1 |
| NFR-SEC-002 | 传输和存储加密 | 部署与对象存储 | TLS 配置、密钥引用、静态加密和轮换演练 | PENDING |
| NFR-SEC-003 | 高风险操作可审计 | `governance` | 原始访问、修改、删除、导出、审批和权限变更完整性测试 | D1 |
| NFR-MAINT-001 | 标签、清洗和准入规则版本化 | `annotationreview`、`qualitycleaning`、`datasetexport` | 冻结版本不可覆盖；旧数据可重建 | D1 |
| NFR-MAINT-002 | 提供迁移、备份和恢复 | Flyway、部署设计 | 空库迁移、升级迁移、PITR、对象恢复和一致性核验 | PENDING |

`PENDING` 项必须在 UAT 计划冻结前补充数据规模、并发、浏览器、RPO/RTO、对象存储产品和医院网络条件，不能在代码中写成未经批准的常量。

## 5. 科学性与验收口径

### 5.1 系统闭环与模型效果分离

- 20 组样例只能验收上传、标注、清洗、冻结和导出闭环。
- Step/对象标签质量必须使用独立抽样、双人标注或盲审证据。
- 模型泛化、临床正确性和业务 SOP 合规不属于 P0 平台验收结论。
- “隐私漏检为 0”应解释为批准导出样本经最终人工检查后观察到 0 个漏检，不能外推为总体风险绝对为零。

### 5.2 AI 试点评估

AI 启用决策前必须冻结：

- 主要终点：单位视频时长的人工处理时间；
- 质量非劣界值：由业务、标注和算法负责人批准；
- 最小有意义效率提升；
- session 级聚类抽样和置信区间方法；
- camera role、遮挡、分辨率和操作者经验分层；
- 失败样本和退出样本的处理规则。

“显著下降”不得只依据平均值或单次演示判断。

### 5.3 需求基线归一化决策

V1.1.13 是不可覆盖的业务需求基线。下表记录 TD-V1.3 的实现归一化口径，不修改原始 DOCX；如果涉及业务范围、验收标准或责任边界变化，仍须发布经批准的新需求版本。

| V1.1.13 表述 | TD-V1.3 实现口径 | 原因 |
| --- | --- | --- |
| 通用 `confidence` | 人工标注使用明确的确定性/不确定性字段；AI 分数必须记录任务、来源和语义版本 | 不同来源的分数不可直接比较 |
| 秒或 0.01 秒粒度 | UI 可显示秒，小数输入在边界转换；存储和契约统一使用整数毫秒 | 避免浮点累计误差和跨语言歧义 |
| 可调整的 `camera_id` | 内部使用不可变 `CameraChannel` 标识；显示别名按版本维护 | 保留历史引用和数据集可复现性 |
| MD5/SHA256 | 内容摘要统一使用 SHA-256；MD5 仅作遗留输入比对；低熵业务标识使用租户级 HMAC 或令牌 | 避免弱摘要和字典反推风险 |
| 清洗后排除或删除 | 逻辑排除、隔离与物理删除分开；物理删除受保留策略、审批和审计约束 | 保证可追溯和合规处置 |
| 默认 70/15/15 划分 | 小样本验收采用 pilot/holdout；正式比例由样本设计批准，并按 session/操作者/批次等泄漏组隔离 | 固定比例不等于科学抽样 |
| 隐私漏检为 0 | 仅表示批准样本经最终人工检查后观察到 0 个漏检，不外推为总体风险绝对为零 | 避免统计结论越界 |
| 护士配药视频默认无敏感信息 | 未经评估的 raw 视频先按待隐私评估处理；确认不含人脸、声音、工牌、屏幕、标签或环境标识后可按批准规则降级 | 拍摄对象不是患者也不代表视频天然无个人或机构敏感信息 |

## 6. 变更规则

1. 需求文档版本变化时，先增加或更新本矩阵，再修改技术设计。
2. API 变化必须同步 OpenAPI 和契约测试。
3. 事件变化必须同步 JSON Schema 和兼容性测试。
4. 数据库变化必须通过唯一 Flyway 目录提交。
5. 条目状态升级必须附可定位的测试或 UAT 证据；禁止手工把整列标为完成。
6. 需求取消、降级或延期必须有批准记录，不得仅从实现中删除。
