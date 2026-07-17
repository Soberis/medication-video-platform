# 安全、隐私与保留销毁

> 文档状态：安全设计基线（评审稿）  
> 适用范围：研发、测试、UAT、院内部署或受控云端部署  
> 最后更新：2026-07-17

## 1. 适用声明

本文给出技术控制基线，不替代医院责任部门、法务、伦理、信息科、数据治理和网络安全团队的正式结论。真实数据处理目的、合法性基础、部署地域、保留期、审批角色和系统等级必须在上线前由责任部门书面确认。

Codex、开发环境和 CI 只允许使用合成数据。未经明确授权和隔离管理，不得使用真实患者视频或所谓“已脱敏样本”。

## 2. 安全目标

- 最小化：只收集、处理和导出任务所需数据。
- 目的绑定：授权与 project、purpose、对象版本和有效期绑定。
- 默认拒绝：隐私状态未知、隔离、权限不明或批准过期时拒绝访问。
- 纵深防御：身份、网络、应用、对象存储、数据库、密钥和审计共同控制。
- 可追溯：原始、派生、标注、清洗和数据集均有不可断裂的来源链。
- 可撤销：授权撤销后能阻止新访问并定位已发布副本。
- 可销毁：保留期结束且无 legal hold 时，能验证对象、数据库、缓存和备份处置。
- 可恢复：误删、密钥故障和基础设施故障有经演练的恢复路径。

## 3. 数据分级

| 等级 | 数据示例 | 默认控制 |
| --- | --- | --- |
| S4 | 未脱敏 raw、身份映射、可识别音视频、密钥材料 | 独立存储范围和密钥、双人审批、最短保留、禁止普通标注/AI |
| S3 | 脱敏视频、隐私框、AnnotationRevision、清洗报告 | 项目和用途授权、短期凭证、禁止跨项目复制 |
| S2 | 已批准冻结训练包和受控下载副本 | 用途绑定、导出审批、到期、指纹/摘要和下载审计 |
| S1 | 不含敏感内容的运行指标和公开技术元数据 | 常规权限、完整性和保留控制 |

分级必须落到对象、数据库记录、导出包和备份策略；不能只作为文档标签。

## 4. 身份、认证和授权

### 4.1 身份

- 使用医院 IdP/OIDC；平台不自建普通用户密码体系。
- 服务、Worker 和外部适配器使用独立机器身份，禁止共享个人账号。
- token audience、issuer、signature、expiry 和 clock skew 必须验证。
- 管理员也不能绕过 project/purpose/classification 守卫直接读取全部对象。

### 4.2 RBAC + ABAC

RBAC 决定能做什么，ABAC 决定能对哪份数据做：

```text
allow =
  authenticated
  AND role permits action
  AND tenant/project match
  AND classification <= clearance
  AND purpose grant valid
  AND privacy release permits action
  AND task assignment permits scope (when applicable)
  AND approval valid (for high-risk action)
  AND not quarantined/revoked/expired
```

#### 4.2.1 MVP 两级界面角色

V1.2 只向用户展示 `ADMIN` 和 `MEMBER`，但角色只是权限模板：

- `ADMIN` 可管理项目、成员、文件夹、标注任务和视频删除，但查看 raw、隐私放行和正式导出仍需单独权限/审批；
- `MEMBER` 只能访问已加入的项目，可按项目权限本地上传、播放已批准的 PREVIEW/DEIDENTIFIED，并编辑分配给自己的任务；
- 平台本地上传必须同时满足角色、项目和上传规则；开发、测试和 CI 仍只使用合成数据；
- AssetFolder 不参与授权计算，不能用“移动到文件夹”授予或撤销数据权限；
- 后续增加复核员或质检员只调整权限模板，不改变 project、purpose、privacy 和 task assignment 守卫。

### 4.3 职责分离

以下操作申请人与审批人分离：

- raw/S4 访问；
- 隔离解除；
- `ALLOWED_INTERNAL_EXPORT` 隐私放行；
- DatasetVersion freeze；
- 正式导出和下载授权；
- 保留期例外、legal hold 变更和物理销毁；
- 权限提升和密钥管理。

紧急访问必须限时、明确原因、增强审计并事后复核。

标注复核同样要求提交者与复核者不同。即使两人界面上都显示为管理员，也不能由同一主体完成提交和通过。

