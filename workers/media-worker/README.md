# Media Worker

最小媒体探测 Worker，同时提供独立的 Canonical artifact writer；不连接平台数据库。媒体探测路径只消费 `media-probe-job.v1`，通过 Core API
取得 job-scoped 短期只读 URL，流式下载并独立复核大小和 SHA-256，再使用 FFprobe/FFmpeg
提取媒体事实和执行完整视频流解码，最后按 `media-probe-result.v1` 提交结果。

Canonical artifact writer 只接受调用方已经完成同步映射的 Frame Slot 输入，验证
`contracts/schemas/synchronized-frame-v1.schema.json` 的逻辑约束，按
`capture_id,sync_revision_id,sync_frame_index,camera_number` 规范排序后写出 Parquet、manifest
和 provenance。它不计算 anchor/drift、timeline 或 tolerance，不连接 PostgreSQL，不创建同步
revision/job，也不上传对象存储。默认 Parquet 物理布局是本地/测试用的非生产默认；生产布局和
容量参数仍需单独批准。

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
Set-Location (Join-Path (git rev-parse --show-toplevel) 'workers/media-worker')
uv sync --frozen
$env:MEDIA_WORKER_BEARER_TOKEN='local-media-worker-bearer-token-do-not-reuse'
$env:DATA_PROFILE='synthetic'
uv run media-worker
```

对已经冻结的合成 Frame Slot 输入，可直接在 Python 中调用：

```python
from medication_media_worker.artifact_writer import CanonicalArtifactWriter

result = CanonicalArtifactWriter().write(input_data, "./out/synchronized-frames.parquet")
```

`result.manifest` 是最小 manifest 的内存表示；调用方可以将其以 JSON 保存为 sidecar。writer
只产生调用方指定的本地输出，不执行业务状态提交。

所需环境变量：

- `MEDIA_WORKER_BEARER_TOKEN`：仅允许 `DATA_PROFILE=synthetic` 的本地静态凭据；
- `MEDIA_WORKER_BEARER_TOKEN_FILE`：非合成环境必填的绝对路径；身份代理写入短期 JWT，
  Worker 每次 Core API 请求前重新读取，因此可无重启轮换。两种 token 来源必须且只能配置一种；
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
Worker 失败退出，交由监督器退避重启。Core API 已提供管理员受控重放：只读取脱敏诊断身份，
校验当前 job/attempt 后创建新 capability 和消息并写追加式审计；仍禁止直接重投 DLQ body。

非 `local` / `test` 的 Core API 必须配置 `WORKER_JWT_ISSUER_URI`、`WORKER_JWT_JWK_SET_URI` 和
`MEDIA_WORKER_JWT_AUDIENCE`。可用
`WORKER_JWT_CLOCK_SKEW` 配置 0～5 分钟偏差（默认 `PT1M`）。缺项或非 HTTPS 配置会阻止服务启动。
