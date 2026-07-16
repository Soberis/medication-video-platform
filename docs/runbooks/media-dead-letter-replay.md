# 媒体死信与受控重放

## 适用范围

本手册只描述 `local/dev/test` 合成环境中已经验证的媒体任务演练，不授权操作生产队列、
真实医院数据或真实凭据。生产重放必须另行审批并使用批准的身份、留存和变更流程。

## 安全边界

- 业务队列 `platform.media.probe.v1` 不得配置 `x-dead-letter-exchange`；永久拒绝由 Worker
  显式发布 `media-dead-letter.v1` 脱敏诊断，broker 确认后才 ACK 原消息。
- 删除并重建队列前必须确认 `messages_ready=0`、`messages_unacknowledged=0`、`consumers=0`，
  并取得人工删除授权；不得删除 DLQ、vhost、Volume 或其他队列。
- DLQ 诊断不得包含原任务 body、lease token、签名 URL、对象 key、存储桶、输入摘要、
  异常原文或堆栈。
- DLQ 诊断作为证据按留存策略处理，不直接投回业务队列；重放只能调用 Core API。

## 空业务队列拓扑修复

先只读检查：

```powershell
docker exec medication-video-platform-rabbitmq-1 rabbitmqctl list_queues `
  -p medication name messages_ready messages_unacknowledged consumers arguments
```

仅当目标队列为空、没有消费者且已经获得人工授权时，删除该队列：

```powershell
docker exec medication-video-platform-rabbitmq-1 rabbitmqctl delete_queue `
  -p medication platform.media.probe.v1
```

使用当前版本 Core API 并启用消息 relay 后，由 Spring AMQP 重新声明 exchange、queue 和
binding。再次运行只读检查，`platform.media.probe.v1` 的 arguments 应只含 broker 默认的
queue type，不应出现 `x-dead-letter-exchange` 或 `x-dead-letter-routing-key`。

## 受控重放演练

1. 使用合成 ClipAsset 创建 `MEDIA_PROBE`，等待 Outbox 把 attempt 1 发布到业务队列。
2. 仅在隔离演练中用无效 Worker 服务身份消费，预期 Core API 拒绝租约，Worker 发布
   `MEDIA_JOB_ACQUIRE_REJECTED` 脱敏诊断并 ACK 原消息。
3. 通过 RabbitMQ 受控管理面以 requeue 模式读取诊断，校验 `media-dead-letter.v1` 字段，
   并确认不存在上述敏感字段。不得使用会确认并删除诊断的读取模式。
4. 项目管理员调用 `POST /api/v1/media-jobs/{media_job_id}/replays`，提交诊断身份、稳定原因、
   操作原因和非敏感变更引用；不得提交原消息或 lease token。
5. 校验旧 attempt 为 `FAILED`、新 attempt 为 `QUEUED`，重放记录、已脱敏 Outbox 和追加式
   审计事件均已写入。
6. 使用批准的软件摘要和正确 Worker 身份执行新 attempt，校验作业 `SUCCEEDED`、业务队列
   清空且 MediaReport 可按项目权限查询。

## 停止条件

出现以下任一情况应立即停止演练并人工调查：

- 目标队列非空、有未确认消息或仍有消费者；
- DLQ 诊断包含 capability、对象位置或原任务内容；
- publisher confirm 失败但原消息没有重新入队；
- 重放复用了旧 message ID、attempt 或 lease capability；
- 没有管理员审计记录，或审计中的项目、操作者、原因和旧/新 attempt 不一致。

2026-07-16 已在本地合成环境按上述边界完成一次真实 broker 演练；该证据不替代生产演练、
2GB/弱网/失联恢复、损坏媒体矩阵或 UAT。
