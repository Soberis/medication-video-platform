# P0 Web 与数据闭环 UAT

## 目的与边界

本手册把 V1.11 的 P0 主 Step 闭环转化为可记录的 UAT；未变更能力继承 V1.10：

```text
登录与项目隔离
→ 本地合成 MP4 上传与人工播放
→ 媒体检测、质量、标注、管理员复核与 post-clean
→ 数据集构建、管理员冻结/导出审批与下载校验
```

它不替代本地跨进程导出验收，也不验证真实患者数据、医院网络、移动端、Object/COCO、AI 或派生清洗视频。没有经批准的环境、身份和样本时，不得把开发机结果写为 UAT 通过。

## 前置记录

开始前由环境负责人填写并保存以下事实；不得记录密码、令牌、预签名 URL 或视频内容。

| 项目 | 必须记录 |
| --- | --- |
| 版本 | Git commit、Core API/Web/Worker 镜像或构建版本、数据库迁移版本 |
| 环境 | 隔离环境标识、对象存储产品版本、是否只含合成或已批准样本 |
| 浏览器 | 目标浏览器及准确版本、操作系统、显示缩放比例 |
| 角色 | 管理员 A（`ROLE_ADMIN` + ACTIVE 项目 `ADMIN` Membership，可创建、复核、申请和审批）与成员 M（`ROLE_MEMBER`，用于上传/标注及管理员命令反向验证）。A 对自己的复核、冻结和导出申请执行审批；A/M 必须是不同账号，M 不得临时提权。 |
| 样本 | 至少两次符合格式的合成或已批准 CaptureSession，及其来源批准记录 |

浏览器验证以 1366×768、1920×1080、2560×1440、3840×2160 为视口基线；记录每个视口的缩放比例。手机和触摸操作不属于本次 UAT。

## 本地浏览器预检查（开发证据）

`pnpm run p0-web:preflight` 是明确授权的本地开发预检查，不是正式 UAT。它只接受 `DATA_PROFILE=synthetic` 和 `localhost`/loopback Web 地址；使用独立临时 Edge 配置，只点击登录，结束前退出登录。除认证会话的登录和退出外，不会写入业务数据：不会上传、播放、下载、创建、冻结、审批或导出。

它在四档视口检查登录页、指定标注任务行和数据集详情；标注必须同时显示复核通过、自审标记和 post-clean PASS，数据集分别检查冻结和导出卡片。截图和 JSON 结果写到已忽略的 `reports/web-preflight/run-*`；不得提交其中的图片、Cookie、URL 或凭据。

运行前准备好当前合成验收的项目、管理员自审的 AnnotationTask 和已冻结/导出的 DatasetVersion。macOS 本地示例：

```bash
set -a
source .env.example
source .env
RUN_P0_WEB_PREFLIGHT=true \
WEB_PREFLIGHT_USERNAME="$PLATFORM_BOOTSTRAP_ADMIN_USERNAME" \
WEB_PREFLIGHT_PASSWORD="$PLATFORM_BOOTSTRAP_ADMIN_PASSWORD" \
P0_WEB_PREFLIGHT_PROJECT_ID='<project-id>' \
P0_WEB_PREFLIGHT_ANNOTATION_TASK_ID='<self-review-task-id>' \
P0_WEB_PREFLIGHT_DATASET_VERSION_ID='<frozen-version-id>' \
pnpm run p0-web:preflight
```

成功输出为 `PRECHECK_PASSED`，并记录 Edge 版本、ADMIN 会话、未登录重定向、页面级溢出和截图 SHA-256。它不证明 MEMBER 反向权限、真实上传/播放/seek、下载、审计 SQL 或人工键盘全流程；这些仍按下面的 U1～U7 在批准环境执行。

## 执行清单

每项同时记录执行人、时间、结果、稳定错误码（如有）和证据位置。失败时停止该路径，不手改数据库或重用短期 URL。

| 编号 | 角色 | 操作与通过条件 |
| --- | --- | --- |
| U1 | 未登录、M | 未登录访问受保护路由回到登录；M 只能看到已加入的项目，不能进入管理或其他项目数据。 |
| U2 | A/M | 上传一段批准样本；页面显示真实上传终态。已完成完整性校验的 MP4 可显式播放和 seek，播放地址不出现在页面、持久化存储或证据附件中。 |
| U3 | A/M | 显式发起媒体检测；报告显示时长、编码和完整解码终态。检测失败样本不得进入质量/标注候选。 |
| U4 | A/M | 对检测通过的视频记录人工质量；A 创建并直接分配给自己，提交主 Step 后由 A 复核。响应中的提交者/复核者为同一 actor，页面和审计显示 `SELF_ADMIN_APPROVAL`；M 记录复核必须得到 `403 + PROJECT_ACCESS_DENIED`，且不新增复核、post-clean 或审计成功事实。A 复核通过后确认 post-clean 为 PASS。 |
| U5 | A/M | A 使用两次 eligible CaptureSession 创建草稿、固定 pilot/holdout 划分、构建配置和关系快照；执行冻结前校验并由 A 批准自己发起的冻结。响应中的申请者/批准者为同一 actor，页面和审计显示 `SELF_ADMIN_APPROVAL`；M 冻结必须得到 `403 + PROJECT_ACCESS_DENIED`，且不新增审批或改变版本状态。冻结后不得修改成员或配置。 |
| U6 | A/M | A 为已冻结版本申请并批准自己的 `MODEL_TRAINING` 导出。响应中的申请者/批准者为同一 actor，页面和审计显示 `SELF_ADMIN_APPROVAL`；M 批准必须得到 `403 + PROJECT_ACCESS_DENIED`，且不新增审批、attempt、Outbox 或成功审计。确认状态依次反映 REQUESTED/QUEUED/RUNNING/AVAILABLE 或稳定失败码；仅 AVAILABLE 可显式下载，下载文件 SHA-256 与登记摘要一致。 |
| U7 | A/M | 在四个规定视口抽查登录、项目、配药操作、视频资产列表、视频详情、标注任务、本地上传、管理和数据集页面：无页面级横向滚动、重叠、被遮挡的主操作或不可见焦点；键盘可完成主要操作，状态和错误不只依赖颜色。 |

## 通过、失败与留档

- U4～U6 的页面和审计证据分开留存：页面截图证明同一 actor 与 `SELF_ADMIN_APPROVAL` 标记；审计由环境负责人通过受控只读查询仅核对 action、对象标识、actor、outcome 和 reason code。视频操作记录是 ClipAsset 范围投影，不能替代冻结或导出的通用审计证据。
- 所有适用项通过，且没有未解释的权限、状态机、数据完整性或安全问题，才可将相应需求条目附上 `UAT` 证据；不是把整张追踪矩阵一次性改为通过。
- 任何失败均记录最小复现步骤、时间、错误码、request ID（如可安全记录）和关联提交；保留脱敏日志，不保存下载地址、凭据或原始视频。
- UAT 结束后将结果链接回[需求追踪矩阵](../architecture/requirements-traceability.md)，并明确尚未覆盖的生产容量、恢复、TLS、对象存储和医院网络验证。
