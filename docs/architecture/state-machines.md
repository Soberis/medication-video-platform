# 状态机与领域守卫

> 文档状态：P0 详细设计（评审稿）  
> 最后更新：2026-07-30

## 1. 原则

平台使用多个正交状态表达项目、资产、session、标注、清洗和数据集生命周期。禁止用一个 `status` 串联全部流程。隐私与隔离状态机已按 V1.6 移出 P0，仅作为未来设计参考。

每次状态迁移必须：

1. 通过拥有模块的应用服务和领域守卫；
2. 携带期望 `row_version` 或等价条件；
3. 记录 actor、reason、request/trace ID 和授权上下文；
4. 同事务写业务状态、审计和所需 Outbox 事件或持久化工作项；
5. 对重复命令返回第一次成功结果；
6. 对非法迁移返回稳定错误码和 HTTP 409；
7. 禁止后台 SQL 绕过守卫。

状态名称进入契约后只能追加兼容值；重命名或改变语义需要新契约版本和迁移计划。

## 2. 通用命令语义

| 场景 | 处理规则 |
| --- | --- |
| 相同 idempotency key、相同请求摘要 | 返回原命令结果 |
| 相同 idempotency key、不同请求摘要 | `IDEMPOTENCY_CONFLICT` |
| 期望版本落后 | `CONCURRENT_MODIFICATION` |
| 状态已达到目标且输入相同 | 成功返回当前资源，不重复发业务副作用 |
| 状态已越过目标或处于终态 | `INVALID_STATE_TRANSITION` |
| 守卫所需外部快照已变化 | 失败并要求重新发起，不隐式使用最新值 |

## 3. 项目状态

```text
ACTIVE → ARCHIVED
```

| 迁移 | 守卫 | 主要副作用 |
| --- | --- | --- |
| ACTIVE → ARCHIVED | 项目内 ACTIVE ADMIN 显式确认 | 从正常项目列表移除；拒绝后续项目内访问；追加审计；保留项目、成员、视频、对象和历史记录 |

P0 不提供 `ARCHIVED → ACTIVE` 或项目物理删除。项目改名不是状态迁移：它只修改 ACTIVE Project 的显示名称并写审计，稳定 ID 和下游归属均不变。

## 4. 上传与资产状态

### 3.1 UploadBatch

```text
INITIATED → UPLOADING → COMPLETING → COMPLETED
     │           │             └→ FAILED
     │           ├→ EXPIRED
     │           └→ ABORTED
     ├→ ABORTED
     └→ EXPIRED
```

| 迁移 | 守卫 | 主要副作用 |
| --- | --- | --- |
| INITIATED → UPLOADING | 批次文件清单完整；multipart 会话存在；短期 part 上传权限签发成功；项目有写权限 | 允许上传 part |
| UPLOADING → COMPLETING | part 清单、大小和 checksum 信息齐全 | 锁定 part 清单，禁止覆盖 |
| COMPLETING → COMPLETED | 全部文件完成最终校验；规范化清单摘要一致；每个 VIDEO 文件已唯一绑定最小 RAW ClipAsset | 固化批次完成时间；后续事件投递启用后发出上传完成事件 |
| COMPLETING → FAILED | checksum、缺片或对象提交失败 | 保存稳定错误码；按策略允许重新 complete |
| INITIATED/UPLOADING → ABORTED | 发起人或管理员有权限 | 中止 multipart，安排临时对象清理 |
| INITIATED/UPLOADING → EXPIRED | 超过策略 TTL 且无活跃提交 | 中止并清理；写审计 |

文件清单注册只创建 `upload_file` 并校验批次声明的文件数与总大小上限，不改变 `UploadBatch` 状态。开始 multipart 前，已注册文件数与总字节数必须和批次声明完全一致。对象存储会话创建后只将文件置为 `SESSION_CREATED`；首次短期 part 上传权限签发成功后，文件和批次才进入 `UPLOADING`，避免数据库宣称可上传而对象存储或凭证尚未就绪。预签名 URL 不入库、不写日志，授权到期时间和期望 part 摘要入库。

