# Media Worker

最小媒体探测 Worker，只消费 `media-probe-job.v1`，不连接平台数据库。它通过 Core API
取得 job-scoped 短期只读 URL，流式下载并独立复核大小和 SHA-256，再使用 FFprobe/FFmpeg
提取媒体事实和执行完整视频流解码，最后按 `media-probe-result.v1` 提交结果。

安全与运行边界：

- 只允许合成测试视频；不得处理真实医院视频或生产凭据；
- RabbitMQ 使用 manual ack、`prefetch=1` 和 publisher confirm；永久拒绝只向 DLQ 发布
  `media-dead-letter.v1` 脱敏诊断，确认后才 ACK 原消息；
- 不记录或复制 broker URL、服务令牌、租约令牌、签名 URL、对象 key、原任务 body 或
  FFmpeg 原始错误到 DLQ；
- 临时文件使用随机名称，完成或失败后删除；
- Worker 不写 PostgreSQL，所有状态和 MediaReport 只能由 Core API 落库；
- 软件摘要默认由 Worker Python 源码、`uv.lock` 和批准 profile 的原始字节计算；部署时可用
  `MEDIA_WORKER_SOFTWARE_DIGEST` 覆盖为经过批准的不可变镜像摘要。

本地运行前需要 Python 3.12、FFmpeg、RabbitMQ 和启用 Worker 内部接口的 Core API：

```powershell
cd C:\Dev\Projects\medication-video-platform\workers\media-worker
uv sync --frozen
$env:MEDIA_WORKER_BEARER_TOKEN='local-media-worker-bearer-token-do-not-reuse'
uv run media-worker
```

所需环境变量：

- `MEDIA_WORKER_BEARER_TOKEN`：必填，独立 Worker 服务凭据；
- `PLATFORM_API_BASE_URL`：默认 `http://localhost:8080`；
- `RABBITMQ_URL`：默认本地 `medication` vhost；
- `MEDIA_WORKER_ID`：默认由主机名和进程 ID 组成，不得包含人员或患者信息；
- `FFMPEG_PATH` / `FFPROBE_PATH`：默认从 `PATH` 查找；
- `MEDIA_PROBE_TIMEOUT_SECONDS`：默认 1800 秒；
- `MEDIA_WORKER_TEMP_DIR`：可选受控临时目录；
- `MEDIA_WORKER_SOFTWARE_DIGEST`：可选，格式为 `sha256:<64 hex>`。

本地 Core API 已提供受独立 Worker 身份和 attempt 租约保护的输入授权、心跳与结果接口。
Worker 在处理期间按服务端返回的 heartbeat interval 后台续租；心跳短暂失败会在本地租约
到期前重试，租约失效或 attempt 被回收后停止提交旧结果。平台在 lease grace period 后自动
创建带新 capability 的 attempt，达到 attempt 上限才终止业务 job。启动常驻消费者前，平台
必须把 Worker 当前软件摘要加入准确 allowlist；只允许在 local/dev/test 使用合成视频联调。
Worker 提交的 retryable 失败由 Core API 使用 Outbox 延迟创建新 attempt；当前消息在结果被
接受后 ACK，Worker 不在消费者进程内 sleep 或重复旧 attempt。永久拒绝消息只生成稳定原因码、
安全摘要、原 body 摘要/大小和安全 job 标识；若该诊断未获 broker 确认，原消息重新入队且
Worker 失败退出，交由监督器退避重启。受控 DLQ 人工重放仍未实现，禁止直接重投 DLQ body。
