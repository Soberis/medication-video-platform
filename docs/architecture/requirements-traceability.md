# 需求追踪矩阵

> 文档状态：P0 设计基线（评审稿）  
> 当前需求基线：`docs/requirements/baselines/V1.3/配药视频数据管理平台需求文档_V1.3.md`
> 继承需求基线：`docs/requirements/baselines/V1.1.13/配药视频数据管理平台需求文档_V1.1.13.docx`
> 总体设计：`docs/architecture/配药平台技术设计.md`  
> 最后更新：2026-07-21

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

当前仓库已完成 Sprint 0，正在进行 Sprint 1 上传/资产纵向闭环，并已建立不会绕过默认阻断的首个人工隐私评估切片和最小数据项目/成员权限切片。下表中的契约面同时包含已实现和计划能力，必须结合“当前状态”和可执行证据判断，不能仅凭接口名称推断需求已经完成。

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
| FR-006 资产列表和常用筛选 | P0 | `assetcapture`、只读投影 | asset/session query API | 资产检索投影 | 规定数据量与并发下第一页 P95 不超过 3 秒 | PI |
| FR-007 资产详情、预览和状态历史 | P0 | `assetcapture`、`mediaprivacy`、`governance` | clip/session detail；preview grant | clip_asset、asset_variant、audit_event | 只能预览有权限的批准 variant；状态历史完整 | PI |
| FR-008 裁剪、抽帧、预览和媒体预处理 | P0 | `mediaprivacy` | media job API；`MediaJobRequestedV1`/result | media_job、asset_variant、media_report | 派生资产可追溯到 raw 摘要、配方和软件摘要 | PI |
| FR-009 批量业务标签 | P0 | `assetcapture` | asset tag command API | asset_tag、audit_event | 幂等添加/移除；跨项目和越权操作被拒绝 | D1 |
| FR-010 隔离区 | P0 | `qualitycleaning`、`governance` | quarantine command/query | quarantine_record、approval_request | 隔离数据默认不可标注、不可导出；解除需要批准 | PI |

当前 `I`/`PI` 证据和边界：