Abort/过期清理采用“先提交业务终态，再异步执行幂等存储副作用”。`PUT /api/v1/upload/{batch_id}/abort` 仅允许同 project 的批次发起人或项目管理员；若服务端 TTL 已经过期，记录 `EXPIRED` 而不是伪装成用户 `ABORTED`。批次终态、文件终态、逐文件 `upload_cleanup_task` 和追加式审计在一个数据库事务中提交；活动的 Complete File 租约会阻止终止。清理任务以 `PENDING → RUNNING → SUCCEEDED/FAILED` 表达，有独立 claim lease 和有限重试：未完成 multipart 执行 Abort，已完成但批次未提交的遗留对象执行 Delete。迟到的完成请求若在终止后产生对象，会重新激活同一清理任务。`FAILED` 不回滚已经成立的批次终态，必须进入运维处置并保持可观察。

对象删除是破坏性副作用，因此调度器默认由 `UPLOAD_CLEANUP_ENABLED=false` 关闭；只有环境负责人确认 bucket、权限、告警和运行手册后才显式启用。关闭调度不会丢失数据库中的终态和清理任务，也不得通过后台 SQL 或直接修改状态绕过守卫。

P0 的 Complete Batch 是医院远端上传客户端和平台本地上传流程共用的幂等控制面命令。它只完成传输清单和最小 RAW ClipAsset 建档；媒体探测、CameraSetSnapshot、CaptureSession 和 CameraStream 绑定属于后续入库状态，不反向改变已经成功的上传传输结论。

Complete Batch 后的 CaptureSession Ingest 读取批次内唯一已验证 METADATA，原子创建 session、服务器配置快照和 stream 绑定。此时未探测视频的 CameraStream 为 `BOUND`，不计入 `received_camera_count`；媒体探测成功后才转为 `INGESTED`。因此上传 `COMPLETED`、stream `BOUND` 和 session `ASSEMBLING` 可以同时成立，彼此不矛盾。

UploadBatch 在 `INITIATED` 时冻结 project 和 `upload_source_type`。第一版只使用 `HOSPITAL_REMOTE` 与 `PLATFORM_LOCAL`；越权或不符合上传规则的请求在创建批次前拒绝。开发、测试和 CI 只允许合成数据。Complete Batch 后不得通过状态迁移更换 project。

### 3.2 UploadFile

```text
REGISTERED → SESSION_CREATED → UPLOADING → COMPLETING → UPLOADED
                                      ↑          ├→ FAILED
                                      └──────────┘  （缺片等可恢复守卫失败）
```

| 迁移 | 守卫 | 主要副作用 |
| --- | --- | --- |
| UPLOADING → COMPLETING | 批次可上传；取得有期限的单文件完成租约 | 阻止新的 Part 授权、确认和协调 |
| COMPLETING → UPLOADING | 对象尚未完成，且 Part 缺失、编号不连续或跟踪记录不完整 | 释放完成租约，允许补传后重试 |
| COMPLETING → UPLOADED | S3 Multipart Complete 成功；最终对象大小和流式计算的完整文件 SHA-256 与清单一致 | 固化对象 ETag、已验证摘要和完成时间 |
| COMPLETING → FAILED | 最终对象大小或完整文件 SHA-256 不一致 | 保存稳定错误码，禁止把对象当作可入库资产 |

`COMPLETING` 使用有期限的尝试租约，避免两个请求并发提交同一 Multipart Upload；进程在对象存储完成后、数据库提交前中断时，重试先通过对象 HEAD 恢复，再重新执行最终大小和 SHA-256 校验。Multipart ETag 是对象存储的非透明标识，不能替代完整文件 SHA-256。对象存储读流和摘要计算不得占用数据库事务。

### 3.3 ClipAsset

```text
UPLOADED → INGESTING → INGESTED
                 └→ INGEST_FAILED
INGEST_FAILED → INGESTING   （显式重试且输入摘要不变）
```

