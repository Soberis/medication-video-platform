# Bruno API Collection

本目录保存可进入 Git 的 Bruno 请求集合。OpenAPI 是正式 API 契约，Bruno 用于手工调试和系统级自动化，不替代 OpenAPI 或 Java 合同测试。

## Bruno Desktop

在 Bruno 3.5.2 中点击 `Open Collection`，选择：

```text
<仓库根目录>\api-tests\bruno\medication-platform
```

选择 `local` 环境后，按顺序运行 `System` 和 `Uploads`，或者直接运行整个 Collection。

## Local runtime

先启动 PostgreSQL/S3 和后端：

```powershell
$repositoryRoot = git rev-parse --show-toplevel
Set-Location $repositoryRoot
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml up -d --wait

Set-Location (Join-Path $repositoryRoot 'services/core-api')
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DEV_BEARER_TOKEN = 'synthetic-local-bearer-token-do-not-reuse'
$env:DEV_ADMIN_BEARER_TOKEN = 'synthetic-local-admin-bearer-token-do-not-reuse'
.\mvnw.cmd spring-boot:run
```

Collection 根目录的 `.env` 被 Git 忽略，并为 Bruno Desktop 提供相同的本地合成令牌。不得在其中使用医院、生产或真实用户凭证。

## Codex and CLI

Bruno CLI 3.5.2 已锁定在根 `pnpm-lock.yaml`。后端运行后，在另一个终端执行：

```powershell
Set-Location (git rev-parse --show-toplevel)
pnpm run api:test
```

启动器按 `process.env`、仓库根 `.env`、Collection `.env`、`.env.example` 的顺序读取
`DEV_BEARER_TOKEN` 和 `DEV_ADMIN_BEARER_TOKEN`；默认本地配置无需再手动设置它们。覆盖时只在
未提交的仓库根 `.env` 中设置合成令牌，避免测试令牌与实际本地 API 配置不一致。

当前自动化场景覆盖：

1. API 健康检查；
2. UploadBatch 初始化；
3. 相同请求幂等重放；
4. 相同幂等键携带不同内容时返回冲突；
5. 注册一个大于 2GB 的不可变文件清单项；
6. 创建 Multipart Session 并签发短期 Part 上传授权；
7. 查询可恢复的 Part 进度；
8. 拒绝确认对象存储中不存在的 Part；
9. 从对象存储幂等协调 Part 进度；
10. 拒绝缺少存储 Part 的文件完成请求；
11. 读取数据库事实生成的批次上传进度；
12. 读取文件进度、确认字节和稳定完成错误码。
13. 验证 MediaJob 缺少幂等键、未知 ClipAsset 和未知 Job 的稳定错误响应；
14. 验证内部数据集导出端点拒绝缺失身份和普通 Web 身份，并返回 `no-store` 的稳定失败响应。

Bruno 不提交大体积二进制测试文件，因此成功直传、确认、恢复、Multipart Complete 和完整文件 SHA-256 的数据面证据由 Java 合同测试及 SeaweedFS 兼容性测试提供；Collection 负责验证真实运行 API 的控制面和失败守卫。所有请求只使用合成项目、设备和摘要。新接口只有在 OpenAPI、实现和对应测试同时就绪后才加入 Collection。
