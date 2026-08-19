# 异步作业协议

> 文档状态：P0 详细设计（评审稿）  
> 适用范围：媒体探测/转码/抽帧、跨进程媒体处理和 P0 AI hand-pose 作业；当前最小 training-data.v2 导出为同步只读操作
> 最后更新：2026-07-30

## 1. 核心决策

平台采用：

```text
模块自有作业聚合
+ 数据库事务与 Outbox
+ RabbitMQ 至少一次投递
+ Worker 幂等执行和租约
+ 临时对象输出
+ Core API 结果提交与验证
+ Inbox/结果去重
+ 原子业务发布
```

不宣称 exactly-once。目标是“至少一次投递 + 可识别 attempt + 幂等吸收 + 可恢复提交”。

Python Worker 不连接业务数据库。所有正式状态、AssetVariant、CleaningResult、AIJob 和 CandidateAnnotation 都由拥有模块的 Core API 写入。
旧 DatasetExport Worker、DatasetVersion 作业和 AiEvaluation 平台已退役；本文件中仍保留的对应协议描述只用于解释历史
Flyway/运行记录，不代表当前 active API、队列或 Worker。

当前 `APPROVED` Revision 的轻量 post-clean 只重验已冻结的标签、时间和摘要事实，耗时短且无
外部副作用，因此与 Review 在 Core API 同一数据库事务完成，不使用本协议。出现批量输入、重复
检测、媒体计算、跨进程执行或独立重试需求时，必须转为本协议，不能继续扩大同步事务。

## 2. 作业所有权

不建立一个可任意承载全部业务状态的全局 `ProcessingJob` 表。各模块拥有自己的业务作业：

| 作业 | 所有模块 | 业务真值表 |
| --- | --- | --- |
| 媒体探测、转码、抽帧、脱敏 | `mediaprivacy` | media_job、media_job_attempt |
| 批量/耗时清洗和重复检测 | `qualitycleaning` | cleaning_run、cleaning_result |
| 最小 training-data.v2 preview/download | `datasetexport` | training_data_eligibility、training_data_candidate_observations（只读投影） |
| AI hand-pose 推理 | `aicandidate` | ai_job、ai_job_attempt、candidate_annotation |

共享代码只提供 `JobEnvelope`、`JobResult`、状态枚举、错误分类和幂等工具，不拥有业务决策。

技术表：

| 表 | 所有权 | 说明 |
| --- | --- | --- |
| outbox_event | 平台基础设施 | 与业务事务同库写入，等待外发 |
| inbox_message | 平台基础设施 | 记录已处理外部消息 ID 和处理结果摘要 |
| adapter_delivery | `integration` | broker/CVAT 等外部投递状态，不是业务作业状态 |

## 3. 端到端时序

```mermaid
sequenceDiagram
    participant U as User/API caller
    participant A as Core API owning module
    participant DB as PostgreSQL
    participant R as Outbox relay
    participant Q as RabbitMQ
    participant W as Python Worker
    participant S as S3-compatible storage

    U->>A: Create job (Idempotency-Key)
    A->>DB: TX: job QUEUED + attempt + outbox event
    A-->>U: 202 job_id + status_url
    R->>DB: claim outbox
    R->>Q: publish JobEnvelope
    Q->>W: deliver
    W->>A: acquire/renew lease
    W->>S: read approved input
    W->>S: write temporary outputs
    W->>A: submit JobResult + manifest + digests
    A->>S: verify output metadata/digests as required
    A->>DB: TX: inbox dedupe + domain commit + outbox
    A-->>W: result accepted
    W->>Q: ack message
```

如果 Worker 无法调用 Core API，可使用版本化结果消息；Core API 消费者仍必须执行相同的 Inbox 去重、租约检查、对象验证和领域提交。两种回传方式不能在同一环境中无规则混用。

## 4. MediaProbeJobEnvelope V1

首个正式任务消息已经冻结在 `contracts/events/media-probe-job-v1.schema.json`。它只携带
定位业务输入所需的标识、摘要、批准配方和当前 attempt 的一次性租约令牌，不携带对象 key、
存储端点或预签名 URL：