视频删除不改变上述入库状态。`deleted_at` 非空表示视频已在平台删除；这是可用性标记，不再建立一套删除状态机。

守卫：

- INGESTED 必须具有受支持的容器/视频流、可验证摘要和 MediaReport。
- 重试只允许复用相同输入摘要；输入变化必须创建新 ClipAsset。
- `REJECT`、`QUARANTINE` 和“已删除”不是 ingest 失败的同义词。
- 删除要求管理员权限、明确目标视频、受控引用检查和审计；删除多路 session 中的一路视频不得级联删除其他视频。

## 4. CaptureSession 和 stream 质量状态

### 4.1 Session assembly

```text
OPEN → ASSEMBLING → COMPLETE
                 ├→ INCOMPLETE
                 └→ CLOSED
INCOMPLETE → ASSEMBLING      （补齐允许的缺流）
COMPLETE/INCOMPLETE → CLOSED （超过组装窗口或人工关闭）
```

- COMPLETE：snapshot 中 enabled 的唯一通道均有 INGESTED stream。
- INCOMPLETE：组装窗口结束但缺流；必须保存 missing channel 列表。
- CLOSED：不再自动吸收新 stream；迟到数据进入人工关联或新 session 流程。
- COMPLETE 不表示质量、隐私或标注通过。

### 4.2 Session/stream quality

```text
PENDING → PASS
        → WARNING → PASS/REJECT  （人工复核）
        → REJECT
```

自动规则不能把 WARNING 隐式升级为 PASS。规则重跑产生新的 CleaningRun/MediaReport，由领域服务明确选择当前有效结论，旧结论保留。

### 4.3 人工视频质量判断

```text
UNREVIEWED → QUALIFIED
           → REJECTED
           → NEEDS_REVIEW
QUALIFIED / REJECTED / NEEDS_REVIEW → 任一新结论（版本并发保护）
```

- 只允许 ACTIVE 项目成员判断已入库且未删除的视频；质量判断不改变媒体入库状态。
- `QUALIFIED` 使用固定的 `MEETS_REQUIREMENTS`；`REJECTED` 与 `NEEDS_REVIEW` 必须使用与结论匹配的固定原因码。
- 相同结论和原因是幂等重试，不新增历史；结论变化写入当前事实并追加审计事件。
- 当前只有 `QUALIFIED` 可以进入正式标注；该守卫在标注切片实现时由服务端再次校验。

## 5. 隐私与脱敏状态

### 5.1 Privacy risk（P0 后置）

V1.6 不运行 PrivacyAssessment、DeidentifyRun 或 PrivacyRelease 状态机。采集端按协议避免敏感信息；人工审核发现意外敏感内容时，直接记录质量 `REJECTED` 与固定原因，再由管理员软删除。未来确有合规或共享需求时，应在新基线中重新定义状态和守卫。

### 5.2 DeidentifyRun

```text
NOT_REQUIRED
PENDING → RUNNING → SUCCEEDED → VERIFIED
                  └→ FAILED → PENDING
```

| 目标状态 | 必要守卫 |
| --- | --- |
| NOT_REQUIRED | 有批准的规则明确该 variant/场景无需脱敏 |
| SUCCEEDED | Worker 输出摘要、大小、配方和日志清单验证成功 |
| VERIFIED | 人工或批准的验证规则确认输出满足用途要求 |
| FAILED → PENDING | 输入摘要未变化；重试原因和 attempt 增加 |

### 5.3 PrivacyRelease

```text
BLOCKED → REVIEW_PENDING → ALLOWED_ANNOTATION
                         → ALLOWED_INTERNAL_EXPORT
                         → BLOCKED
ALLOWED_* → REVOKED
```

放行决定必须绑定：tenant、project、purpose、variant ID/digest、批准人、有效期和策略版本。

- `ALLOWED_ANNOTATION` 不自动包含内部导出权限。
- `ALLOWED_INTERNAL_EXPORT` 不自动包含对外下载或跨项目复制权限。
- REVOKED 后停止新访问；已发布数据集按事件响应流程评估召回，不能只改一行状态。

