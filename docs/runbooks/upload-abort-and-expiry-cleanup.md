# 上传终止与过期清理

> 适用范围：当前 P0 实现；默认只在合成数据环境验证。
> 最后更新：2026-07-16

## 1. 目的与安全边界

未完成的上传批次可能同时留下数据库状态、S3 Multipart Upload，或已经完成但尚未被 Complete Batch 接纳的对象。平台先原子提交 `ABORTED/EXPIRED`、逐文件清理任务和审计，再异步执行 Multipart Abort 或对象 Delete。

对象清理不可撤销。`UPLOAD_CLEANUP_ENABLED` 默认必须为 `false`；启用不代表允许清理真实医院数据，生产启用仍需环境负责人完成变更评审。不得用后台 SQL 修改批次、文件或清理任务状态，也不得把 object key、upload ID、令牌或预签名 URL 复制到工单和日志。

## 2. 启用前检查

只有以下条件全部成立才可启用：

1. 当前 API 指向预期环境和隔离 bucket，local/CI 只含合成数据；
2. API 服务身份只拥有目标 bucket 所需的 Multipart Abort、Head 和 Delete 权限；
3. `upload_cleanup_task` 已由 Flyway V17 建立，数据库备份与恢复路径可用；
4. 已为 PENDING 最老年龄和 FAILED 数量配置 owner、告警与升级路径；
5. 已确认 `UPLOAD_BATCH_TTL`、扫描批量、claim 时长、重试间隔和最大次数适合该环境；
6. 已在同版本 SeaweedFS/S3 产品上通过兼容性验证。

批准后设置 `UPLOAD_CLEANUP_ENABLED=true` 并按环境发布流程重启 Core API。不要仅为“清空列表”临时启用，也不要缩短 TTL 来批量删除未知数据。

## 3. 手动终止

批次发起人或同 project 管理员调用：

```http
PUT /api/v1/upload/{batch_id}/abort
Authorization: Bearer <approved token>
X-Request-Id: <traceable request id>
```

命令不接收对象地址，也不返回对象存储标识。TTL 已过时响应 `EXPIRED`；活动的 Complete File 租约返回可重试的 `UPLOAD_FILE_COMPLETION_IN_PROGRESS`。重复调用返回当前终止与清理投影，不重复创建任务或审计副作用。

## 4. 观察和判定

通过 `GET /api/v1/upload/{batch_id}` 查看：

| cleanup_status | 含义 | 操作 |
| --- | --- | --- |
| `NOT_REQUIRED` | 批次没有创建过 multipart 会话 | 无存储清理动作 |
| `PENDING` | 任务已持久化，等待 claim 或下一次重试 | 检查调度开关和最老等待时间 |
| `IN_PROGRESS` | 至少一个任务持有未过期 claim | 观察 claim 时长，不并行手工删除 |
| `SUCCEEDED` | 所有清理任务均成功 | 保留审计证据 |
| `FAILED` | 至少一个任务达到重试上限 | 进入故障升级，不视为已清理 |

批次 `ABORTED/EXPIRED` 与清理状态是两个事实：前者说明业务上不再接纳上传，后者说明对象存储副作用是否完成。不得因清理失败把批次改回上传态。

## 5. FAILED 处置

1. 记录 batch ID、请求/trace ID、失败数量、首次和最近发生时间；不要复制存储标识或凭证；
2. 检查 S3 可用性、服务身份权限、网络和产品错误语义；
3. 保持调度开关稳定，避免反复重启或并行手工删除；
4. 当前版本尚未开放清理任务人工重排 API。修复根因后应升级到平台维护人员，由受审计的后续修复切片恢复任务；禁止直接 UPDATE 数据库伪造成功；
5. 若真实数据环境出现错误删除迹象，立即关闭调度、保留证据并按隐私/数据事件流程升级。

关闭调度只会停止新的过期扫描和任务 claim，不会撤销已经提交的批次终态，也不会丢失任务。已经在对象存储执行的 Delete 无法由平台回滚。

## 6. 当前可执行验证

- `UploadBatchTerminationContractTests`：权限、幂等、TTL 判定、活动完成租约、删除/Abort 分支、有限重试和审计；
- `S3MultipartCompatibilityTests`：SeaweedFS 上真实 Multipart Complete、完整摘要、对象 Delete 与重复 Abort；
- OpenAPI/Bruno：终止命令、稳定错误码和清理进度契约。

这些合成证据不替代生产 S3 产品的权限演练、清理积压告警、备份恢复和 UAT。