```json
{
  "schema_version": "media-probe-job.v1",
  "message_id": "uuid",
  "job_id": "uuid",
  "job_type": "MEDIA_PROBE",
  "attempt": 1,
  "lease_token": "opaque-random-token",
  "project_id": "uuid",
  "clip_asset_id": "uuid",
  "input_sha256": "hex",
  "input_size_bytes": 123,
  "content_type": "video/mp4",
  "probe_profile_key": "media-probe-baseline-v1",
  "recipe_digest": "hex",
  "requested_at": "RFC3339 UTC"
}
```

规则：

- 消息只传业务标识、摘要、批准版本和租约令牌，不传视频字节、对象位置、预签名 URL、患者或配药人员标识。
- attempt 事实表只保存租约 SHA-256。Transactional Outbox 在发布确认前会短暂保存原始租约能力，
  因此 Outbox 必须限制数据库角色访问；publisher confirm 成功后同一事务立即从已发布 payload
  删除 `lease_token` 并记录脱敏时间。应用日志不得记录原始令牌。
- Worker 同时使用独立服务身份和租约令牌，通过 Core API 换取 job-scoped、短期只读对象权限。
- `recipe_digest` 来自服务器批准且规范化的 profile，调用方不能提交任意探测参数。
- 任务类型和 schema version 必须采用 allowlist，未知类型进入不可重试失败。

## 5. JobResult V1

`MEDIA_PROBE` 的首个正式专用结果契约已经冻结在
`contracts/events/media-probe-result-v1.schema.json`，批准的输入配方位于
`contracts/profiles/media-probe-baseline-v1.json`。通用示例仍用于其他 job type 的后续设计，
不得覆盖专用契约。成功结果必须同时证明完整解码和输入 SHA-256 复核；失败结果只能携带
allowlist 中的错误分类、稳定错误码和安全摘要，不创建 MediaReport。

```json
{
  "schema_version": "job-result.v1",
  "message_id": "uuid",
  "job_id": "uuid",
  "attempt": 1,
  "lease_token": "opaque-random-token",
  "status": "SUCCEEDED",
  "input_sha256": "hex",
  "parameter_digest": "hex",
  "outputs": [
    {
      "artifact_role": "PREVIEW_VIDEO",
      "temporary_object_ref": "opaque-object-id",
      "sha256": "hex",
      "size_bytes": 123,
      "media_type": "video/mp4"
    }
  ],
  "metrics": {},
  "software_digest": "sha256:...",
  "started_at": "RFC3339 UTC",
  "completed_at": "RFC3339 UTC",
  "error": null
}
```

结果接受守卫：

1. job、attempt 和 lease token 匹配当前执行；
2. 输入摘要和参数摘要未变化；
3. schema version 和 job type 兼容；
4. 输出只位于批准的临时前缀；
5. 对象大小、摘要、媒体类型和数量满足 profile；
6. software/model digest 已批准；
7. job 未取消、未过期且结果未被先前 attempt 接受；
8. 业务状态仍允许提交。

任何守卫失败都不得把临时对象注册为正式 AssetVariant 或 ExportArtifact。

## 6. 幂等模型

### 6.1 创建作业

作业唯一键按业务类型定义。例如：

```text
media_job:
  job_type + input_variant_id + input_digest + recipe_digest

dataset_export:
  dataset_version_id + export_profile_digest

ai_job:
  algorithm_id + input_variant_id + sampling_profile_id + parameter_digest
```

相同唯一键：

- 已成功：返回既有结果；
- 运行中：返回既有 job；
- 可重试失败：创建新 attempt，不创建第二个业务 job；
- 输入或参数摘要不同：创建新 job。

### 6.2 消息和结果

- `message_id` 用 Inbox 去重。
- `(job_id, attempt)` 唯一。
- 每个业务 job 最多一个 accepted result。
- 重复结果返回第一次接受结果，不重复创建 variant、候选或导出记录。
- 迟到旧 attempt 结果返回 `STALE_ATTEMPT` 并安排临时对象清理。

