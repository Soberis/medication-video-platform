# Web

Vue 3 管理端。当前激活的纵向功能包括上传排障、护士配药 CaptureSession 和单路视频资产只读查询。

## Commands

```powershell
pnpm --dir apps/web dev
pnpm --dir apps/web lint
pnpm --dir apps/web type-check
pnpm --dir apps/web test
pnpm --dir apps/web build
```

开发服务器默认监听 `http://127.0.0.1:5173`（可由 `WEB_PORT` 覆盖），并把 `/api` 代理到 `http://localhost:8080`。Web 使用平台内部账号登录；密码不进入浏览器存储，服务端仅保存 BCrypt 哈希，浏览器通过 HttpOnly 会话 Cookie 与 CSRF Header 调用 API。项目可见性和 ADMIN/MEMBER 仍由后端 Membership 在每次请求中校验，前端从不自行授予权限。医院边端使用独立上传凭据，不使用 Web 登录页。

根路径重定向到 `/projects`，只列出当前用户具有 ACTIVE Membership 的项目。项目 ADMIN 可以修改项目显示名称或归档项目；归档会停止后续项目访问并从日常列表移除，但不物理删除项目、视频、对象或审计。进入项目后，`/projects/:projectId/sessions` 以一次护士配药操作为主对象展示多相机完整性、组装和质量状态，`/projects/:projectId/assets` 用于单路视频及项目内一层文件夹/未分类筛选；详情路由保持同一项目上下文。`/uploads` 继续只读展示批次和文件进度，`/uploads/new` 用于平台本地上传。

`/uploads/new` 已启用平台本地上传：用户选择已加入的项目、可选的一层文件夹和一个或多个本机视频；每个视频使用独立 `PLATFORM_LOCAL` 批次，浏览器先在 Web Worker 中分块计算 SHA-256，再复用 Upload/Multipart/Complete/CaptureSession 协议直传对象存储。页面只显示运行期批次 ID，不记录或输出预签名 URL；多视频按顺序执行，避免误组装为同一次多相机拍摄。

`/projects/:projectId/folders` 已提供项目内一层文件夹管理；管理员可创建、重命名和删除文件夹，删除只会将其中视频变为未分类。管理员也可在资产详情变更单个视频的分类；这些操作不改变项目权限或对象存储位置。

当前已支持项目成员在媒体检测完成后显式点击播放；播放地址短期有效、`no-store` 且不落库。合格视频可以由管理员创建并分配标注任务，受分配成员可在任务页提交一次含时间段和可选说明的不可变标注；另一名项目管理员可记录一次通过/不通过复核。当前不含对象框、返工或抽样复核。当前仍不包含外部 IdP、跨浏览器/医院网络的容量 UAT、上传中断后继续同一批次的页面恢复、派生视频或独立隐私流程。资产详情提供管理员软删除与项目内分类入口；视频软删除会立即阻断播放和后续业务使用，但不会在 P0 中直接物理删除对象或审计。

Pinia 已锁定技术选型和准确版本，但尚未注册，也没有 Store；出现登录会话或跨页面共享状态后再启用。