## 6. 标注任务与复核

### 6.1 AnnotationTask

```text
PENDING → ASSIGNED → IN_PROGRESS → SUBMITTED → COMPLETED
             │            │            └→ REWORK → ASSIGNED/IN_PROGRESS
             │            └→ ABANDONED
             └→ PENDING    （回收）
```

当前 P0 切片启用 `ASSIGNED → SUBMITTED → COMPLETED` 和 `SUBMITTED → ASSIGNED` 返工分支：项目管理员仅能为 `INGESTED`、未删除且当前质量为 `QUALIFIED` 的视频创建一个直接分配任务；受分配人提交不可变 revision，独立管理员通过后任务完成，不通过则退回原受分配人。`PENDING`、`IN_PROGRESS`、`ABANDONED` 和重新分配仍未启用，不能由前端提前伪造。

| 迁移 | 角色与守卫 |
| --- | --- |
| PENDING → ASSIGNED | 调度员/项目管理员；assignee 有项目权限；范围未被冲突任务锁定 |
| ASSIGNED → SUBMITTED | 当前 assignee；创建不可变 revision；schema digest 与任务一致；基础校验通过 |
| SUBMITTED → COMPLETED | 当前 revision 获独立 `APPROVED`；同一事务生成绑定该 revision 的不可变 post-clean run/result |
| SUBMITTED → ASSIGNED | 当前 revision 获 `REJECTED`；保留决定和旧 revision，下一次提交递增 revision number |

### 6.2 Review

```text
NOT_SUBMITTED → PENDING → PASS
                       → REJECT
                       → ADJUDICATION_REQUIRED → PASS/REJECT
```

- 复核人不得与提交人相同，除非批准的低风险试运行策略明确允许并留痕。
- PASS 绑定特定 revision；新 revision 不继承旧 PASS。
- 裁决产生 adjudication record 或新 revision，不覆盖任一原始提交。

## 7. 清洗、隔离和例外准入

### 7.1 前置/后置清洗

```text
APPROVED revision
→ 创建 CleaningRun
→ 同事务完成为 Result(PASS | WARNING | REJECT)
```

- 结果绑定 CleaningRun、输入指纹、输入摘要、所选 MediaReport 证据和 rule set digest。
- run/result 创建后不可原地改写；相同输入指纹幂等复用，规则或输入变化时允许创建新 run。
  当前切片只在首次 `APPROVED` 时触发，不暴露人工重跑入口。WARNING 进入数据集前必须有
  AdmissionOverride。
- REJECT 表示默认不准入，不表示删除。
- 当前主 Step 纵向切片不建立通用异步清洗入口：`APPROVED` review 在 Core API 的同一数据库事务中把任务推进为 `COMPLETED`，并创建一个版本化、不可变的 post-clean run/result；不新增 Worker、队列或调度状态。
- 当前规则从持久化文档重新计算 revision 文档摘要，重验 LabelSchema 摘要、标签集合、主 Step
  左闭右开时间范围、互斥关系，以及按 `created_at + media_report_id` 稳定选出的最新已验证媒体
  时长和输入摘要。缺少可用于边界复核的已验证媒体时长时结果为 `WARNING`；已经持久化的标签、
  摘要或时间事实非法时结果为 `REJECT`；全部规则通过时结果为 `PASS`。
- post-clean `PASS` 只证明该 revision 通过当前规则版本，不自动创建 DatasetVersion 或数据集成员；正式准入仍须在 `datasetexport` 按 CaptureSession、stream、当前资产/质量事实和摘要一致性单独验证。

### 7.2 Quarantine（P0 后置）

V1.6 不暴露隔离命令或查询。历史 `quarantine_record` 仅为不可改写的迁移记录；当前不合格数据使用质量结论控制标注和数据集准入，删除仍采用可审计软删除。

### 7.3 AdmissionOverride

```text
REQUESTED → APPROVED
          → REJECTED
APPROVED → EXPIRED/REVOKED
```