## 7. 租约、心跳和并发

作业被 Worker 领取时生成不可预测的 `lease_token`，并保存：

```text
worker_id
attempt
lease_token_hash
lease_started_at
lease_until
last_heartbeat_at
```

- 心跳只延长同一 attempt 的租约，不能改变参数。
- 心跳必须同时匹配独立 Worker 身份、当前 attempt、`worker_id` 和不可预测 lease capability；
  仅 broker delivery 不能续租。
- 心跳只接受尚未过期的租约，并把 `lease_until` 设置为服务器当前时间加 lease duration；
  不从旧截止时间累加，也不允许复活已过期或 `LOST` 的 attempt。
- 租约过期后，调度器经过 grace period 才允许新 attempt。
- grace period 后，平台在同一数据库事务内把旧 attempt 标记为 `LOST`；未达到上限时，
  为下一 attempt 生成新的 message ID、lease capability 和 Outbox 事件，业务 job 保持非终态。
- 新 attempt 创建后，旧 lease token、旧 heartbeat 和旧结果永久失效。
- 达到 max attempts 后，平台把 job 和资产入库状态提交为失败，不再创建消息。
- Worker 不能仅凭 broker delivery tag 证明自己仍持有业务租约。
- 同一 job 的并发结果以数据库唯一约束和当前 attempt 守卫裁决。

当前媒体探测默认 lease duration 为 30 分钟、heartbeat interval 为 5 分钟、grace period
为 5 分钟、max attempts 为 3，恢复扫描默认每 30 秒处理最多 20 个候选。它们是可配置的
首个切片运行值，后续必须根据长视频、弱网和 Worker 故障注入测试冻结容量基线。

## 8. 重试和错误分类

| 类别 | 示例 | 默认动作 |
| --- | --- | --- |
| RETRYABLE_TRANSIENT | broker/对象存储短暂不可用、网络超时 | 指数退避 + 抖动，受 max attempts 限制 |
| RETRYABLE_RESOURCE | 临时磁盘不足、Worker 被抢占 | 换 Worker/profile 后重试，记录资源原因 |
| NON_RETRYABLE_INPUT | 摘要不符、损坏媒体、schema 不兼容 | FAILED，人工修复输入后创建新 job |
| NON_RETRYABLE_POLICY | 隐私放行撤销、权限不足、算法未批准 | FAILED/BLOCKED，不自动重试 |
| CANCELLED | 用户/治理流程取消 | 清理临时输出，不作为失败率 |
| INTERNAL_BUG | 未分类异常 | 有限重试后 DLQ，告警并保留诊断摘要 |

错误响应只包含稳定 `error_code`、安全摘要和 `retryable`，不得包含堆栈、签名 URL、对象内部地址或敏感数据。

当前媒体探测收到 `retryable=true` 的有效 Worker 结果时，在同一事务内完成 Inbox 记录、旧
attempt `FAILED`、job `RETRY_WAIT`、新 attempt/新 capability 和 Outbox 写入。Outbox 的
`occurred_at` 保留实际发生时间，`available_at` 使用有上限的指数退避和按 job/attempt 分散的
确定性抖动；默认 base delay 为 5 秒、max delay 为 5 分钟。Worker 接受结果响应后 ACK 当前
消息，不在消费者进程中 sleep 或重复执行旧 attempt。不可重试错误或第 3 次 attempt 失败时，
job 与资产入库状态才进入终态失败。