- FR-001：UploadBatch 初始化、不可变 UploadFile 清单、Complete Batch、VIDEO 到最小 RAW ClipAsset 的幂等绑定、EVENT_LOG 到不可变对象索引的幂等绑定及项目隔离查询、严格 METADATA 解析、CaptureSession/CameraSetSnapshot/CameraStream 原子组装、媒体探测入库、项目隔离的批次/文件只读进度查询和 Vue `/uploads` 只读进度页已有 OpenAPI、Flyway、合同测试与前端测试；事件负载解析不属于当前对象索引，UAT 尚未闭合。
- FR-002：已校验声明大小、摘要格式、Part 范围、存储实际大小、最终对象大小和流式计算的完整文件 SHA-256；MediaJob、批准配方摘要、任务/结果/脱敏死信 Schema、Transactional Outbox、RabbitMQ 单一业务队列/DLQ 和最小 Media Worker 已有可执行证据。合成 MP4 已真实完成队列消费、独立摘要复核、FFprobe 事实提取、FFmpeg 完整解码、受租约保护的结果提交、Inbox 去重、状态提交和 MediaReport 查询；Worker 心跳、grace 后失联回收、retryable 结果的 `RETRY_WAIT`/Outbox 有限退避、新 attempt/新 capability、旧结果拒绝、max-attempts 终止、不复制 lease capability 的脱敏死信路径，以及独立管理员令牌、幂等受控重放和数据库追加式审计已有合同测试。2026-07-16 的本地合成演练进一步验证了真实 broker 上的错误身份拒绝、脱敏 DLQ、管理员重放、新 attempt/Outbox/审计和最终 MediaReport 闭环；损坏媒体矩阵和 UAT 尚未完成，因此仍为 `PI`。
- FR-003：Multipart Session、短期 Part 授权、对象存储侧 Part 确认、恢复协调、Part/文件/批次进度查询、幂等 Complete File/Complete Batch，以及发起人或项目管理员 Abort 已有实现。TTL 过期会原子写入批次/文件终态、追加式审计和持久化清理任务；清理使用 claim lease、有限重试，并区分 Multipart Abort 与已完成遗留对象删除。2026-07-17 已在本地固定 SeaweedFS 4.29 上完成准确 2 GiB、64 MiB × 32 Parts 的双进程中断恢复：未确认 Part 经 reconciliation 找回，最终大小/完整 SHA-256、重复 Complete 和合成对象清理均通过；三 Part 兼容测试同时覆盖 SeaweedFS marker 空页回退。应用内清理积压指标、WARNING/CRITICAL/恢复判定和无重复状态跃迁已有合同测试；弱网、并发、目标 S3 产品、生产监控采集/通知路由和 UAT 环境验收尚未完成，因此仍为 `PI`。
- FR-004：`capture-session-metadata-v1` 严格要求 capture session、device、CameraSet、布局、同步组、设备时间/时区以及每路 VIDEO/CameraChannel/index/enabled/record start/offset/sync status；缺字段和未知字段被阻断，服务器配置与视频一一绑定均经合同测试。该条达到实现态 `I`，但尚无 20 组真实授权样例的 UAT 证据。
- FR-006/FR-007 当前完成的是第一段只读能力：显式项目上下文和 ACTIVE Membership 隔离的 Session/ClipAsset 列表与详情、设备/批次/相机角色/拍摄时间/当前状态/项目内文件夹/未分类筛选、不透明 cursor、无对象存储信息的安全响应，以及 Vue 授权项目入口和列表/详情。FR-008 已有媒体探测作业和报告，但尚未生成可播放 Variant。场景、科室、隐私、标注、清洗筛选、性能基准、预览 Variant、状态历史和完整审计尚未完成，因此三条均为 `PI`，不能宣布需求验收通过。
- FR-010 当前完成活动隔离的第一段：`QUARANTINE_REQUIRED` 的人工隐私评估通过 `qualitycleaning` 公开应用服务在同一事务内幂等建立 `quarantine_record` 和追加式审计；并发相同命令只产生一份评估、隔离及对应审计，数据库以项目/资产和评估来源复合外键拒绝跨项目、伪造来源或错误版本。管理员可按项目查询资产的活动隔离记录，普通用户和跨项目查询被拒绝，`NONE` 不被误判为隐私放行，后续重新评估也不会自动解除既有隔离。尚未实现隔离复核、批准解除、销毁申请，以及标注/AI/数据集各入口的统一隔离守卫，因此仍为 `PI`。
- 当前可执行证据位于 OpenAPI、`capture-session-metadata-v1`、`media-probe-job-v1`、`media-probe-result-v1`、`media-dead-letter-v1`、批准的媒体探测与人工隐私评估 profile、Flyway V1～V23、V19→V21 历史升级与约束测试、项目/成员/文件夹/视频分类、资产/EVENT_LOG 对象索引/上传终止与清理及其积压告警判定/MediaJob/Outbox/Inbox/租约恢复/脱敏死信/受控重放/追加式审计/人工隐私评估/活动隔离及并发合同测试、S3 与 RabbitMQ 兼容性验证、合成上传客户端、2 GiB 中断恢复工具及本地结果、Media Worker、Vue 页面测试和 Bruno 控制面场景；合成链路已真实完成 VIDEO/METADATA/EVENT_LOG 直传、Session 组装、2 GiB 双进程恢复、Outbox 投递、队列消费、探测解码、结果入库和 MediaReport 查询，并完成一次真实 DLQ 到受控重放闭环；人工隐私评估和活动隔离仍默认阻断且不等于放行。这些证据不能替代弱网/并发/目标 S3 性能测试、目标环境监控采集与通知演练、损坏媒体矩阵或 UAT。

接口细化说明：需求基线中的 `/upload/chunk` 表示分片上传能力，实际契约采用 Part 授权、客户端预签名直传 S3、服务端确认/协调；需求基线中的 `/upload/complete` 分为单对象 Complete File 和批次清单 Complete Batch。该映射保持 FR-001～FR-003 的业务能力，同时避免 Core API 代理大视频字节。媒体探测、EVENT_LOG 对象索引、2 GiB 本机中断恢复、Abort、过期清理代码路径和应用内积压判定已经闭合；弱网/并发、目标环境监控通知和 UAT 环境验收仍未闭合，因此相关需求仍为 `PI`。