## 5. 隐私处理流程

```text
raw INGESTED
→ PrivacyAssessment
→ NONE / SUSPECTED / CONFIRMED
→ DeidentifyRun（需要时）
→ 输出 DEIDENTIFIED variant
→ 人工/规则验证
→ PrivacyReleaseDecision
→ ALLOWED_ANNOTATION 或 ALLOWED_INTERNAL_EXPORT
```

规则：

- 自动检测只能提出 finding，不能独立批准内部导出。
- 放行绑定具体 variant digest、project、purpose、期限和 policy version。
- 脱敏后重新转码、裁剪或叠加内容产生新 variant，需要继承规则明确的重新评估。
- 隐私区域默认不作为普通目标检测标签导出。
- 屏幕、纸质医嘱、标签敏感文本、人脸、工牌和可能的语音隐私纳入评估范围。
- 正式导出前执行最终人工检查；“观察到零漏检”不代表总体风险数学上为零。

当前首个 P0 实现只落地人工 `PrivacyAssessment`，未启用自动隐私检测、脱敏 Worker、
`PrivacyReleaseDecision` 或隔离解除。未评估资产返回 `UNKNOWN/BLOCKED`；已评估的
`NONE/SUSPECTED/CONFIRMED` 也保持 `BLOCKED`。评估仅允许管理员（真实 OIDC 接入后应拆分为批准的
隐私复核角色），绑定 ClipAsset、平台验证 SHA-256、策略 key/digest、固定 finding 类别、操作者、
请求 ID 和追加式审计。`NONE` 只能进入后续人工放行评审，不能直接进入标注池或导出。

## 6. 假名化与身份映射

- 低熵标识使用 tenant scoped HMAC 或令牌服务，不使用裸 MD5/SHA。
- HMAC 输入先做规范化和域分离：`domain || tenant || normalized_id`。
- 不同用途需要不可关联时使用不同 key/purpose domain。
- 身份映射服务与业务数据库分离，平台只保存 token。
- key version 随 token 保存；轮换产生新 token 映射，不静默改变历史引用。
- 日志、指标、对象 key 和消息不得包含原始标识或可逆映射。

## 7. 对象存储

### 7.1 命名与隔离

- 对象 key 只含内部随机 ID、数据级别和版本，不含姓名、病历号、处方号、工号、科室明文。
- S4 raw、S3 working/deidentified、S2 exports 使用独立 bucket 或等价的策略隔离范围。
- Worker 输入只读、输出仅限 job 临时前缀；禁止列举整个 bucket。
- 对象版本、锁定、生命周期和删除能力必须对选定 S3 兼容产品做实测。

### 7.2 预签名 URL

预签名 URL 只能由 Core API 在授权通过后签发：

```text
subject
action (read/write)
object/version
project/purpose
content constraints
expiry
request/approval reference
```

要求：

- TTL 按操作最短化；具体数值由安全评审冻结。
- 上传 URL 限制 key、method、大小、content type 和 checksum 条件。
- 下载 URL 只针对批准的具体对象/版本，不允许 prefix 通配。
- URL 不进入普通日志、异常、浏览器分析、Referer 或消息队列。
- 撤销通过禁用业务授权、对象策略/版本和短期 TTL 组合实现；不能宣称已签发 URL 可被普遍即时撤回。
- Web 响应使用适当的 `Referrer-Policy`、缓存和内容处置头。

播放 URL 只针对已批准的具体 Variant，签发前检查项目成员、purpose、PrivacyReleaseDecision、Quarantine 和 `deleted_at`；`PrivacyAssessment=NONE` 不能代替放行决定。普通用户不得取得 raw URL。

平台本地上传的上传 URL 绑定用户、项目、文件大小、类型和 checksum 条件。开发、测试和 CI 环境只允许合成数据。

### 7.3 完整性

- 正式资产使用 SHA-256；multipart ETag 不作为完整文件摘要。
- 摘要验证与对象版本 ID 一同保存。
- 下载、复制、恢复和导出发布后按风险使用 checksum 复核。

## 8. 加密和密钥

