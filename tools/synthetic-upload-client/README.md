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
$env:DEV_ADMIN_BEARER_TOKEN='synthetic-local-admin-bearer-token-do-not-reuse'
pnpm run synthetic-upload
```

默认 `local` profile 会创建名为“本地合成上传验证”的保留项目，作为没有显式目标时的本地夹具。
本地合成 bearer 只用于测试：在 `local` profile 下可直接操作明确指定的 ACTIVE 项目，不要求先创建
`local-synthetic-uploader`/`local-synthetic-admin` 的项目成员关系，也不会把这条旁路带入生产。
设置 `LOCAL_SYNTHETIC_PROJECT_ENABLED=false` 可关闭该开发夹具。此行为不在生产或其他 profile 中加载。

可选环境变量：

- `PLATFORM_BASE_URL`：默认 `http://localhost:8080`；
- `DEV_PROJECT_ID`：默认本地合成项目；
- `DEV_ADMIN_BEARER_TOKEN`：仅用于一次性配置合成 CameraChannel/CameraSet；
- `FFMPEG_PATH`：FFmpeg 不在 `PATH` 时指定可执行文件。

工具用管理员身份配置项目内的合成相机，再用上传身份模拟边端上传；这保持了“项目配置仅管理员可改、边端只负责上传”的权限边界。

安全边界：

- 命令行不接受令牌或视频路径，避免令牌进入进程参数，也避免误传真实视频；
- 不打印、不持久化预签名 URL；
- 临时 MP4 在完成或失败后都会删除；
- 该工具不是生产 RK3588 客户端，不证明 2GB、中断恢复、弱网或真实硬件验收通过。

## 2 GiB 中断恢复验收

常规 `synthetic-upload` 仍只负责小型功能闭环。独立的 opt-in 工具 `large-upload-acceptance.mjs` 用两个 Node 进程验证准确 2 GiB 的 Multipart 中断恢复，不接收源文件、不保存签名 URL，并在结束时 Abort 和清理自己创建的合成对象。

执行条件、环境授权、冻结口径和通过标准见 [`docs/runbooks/large-upload-resume-acceptance.md`](../../docs/runbooks/large-upload-resume-acceptance.md)。该耗时测试不进入日常 `quality-gate`，必须显式设置 `RUN_LARGE_UPLOAD_ACCEPTANCE=true`。
