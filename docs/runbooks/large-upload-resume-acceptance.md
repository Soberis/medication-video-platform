# 2 GiB 上传中断恢复验收

## 1. 目的与边界

本验收只使用运行时生成的合成 MP4，验证 NFR-PERF-001 和 FR-003 的单文件容量、客户端进程中断后恢复、最终完整文件 SHA-256 以及 Complete File 幂等。它不读取真实医院视频，不验证弱网、并发、医院终端或 RK3588 硬件，也不能单独替代 UAT。

测试数据是一个可解码的短 MP4 前缀和一个合法 MP4 `free` box，准确填充到 2 GiB。工具逐 Part 生成字节，内存只保留一个 Part，不创建或提交 2 GiB 源文件。

## 2. 冻结口径

| 项目 | 本地基线 |
| --- | --- |
| 文件大小 | 2,147,483,648 bytes（准确 2 GiB） |
| Part 大小 | 67,108,864 bytes（64 MiB） |
| Part 数量 | 32 |
| 客户端 | 单进程、顺序上传、本机 loopback |
| 中断点 | 第一进程确认 Part 1，上传但不确认 Part 2，然后退出 |
| 恢复 | 第二个 Node 进程读取不含凭证的 checkpoint，先执行 reconciliation |
| 完成 | 32 Parts 全部确认；最终大小和 SHA-256 一致；重复 Complete File 响应一致 |
| 清理 | Abort 本次未完成批次，等待异步清理删除本次合成对象 |

## 3. 前置条件

1. 使用仓库准确版本启动 PostgreSQL、SeaweedFS S3 和 Core API；
2. Core API 使用 `local` profile、合成身份，并显式启用上传清理执行器；
3. 清理只针对本工具新建的合成批次，运行前确认没有使用生产或医院配置；
4. 至少预留 4 GiB Docker 数据空间和足够的测试时间；
5. `ffmpeg`、Node.js 22 和 pnpm 11 可用。

## 4. 执行

```powershell
$env:DEV_BEARER_TOKEN='synthetic-local-bearer-token-do-not-reuse'
$env:DEV_PROJECT_ID='00000000-0000-0000-0000-000000000001'
$env:RUN_LARGE_UPLOAD_ACCEPTANCE='true'
pnpm run synthetic-upload:2gb
```

工具不会打印或持久化预签名 URL。成功时最后一行以 `ACCEPTANCE_RESULT` 开头，并包含容量、恢复、摘要、重复 Complete 和清理结果；批次 ID 只存在于临时 checkpoint，结束后删除。

## 5. 通过条件

- `process_boundary_resume=true`；
- `uploaded_unconfirmed_part_recovered=true`；
- `reconciled_uploaded_parts=2`；
- `total_size_bytes=2147483648` 且 `total_parts=32`；
- `verified_sha256` 为 64 位小写十六进制；
- `complete_replay_identical=true`；
- `cleanup_status=SUCCEEDED` 或 `NOT_REQUIRED`；
- Core API、S3 和数据库无未解释的异常，工具退出码为 0。

失败时不得手工改数据库。先保留脱敏错误码和运行条件，再通过同一批次 Abort/清理状态确认合成对象是否已回收。

## 6. 尚未覆盖

- 限带宽、延迟、丢包和长时间断网；
- 多 RK3588 或多文件并发；
- 浏览器 2 GiB 上传；
- S3 产品与医院网络的 UAT；
- 媒体探测、隐私、标注和数据集业务验收。

## 7. 2026-07-17 本地合成基线结果

本次结果来自本证据所在提交的代码（基于 `b27a6fc`），运行于 Windows 本机 loopback、Docker Desktop 29.4.3、Java 21.0.11、Node.js 22、PostgreSQL 18.4 和固定摘要的 SeaweedFS 4.29。Core API 使用 `local` profile，RabbitMQ relay 关闭，上传清理执行器仅为回收本次合成对象而启用。

第一次扩展到三 Part 时发现 SeaweedFS 4.29 对 `partNumberMarker + maxParts=1` 在第三 Part 返回空页，但权威分页列表包含该 Part。适配器保留标准精确查询，并仅在空页时回退到全量分页核验；没有接受客户端 ETag。`S3MultipartCompatibilityTests` 已扩展为三个不同内容的 8 MiB Part，验证精确查找回退、Complete、整对象 SHA-256、读取、删除和 Abort。两次诊断性失败批次均为 `ABORTED / cleanup SUCCEEDED`。

最终运行结果：

```json
{
  "schema_version": "large-upload-resume-acceptance-v1",
  "total_size_bytes": 2147483648,
  "part_size_bytes": 67108864,
  "total_parts": 32,
  "process_boundary_resume": true,
  "uploaded_unconfirmed_part_recovered": true,
  "reconciled_uploaded_parts": 2,
  "verified_sha256": "dc1d77da10fa10a439ecd3a157707d4b558b4bbf4b7c29bda5699f455bbafe9d",
  "complete_replay_identical": true,
  "cleanup_status": "SUCCEEDED",
  "resume_and_verify_duration_ms": 168026
}
```

整条命令墙钟时间约 200.7 秒。结束后数据库复核为 `UploadBatch=ABORTED`、`UploadFile=ABORTED`、`UploadCleanupTask=SUCCEEDED` 且清理尝试次数为 1。由此可以把“2 GiB、本机进程中断、未确认 Part 恢复、最终摘要和 Complete 重放”记为实现证据；弱网、并发、目标 S3 产品和医院环境仍保持未验收。
