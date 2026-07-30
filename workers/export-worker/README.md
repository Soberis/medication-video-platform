# Export Worker

独立的数据集导出消费者。它只处理已经过独立审批并由 Core API 发放租约的
`p0-dataset-export-v2` 任务：

1. 从短期只读 URL 下载冻结的合成/受控 ClipAsset，并复核大小和 SHA-256。
2. 生成确定性的 Step-only ZIP（元数据、划分、原始视频和 `SHA256SUMS`）。
3. 通过任务范围内的 multipart URL 上传 ZIP。
4. 向 Core API 提交文件清单和包摘要；只有服务端复核通过后才会成为 `AVAILABLE`。

Worker 不连接业务数据库，不接触账号会话，也不记录签名 URL、lease token 或对象 key。

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
