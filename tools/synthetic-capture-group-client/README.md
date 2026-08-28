# Synthetic CaptureGroup Acceptance Tool

这是 V1.25 多机位配药事件后端闭环的合成数据验收工具。它调用真实 Core API，验证：

```text
显式 CaptureGroup manifest
→ CaptureSession / Receipt
→ Media Probe（可选 Candidate）
→ per-view human View Quality
→ CaptureGroup Quality
→ 一个 CaptureGroup AnnotationTask / append-only Revision chain
→ human Review
→ event-level Cleaning
→ training-data.v3 preview / download / readback
```

工具只负责验收编排，不改变生产 CaptureGroup、Annotation、Quality、Cleaning 或上传语义；不做前端工作，不创建 DatasetVersion、CaptureSync 或通用 workflow engine。

## 安全边界

- 每个视频都由现有 `tools/synthetic-upload-client/synthetic-video.mjs` 在临时目录中生成；不接受 `--video`、`--video-path`、`--source` 或任意源视频路径。
- 分组、expected camera numbers、present camera numbers、缺失相机和每路 `camera_number` 都由场景/manifest 显式给出，不从文件名、上传顺序或时间戳推断。文件名中的标签只是便于排查，平台不会解析它。
- bearer token 只从环境变量读取，不出现在 CLI 参数或 summary；预签名 URL、响应头和对象存储位置不输出。
- 临时 MP4 在成功和失败路径都会清理。API 侧合成记录使用唯一 key；当前没有安全的 CaptureGroup 删除接口，因此工具不会改变删除语义或直接清理服务器事实。
- 这是本地/测试环境的 synthetic acceptance，不是医院、患者数据、RK3588、相机/硬件或 UAT 验收。

## 使用

先启动本地 PostgreSQL、对象存储、RabbitMQ、Core API 和对应 Worker。v3 读取需要启用 synthetic export profile，例如 `platform.training-data-export.data-profile=synthetic`；`no-hand-detected` 和 `candidate-failure` 还需要 AI Worker/模型配置。

PowerShell 示例：

```powershell
$env:RUN_SYNTHETIC_CAPTURE_GROUP_E2E='true'
$env:DEV_BEARER_TOKEN='synthetic-local-bearer-token-do-not-reuse'
$env:DEV_ADMIN_BEARER_TOKEN='synthetic-local-admin-bearer-token-do-not-reuse'
pnpm run synthetic-capture-group -- --scenario n5-missing-camera
```

可选环境变量：

- `PLATFORM_BASE_URL`，默认 `http://localhost:8080`；
- `DEV_PROJECT_ID`，必须与启动 Core API 时使用的项目 ID 一致；未设置时仅适用于 `.env.example` 的默认本地 synthetic project；
- `FFMPEG_PATH`，FFmpeg 不在 `PATH` 时使用；
- `SYNTHETIC_E2E_TIMEOUT_MS`，Media Probe/Candidate 轮询上限。

命令默认拒绝服务调用；只有显式设置 `RUN_SYNTHETIC_CAPTURE_GROUP_E2E=true` 才运行昂贵的 service E2E。场景清单见 [`SCENARIOS.md`](./SCENARIOS.md)：

```powershell
pnpm run synthetic-capture-group -- --list
pnpm run synthetic-capture-group -- --scenario n1-complete
pnpm run synthetic-capture-group -- --scenario n2-complete
pnpm run synthetic-capture-group -- --scenario n5-complete
pnpm run synthetic-capture-group -- --scenario n5-missing-camera
```

快速、无服务 deterministic tests：

```powershell
pnpm run synthetic-capture-group:test
```

它们不运行 FFmpeg、不访问网络，也不触碰数据库。`synthetic-capture-group` 是 opt-in service E2E，不纳入日常 Strict Gate。

## 验收重点

- `receiving-before-finalize` 先读到 `RECEIVING`，显式 finalize 后才产生 `COMPLETE`/`INCOMPLETE`；Media Probe 失败不改 Receipt truth。
- `n5-missing-camera` 的 expected 是 `[1,2,3,4,5]`，present 是 `[1,3,4,5]`，Camera 3 始终是 3；`QUALIFIED_WITH_EXCLUSIONS` + `APPROVED` 才能让 Cleaning `PASS`。
- REJECTED view 保留并可审计，但在 `excluded_views` 中 `retained=true`、`downstream_included=false`；原始媒体不删除。
- Primary View 的 annotation interval 是唯一 action-aligned timebase。Supporting View 仅保留 `EVENT_LEVEL_EVIDENCE`；当前 synthetic V1.25 不提交 caller mapping，因此不产生伪造 supporting interval。
- Review 只由 admin API 明确记录；Candidate/AI 状态不会自动成为 human View Quality、Review 或正式 Annotation。
- v3 preview/download/readback 强制 `split_unit=capture_group`，并验证同一 CaptureGroup 跨 train/validation 的分配会被拒绝。v2 的既有 `synthetic-upload` 入口和行为保持不变。

`candidate-failure` 需要测试环境的 Worker 受控地产生不可重试失败；工具不会为了制造失败去修改生产语义或伪造 Worker 结果。没有该 fixture 时，该场景应保持未运行，而不是被解释为 UAT 通过。
