# Web

所有前端 UI 变更开始前必须阅读 [`docs/DESIGN_SYSTEM.md`](../../docs/DESIGN_SYSTEM.md)。不得在页面中引入第二套视觉语言、任意设计 Token 或一次性布局系统。

React 管理端。当前激活的纵向功能包括平台本地上传、上传状态与异常查询、CaptureGroup 视频管理、Media Probe、手部候选/Skeleton Overlay、人工质量、动作标注、复核、事件级 Cleaning，以及 MediaPipe 手部训练数据预览和 CSV 下载。上传状态与异常查询通过上传失败上下文进入，不作为主导航入口。事件资格与追溯 manifest 仍由服务端内部能力维护，不作为用户侧的手部训练数据下载入口。当前规则下，拥有 ACTIVE 项目 `ADMIN` Membership 的 `ROLE_ADMIN` 可自审/自批，页面必须显示 `SELF_ADMIN_APPROVAL`；`ROLE_MEMBER` 不能执行管理员命令。

## Commands

```powershell
pnpm run web:dev
pnpm run web:lint
pnpm run web:type-check
pnpm run web:test
pnpm run web:build
```

浏览器验收：

```powershell
pnpm run web:e2e
```

首次运行前安装 Chromium：

```powershell
pnpm --dir apps/web exec playwright install chromium
```

以上命令从仓库根目录运行；如在 `apps/web` 目录内工作，可分别使用 `pnpm dev`、`pnpm lint`、`pnpm type-check`、`pnpm test` 和 `pnpm build`。

开发服务器默认监听 `http://127.0.0.1:5173`（可由 `WEB_PORT` 覆盖），并把 `/api` 代理到 `http://127.0.0.1:${PLATFORM_API_PORT:-8080}`。Web 使用平台内部账号登录；密码不进入浏览器存储，服务端仅保存 BCrypt 哈希，浏览器通过 HttpOnly 会话 Cookie 与 CSRF Header 调用 API。项目可见性和 ADMIN/MEMBER 仍由后端 Membership 在每次请求中校验，前端从不自行授予权限。医院边端使用独立上传凭据，不使用 Web 登录页。

根路径重定向到 `/projects`，只列出当前用户具有 ACTIVE Membership 的项目。项目 ADMIN 可以修改项目显示名称或归档项目；归档会停止后续项目访问并从日常列表移除，但不物理删除项目、视频、对象或审计。进入项目后，`/projects/:projectId/capture-groups` 是当前视频管理入口；每个 CaptureGroup 详情页统一承载各路视频事实、Media Probe、MediaPipe 候选、逐视图质量、事件级质量、动作标注、复核和 Cleaning。`/projects/:projectId/annotation-tasks` 只承载 CaptureGroup 标注任务入口。`/uploads` 继续只读展示批次和文件进度，`/uploads/new` 用于平台本地上传。

`/uploads/new` 已启用平台本地上传：用户选择已加入的项目和一个或多个本机视频；每个视频使用独立 `PLATFORM_LOCAL` 批次，浏览器先在 Web Worker 中分块计算 SHA-256，再复用 Upload/Multipart/Complete/CaptureSession 协议直传对象存储。页面只显示运行期批次 ID，不记录或输出预签名 URL；多视频按顺序执行，避免误组装为同一次多相机拍摄。

CaptureSession、CameraSet 和 CameraStream 仍由上传接收协议记录为领域事实；它们不再作为独立的历史 Session 管理页面暴露。

当前已支持项目成员在媒体检测完成后显式点击播放；播放地址短期有效、`no-store` 且不落库。CaptureGroup 通过事件级质量准入后生成一个标注任务；受分配成员提交主 Step 时间段后，同一合格管理员可记录一次通过/不通过复核，且同人决定显示 `SELF_ADMIN_APPROVAL`。复核不通过会退回任务，下一次提交产生新的不可变 revision。管理员可在项目标注入口预览服务端统计的 MediaPipe 手部关键点样本，并在有样本时下载服务端生成的 CSV；事件资格与追溯 manifest 不依赖旧 DatasetVersion、Admission、Build、Freeze 或异步 Export Worker 流程。

认证会话使用 React Context；批次、资产、标注和训练导出等服务端对象继续使用页面局部状态，不复制为客户端业务真值。