数据集 v2 导出使用同一原则，但使用独立的
`dataset-export-job.v1`、`dataset-export-result.v1`、`dataset-export-dead-letter.v1` 契约、
RabbitMQ 路由和 Worker 身份。批准命令原子创建首次 attempt 与 Outbox；Worker 领取后通过
attempt-scoped 租约换取短期输入和 multipart 输出能力，不能读取对象 key 或业务数据库。成功结果必须
包含包摘要及准确文件清单，Core API 核对临时对象、Profile、固定输入和批准软件摘要后才登记正式
`ExportArtifact`。Worker 明确报告的可重试错误会创建新 attempt。失联 attempt 超过 lease 和 grace
period 后，恢复扫描会在锁定导出及最新 attempt 的事务中再次确认过期，将旧 attempt 标为 `LOST`，
清理其 multipart 或已完成临时对象，并立即创建新 capability、attempt 与 Outbox；达到最大尝试次数时
导出进入 `FAILED`。默认 grace period 为 5 分钟、每 30 秒扫描最多 20 个候选；旧心跳和迟到结果不能复活。

## 9. 取消协议

### 9.1 状态

```text
REQUESTED/QUEUED → CANCELLED
RUNNING → CANCEL_REQUESTED → CANCELLED
```

### 9.2 规则

- 取消接口幂等；重复取消返回当前状态。
- Worker 在帧批次、文件边界或其他安全检查点查询取消标志。
- 不使用强制杀进程作为正常取消机制；超时终止属于故障恢复。
- SUCCEEDED 且结果已提交后不能改为 CANCELLED；需要新治理命令撤销/销毁产物。
- 取消后的临时对象、临时本地文件和未完成 multipart 必须进入清理队列。

## 10. RabbitMQ 拓扑

当前 P0 首个切片只启用：

```text
platform.media.probe.v1
platform.media.dead-letter.v1
```

只有在形成第二类真实负载、优先级或资源隔离证据后才增加队列；不提前预建
`privacy`、`export`、`ai_cpu` 或 `ai_gpu` 队列。

首个 Python Worker 固定 `prefetch=1` 和 manual ack。无效或未知契约消息不会由 broker 原样
dead-letter；Worker 使用 publisher confirm 向死信 exchange 显式发布
`media-dead-letter.v1` 脱敏诊断，确认成功后才 ACK 原消息。只有 Core API/结果接口的短暂不可用
才允许 broker 重投。Worker 不持有数据库凭据，输入媒体
先流式复核大小和 SHA-256，再提取容器/流/时间轴事实并使用 FFmpeg 对主视频流完整解码。

当前首个媒体探测切片已经实现任务 Outbox、publisher confirm、单一业务队列/DLQ、独立
Worker 身份、attempt 租约与心跳、短期输入授权、grace 后失联回收、可重试结果的有限退避与
新 attempt、结果 Inbox 去重、稳定错误、状态提交、不可变 MediaReport 和脱敏死信诊断，并用
合成 MP4 完成真实队列闭环。2026-07-16 的本地合成演练还验证了业务队列无 DLX 参数、
错误 Worker 身份产生脱敏诊断、管理员创建新 attempt/Outbox/审计记录，以及正确 Worker 完成
重放 attempt 和 MediaReport 入库。尚未完成的可靠性证据包括真实长任务心跳联调、更多故障
注入和恢复容量测试；取消尚未实现，不能把首个闭环等同于完整异步作业平台。

要求：

- 持久 exchange、queue 和 persistent message；
- publisher confirms；
- consumer manual ack；
- 合理 prefetch，避免大作业占满单个 Worker；
- 按 job type 设置最大消息大小和 allowlist；
- 重试使用延迟/重试队列或调度器，不在消费者进程中长时间 sleep；
- DLQ 消息包含 job/message ID 和安全错误摘要，不复制敏感 payload。

业务队列不得配置会复制原消息的 `x-dead-letter-exchange`。媒体任务包含短期 lease capability，
所以 DLQ 只能保存稳定原因码、安全摘要、原消息 SHA-256/大小，以及契约有效时解析出的
job/message ID 和 attempt；不得保存原始 body、lease token、签名 URL、对象 key、输入摘要、
异常文本或堆栈。脱敏诊断发布未获确认时，Worker 重新入队原消息并以失败退出，由进程监督器
退避重启，避免 ACK 后丢失证据或在消费者内形成无等待热循环。相同原消息和原因使用确定性
`dead_letter_id`，下游仍必须按该 ID 幂等处理，因为发布成功但 ACK 丢失时可能重复投递。

