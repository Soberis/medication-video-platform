# ADR-0003：React Web 前端替换

- 状态：Accepted
- 日期：2026-07-27
- 替代范围：ADR-0001 决策第 1 条；其余技术基线保持不变

## 背景

现有 `apps/web` 已完成登录、项目、上传、Session、视频资产、人工质量、标注复核和后置清洗等纵向能力。当前产品决定把 Web 实现从 Vue 迁移到 React，但不改变业务需求、OpenAPI、权限、安全、审计、页面路由或部署边界。

迁移必须保持单一 Web 工程，不能长期维护两个前端，也不能把 Vue/React 混合运行时交付到生产环境。

## 决策

1. `apps/web` 使用 React 19.2.8、React DOM 19.2.8、TypeScript 6.0.3、Vite 8.1.4、`@vitejs/plugin-react` 6.0.4、React Router 7.18.0 和 Ant Design 6.5.2。
2. Web 仍是纯客户端 SPA，不引入 SSR、React Server Components 或新的服务端运行时。
3. 保留现有 `apps/web`、包名、构建输出、URL、`/api` 开发代理和部署方式；在迁移分支内直接替换 Vue 实现，不创建长期存在的第二个 Web 工程或双入口。
4. 认证会话使用 React Context；服务端业务对象继续使用页面局部状态。没有新的真实跨页面状态需求前，不引入 Redux、Zustand、TanStack Query、Axios 或通用客户端缓存层。
5. API、CSRF、幂等键、错误码映射、上传哈希 Worker 和上传协议优先原样复用。React 页面不得改变服务端事实、授权守卫或短期播放地址的内存边界。
6. Ant Design 只替换 Element Plus 的表格、表单、对话框、状态标签等组件能力；视觉语言继续使用既有“克制的临床作业台”和项目色彩，不借迁移重建设计系统。
7. 组件测试使用 Vitest、jsdom、React Testing Library 和 `user-event`；纯 API 测试继续使用 Vitest。路由、关键权限、上传、播放和标注闭环使用真实浏览器测试补齐。
8. React StrictMode 保持启用。读取请求必须可取消或去重；上传、播放授权、删除、复核等写操作只能由显式用户事件触发。

## 影响

- 13 个 Vue SFC、Vue Router、Pinia Auth Store、Element Plus 集成和 Vue 组件测试需要重写。
- `src/api/`、上传 Worker、核心上传编排、Vite 环境/代理逻辑、业务文案和大部分布局样式可复用。
- 迁移完成后删除 Vue、Pinia、Element Plus、Vue Test Utils、Vue ESLint 配置和 `vue-tsc`，锁文件只保留 React 技术栈。
- 部署回滚使用迁移前 Vue 构建产物或回退切换提交；迁移不要求数据库、OpenAPI 或后端行为变化。

## 非目标

- 不新增移动端、SSR、RSC、微前端或第二套 Web 应用。
- 不借迁移启用 Privacy、Dataset、AI、CVAT、Object 画框、图表或视频墙。
- 不把项目 Membership、服务端状态或审计事实复制成客户端业务真值。
