# V1.19 G0 Golden Capture

这是一次性、纯合成、非生产验证工具。它不调用 Core API，不连接 PostgreSQL、RabbitMQ 或对象
存储，也不创建正式同步表、正式 API、正式 Worker 路由或 DatasetVersion 事实。

工具使用 FFmpeg 生成五组低分辨率合成 MP4，并通过 FFprobe 读取每帧原始 PTS：

- Case A：Expected/Actual 均为 Camera `[1,2,3]`；
- Case B：Expected `[1,2,3,4,5]`、Actual `[1,3,5]`；
- Case C：30 FPS 与 25 FPS；
- Case D：Camera 3 局部缺帧，并在 Camera 2 的 fixture manifest 中注入一个稳定的
  `SYNTHETIC_DECODE_VALIDATION_FAILED` 帧；
- Case E：VFR、非零原始 PTS。

Case D 的 `INVALID` 是 manifest 层故障注入，用于验证 `INVALID` 与 `MISSING` 的逻辑契约，
不是损坏 MP4 的解码器检出证明。所有 MP4 都由本机 FFmpeg 生成到 `tmp/`，受仓库
`.gitignore` 排除，不作为媒体 fixture 提交。

## 运行

要求 Python 3.12、uv、FFmpeg 和 FFprobe：

```powershell
Set-Location (Join-Path (git rev-parse --show-toplevel) 'tools/golden-capture-g0')
uv sync --frozen
uv run ruff format --check .
uv run ruff check .
uv run python -m mypy .
uv run python -m pytest
uv run golden-capture-g0 --worker-trials 3
```

最后一条命令默认把合成媒体、Parquet 和机器相关原始证据写到仓库
`tmp/v1.19-g0/`。可用 `--work-dir` 和 `--evidence-out` 覆盖位置。

## 证据边界

`30 Hz + 20 ms` 只作为本工具比较候选时使用的 `G0_MEASUREMENT_ONLY_NOT_APPROVED` profile，
不是生产同步 profile。输出同时比较 10/25/30 Hz、10/15/20 ms、不同压缩与 row-group
候选。目标业务时间分辨率、真实边端公共时钟、实际同步误差、目标规模、Java/跨语言读取、
对象存储和队列均不在本工具证据内；这些值不能从小型本机合成结果外推为生产常量。