RabbitMQ 只保存传递状态，不是最终业务事实数据库。

## 11. DLQ 处理

DLQ 不自动无限重放。重放流程：

1. 运维确认失败类别和影响范围；
2. 业务模块确认 job 仍允许执行；
3. 修复配置/代码后创建新的 attempt 和 lease token；
4. 发布新 message ID；
5. 记录操作者、原因、旧/新 attempt 和变更版本；
6. 观察重复失败阈值并触发熔断。

原 DLQ 消息作为诊断证据保留到策略期限，不直接修改后重新入队。

这里的“原 DLQ 消息”指不可变的 `media-dead-letter.v1` 脱敏诊断，不是原始媒体任务 envelope。
受控重放必须回到 Core API 校验当前 job/项目/策略状态，生成新的 attempt、capability 和
message ID；禁止把 DLQ body 直接投回业务队列。

当前受控媒体重放接口为 `POST /api/v1/media-jobs/{media_job_id}/replays`，规则如下：

- 仅项目管理员可调用；local/test 使用与边端上传令牌分离的合成管理员服务凭据，内部管理端使用平台账号会话；
- 请求只复制脱敏诊断的 `dead_letter_id`、source message/attempt、稳定原因和 body SHA-256，
  不提交原消息 body、lease token、URL 或对象信息；
- 服务端按与 Worker 一致的 UUIDv5 规则复核 `dead_letter_id`，并要求 source message/attempt
  正好是当前 `QUEUED`/`RUNNING` attempt；无可信 job 身份的契约错误不得重放；
- 操作者必须提交枚举原因码和不含敏感信息的 ticket/deployment/commit reference；
- 同一事务终止旧 attempt，生成新 attempt、新 capability、新 message ID、Outbox、幂等重放记录
  和追加式审计事件；原 ClipAsset 继续保持 `UPLOADED` 或 `INGESTING`，不回退原始资产；
- 默认每个 job 最多 2 次人工重放，配置硬上限为 10；超限后需要人工调查而不是绕过熔断；
- 相同 Idempotency-Key 和完全相同请求返回原 replay；不同请求冲突；同一 dead letter 只能使用一次；
- `audit_event` 由 PostgreSQL trigger 阻止 UPDATE/DELETE，记录 actor、项目、原因、request ID、
  旧/新 attempt、dead letter 和变更引用。

UUIDv5 校验能够发现抄录错误并绑定稳定诊断身份，但不是签名。当前流程的真实性边界仍是
管理员从受控 RabbitMQ 管理面读取保留诊断并接受审计；若未来需要无人值守重放，必须先增加
签名诊断或受信 DLQ Inbox，不能把本接口改为自动消费后直接重试。

若 RabbitMQ 在 publisher confirm 之后发生灾难性丢失，数据库中的当前 `QUEUED` attempt 不会被
自动重新投递：Core API 无法仅凭队列瞬时为空判断消息丢失，自动重投会造成重复执行和伪造 DLQ。
管理员在确认 source message 已丢失后，才可调用
`POST /api/v1/media-jobs/{media_job_id}/delivery-recoveries`。该命令仅接受 source message/attempt、
固定原因 `BROKER_MESSAGE_LOST` 与非敏感变更引用；锁定项目和当前 `QUEUED` attempt，标记旧 attempt
为失败并原子创建新的 attempt、capability、message ID、Outbox、恢复记录和审计。它不扫描队列、
不重发旧 capability、不能用于 RUNNING/终态 attempt，并受既有最大 attempt 上限约束。