- 所有外部和跨节点链路使用医院批准的 TLS 配置。
- PostgreSQL、对象存储、备份和 Worker 临时盘按数据等级启用静态加密。
- 优先使用 KMS/密钥管理系统和信封加密；应用配置不保存主密钥明文。
- S4 和 S2 的密钥范围不得与普通 S1 指标共享。
- 记录 key ID/version，不记录密钥内容。
- 密钥轮换、禁用、恢复和 crypto-shredding 必须演练。
- 密钥备份权限与数据备份权限分离，防止单人同时取得数据和解密能力。

具体产品、算法套件、轮换周期和密钥托管责任为上线前待确认项。

## 9. 数据库和应用安全

- Core API 使用最小数据库账号；Flyway 迁移账号与运行账号分离。
- Python Worker、Web 浏览器和 CVAT 不直接连接平台业务数据库。
- 所有查询带 tenant/project 条件；关键越权测试覆盖 IDOR 和批量接口。
- JPA 聚合写入与 jOOQ/SQL 查询分离，禁止将用户输入拼接为 SQL。
- 上传文件类型不只相信扩展名/MIME；使用媒体探测、大小限制和隔离处理。
- FFmpeg/OpenCV 等媒体解析运行在受限容器，设置 CPU、内存、文件、时长和临时盘配额。
- 错误响应不暴露堆栈、SQL、对象地址、内部主机或密钥引用。
- 管理/Actuator 端点使用独立网络和强认证，不暴露到普通用户入口。

## 10. 外部系统

### 10.1 CVAT

- 只同步获得 `ALLOWED_ANNOTATION` 的批准 variant。
- 使用独立项目/组织边界和服务身份。
- 外部 task ID 映射由 `integration` 保存，正式 AnnotationRevision 由平台导入验证后创建。
- CVAT 导出结果视为不可信外部输入，验证 schema、范围、时间和几何。
- 删除/归档策略与平台任务关闭联动但不假设外部删除等于备份销毁。

### 10.2 AI Worker

- 默认禁止公网出站；模型和依赖离线导入并校验摘要。
- 只读取 DEIDENTIFIED、ALLOWED_ANNOTATION、非隔离 variant。
- 输出只能进入 CandidateAnnotation。
- DNS、连接尝试、模型摘要和镜像摘要进入安全监控。

### 10.3 OIDC、S3 和 RabbitMQ

- 每个适配器设置连接超时、证书验证、最小权限、重试上限和熔断。
- 外部 payload 不直接成为业务真值；必须经适配器规范化和领域守卫。
- broker 消息不传视频、签名 URL、令牌和身份映射。

## 11. 审计

至少审计：

- 登录、失败认证和权限变更；
- raw/隔离数据查看与下载授权；
- 预签名 URL 签发；
- 标注提交、退回、裁决和 schema 变更；
- 清洗 override 和隔离解除；
- 数据集 freeze、export、download 和过期；
- 项目/成员和 AssetFolder 创建、重命名、删除及视频移动；
- 视频删除；
- retention、legal hold 和销毁；
- 管理配置、密钥引用和安全策略变更。

AuditEvent 最小字段：

```text
event_id
occurred_at_utc
actor_type/actor_id
tenant_id/project_id
action
object_type/object_id/object_version
outcome
reason_code
request_id/trace_id
source_ip/device context（按合规要求最小化）
authorization/approval reference
previous_event_hash（如启用链式完整性）
```

审计只追加。高价值审计定期导出到独立受控存储或 SIEM，并验证完整性。审计日志本身也可能含个人信息，需分级、最小化和保留控制。

当前首个落地场景是媒体 DLQ 受控重放：`audit_event` 使用数据库 trigger 阻止 UPDATE/DELETE，
记录独立管理员 actor、项目、稳定原因、request ID、旧/新 attempt、dead-letter ID 和非敏感
变更引用。该实现只证明应用写入和数据库追加约束；独立 SIEM/WORM 导出、真实 OIDC 角色映射、
失败认证审计和数据库职责分离仍属于后续安全验收项。

## 12. 保留、legal hold 与销毁

### 12.1 原则

“不可变”表示在批准的保留期内不能覆盖内容，不表示永不销毁。生命周期：

用户删除视频后，该视频退出普通查询和新业务候选。该操作不代表对象、备份和下游引用已经全部销毁，也不得改写历史入库、标注和数据集事实。