批准只适用于明确的 DatasetVersion 构建、对象集合和规则结论，不能作为永久全局 PASS。

## 8. 数据集与导出

### 8.1 DatasetVersion

```text
DRAFT → BUILDING → VALIDATING → FROZEN → RETIRED
          │             │
          └→ FAILED     └→ FAILED
```

| 迁移 | 守卫 |
| --- | --- |
| DRAFT → BUILDING | 选择规则、label/clean/split/export profile 已固定 |
| BUILDING → VALIDATING | session、stream、variant、revision 和泄漏分组快照完成 |
| VALIDATING → FROZEN | 当前准入、引用、摘要、split、防泄漏和构建契约全部通过；冻结审批完成 |
| FROZEN → RETIRED | 管理员批准；不修改历史导出和审计 |

当前 V39 允许在 `DRAFT` 内固定一次 CaptureSession 级 pilot/holdout 计划。V40 允许管理员在 split
存在且候选 stream 使用统一 label/clean 摘要时，再固定一次不可变 `DatasetBuildSpecification`，绑定
显式候选快照、统一摘要、split 与 `p0-dataset-export-v1`。两个命令都不会自动推进 `BUILDING`；调整
任一已固定事实必须创建新 DatasetVersion。

当前只读构建准备检查会按上述 `DRAFT → BUILDING` 守卫报告尚未固定的配置，并同时提示当前准入
失败或固定证据变化。它不写 DatasetVersion、不产生审计，也不执行状态迁移；“检查通过”仍不等同于
`VALIDATING → FROZEN` 的校验和审批通过。

V41 增加显式开始构建命令。项目管理员确认后，服务端锁定项目并在同一事务重新执行当前准入和
固定证据比较；只有零阻断且状态仍为 `DRAFT` 时，才记录启动人、启动时间、幂等指纹和审计，并迁移到
`BUILDING`。该命令只封存草稿配置，不创建文件、不启动 Worker、不进入 `VALIDATING`，也不批准冻结或导出。

V42 增加关系快照封印。项目管理员在 `BUILDING` 状态显式执行后，Core API 复用既有不可变成员、stream、
原始 ClipAsset ID/摘要、revision、清洗证据、泄漏组、split 和构建配置，先校验关系完整性，再按
`DATASET_RELATIONAL_SNAPSHOT_V1` 生成确定性 SHA-256。封印记录、DatasetVersion 的快照引用、
`VALIDATING` 状态和审计在同一事务提交。P0 的 `RAW_CLIP_ASSET_V1` 只表示使用草稿已固定的原始输入，
不创建派生 AssetVariant、文件、Worker 或队列；冻结前仍必须另行完成当前准入、引用、摘要和防泄漏校验。

V43 增加一次性冻结前技术校验。项目管理员在 `VALIDATING` 状态显式执行后，Core API 重新检查当前准入、
固定引用、关系快照摘要、CaptureSession 级 split 隔离和已批准构建契约，并保存不可变 `PASS/FAIL`、
稳定原因码、检查项、受检摘要、操作者和时间。结果不会改变 DatasetVersion 状态；`PASS` 仍需独立冻结
审批，`FAIL` 不得覆盖或重跑，修正只能创建新 DatasetVersion。该命令不创建文件、Worker、队列或导出。

V44 在 `PASS` 技术校验的同一事务创建一条绑定该 validation 的 `PENDING` 冻结请求，校验执行人即申请人。
另一位当前项目管理员批准时，Core API 锁定项目并重新执行 V43 的全部检查；只有证据仍一致时，才在同一
事务写入唯一 `APPROVED` 决定、冻结事实和审计，并迁移到 `FROZEN`。申请人不能自批，失败不产生部分决定；
请求没有独立有效期，因为它只绑定一个不可变 validation，且批准与冻结在一个命令内原子完成。冻结不等于
导出授权，也不创建文件、Worker 或队列。

