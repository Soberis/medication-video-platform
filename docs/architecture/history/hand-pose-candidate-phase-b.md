# Ego Hand Pose Candidate Phase B 技术设计

> 版本：TD-HP-CANDIDATE-V1  
> 状态：Phase B 冻结前设计基线；不是启用授权  
> 对齐需求：V1.15 FR-042B  
> 日期：2026-08-10

> 历史状态：SUPERSEDED。`Ego` 为历史内部命名；当前产品语言和实施规则以 [V1.18 当前技术设计](../video-hand-pose-mvp-technical-design.md) 为准。

## 1. 目的与当前状态

本文定义 Phase A 得到书面 `GO` 后才可实施的最小候选标注闭环。它约束后续实现，不把现有
`AIJob`、RabbitMQ、AI Worker、候选查询或 Overlay 代码认定为 Phase B 完成证据。

```text
已批准 Ego 输入
→ AIJob / attempt / Outbox
→ RabbitMQ
→ 独立 AI Worker
→ Core API 验证并保存候选事实
→ 人工 ACCEPT / MODIFY / REJECT
→ 既有 AnnotationRevision / Review / Post-clean
```

Worker 从不直连平台数据库；`aicandidate` 拥有 AI 作业、候选和候选审核事实，`annotationreview`
拥有正式 revision。跨模块写入只经公开应用服务或版本化领域事件完成。

## 2. 启用守卫

在以下决策全部记录前，功能必须保持不可用：

1. Phase A 的书面 `GO`、模型/Golden Set/网络隔离/评审证据；
2. 获批 `EGO` camera role 清单及资产绑定的可验证方式；
3. profile、结果 schema、存储方案、保留期和容量评估；
4. 人工审核权限、正式 revision 映射和审计事件名；
5. lease、幂等、迟到结果、重试、回收和 DLQ 语义；
6. OpenAPI、事件 JSON Schema、迁移和跨进程验收方案。

模型 SHA-256 配置为空只能作为本地防误启保护，不能代替上述业务 GO guard。

## 3. 输入准入

候选作业创建必须在同一事务锁定并验证：

```text
ACTIVE project membership
AND ClipAsset.status == INGESTED
AND ClipAsset 未删除
AND 唯一 CameraStream 绑定存在
AND CameraStream.camera_role 属于预先批准的 EGO role
AND 该资产属于获授权、隔离的输入范围
AND input_sha256 / size / content_type 已冻结
```

`video/*`、文件名、文件夹、客户端声明或页面显示文字都不是 Ego 准入事实。若当前相机模型不能表达
批准的 Ego role，应先扩展该模型和审计，不得在 `aicandidate` 内维护第二份角色真值。

## 4. 冻结的 Profile 和结果契约

### 4.1 Profile

`HandPoseInferenceProfileV1` 是不可变 JSON 文档；其规范化 UTF-8 原文的 SHA-256 与 Job 一同保存。

```json
{
  "profile_schema_version": "hand-pose-inference-profile.v1",
  "algorithm_key": "MEDIAPIPE_HAND_LANDMARKER",
  "algorithm_version": "v1",
  "model_sha256": "<approved sha256>",
  "software_digest": "sha256:<approved worker digest>",
  "num_hands": 2,
  "min_detection_confidence": 0.5,
  "min_presence_confidence": 0.5,
  "min_tracking_confidence": 0.5,
  "sampling_fps": 10,
  "handedness_policy": "MODEL_ORIGINAL_V1",
  "timestamp_policy": "CONTAINER_PTS_STRICT_V1",
  "bbox_recipe": {"version": "LANDMARK_MINMAX_CLAMP_V1", "margin_ratio": 0.0},
  "world_landmarks_enabled": false
}
```

任一字段变化必须创建新 profile 摘要和新 Job；不得只以 model SHA + sampling FPS 视为相同推理。

### 4.2 帧级结果

结果工件必须保留每个解码帧的下列事实，即使该帧没有手或没有执行推理：

```text
frame_index
source_timestamp_ms / timestamp_ms / timestamp_source
image_width / image_height
inference_performed
hands: 0..2
```

