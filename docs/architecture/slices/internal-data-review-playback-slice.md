# 切片设计：项目内训练数据审核播放

> 版本：v1｜日期：2026-07-22
> 需求依据：需求基线 V1.6 FR-039
> 范围：只完成项目成员播放；不在本切片创建质量判断、标注、派生 Variant 或 AI 能力。

## 1. 业务目的

医院边端自动上传视频后，平台内部人员必须能查看视频，才能判断它是否适合训练数据。播放是人工审核的工具，不是隐私审批、质量结论或标注结果。

## 2. 纵向流程

```text
ClipAsset INGESTED
→ ACTIVE 项目 ADMIN/MEMBER 显式点击播放
→ Core API 校验项目、资产状态、软删除和媒体类型
→ S3 签发短期只读地址
→ 追加 CLIP_ASSET_PLAYBACK_GRANTED 审计（不保存 URL）
→ Web 延迟创建 <video controls preload="none">
→ 到期或加载失败时重新申请
```

## 3. 契约

- `POST /api/v1/projects/{project_id}/clip-assets/{clip_asset_id}/playback-grants`
- 成功：`200`、`Cache-Control: no-store`、`purpose=DATA_REVIEW`、短期 `media_url` 和 `expires_at`。
- 稳定拒绝：项目不可见、资产不存在/已删除、未完成媒体检测、不支持的媒体、对象存储不可用。
- 地址只保存在前端页面局部内存，不写数据库、日志、URL 参数或浏览器持久化存储。

## 4. 权限与状态

- 全局 ADMIN 不自动获得未加入项目的数据访问权。
- 项目 ADMIN/MEMBER 都可播放；只有项目 ADMIN 能删除、分类和管理成员。
- 播放不自动把视频标记为合格，也不创建标注任务。
- P0 不运行专门隐私或隔离守卫；人工发现敏感内容时进入质量 `REJECTED` 与管理员软删除流程。

## 5. 验证

- 后端合同测试覆盖两种项目角色、未就绪、软删除、不支持媒体、对象存储失败、no-store 和审计不含 URL。
- 前端测试覆盖显式点击后才请求、`preload=none`、地址到期、加载失败和重试。
- OpenAPI lint、React lint/type-check/test/build、Java 测试和 Modulith 边界测试进入质量门禁。
