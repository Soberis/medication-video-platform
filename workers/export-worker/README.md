# Export Worker

独立的数据集导出消费者。它只处理已经过独立审批并由 Core API 发放租约的
`p0-dataset-export-v2` 任务：

1. 从短期只读 URL 下载冻结的合成/受控 ClipAsset，并复核大小和 SHA-256。
2. 生成确定性的 Step-only ZIP（元数据、划分、原始视频和 `SHA256SUMS`）。
3. 通过任务范围内的 multipart URL 上传 ZIP。
4. 向 Core API 提交文件清单和包摘要；只有服务端复核通过后才会成为 `AVAILABLE`。

Worker 不连接业务数据库，不接触账号会话，也不记录签名 URL、lease token 或对象 key。
如果 Worker 未能继续心跳，Core API 会在租约和 grace period 到期后将旧 attempt 标记为
`LOST`，清理其临时输出，并按最大尝试次数决定重新入队或终止导出；旧租约不能复活。
启动时会计算当前源码摘要；若配置了 `EXPORT_WORKER_SOFTWARE_DIGEST`，它必须与计算值完全一致，不能用旧摘要冒充当前 Worker 版本。
`EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT` 仅供本地恢复验收使用，且必须同时具备合成数据、显式验收授权以及 loopback Core API/RabbitMQ；正常启动会关闭该开关。

本地运行由仓库根目录的 `scripts/start-local.ps1` 管理。单独运行：

```powershell
uv run --directory workers/export-worker export-worker
```

测试与静态检查：

```powershell
uv run --directory workers/export-worker ruff check .
uv run --directory workers/export-worker mypy
uv run --directory workers/export-worker pytest
```
