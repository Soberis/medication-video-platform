# Runbooks

本目录保存开发、合成验证、UAT 和生产运行手册。运行手册说明如何操作环境，不定义产品需求；产品边界以 [V1.35 Consolidated Baseline](../requirements/baselines/V1.35/配药视频数据处理平台需求文档_V1.35.md) 为准。

## 当前运行手册

- [2 GiB 上传中断恢复验收](./large-upload-resume-acceptance.md)：上传续传和断点恢复的合成验收步骤。
- [媒体死信与受控重放](./media-dead-letter-replay.md)：本地合成环境中已经验证的队列重建、脱敏诊断检查和管理员重放边界。
- [上传终止与过期清理](./upload-abort-and-expiry-cleanup.md)：破坏性调度开关、可观察状态、启用前检查和失败升级边界。
- [生产就绪检查与部署边界](./production-readiness.md)：本地 synthetic smoke、配置/秘密边界、备份恢复、健康检查、故障恢复和回滚边界；不把本地 Compose 当作生产部署。

## 历史手册

- [P0 Web 与数据闭环 UAT](./p0-uat-acceptance.md)：历史 UAT 口径，不是当前发布流程。
- [V1.25 UAT Readiness](./v1.25-uat-readiness.md)：历史现场准备模板，不定义当前验收。

队列死信、上传清理、UAT、密钥轮换和隐私事件仍由对应专项手册或目标环境责任人负责；未验证的生产命令不得从本地步骤直接推导。