V45 允许项目管理员为 `FROZEN` 版本提交一次 `MODEL_TRAINING` 导出申请。申请固定
`p0-dataset-export-v1`、构建规范、关系快照、通过的 validation、用途和未来到期时间，并在同一事务创建
`REQUESTED` DatasetExport、`PENDING` 独立审批请求和审计。相同幂等请求返回原记录；同一版本和 profile
不建立第二个申请。`REQUESTED` 不进入 `QUEUED`，也不创建文件、Worker 消息、下载授权或导出路径；
只有后续审批与作业消费者一起落地时才允许推进。

OpenAPI 0.50 增加按需、只读的审批准备检查。它在同一个 `REPEATABLE_READ` 视图中核对当前项目角色、
申请人与审批人分离、purpose 到期时间、`FROZEN` 状态、导出申请/审批引用和 V43 全部当前证据，返回
稳定阻断码。该查询不写审批决定、审计或业务状态；即使结果为 ready，后续批准命令仍必须在入队事务中
重新执行全部检查。

FROZEN 没有返回 DRAFT/BUILDING 的迁移。修正只能创建新 DatasetVersion。

### 8.2 DatasetExport

```text
REQUESTED → QUEUED → RUNNING → VALIDATING → AVAILABLE → EXPIRED
                      │             └→ FAILED
                      ├→ CANCEL_REQUESTED → CANCELLED
                      └→ FAILED
```

- AVAILABLE 前，临时前缀中的文件不可下载。
- VALIDATING 校验文件集合、schema、引用和 SHA256SUMS。
- 发布通过不可变最终前缀或发布清单完成；不能依赖对象存储目录 rename。
- EXPIRED 使下载授权失效；导出包的物理保留按策略独立处理。

## 9. 通用异步作业状态

```text
REQUESTED → QUEUED → RUNNING → SUCCEEDED
    │          │         ├→ RETRY_WAIT → QUEUED
    │          │         ├→ CANCEL_REQUESTED → CANCELLED
    │          │         └→ FAILED
    │          └→ CANCELLED
    └→ CANCELLED
```

规则：

- `SUCCEEDED` 只表示业务模块已验证并提交结果，不以 Worker 进程退出码代替。
- job 状态与 attempt 状态分离；attempt 使用 `QUEUED/RUNNING/SUCCEEDED/FAILED/LOST/CANCELLED`，job 不使用 `LOST`。
- lease 超时不直接判定业务 job 失败；超过 grace period 后原 attempt 记为 `LOST`，未达上限时创建新 attempt 并让 job 回到 `QUEUED`。
- 新 attempt 必须生成新的 message ID 和 lease capability；旧 attempt 的心跳与迟到结果永久拒绝。
- 只有 retryable 错误进入 RETRY_WAIT；下一 attempt 与 Outbox 在同一事务创建，由 `available_at` 执行有上限的指数退避和抖动，领取后进入 RUNNING。
- 验证失败、权限失败和 schema 不兼容默认不重试；retryable attempt 达到上限后 job 才进入 FAILED。
- CANCEL_REQUESTED 是协作取消；Worker 必须在安全检查点停止并清理临时输出。
- 迟到结果必须按 job ID、attempt 和 lease token 拒绝。

## 10. P0 标注任务

```text
ASSIGNED → SUBMITTED → COMPLETED
              └──────→ ASSIGNED → next revision
```

- 仅 ACTIVE 项目成员中的任务受分配人可以提交；管理员创建任务不等于可以代替受分配人提交。
- 提交前 ClipAsset 必须仍为 `INGESTED`、未软删除，且当前人工质量结论为 `QUALIFIED`。
- P0 每轮提交创建一条不可变 `annotation-document.v1` revision；相同幂等键和相同内容返回原 revision，不同内容或尚未退回的已提交任务拒绝。
- P0 文档只包含一个或多个绑定不可变 P0 LabelSchema 的主 Step 时间段和可选说明。主 Step 可在边界相接但不得重叠；存在已验证 MediaReport 时，结束时间不得超过媒体时长。返工已启用，对象框仍在后续真实用例中启用。