每只 hand 包含 `hand_index`、`LEFT|RIGHT|UNKNOWN`、handedness score、21 个 0..20 固定点、
有效点数、bbox、bbox recipe version。NaN/Inf 点的序列化、有效点计数和不绘制连接线的规则必须由
schema 明确；不能让一个坏点终止整个任务。

`handedness_score` 仅是类别分数，bbox 的来源必须为 `LANDMARK_ENVELOPE`，不暴露虚假的 bbox confidence。

### 4.3 存储

在 Golden Set 容量评估完成前不预设 dense 数据存储实现。允许的最终方案为：

- 关系型帧/手记录：适用于已测得容量、审核查询和保留期均可接受的范围；或
- 不可变对象存储结果工件（JSONL/Parquet）加关系型 Job、摘要、审核索引：适用于 dense 输出。

无论方案如何，关系型表必须保存输入、profile、工件摘要、schema 版本、审核状态和审计关联；对象路径
不向浏览器直接暴露。候选数据不得直接成为 DatasetVersion 成员证据。

## 5. 可靠投递和安全

### 5.1 作业与 idempotency

建立一个 request receipt，键为 `(project_id, idempotency_key)`，保存请求指纹和返回 Job ID。

```text
同 key + 同请求指纹 → 返回同一 Job
同 key + 不同请求指纹 → 409 稳定错误码
不同 key + 同自然键 → 返回既有 Job，并原子建立新的 receipt
并发自然键冲突 → 捕获后锁定/读取既有 Job 与 receipt；不暴露 DB unique error
```

自然键至少包含项目、资产、冻结输入摘要和完整 profile 摘要。

### 5.2 capability 与 Outbox

attempt 表只保存 `lease_token_hash`。Outbox 在 publisher confirm 前临时保存原 capability，以支持可靠投递；
confirm 成功后，`ai-hand-pose-job.v1` 必须和 media/export 一样在同一事务删除 `lease_token`，设置
`payload_redacted_at`，并由数据库约束保证已发布 AI payload 不含 token。重试必须创建新 message ID 和
新 capability，禁止重发旧 body；DLQ、日志、审计和管理查询只保存脱敏诊断。

### 5.3 Worker

Worker 领取输入时验证独立机器身份、attempt、lease hash、worker ID 和冻结输入；下载过程流式复核大小、
SHA-256。心跳失败只能在本地 lease 到期前重试；失去 lease 后停止推理/回传。生产时间轴复用
`CONTAINER_PTS_STRICT_V1`，默认禁止以 `previous + 1 ms` 修正异常 PTS。

Core API 必须对 job/attempt/message/lease/input/profile/结果摘要做幂等接收；旧 attempt、失效 lease 或
不同内容复用 message ID 一律拒绝。重试、失联回收、最大次数和最终失败状态由一份状态机规格定义后再实现。

## 6. 人工审核与正式 Revision

候选审核至少有三条命令：

```text
ACCEPT：引用未改变的候选事实
MODIFY：保存人工修改后的关键点/属性与候选来源
REJECT：保留拒绝原因，不产生正式 revision
```

三者均产生追加式审计。`ACCEPT` 或 `MODIFY` 仅能调用 `annotationreview` 的公开服务创建新的
AnnotationRevision；仍要经过既有复核和清洗。任何 Job `SUCCEEDED`、候选读取或 Overlay 渲染均不得
自动创建 Revision，亦不得更新质量、数据集或导出状态。

## 7. 验证门禁

实施顺序与最小证据如下：

1. Phase A 单元测试：NaN/Inf、混合/异常 PTS、partial 恢复、准确指标命名；
2. 获批 Golden Set 的断网实测和书面 `GO`；
3. Phase B 契约测试：Ego 准入、profile 摘要、idempotency、redaction、租约、迟到/重复结果；
4. AI Worker 单元测试：契约解析、下载摘要、PTS、心跳、错误分类和无 token 日志；
5. 合成 MP4 跨进程验收：Core API → Outbox → RabbitMQ → AI Worker → Core API → 候选查看；
6. 人工 ACCEPT/MODIFY/REJECT 到 AnnotationRevision 的集成验收，以及候选不进入 DatasetVersion 的反向断言。

任一项缺失时，FR-042B 只能报告为实施中，不得报告为完成。
