# 配药视频数据管理平台

平台对接医院边端采集系统接收固定舱内相机视频，在云端完成视频与数据管理、MediaPipe 手部预标注、配药动作标注、复核和数据清洗。

当前主线：

```text
边端接收 → 视频/数据管理 → 单视频手部预标注 → 配药动作标注复核 → 数据清洗 → 可用数据
```

边端负责采集、缓存和上传策略；平台不控制相机或边端设备。MediaPipe 只生成可追溯的候选和播放器 Overlay，不能自动成为正式标注或清洗结论。批量和自动预标注属于后续能力。

当前规范：

- [V1.18 需求基线](./docs/requirements/baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md)
- [当前技术设计](./docs/architecture/video-hand-pose-mvp-technical-design.md)
- [当前需求追踪矩阵](./docs/architecture/requirements-traceability.md)
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

`start-local` 启动本地基础设施、Core API 和已配置的 Worker；前端由 pnpm 单独启动。用 `status-local` 查看状态，用 `stop-local` 停止脚本启动的本地进程；停止不会删除数据卷。

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
services/platform-api/       Java Core API
workers/                     Media、AI 与导出 Worker
contracts/                   OpenAPI、事件、JSON Schema 和 profiles
api-tests/bruno/             服务核心场景
infra/docker-compose/        本地基础设施
docs/                        当前规范与历史参考
```

## 数据与安全

- 平台不接触生产数据库、医院生产账号、真实患者视频或生产密钥。
- 原视频、模型文件、令牌和预签名 URL 不得提交到仓库或写入日志。
- 视频普通删除为可审计软删除；彻底清理仅允许明确标识的合成测试数据，并需单独确认。