### P0 revision 独立复核

```text
SUBMITTED revision → APPROVED review
                   → REJECTED review → task ASSIGNED → next revision
```

- 复核是独立于任务状态的一次不可变事实：只有项目 ADMIN 可以创建，且 `reviewed_by != submitted_by`。
- 当前只支持每个 revision 一条复核决定。`REJECTED` 会把同一任务退回受分配人；再次提交产生下一个 revision，旧 revision 和 review 永不覆盖。
- `APPROVED` 会把任务推进到 `COMPLETED`，并在同一事务内为该 revision 生成 post-clean run/result；重复的相同复核请求不得重复创建任务迁移、run、result 或审计。

## 11. AI 候选状态（P1）

```text
GENERATED → PENDING_REVIEW → ACCEPTED
                           → MODIFIED
                           → REJECTED
GENERATED/PENDING_REVIEW → EXPIRED
```

- ACCEPTED/MODIFIED 必须产生可追溯的新 AnnotationRevision。
- `CandidateAnnotation.status` 与正式 `Review.status` 是不同概念，数据库字段不得都模糊命名为 `review_status`。
- 候选过期条件包括输入摘要变化、schema 不兼容、算法撤销或隐私放行撤销。

## 12. 关键兼容性守卫

| 操作 | 必须满足 |
| --- | --- |
| 创建标注任务 | asset `INGESTED`、未删除；当前人工质量结论 `QUALIFIED`；项目与任务范围有效 |
| 播放视频 | 显式 `DATA_REVIEW` 请求；ACTIVE 项目 ADMIN/MEMBER；已完成上传完整性校验的 MP4、`deleted_at IS NULL`；媒体检测状态不阻断人工查看；短期 URL 与签发审计；不等于质量合格、标注批准或导出授权 |
| 发现异常敏感内容 | 当前 P0 记录质量 `REJECTED` 与固定原因，管理员按审计流程软删除；不运行专用隔离状态机 |
| 提交 revision | task `ASSIGNED` 且由受分配人提交；asset 仍为 `INGESTED`、未删除且人工质量 `QUALIFIED`；时间段终点晚于起点 |
| 复核 revision | 项目 ADMIN 且不等于提交者；asset 仍为 `INGESTED`、未删除且人工质量 `QUALIFIED`；每 revision 只有一次决定 |
| 后置清洗 | 当前 revision review `APPROVED`；文档、LabelSchema、ClipAsset 输入摘要和规则版本被当前 run/result 冻结；缺媒体时长为 WARNING，非法事实为 REJECT |
| 数据集冻结 | 以 CaptureSession 为成员单位；session/stream 准入、人工质量 `QUALIFIED`、annotation review APPROVED、post-clean PASS 或批准 override，且所有冻结摘要仍匹配 |
| 数据集导出 | DatasetVersion FROZEN；导出审批有效；purpose 和 expiry 有效 |
| AI 作业 | P1 已启用；DEIDENTIFIED variant；privacy ALLOWED_ANNOTATION；quarantine NONE；算法版本已批准 |
| 物理销毁 | retention 到期或批准请求；无 legal hold；影响分析和双人审批完成 |
| 移动项目内文件夹 | folder 与 asset 同 project；只更新 `folder_id` |

## 13. 事件与测试要求

每个跨模块可观察迁移都应定义版本化事件，例如：

```text
AssetIngestedV1
SessionAssemblyClosedV1
PrivacyReleaseGrantedV1
AnnotationRevisionSubmittedV1
AnnotationReviewPassedV1
CleaningCompletedV1
DatasetFrozenV1
DatasetExportAvailableV1
```

事件名称只是设计候选，只有进入 `contracts/events/` 并通过兼容性测试后才是正式契约。

每个状态机至少测试：

- 所有合法迁移；
- 每个非法迁移；
- 幂等重放；
- 乐观锁冲突；
- 守卫依赖快照变化；
- 审计和 Outbox 同事务；
- 故障重试与迟到结果；
- 权限正反向矩阵。
