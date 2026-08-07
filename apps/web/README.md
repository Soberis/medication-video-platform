# Web

React 管理端。当前激活的纵向功能包括上传排障、护士配药 CaptureSession、单路视频资产查询、人工质量、管理员标注复核，以及数据集草稿、划分、构建配置、准备检查、显式构建、关系快照、冻结前技术校验、管理员冻结审批和 Step-only 训练包的申请、审批、生成状态与短期下载。V1.11 下，拥有 ACTIVE 项目 `ADMIN` Membership 的 `ROLE_ADMIN` 可自审/自批，页面必须显示 `SELF_ADMIN_APPROVAL`；`ROLE_MEMBER` 不能执行管理员命令。

## Commands

```powershell
pnpm run web:dev
pnpm run web:lint
pnpm run web:type-check
pnpm run web:test
pnpm run web:build
```

以上命令从仓库根目录运行；如在 `apps/web` 目录内工作，可分别使用 `pnpm dev`、`pnpm lint`、`pnpm type-check`、`pnpm test` 和 `pnpm build`。

开发服务器默认监听 `http://127.0.0.1:5173`（可由 `WEB_PORT` 覆盖），并把 `/api` 代理到 `http://localhost:8080`。Web 使用平台内部账号登录；密码不进入浏览器存储，服务端仅保存 BCrypt 哈希，浏览器通过 HttpOnly 会话 Cookie 与 CSRF Header 调用 API。项目可见性和 ADMIN/MEMBER 仍由后端 Membership 在每次请求中校验，前端从不自行授予权限。医院边端使用独立上传凭据，不使用 Web 登录页。

根路径重定向到 `/projects`，只列出当前用户具有 ACTIVE Membership 的项目。项目 ADMIN 可以修改项目显示名称或归档项目；归档会停止后续项目访问并从日常列表移除，但不物理删除项目、视频、对象或审计。进入项目后，`/projects/:projectId/sessions` 以一次护士配药操作为主对象展示多相机完整性、组装和质量状态，`/projects/:projectId/assets` 用于单路视频及项目内一层文件夹/未分类筛选；`/projects/:projectId/event-logs` 供项目成员按批次、设备和平台索引时间查询已验证的事件日志对象索引，不读取日志内容、不暴露存储地址或下载链接，也不推断设备动作。详情路由保持同一项目上下文。`/uploads` 继续只读展示批次和文件进度，`/uploads/new` 用于平台本地上传。

`/uploads/new` 已启用平台本地上传：用户选择已加入的项目、可选的一层文件夹和一个或多个本机视频；每个视频使用独立 `PLATFORM_LOCAL` 批次，浏览器先在 Web Worker 中分块计算 SHA-256，再复用 Upload/Multipart/Complete/CaptureSession 协议直传对象存储。页面只显示运行期批次 ID，不记录或输出预签名 URL；多视频按顺序执行，避免误组装为同一次多相机拍摄。

`/projects/:projectId/folders` 已提供项目内一层文件夹管理；管理员可创建、重命名和删除文件夹，删除只会将其中视频变为未分类。管理员也可在资产详情变更单个视频的分类；这些操作不改变项目权限或对象存储位置。

当前已支持项目成员在媒体检测完成后显式点击播放；播放地址短期有效、`no-store` 且不落库。合格视频可由拥有 ACTIVE 项目 `ADMIN` Membership 的 `ROLE_ADMIN` 创建并分配标注任务；受分配成员提交主 Step 时间段后，同一合格管理员可记录一次通过/不通过复核，且同人决定显示 `SELF_ADMIN_APPROVAL`。复核不通过会退回任务，下一次提交产生新的不可变 revision。Session 满足技术检测、人工质量、复核和 post-clean 准入条件时，管理员可创建 `DRAFT` 数据集草稿，并固定成员/证据快照、CaptureSession 级 pilot/holdout 划分和 `p0-dataset-export-v2` 构建配置；准备检查只读返回阻断项，零阻断时才可进入 `BUILDING`、封印关系快照并执行冻结前校验。`PASS` 建立待审批请求；同一合格管理员可批准并冻结，仍会重新校验全部技术证据并写入审计。`FROZEN` 后，合格管理员可申请并批准 `MODEL_TRAINING` 导出入队；页面显示真实生成状态，只有 `AVAILABLE` 才显示短期下载动作。当前尚不包含抽样复核、P1 Object/COCO、历史 v1 真实执行、外部 IdP、跨浏览器/医院网络容量 UAT、页面弱网恢复、派生视频或独立隐私流程。

认证会话使用 React Context；批次、资产、标注和数据集等服务端对象继续使用页面局部状态，不复制为客户端业务真值。
