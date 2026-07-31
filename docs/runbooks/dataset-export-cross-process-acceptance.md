# 数据集导出跨进程验收

## 1. 目的与边界

本手册验证已实现的 P0 `p0-dataset-export-v2` 最小闭环：Core API 创建项目和合成采集数据，Media Worker 完成技术检测，两个独立的合成身份完成标注、冻结和导出审批，Outbox 经 RabbitMQ 交给 Export Worker，最后由 Core API 登记 ZIP 并签发一次短期下载授权。

它只使用运行时生成的合成 MP4，只允许连接 `localhost`/loopback 且 `DATA_PROFILE=synthetic`。工具不接受视频路径、不读取真实数据、不输出或持久化任何预签名 URL、lease token 或账号密码。

这是一项本地验收，不是生产 UAT、性能压测、弱网演练或医院环境验收。

## 2. 人工审批与前置条件

运行会创建一个新的本地合成项目、两个 CaptureSession、不可变 DatasetVersion 和一个导出包；这些事实用于保留审计证据，工具不会自动删除或覆盖它们。因此，每次执行前需要由环境负责人明确批准。

1. 本机 Docker Desktop、Node.js 22、pnpm 11、Python 3.12、uv、ffmpeg 可用；
2. 未提交的 `.env` 只含本地合成配置，或直接使用仓库 `.env.example`；
3. `DATA_PROFILE=synthetic`，Core API 使用 `local` profile；
4. Core API、RabbitMQ、Media Worker 和 Export Worker 已启动且健康；
5. 运行环境不含真实患者视频、医院凭证或生产对象存储配置。

可用以下命令启动本地依赖：

```powershell
.\scripts\start-local.ps1
```

## 3. 执行

在环境负责人确认后，显式打开一次性开关：

```powershell
$env:RUN_DATASET_EXPORT_ACCEPTANCE='true'
pnpm run dataset-export:acceptance
```

工具自行从未提交的仓库根 `.env` 或 `.env.example` 读取本地合成令牌；进程环境变量优先。它拒绝非 loopback 地址、非 `synthetic` 数据配置、缺少两个本地身份或未设置显式开关的执行。

每个媒体入库最多等待 120 秒，Export Worker 最多等待 180 秒。成功时最后一行以 `ACCEPTANCE_RESULT` 开头，包含合成项目/版本/导出 ID、两个独立审批、Worker attempt、已登记产物的大小与 SHA-256，以及短期下载后复核的 SHA-256；不包含下载 URL。

## 4. 通过条件

- 两个 CaptureSession 均经媒体检测、人工质量、独立标注复核和 post-clean 后变为 eligible；
- DatasetVersion 依次通过 `DRAFT → BUILDING → VALIDATING → FROZEN`；
- 导出申请人与批准人为不同的 ACTIVE 项目管理员；
- 导出首次 attempt 从 `QUEUED` 进入 `AVAILABLE`，并登记一个非空 ZIP 产物；
- 仅通过 Core API 取得的短期下载内容大小和 SHA-256 与登记产物一致；
- 命令退出码为 0，Core API、RabbitMQ、Media Worker 与 Export Worker 日志无未解释错误。

失败时不要手工修改数据库、伪造结果或重用下载 URL。保留脱敏错误码、运行时间和合成项目 ID，再按同一提交定位故障；成功输出应与提交 SHA 一起记录为本地验收证据。

## 5. 尚未覆盖

- 生产身份、生产 S3、医院网络和真实视频；
- 导出失败后的人工恢复、长租约丢失和 DLQ 演练；
- 大数据集吞吐、并发导出、限带宽或断网；
- 正式 UAT、性能 SLO 与生产发布审批。