```text
ACTIVE
→ RETENTION_EXPIRED
→ DESTRUCTION_REQUESTED
→ APPROVED
→ LOGICALLY_UNAVAILABLE
→ PRIMARY_OBJECTS_DESTROYED
→ BACKUP_EXPIRY_PENDING
→ DESTROYED / CERTIFIED
```

legal hold 存在时暂停普通到期销毁。

生产物理销毁只有在冻结数据集、有效导出、保留期、legal hold、外部副本和备份处置均可检查后才启用；申请人与审批人必须不同。

### 12.2 销毁范围

销毁清单至少覆盖：

- raw 和派生对象；
- 临时 multipart、Worker 临时前缀和本地临时盘；
- 缩略图、帧、缓存和搜索索引；
- 导出包及下载副本记录；
- 数据库中的可识别映射；
- CVAT/外部工具副本；
- 备份和归档中的到期处置；
- 加密密钥或数据密钥（采用 crypto-shredding 时）。

业务、审计和销毁证明可保留最小 tombstone，但不得保留可恢复的已销毁内容。

### 12.3 DestructionCertificate

记录：

```text
case_id
policy/legal basis reference
scope and object count
content digest inventory digest
approved_by / executed_by / verified_by
primary deletion result
external-system deletion result
backup expiry or crypto-shred status
completed_at
exceptions and residual risk
certificate digest
```

不能在备份仍可恢复时简单声明“彻底删除”；应明确 `BACKUP_EXPIRY_PENDING` 和预计不可恢复时间。

## 13. 备份与恢复安全

- 数据库和对象存储备份必须使用匹配的数据分级和加密控制。
- 恢复到隔离环境，禁止恢复到普通开发环境。
- 恢复演练验证数据库记录、对象版本、SHA-256、审计链和密钥引用一致。
- 恢复不得重新激活已撤销授权或已过期预签名 URL。
- 已销毁数据从旧备份恢复时必须有再销毁/抑制流程。
- RPO/RTO、备份周期、异地副本和保留期在部署设计中冻结。

## 14. 安全事件响应

至少建立：

1. 发现与分级；
2. 隔离账号、服务、对象或数据集；
3. 保全必要审计和技术证据；
4. 评估涉及对象、人员、用途和已导出副本；
5. 根据医院流程和适用要求报告、通知和补救；
6. 撤销访问、轮换凭证/密钥、修复漏洞；
7. 验证恢复并进行复盘；
8. 更新威胁模型、测试和运行手册。

需要演练的情景包括：预签名 URL 泄露、项目越权、Worker 外连、恶意媒体、错误导出、隐私漏检、对象误删、密钥不可用和审计链中断。

## 15. 开发和 CI 禁区

禁止在仓库、开发机自动化、CI 日志和测试夹具中放入：

- 真实患者视频、截图和音频；
- 身份映射、医院真实账号和生产 URL；
- token、密码、私钥、预签名 URL；
- 生产数据库/对象存储备份；
- 未经批准的脱敏样本；
- 模型大文件和未经摘要审批的模型。

测试默认使用合成视频、合成标签和本地占位凭证。

## 16. 上线前必须冻结

- [ ] 数据处理目的、部署地域、责任主体和审批流程。
- [ ] 系统安全等级及对应测评/测试要求。
- [ ] S4/S3/S2 分级映射和角色权限矩阵。
- [ ] ADMIN/MEMBER 到细粒度权限项的映射、标注自审拒绝和高风险职责分离。
- [ ] OIDC 提供方、服务身份和紧急访问流程。
- [ ] S3 产品、bucket/密钥隔离、对象锁和生命周期能力。
- [ ] TLS、KMS、轮换、备份密钥和 crypto-shredding 方案。
- [ ] 隐私检测/人工复核范围，含画面文本、人脸、工牌和语音。
- [ ] 保留期、legal hold、销毁和备份到期策略。
- [ ] 平台本地上传的项目权限、文件规则、目标环境 UAT 和测试合成数据限制。
- [ ] 可播放视频格式、目标浏览器、播放授权 TTL、Range/seek 和 raw 访问策略。
- [ ] 审计保留、完整性、SIEM 和事件响应流程。
- [ ] CVAT/AI Worker 网络边界和外部副本销毁责任。
- [ ] 安全测试、漏洞扫描、越权测试和恢复演练证据。
