# 配药视频数据管理平台

平台对接医院边端采集系统接收固定舱内相机视频，在云端完成视频与数据管理、MediaPipe 手部预标注、配药动作标注、复核和数据清洗。

当前需求基线：V1.25（多机位 CaptureGroup 设计已批准，运行时尚未完整实现）

```text
一次真实配药事件 → 一个 CaptureGroup → 动态 N 路视图 → 逐视图 Media Probe / MediaPipe Candidate
→ 人工质量与组质量准入 → 一个组 AnnotationTask / Revision 修订链 → Review → 事件级 Cleaning → 版本化训练数据
```

当前已实现运行时（V1.24-compatible 单视频流程）：

```text
边端接收 → 视频/数据管理 → Media Probe → 单视频 MediaPipe Candidate
→ 人工质量判断 → 合格后自动任务分配 → 人工配药动作标注复核 → 数据清洗 → 可用数据
```

边端负责采集、缓存和上传策略；平台不控制相机或边端设备。当前运行时每个成功入库视频会自动提交一个幂等 MediaPipe 候选作业；MediaPipe 只生成可追溯的候选和播放器 Overlay，不能自动成为正式标注或清洗结论。质量合格只会自动创建并分配人工标注任务，不能跳过人工 revision、复核、清洗或管理员导出确认。V1.25 的 CaptureGroup 组质量、组任务/修订、事件级 Cleaning 和多视图导出仍待实施；批量 API 和可配置自动策略属于后续能力。

当前规范：

- [V1.25 当前正式需求基线（多机位 CaptureGroup）](./docs/requirements/baselines/V1.25/配药视频数据管理平台需求文档_V1.25.md)
- [V1.25 多机位实现技术设计](./docs/architecture/capture-group-multicamera-workflow-technical-design.md)
- [V1.25 多机位质量评审与清洗工作流](./docs/architecture/multi-camera-quality-review-workflow.md)
- [当前需求追踪矩阵](./docs/architecture/requirements-traceability.md)
- [V1.24 前一正式基线与当前运行时参考](./docs/requirements/baselines/V1.24/配药视频数据管理平台需求文档_V1.24.md)
- [V1.24 单视频手部预标注组件设计](./docs/architecture/video-hand-pose-mvp-technical-design.md)
- [边端接收 Manifest 契约](./contracts/schemas/external-video-ingest-v1.schema.json)

## 本地运行

要求 Java 21、Node.js 22、pnpm 11、Python 3.12、uv、ffmpeg 和 Docker Desktop。开发、测试和 CI 只使用合成或明确授权且隔离的数据。

Windows PowerShell 7：

```powershell
.\scripts\check-environment.ps1
.\scripts\start-local.ps1
cd apps\web
pnpm dev
```

macOS / Linux：

```bash
./scripts/check-environment.sh
./scripts/start-local.sh
cd apps/web
pnpm dev
```

`start-local` 启动本地基础设施、Core API、Media Worker，并在手部模型配置完整时启动 AI Worker；LEAN-P0 不启动已退役的 Export Worker。前端由 pnpm 单独启动。用 `status-local` 查看状态，用 `stop-local` 停止脚本启动的本地进程；停止不会删除数据卷。

默认每类 Worker 启动 2 个进程：不同视频会由 RabbitMQ 自动分发给可用 Worker，因此上传多个视频后会并行完成媒体检测和手部候选检测；同一视频的任务仍受租约和幂等保护，不会被多个进程重复处理。每个 AI Worker 都会独立占用模型、CPU 和内存；开发机资源紧张时可显式降为 1 个进程：

```powershell
.\scripts\start-local.ps1 -MediaWorkerConcurrency 1 -AiWorkerConcurrency 1
```

```bash
./scripts/start-local.sh --media-worker-concurrency 1 --ai-worker-concurrency 1
```

如需更高吞吐量，可以在确认本机 CPU、内存和模型容量后提高到最多 8 个副本。副本数只提高后台吞吐量；不会创建批量预标注入口，也不会绕过人工质量判断、动作标注、复核或清洗。

## 验证

Windows：

```powershell
.\scripts\quality-gate.ps1 -Strict
```

macOS / Linux：

```bash
./scripts/quality-gate.sh --strict
```

## 目录

```text
apps/web/                    React 管理端
services/core-api/           Java Core API
workers/                     Media 与 AI Worker
contracts/                   OpenAPI、事件、JSON Schema 和 profiles
api-tests/bruno/             服务核心场景
infra/docker-compose/        本地基础设施
docs/                        当前规范与历史参考
```

## 数据与安全

- 平台不接触生产数据库、医院生产账号、真实患者视频或生产密钥。
- 原视频、模型文件、令牌和预签名 URL 不得提交到仓库或写入日志。
- 视频普通删除为可审计软删除；彻底清理仅允许明确标识的合成测试数据，并需单独确认。
