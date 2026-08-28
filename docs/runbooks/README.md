# Runbooks

本目录保存开发、UAT 和生产运行手册。

- [媒体死信与受控重放](./media-dead-letter-replay.md)：当前仅覆盖本地合成环境中已经验证的队列重建、脱敏诊断检查和管理员重放边界。
- [上传终止与过期清理](./upload-abort-and-expiry-cleanup.md)：说明破坏性调度开关、可观察状态、启用前检查和失败升级边界。
- [P0 Web 与数据闭环 UAT](./p0-uat-acceptance.md)：固定真实角色、目标桌面浏览器、主 Step 闭环、证据和失败退出口径；不把本机演示当作 UAT。
- [生产就绪检查与部署边界](./production-readiness.md)：V1.25 实际运行拓扑、本地 synthetic smoke、配置/秘密边界、备份恢复、健康检查、故障恢复和回滚边界；不把本地 Compose 当作生产部署。

队列死信、上传清理、UAT、密钥轮换和隐私事件仍由对应专项手册或目标环境责任人负责；未验证的生产命令不得从本地步骤直接推导。
