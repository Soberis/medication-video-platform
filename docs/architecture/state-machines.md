# 状态机与领域守卫

> 文档状态：P0 详细设计（评审稿）  
> 最后更新：2026-07-14

## 1. 原则

平台使用多个正交状态表达资产、session、隐私、标注、清洗、隔离和数据集生命周期。禁止用一个 `status` 串联全部流程。

每次状态迁移必须：

1. 通过拥有模块的应用服务和领域守卫；
2. 携带期望 `row_version` 或等价条件；
3. 记录 actor、reason、request/trace ID 和授权上下文；
4. 同事务写业务状态与 Outbox；
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

## 3. 上传与资产状态

### 3.1 UploadBatch

```text
INITIATED → UPLOADING → COMPLETING → COMPLETED
     │           │             └→ FAILED
     │           ├→ EXPIRED
     │           └→ ABORTED
     └→ ABORTED
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

P0 的 Complete Batch 是 RK3588 边端上传程序自动调用的幂等控制面命令，不是 Web 人工按钮。它只完成传输清单和最小 RAW ClipAsset 建档；媒体探测、CameraSetSnapshot、CaptureSession 和 CameraStream 绑定属于后续入库状态，不反向改变已经成功的上传传输结论。

Complete Batch 后的 CaptureSession Ingest 读取批次内唯一已验证 METADATA，原子创建 session、服务器配置快照和 stream 绑定。此时未探测视频的 CameraStream 为 `BOUND`，不计入 `received_camera_count`；媒体探测成功后才转为 `INGESTED`。因此上传 `COMPLETED`、stream `BOUND` 和 session `ASSEMBLING` 可以同时成立，彼此不矛盾。

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

`ARCHIVED`、`DESTRUCTION_PENDING` 和 `DESTROYED` 属于保留/销毁维度，不与 ingest 状态混为一列。

守卫：

- INGESTED 必须具有受支持的容器/视频流、可验证摘要和 MediaReport。
- 重试只允许复用相同输入摘要；输入变化必须创建新 ClipAsset。
- `REJECT`、`QUARANTINE` 和 `DESTROYED` 不是 ingest 失败的同义词。

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

## 5. 隐私与脱敏状态

### 5.1 Privacy risk

```text
UNKNOWN → NONE
        → SUSPECTED → NONE/CONFIRMED
        → CONFIRMED
```

`NONE` 表示在指定 assessment/version 下未发现风险，不表示永久或绝对无风险。

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

| 迁移 | 角色与守卫 |
| --- | --- |
| PENDING → ASSIGNED | 调度员/项目管理员；assignee 有项目权限；范围未被冲突任务锁定 |
| ASSIGNED → IN_PROGRESS | 当前 assignee；访问的 variant 仍获隐私放行 |
| IN_PROGRESS → SUBMITTED | 创建不可变 revision；schema/guideline digest 与任务一致；基础校验通过 |
| SUBMITTED → COMPLETED | review PASS |
| SUBMITTED → REWORK | review REJECT；必须有错误类型和理由 |
| REWORK → ASSIGNED/IN_PROGRESS | 创建新任务轮次；旧 revision 只读 |

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
PENDING → PASS
        → WARNING → PASS/REJECT
        → REJECT
```

- 结果绑定 CleaningRun、输入摘要和 rule set digest。
- WARNING 进入数据集前必须有 AdmissionOverride。
- REJECT 表示默认不准入，不表示删除。

### 7.2 Quarantine

```text
NONE → ACTIVE → REVIEW_PENDING → RELEASED
          │             └→ ACTIVE
          └→ DESTRUCTION_PENDING （独立销毁审批通过后）
```

- ACTIVE 时禁止新标注、AI、数据集准入和普通预览。
- RELEASED 只解除指定原因和范围；若仍有其他 active 原因，整体仍隔离。
- 隔离解除必须重新评估已经过期的隐私或质量结论。

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
| VALIDATING → FROZEN | 准入、引用、摘要、隐私、split、防泄漏和格式校验全部通过；冻结审批完成 |
| FROZEN → RETIRED | 管理员批准；不修改历史导出和审计 |

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
- 只有 retryable 错误进入 RETRY_WAIT；验证失败、权限失败和 schema 不兼容默认不重试。
- CANCEL_REQUESTED 是协作取消；Worker 必须在安全检查点停止并清理临时输出。
- 迟到结果必须按 job ID、attempt 和 lease token 拒绝。

## 10. AI 候选状态（P1）

```text
GENERATED → PENDING_REVIEW → ACCEPTED
                           → MODIFIED
                           → REJECTED
GENERATED/PENDING_REVIEW → EXPIRED
```

- ACCEPTED/MODIFIED 必须产生可追溯的新 AnnotationRevision。
- `CandidateAnnotation.status` 与正式 `Review.status` 是不同概念，数据库字段不得都模糊命名为 `review_status`。
- 候选过期条件包括输入摘要变化、schema 不兼容、算法撤销或隐私放行撤销。

## 11. 关键兼容性守卫

| 操作 | 必须满足 |
| --- | --- |
| 创建标注任务 | asset INGESTED；允许的 session/stream 质量；privacy ALLOWED_ANNOTATION；quarantine NONE |
| 提交 revision | task 可提交；schema/guideline 未变化；时间和几何基础校验通过 |
| 后置清洗 | revision review PASS；引用的 variant 和 schema 摘要不变 |
| 数据集冻结 | session/stream 准入、privacy ALLOWED_INTERNAL_EXPORT、review PASS、post-clean PASS 或批准 override、quarantine NONE |
| 数据集导出 | DatasetVersion FROZEN；导出审批有效；purpose 和 expiry 有效 |
| AI 作业 | P1 已启用；DEIDENTIFIED variant；privacy ALLOWED_ANNOTATION；quarantine NONE；算法版本已批准 |
| 物理销毁 | retention 到期或批准请求；无 legal hold；影响分析和双人审批完成 |

## 12. 事件与测试要求

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