上传入口说明：生产支持医院侧设备或系统远端上传，也支持用户在平台页面上传本机视频。开发、回归和 CI 使用复用相同协议且只处理合成数据的自动化上传客户端。三种入口不得通过私有接口、直接插库或绕过后端权限和状态守卫；平台本地上传的格式、容量、浏览器和中断恢复范围由目标环境 UAT 确认。

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
| FR-025 隐私风险标记 | P0 | `mediaprivacy` | privacy assessment/review | privacy_assessment、deidentify_run | 人脸、屏幕、工牌、文本和语音风险有人工复核路径 | PI |
| FR-026 清洗结果人工确认 | P0 | `qualitycleaning`、`governance` | cleaning review/override | cleaning_result、admission_override、audit_event | 自动 reject 不等于删除；override 有批准人、理由和版本范围 | D1 |
| FR-027 创建数据集版本 | P0 | `datasetexport` | dataset versions | dataset_version | 选择规则、标签/清洗规则、创建人和时间冻结 | D1 |
| FR-028 数据集准入 | P0 | `datasetexport` | dataset validation | dataset_member_session、dataset_member_stream | 未复核、未放行、警告未批准或摘要变化均阻断冻结 | D1 |
| FR-029 split 和防泄漏 | P0 | `datasetexport` | split policy/result | dataset_split_assignment | 同一 leakage group 不跨 split；冲突必须失败而非自动降级 | D1 |
| FR-030 MVP 必需导出 | P0 | `datasetexport` | export jobs | dataset_export、export_artifact | manifest、action segments、COCO、schema、split、README 和 SHA256SUMS 一致 | D1 |
| FR-031 数据集冻结 | P0 | `datasetexport`、`governance` | freeze command/approval | dataset_version、approval_request | FROZEN 后成员、revision、split 和规则不可修改 | D1 |
| FR-032 扩展导出格式 | P1/P2 | `datasetexport` | export profiles | export_profile | YOLO/CVAT/Parquet 等逐格式建立契约和 Golden 文件 | DEFERRED |

FR-025 当前 `PI` 证据和边界：

- V19 已建立 `privacy_assessment` 和 `privacy_finding` 所有权表；人工评估绑定 project、已
  `INGESTED` ClipAsset、平台验证 SHA-256、固定策略 key/digest、操作者和请求证据。
- OpenAPI 0.20.1 提供管理员创建、按 ID 查询、ClipAsset 当前隐私投影和活动隔离查询；未评估返回
  `UNKNOWN/BLOCKED`，所有评估结论仍为 `BLOCKED`，不会因 `NONE` 自动开放标注或导出。
- `NONE` 必须无 finding 且只进入 release review；`SUSPECTED` 必须隔离复核；`CONFIRMED`
  必须有固定 finding 并进入脱敏或隔离。重复命令在并发下保持幂等，重新评估显式 supersede 当前
  版本，旧证据和追加式审计保留；V21 以复合外键固定项目、资产、摘要、来源和版本。
- 自动隐私检测属于 P2；P0 已有 `QUARANTINE_REQUIRED` 到活动隔离记录的最小编排，但尚缺隔离复核/解除、DeidentifyRun/DEIDENTIFIED variant、PrivacyReleaseDecision、隐私区域标注/复核、预览授权和正式导出前最终人工检查，因此不能宣布
  FR-025 或隐私闭环完成。

### 3.4 V1.2/V1.3 新增与细化需求

V1.2 增加项目/文件夹、本地上传、删除和两级角色；V1.3 进一步明确内部平台账号、边端自动上传和账号管理。按纵向切片实现时同步更新 OpenAPI、迁移、测试和本矩阵状态，不预建空接口。