数据集导出沿用同一人工边界，当前接口为 `POST /api/v1/dataset-exports/{dataset_export_id}/replays`。
V49 只持久化人工复制的安全字段和不可变 replay 记录，不新增 DLQ inbox、不自动消费 RabbitMQ，
也不接受原始 body、lease token、签名 URL 或对象存储字段。可重放原因是
`DATASET_EXPORT_JOB_PROFILE_UNSUPPORTED`、`DATASET_EXPORT_JOB_ACQUIRE_REJECTED`、
`DATASET_EXPORT_JOB_LEASE_REJECTED` 和 `DATASET_EXPORT_JOB_RESULT_REJECTED`；
`DATASET_EXPORT_JOB_CONTRACT_INVALID` 或缺少可信 message/export/attempt 身份的诊断必须拒绝。
服务端会锁定当前项目和导出，清理已有临时输出，终止旧 attempt，复用既有 AttemptQueueService
创建新 attempt/capability/message/Outbox，并记录 `DATASET_EXPORT_MANUAL_REPLAY` 审计；清理失败时
事务不创建新 replay 或 Outbox。默认每个 export 最多 2 次人工重放，配置边界仍为 1..10。
本切片的代码和合同测试完成不等于真实 broker DLQ 运维演练完成。

## 12. 对象输出与原子发布

对象存储通常没有目录级原子 rename。发布采用两阶段：

```text
temporary/{job_id}/{attempt}/...
→ Worker 提交 manifest 和摘要
→ Core API 验证
→ 复制/写入不可变 final key 或注册内容寻址对象
→ 数据库事务注册正式 artifact
→ 发出 ArtifactPublished 事件
→ 异步清理 temporary
```

如果使用“注册现有不可变对象”而不复制，临时 key 必须在注册前转为不可被 Worker 修改的权限/版本；否则结果提交后仍可能被篡改。

导出包只有在文件集合、引用和 SHA256SUMS 校验通过后才进入 AVAILABLE。

## 13. S3 multipart 完整性

- 不把 multipart ETag 当作完整文件 MD5。
- 创建 multipart 时明确 checksum 能力和算法；不同 S3 兼容产品必须通过能力探测。
- 每个 part 记录 part number、大小、算法、checksum 和 ETag。
- complete 请求必须与已冻结 part 清单一致。
- 平台正式资产摘要为 SHA-256；若对象存储不能验证完整 SHA-256，由媒体 Worker 流式读取对象计算并在 INGESTED 前校验。
- 只校验边端自报 SHA-256 不足以证明对象存储中的字节一致，必须有服务端/Worker 独立验证或受批准的存储校验机制。

## 14. 安全边界

- Worker 使用独立服务身份和 job scoped 短期凭证。
- 输入只读，输出只能写指定临时前缀。
- AI Worker 默认禁止公网出站；DNS/连接尝试进入安全日志。
- 消息不含预签名 URL、密钥、真实身份映射或视频帧。
- 临时盘加密或采用受控节点，作业结束清理。
- Worker 镜像、依赖和模型使用摘要锁定，运行时不下载。

## 15. 可观测性

至少记录：

- queue wait、run、verify、commit 各阶段时长；
- attempt、retry 和取消次数；
- lease 过期、迟到结果和幂等命中；
- 临时对象数量/字节和清理积压；
- 按 error class 的失败率；
- broker publish/consume lag；
- Worker CPU、内存、临时盘和解码错误；
- trace ID 从创建命令贯穿到结果提交。

指标标签不得包含患者、操作者、处方、对象 key 或其他高基数敏感字段。

## 16. 最小测试矩阵

- [ ] 事务回滚时不产生可投递 Outbox。
- [ ] relay 重复发布被 Worker/Core API 幂等吸收。
- [ ] Worker 处理完成但 ack 前崩溃，重投不生成重复业务产物。
- [ ] Worker 写完临时对象但结果提交失败，可以安全重试。
- [x] 结果提交成功但响应丢失，重复提交返回原结果。
- [x] lease 过期后旧 attempt 迟到结果被拒绝。
- [ ] 运行中取消能停止并清理临时输出。
- [x] non-retryable 输入不会进入重试风暴。
- [x] 受控 Media/Export DLQ 重放创建新 attempt、新 capability/message ID 并保留追加式审计；真实
  broker DLQ 运维演练仍待完成。
- [ ] 对象摘要、输入摘要或参数摘要变化会阻断提交。
- [ ] Worker 无数据库写权限、无默认公网出站权限。
