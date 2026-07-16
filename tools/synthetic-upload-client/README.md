# Synthetic Upload Client

本工具只生成并上传合成测试数据，用于 RK3588 边端程序尚未就绪时验证正式上传链路。它不接受源视频路径，不读取真实医院视频，也不通过 Core API 代理媒体字节。

完整流程：

```text
生成 2 秒合成 MP4、METADATA 和 EVENT_LOG
→ 配置可重复使用的单相机 CameraSet
→ 初始化批次并注册不可变文件清单
→ 获取短期 Part URL 并直传对象存储
→ Confirm Part / Complete File / Complete Batch
→ 组装 CaptureSession
→ 输出 batch_id、capture_session_id 和 clip_asset_ids
```

先启动本地 PostgreSQL、S3 和 `local` profile 后端，然后在 PowerShell 中运行：

```powershell
$env:DEV_BEARER_TOKEN='synthetic-local-bearer-token-do-not-reuse'
pnpm run synthetic-upload
```

可选环境变量：

- `PLATFORM_BASE_URL`：默认 `http://localhost:8080`；
- `DEV_PROJECT_ID`：默认本地合成项目；
- `FFMPEG_PATH`：FFmpeg 不在 `PATH` 时指定可执行文件。

安全边界：

- 命令行不接受令牌或视频路径，避免令牌进入进程参数，也避免误传真实视频；
- 不打印、不持久化预签名 URL；
- 临时 MP4 在完成或失败后都会删除；
- 该工具不是生产 RK3588 客户端，不证明 2GB、中断恢复、弱网或真实硬件验收通过。
