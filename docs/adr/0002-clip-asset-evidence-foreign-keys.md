# ADR-0002：ClipAsset 证据记录使用受限外键

- 状态：已接受
- 日期：2026-07-17

## 决策

`clip_asset` 以 `(project_id, clip_asset_id)` 提供组合唯一键。`privacy_assessment` 和
`quarantine_record` 必须使用同一组 `(project_id, clip_asset_id)` 保存对 `clip_asset` 的组合物理
外键，不能把 `project_id` 仅作为未受数据库约束的冗余查询字段。删除行为固定为
`ON DELETE RESTRICT`。

当前 `quarantine_record.source_type` 只允许 `PRIVACY_ASSESSMENT`。因此
`privacy_assessment` 额外提供
`(project_id, clip_asset_id, input_digest, privacy_assessment_id, assessment_version)` 组合唯一键，
`quarantine_record` 的
`(project_id, clip_asset_id, input_digest, source_id, source_version)` 必须通过组合外键精确回指产生
该隔离记录的评估。这样数据库同时约束项目归属、资产、输入摘要、来源标识和来源版本，不能伪造或
错绑隔离来源。

上述约束不改变模块访问规则。模块仍只能通过公开应用服务或查询接口访问彼此，禁止跨模块
Repository 注入和级联更新/删除。若未来增加其他 `source_type`，必须先为新来源定义等价的数据库
完整性或经批准的一致性机制，再放宽当前来源枚举。

## 理由

两类记录都是绑定特定项目、视频和摘要的合规证据，父对象标识稳定，V1.2 的用户删除是软删除。
只引用全局唯一的 `clip_asset_id` 虽能阻止父记录消失，却不能阻止证据行携带错误的 `project_id`；
只保存 `source_id` 也不能证明隔离记录与评估的资产、摘要和版本一致。组合受限外键同时阻止这些
错绑和绕过引用检查的物理删除。当前模块化单体共享同一 PostgreSQL 迁移序列，不会引入跨服务
部署耦合。

若未来拆分数据库或服务，必须先用一致性巡检和可验证迁移替代这些外键，再更新本 ADR。
