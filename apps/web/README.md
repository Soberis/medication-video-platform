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

开发服务器默认监听 `http://127.0.0.1:4173`，并把 `/api` 代理到 `http://localhost:8080`。本地 API 使用开发认证时，应在启动 Vite 的进程环境中提供与后端一致的合成 `DEV_BEARER_TOKEN`；Vite 只在服务端代理请求中注入该令牌，不将它编译到浏览器代码。不得使用医院、生产或真实用户令牌。

根路径重定向到 `/sessions`，以一次护士配药操作为主对象展示多相机完整性、组装和质量状态；`/sessions/:id` 展示冻结的 CameraSet 与各路 CameraStream；`/assets` 和 `/assets/:id` 用于单路视频筛选与排障；`/uploads` 继续只读展示批次和文件进度。当前页面不会上传视频、自动协调对象存储或改变业务状态，也不会在浏览器分页后伪造聚合工作台。

Element Plus 2.14.3 已在首个真实 Session/资产列表阶段启用，页面按路由懒加载。当前仍不包含 OIDC、视频播放器、生产级浏览器大文件上传器或 Complete Batch 页面操作；Complete Batch 由 RK3588 边端上传程序或复用同一协议的合成上传客户端自动调用，Web 只读展示结果。自动化客户端稳定后可增加仅 local/dev/test 启用的 `/import` 受控手动导入，但不得绕过后端环境、权限、项目、审计和大小策略。媒体处理、隐私评估和预览授权完成前，资产详情明确说明预览尚未开放，不请求对象存储定位或预签名 URL。

Pinia 已锁定技术选型和准确版本，但尚未注册，也没有 Store；出现登录会话或跨页面共享状态后再启用。
