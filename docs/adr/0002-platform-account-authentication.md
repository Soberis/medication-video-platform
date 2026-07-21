# ADR-0002：平台内部账号与会话认证

- 状态：Accepted
- 日期：2026-07-21

## 背景

医院侧 RK3588 只负责采集并自动上传视频，不使用 Web 管理端。平台的项目、视频、标注和数据集由内部管理人员操作，因此需要明确的人类管理账号，而不是把开发 Bearer 或医院 IdP 当作日常登录方案。

## 决策

1. P0 人类管理端使用平台内部账号密码登录，仅有 `ADMIN` 与 `MEMBER` 两种全局角色；项目内仍通过 ACTIVE Membership 决定可见性和项目级操作。
2. 密码只保存 BCrypt 哈希；浏览器使用 HttpOnly、SameSite=Lax 会话 Cookie。所有会改变平台状态的浏览器请求携带 `XSRF-TOKEN` Cookie 对应的 CSRF Header。
3. 首个管理员由未提交环境变量 `PLATFORM_BOOTSTRAP_ADMIN_USERNAME` 与 `PLATFORM_BOOTSTRAP_ADMIN_PASSWORD` 在首次启动时建立。没有自注册、默认密码、找回密码或匿名访问。
4. 医院边端、媒体 Worker 等非浏览器调用继续使用独立服务凭据；它们不使用平台用户 Cookie，也不得复用管理员账号密码。
5. 未来如出现明确的统一身份集成需求，可增加外部 IdP 适配器；不得删除平台账号、项目 Membership、审计和会话/CSRF 边界。

## 影响

- Web 在未登录时只显示登录页；登录后显示当前账号和角色。
- 管理员创建的项目仍会原子授予其首个项目 ADMIN Membership；普通用户必须被管理员加入项目。
- 用户管理、禁用、密码重置与登录限流作为后续纵向切片实现，不在本 ADR 中伪造页面或默认账号。
- HTTPS 环境必须设置 `PLATFORM_SESSION_COOKIE_SECURE=true`，并在 UAT 验证会话过期、CSRF、注销和账号停用。