| 需求 | 优先级 | 领域模块 | 计划契约面 | 主要持久化 | 最小验证 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| FR-033 数据项目、成员与项目内一层文件夹 | P0 | `iamproject`、`assetcapture` | projects/memberships；asset folders；video folder move | project、membership、asset_folder、clip_asset.folder_id、audit_event | 普通用户仅见成员项目；不同视频可归入不同文件夹；删除文件夹不删除视频；跨项目移动被拒绝 | PI |
| FR-034 平台本地上传 | P0 | `assetcapture`、Web | 复用 upload/multipart/complete | upload_batch.upload_source_type 及现有上传表 | 页面选择本机视频完成上传和入库；越权或不符合上传规则的请求被拒绝 | PI |
| FR-035 视频删除 | P0 | `assetcapture`、`governance` | delete video；reference guard | clip_asset.deleted_at/deleted_by/delete_reason、audit_event | 管理员可删除无用或不合格视频；不级联删除其他视频；操作可审计；受控引用守卫生效 | D1 |
| FR-036 ADMIN/MEMBER 两级角色 | P0 | `iamproject`、`governance` | users/memberships | membership.role、audit_event | 只提供两种角色；后端角色检查和项目隔离有效 | PI |
| FR-037 平台内部账号登录 | P0 | `iamproject`、Web、`governance` | auth login/session/logout | platform_account、audit_event、HTTP session | BCrypt、统一失败提示、会话/CSRF/注销和未认证拒绝 | I |
| FR-038 管理员账号管理 | P0 | `iamproject`、Web、`governance` | users/account enablement/memberships | platform_account、membership、audit_event | 管理员创建/启停普通用户并分配项目成员；无公开注册 | D1 |

“上传后可播放”由既有 FR-007/FR-008 细化，“标定数据功能”按业务意图归一为既有 FR-011～FR-018 的视频标注闭环，不创建重复 FR。用户界面可以使用“数据标注”文案，领域/API 始终使用 annotation；相机内外参 calibration 仍不属于该新增需求。

项目内文件夹不是权限或对象存储目录：P0 只允许一层，一个视频当前最多属于一个文件夹。移动视频只更新 `folder_id`；删除文件夹只将其中视频改为未分类，不删除视频。视频删除也不得级联删除同一 CaptureSession 的其他视频。

FR-033/FR-036 当前 `PI` 证据和边界：

- OpenAPI 0.21.0、Flyway V22 和 `ProjectMembershipContractTests` 已覆盖项目幂等创建、首个 ADMIN 原子建立、ADMIN/MEMBER 成员授权/改角/移除、普通成员只见授权项目、最后管理员保护、并发单写和审计失败全事务回滚。
- 数据库只接受 `ADMIN`/`MEMBER` 和 `ACTIVE`/`REMOVED`，移除成员保留历史；变更命令保存原响应快照并使用追加式审计，重放不会重复产生业务变更或审计。
- OpenAPI 0.22.0、Flyway V23 和 `AssetFolderContractTests` 已覆盖一层文件夹创建/重命名/删除、成员只读、管理员写入、视频同项目分类/未分类、大小写重名、跨项目复合外键、并发单写和审计失败回滚。删除文件夹只清空 `clip_asset.folder_id` 并递增资产版本，不删除视频或移动对象。
- OpenAPI 0.24.0 与资产目录合同测试已将 Session、ClipAsset 和 EVENT_LOG 查询统一到显式项目路径并接入 ACTIVE Membership；非成员项目隐藏为 `PROJECT_NOT_FOUND`，成员读取其他项目资源返回资源级 404。Vue 先列授权项目，再在项目路由内查询 Session/视频，视频列表可按真实一层文件夹或“未分类”投影筛选。
- 本实现没有从历史上传或资产活动推断 Membership，避免把既有行为错误升级为授权事实。文件夹/分类、全部上传入口、Camera 配置和资产目录已使用 Membership 守卫；项目成员可上传和读取目录，Camera 配置要求项目 ADMIN，批次 Abort 的管理员判断来自项目 Membership。内部账号登录、会话与 CSRF 已实现；管理员账号创建/启停、Tenant/RoleBinding/PurposeGrant、项目关闭和 UAT 仍未完成，因此相关需求仍为 `PI`/`D1`。

FR-034 当前 `PI` 证据和边界：

- Flyway V24、OpenAPI 0.24.0 和 `UploadBatchContractTests` 已覆盖 `PLATFORM_LOCAL` 来源、固定来源标识和“一批次恰好一个 VIDEO”守卫；错误的多文件批次返回稳定错误码。
- Vue `/uploads/new` 加载授权项目和项目内一层文件夹。每个用户选择的视频独立计算完整文件与 Part SHA-256，独立初始化/登记/Multipart/Part 授权与确认/Complete File/Complete Batch/CaptureSession；Web 单元测试覆盖该控制面调用顺序和多文件顺序队列，浏览器不会记录预签名 URL。
- 本地 SeaweedFS 的浏览器 `PUT` 预检允许该开发场景；真实医院网络、目标浏览器、支持格式与容量、弱网/断点恢复页面体验以及 UAT 仍未完成，因此不能称为需求验收完成。

