# 配药视频数据处理平台

平台接收外部采集端上传的视频数据，在云端完成数据归组、媒体检查、MediaPipe 分析、必要的人工训练数据处理和导出；不负责医院业务、患者、药品业务、摄像头控制、设备管理、采集侧运维或医院工作流。

当前唯一需求入口：V1.35 Consolidated Baseline（已确定需求已收敛，D1–D5 分别约束相关待决实施项）。V1.35 正文自包含，不要求逐层阅读历史版本；V1.28 仍是上一正式运行时基线，需求收敛不表示现有 runtime 已全部实现或已授权删除。

V1.35 的唯一主链：

```text
数据接收
→ CaptureGroup
→ Media Probe
  ├─ MediaPipe 手部事实
  └─ Action Candidate 动作候选
→ 自动标注 / 必要时人工确认
→ Export Eligibility
→ human-demonstration-hdf5.v1
```

`ClipAsset` 是不可变视频证据对象，不是独立的产品工作流对象。`CaptureGroup` 是一次采集记录；`CameraStream` 是记录中的视频视角，不是平台管理的物理摄像头资产。Local Upload 的单视频场景统一建模为 `CaptureGroup → CameraStream #1 → ClipAsset`，不是第二条 single-video 流程。

采集端负责采集、缓存和上传策略；平台不控制相机或边端设备。MediaPipe 与 Action Candidate 独立运行。系统自动准备标注草稿，正确片段无需逐段采纳，用户可修改并最后显式提交；已提交 Revision 经现有 Review APPROVED 后才进入 HDF5 最终准入。当前唯一导出为 `human-demonstration-hdf5.v1`。

当前规范：

- [V1.35 Consolidated Baseline（当前唯一需求）](./docs/requirements/baselines/V1.35/配药视频数据处理平台需求文档_V1.35.md)
- [需求基线入口与版本 manifest](./docs/requirements/README.md)
- [当前架构文档入口](./docs/architecture/README.md)
- [动作候选技术设计（支持性 DRAFT）](./docs/architecture/v1.34-action-annotation.md)
- [自动标注与异常人工接管 ADR](./docs/adr/0004-automatic-annotation-approval-policy.md)
- [安全、隐私与保留销毁](./docs/architecture/security-privacy-retention.md)
- [部署、容量与灾难恢复](./docs/architecture/deployment-capacity-and-dr.md)
- [前端设计系统](./docs/DESIGN_SYSTEM.md)
- [Web 前端与用户体验设计规范](./docs/architecture/web-ux-and-frontend-guidelines.md)
- [当前需求追踪与实现证据](./docs/architecture/requirements-traceability.md)
- [Platform 文档 Active / History 清理清单](./docs/audits/v1.35-platform-document-active-history-inventory.md)
- [边端接收 Manifest 契约](./contracts/schemas/external-video-ingest-v1.schema.json)

领域模型、状态机和异步协议中的历史产品段落仅供参考。自动建任务与派发是待验收目标；现有显式分配、提交和复核入口保留。自动化准备不代替提交和复核，不覆盖人工修改。

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

部署前的运行时拓扑、配置边界、合成 smoke、备份恢复和故障恢复检查见[部署就绪检查与边界手册](./docs/runbooks/production-readiness.md)。该手册区分本地 synthetic smoke 与真实环境/UAT，不把前者当作生产通过。

默认每类 Worker 启动 2 个进程：不同视频会由 RabbitMQ 自动分发给可用 Worker，因此上传多个视频后会并行完成媒体检测和手部候选检测；同一视频的任务仍受租约和幂等保护，不会被多个进程重复处理。每个 AI Worker 都会独立占用模型、CPU 和内存；开发机资源紧张时可显式降为 1 个进程：

```powershell
.\scripts\start-local.ps1 -MediaWorkerConcurrency 1 -AiWorkerConcurrency 1
```

```bash
./scripts/start-local.sh --media-worker-concurrency 1 --ai-worker-concurrency 1
```

如需更高吞吐量，可以在确认本机 CPU、内存和模型容量后提高到最多 8 个副本。副本数只提高后台吞吐量；不会创建批量预标注入口，也不会绕过媒体/组级质量、草稿初始化规则、异常人工接管或最终导出校验。

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