## 4. 非功能需求追踪

| 编号 | 基线要求 | 设计归属 | 验收方法 | 当前状态 |
| --- | --- | --- | --- | --- |
| NFR-PERF-001 | 单文件至少支持 2GB，上传可恢复 | `assetcapture`、对象存储 | 2GB/边界大小合成文件；断网、重传、重复 complete 和过期 upload 测试 | PI |
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

V1.2 是当前业务需求基线，未变更条款继承 V1.1.13 DOCX。下表记录 TD-V1.4 对继承条款的实现归一化口径，不修改历史 DOCX。

| 继承的 V1.1.13 表述 | TD-V1.4 实现口径 | 原因 |
| --- | --- | --- |
| 通用 `confidence` | 人工标注使用明确的确定性/不确定性字段；AI 分数必须记录任务、来源和语义版本 | 不同来源的分数不可直接比较 |
| 秒或 0.01 秒粒度 | UI 可显示秒，小数输入在边界转换；存储和契约统一使用整数毫秒 | 避免浮点累计误差和跨语言歧义 |
| 可调整的 `camera_id` | 内部使用不可变 `CameraChannel` 标识；显示别名按版本维护 | 保留历史引用和数据集可复现性 |
| MD5/SHA256 | 内容摘要统一使用 SHA-256；MD5 仅作遗留输入比对；低熵业务标识使用租户级 HMAC 或令牌 | 避免弱摘要和字典反推风险 |
| 清洗后排除或删除 | 逻辑排除、隔离与物理删除分开；物理删除受保留策略、审批和审计约束 | 保证可追溯和合规处置 |
| 默认 70/15/15 划分 | 小样本验收采用 pilot/holdout；正式比例由样本设计批准，并按 session/操作者/批次等泄漏组隔离 | 固定比例不等于科学抽样 |
| 隐私漏检为 0 | 仅表示批准样本经最终人工检查后观察到 0 个漏检，不外推为总体风险绝对为零 | 避免统计结论越界 |
| 护士配药视频默认无敏感信息 | 未经评估的 raw 视频先按待隐私评估处理；确认不含人脸、声音、工牌、屏幕、标签或环境标识后可按批准规则降级 | 拍摄对象不是患者也不代表视频天然无个人或机构敏感信息 |

V1.2 新增需求归一化：

| 新增业务表达 | 技术/验收口径 | 原因 |
| --- | --- | --- |
| 医院远端上传和平台本地上传 | 两种入口复用 Upload/Multipart/Complete、项目权限和入库校验 | 入口不同，入库规则一致 |
| 上传视频可以播放 | 入库后生成获准 Variant，再按 project、privacy 和 quarantine 签发短期 `PLAYBACK` 授权；raw 隐私复核使用不可复用的独立 `PRIVACY_REVIEW` 授权 | 上传成功不等于具备播放条件，管理员身份也不自动获得 raw 权限 |
| 无用或不合格视频可以删除 | 删除目标视频并保留权限、引用和审计守卫 | 满足数据整理需求且不破坏受控结果 |
| 项目下不同文件夹 | 一层 AssetFolder，按视频归类，不承载权限或对象路径 | 满足简单分类需求 |
| 只有管理员和普通用户 | 两级界面模板映射细粒度权限；高风险职责分离和标注禁止自审仍保留 | 简化 UI 不降低安全边界 |
| 标定数据 | 解释为视频内容 annotation；camera calibration 仅在内外参需求出现时使用 | 避免领域、API 和用户理解歧义 |

## 6. 变更规则

1. 需求文档版本变化时，先增加或更新本矩阵，再修改技术设计。
2. API 变化必须同步 OpenAPI 和契约测试。
3. 事件变化必须同步 JSON Schema 和兼容性测试。
4. 数据库变化必须通过唯一 Flyway 目录提交。
5. 条目状态升级必须附可定位的测试或 UAT 证据；禁止手工把整列标为完成。
6. 需求取消、降级或延期必须有批准记录，不得仅从实现中删除。
