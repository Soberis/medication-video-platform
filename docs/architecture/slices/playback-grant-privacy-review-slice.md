# 切片设计：管理员 RAW 人工审核播放授权

> 版本：切片设计 v1（待评审）｜日期：2026-07-21
> 上游依据：`配药平台技术设计.md` §6.2「视频播放」L458、`state-machines.md` §11 L327、§10 L240；ADR-0002（platform-account-authentication）
> 范围锁定：仅内部项目 ADMIN 对 RAW 视频的人工审核播放。不做 AssetVariant / 转码 / PrivacyReleaseDecision / 普通成员播放 / CVAT / MediaPipe。

## 1. 目标与非目标

### 1.1 目标（本切片交付）

内部项目管理员在资产详情页点击「查看视频」后：

1. 后端校验其项目 ADMIN Membership、资产 `INGESTED` 且未删除；
2. 以独立用途码 `PRIVACY_REVIEW` 签发**短期** S3 读取预签名 URL；
3. 每次签发写一条 `audit_event`；
4. 前端拿到 URL 后才创建 `<video controls>`，不自动播放、不预加载；
5. 播放失败 / 授权过期 / 无权限 / 已删除分别展示真实状态。

### 1.2 非目标（明确排除）

| 排除项 | 原因 |
|---|---|
| AssetVariant（PREVIEW/DEIDENTIFIED）实体与迁移 | 依赖 FFmpeg 转码 Worker，属下一切片 |
| PrivacyReleaseDecision | 仅服务于「普通成员/标注人员播放获准 Variant」，本切片无此场景 |
| 普通成员（MEMBER）播放 | 架构规定普通用户不能取得 raw 地址 |
| 播放授权持久化表 | 见 §3 决策 |
| 新播放器框架 / Redis / OIDC / Worker | MVP 原则，浏览器原生 `<video>` 足够 |

## 2. 架构定位与合规性核对

### 2.1 与架构文档的关系

架构 §6.2 L458 规定播放复用 PREVIEW/DEIDENTIFIED Variant 且需 PrivacyReleaseDecision，但同时明确：**「隐私复核查看 raw 使用独立的短期 `PRIVACY_REVIEW` 授权，绑定复核人、对象版本、任务/审批依据和请求 ID，并完整审计。管理员角色本身不自动获得 raw 权限，该授权也不能替代 PrivacyReleaseDecision。」**

本切片正是这条 `PRIVACY_REVIEW` 例外的落地，**不违反**播放主规则，因为：

- 用途码用 `PRIVACY_REVIEW` 而非 `PLAYBACK`；
- 仅面向「人工审核/隐私复核」这一内部管理场景；
- 后续做 Variant 播放时，`PLAYBACK` 授权与 PrivacyReleaseDecision 另行实现，与本切片并存不冲突。

### 2.2 模块归属

播放授权读取 ClipAsset 的 `storage_bucket`/`object_key` 并调用对象存储适配器，与 ClipAsset 同属 **assetcapture** 模块（技术设计 §22 L1246 将 playback-grants 列为 API 资源，未指定独立模块）。本切片在 assetcapture 内新增控制器与服务，**不新建模块**。

`MultipartObjectStorage`（含 `authorizeRead`）已在 assetcapture（integration 端口），`ClipAsset.getObjectReference()` 已存在（package-private），均同模块内直接复用，无跨模块边界问题。

### 2.3 认证与授权主体

- 认证：复用现有 `PlatformSecurityFilterChain`（`/api/v1/**` authenticated）。local profile 用 `LocalBearerAuthenticationFilter`，正式用 session 登录，两者 principal 均实现 `ProjectActor` 接口（`UploadActor` / session actor）。控制器以 `ProjectActor` 取 `actorId()`，与 `AssetFolderController`/`ClipAssetDeletionController` 一致。
- 授权：`ProjectAccessService.lockAndRequireAdministrator(projectId, actorId)` —— 项目 ACTIVE + 有效 Membership + `ProjectRole.ADMIN`。这正是「内部全局 ADMIN 且具备该项目有效 Membership」的现有实现（本项目无独立全局角色表，项目级 ADMIN 即内部管理员）。

## 3. 数据模型决策：不新增播放授权表

**决策：本切片不创建 `playback_grant` 表，授权记录完全落入既有 append-only `audit_event` 表。**

| 选项 | 评估 |
|---|---|
| A. 新建 `playback_grant` 表 | 可查询/可撤销，但当前无「撤销单次授权」「列出历史授权」需求；预签名 URL 本身短期（分钟级）即天然过期，撤销意义低。违反「第二个真实用例出现前不抽象通用能力」。 |
| B. 复用 `audit_event`（**选定**） | 满足「每次签发写审计」硬约束；`audit_event` 已 append-only（V15 trigger 禁改删）、字段齐全（actor/action/object/outcome/authorizationReference/details）。授权短命，无需独立生命周期管理。 |

`audit_event` 行的 `event_id` 即授权标识，`details` 记录用途码与有效期。若后续出现「授权撤销列表」「按资产统计播放次数」等真实需求，再迁独立表。

**Flyway：本切片无新迁移。**（V1–V26 已覆盖 clip_asset 软删、audit_event。）

## 4. 审计字段设计

每次成功签发写一条 `audit_event`（经 `AuditEventRecorder.record`）：

| AuditEventCommand 字段 | 取值 |
|---|---|
| `occurredAtUtc` | 签发时刻（`Instant.now().truncatedTo(MICROS)`） |
| `actorType` | `USER` |
| `actorId` | 当前 ADMIN 的 actorId |
| `projectId` | 项目 ID |
| `action` | `CLIP_ASSET_PLAYBACK_GRANTED` |
| `objectType` | `ClipAsset` |
| `objectId` | clipAssetId |
| `objectVersion` | `Long.toString(asset.getVersion())`（对象版本绑定，符合架构「绑定对象版本」） |
| `outcome` | `SUCCEEDED` |
| `reasonCode` | `PRIVACY_REVIEW`（独立用途码） |
| `requestId` | 回显 `X-Request-Id` 或请求内生成 |
| `traceId` | `MDC.get("traceId")` |
| `authorizationReference` | `PROJECT_MEMBERSHIP_ADMIN`（授权依据） |
| `details` | `{"purpose":"PRIVACY_REVIEW","expiresAt":"<iso>","lifetimeSeconds":"<n>"}` |

**拒绝也审计**：当 ADMIN 校验通过但资产状态不允许播放（非 INGESTED / 已删除）时，另写一条 `outcome=DENIED`、`action=CLIP_ASSET_PLAYBACK_GRANTED`、`reasonCode` 取拒绝原因（如 `CLIP_ASSET_NOT_INGESTED` / `CLIP_ASSET_DELETED`），保证「谁试图看了什么」可追溯。认证/项目权限失败（404/403）由安全层拦截，是否审计遵循既有惯例（本切片不新增）。

## 5. OpenAPI 契约变更

新增一个端点，版本 `0.26.0 → 0.27.0`，归入新 tag `Playback`（或并入 `Clip Assets`，评审定）。

### 5.1 端点

```
POST /api/v1/projects/{project_id}/clip-assets/{clip_asset_id}/playback-grants
```

- **为什么 POST 而非 GET**：签发授权是「创建一次短期授权 + 写审计」的副作用操作，非幂等读取；GET 语义不符。
- 请求体：可选 `{ "purpose": "PRIVACY_REVIEW" }`，缺省即 `PRIVACY_REVIEW`（本切片唯一合法值；传其他值 → 400 `UNSUPPORTED_PLAYBACK_PURPOSE`，为未来 PLAYBACK 预留枚举）。
- 鉴权：`/api/v1/**` authenticated + 项目 ADMIN。

### 5.2 响应 201

```json
{
  "playback_url": "https://s3.../bucket/key?X-Amz-Signature=...",
  "purpose": "PRIVACY_REVIEW",
  "expires_at": "2026-07-21T12:05:00Z",
  "lifetime_seconds": 300,
  "content_type": "video/mp4",
  "size_bytes": 12345678
}
```

`playback_url` 为预签名 GET URL，**不入库、不写日志**（遵循 §8.1 L583 预签名 URL 不入库原则）。

### 5.3 错误码

| HTTP | code | 触发 |
|---|---|---|
| 400 | `UNSUPPORTED_PLAYBACK_PURPOSE` | purpose 非 PRIVACY_REVIEW |
| 403 | `PROJECT_ACCESS_DENIED` | 非项目 ADMIN（沿用 ProjectAccessException） |
| 404 | `PROJECT_NOT_FOUND` / `CLIP_ASSET_NOT_FOUND` | 项目/资产不存在或对当前 actor 不可见 |
| 409 | `CLIP_ASSET_NOT_INGESTED` | 状态非 INGESTED（UPLOADED/INGESTING/INGEST_FAILED） |
| 410 | `CLIP_ASSET_DELETED` | `deleted_at` 非空 |

410（Gone）区分「已删除」与「不存在」，符合前端「已删除显示真实状态」要求。

## 6. 后端实现要点

### 6.1 新增类（assetcapture 内）

- `PlaybackPurpose`（枚举，仅 `PRIVACY_REVIEW`）
- `PlaybackGrantRequest`（DTO，purpose 可空）
- `PlaybackGrantResponse`（DTO，见 §5.2）
- `ClipAssetPlaybackException`（code/message/httpStatus，模式同 `ClipAssetDeletionException`）
- `ClipAssetPlaybackService`：
  - `lockAndRequireAdministrator`（复用 ProjectAccessService）
  - `ClipAssetRepository` 按 (clipAssetId, projectId) 查资产（只读即可，无需 `findForUpdate`——播放不改资产；删除竞态由 `deleted_at` 检查覆盖）
  - 校验：`isDeleted()` → 410；`getStatus() != INGESTED` → 409
  - `MultipartObjectStorage.authorizeRead(asset.getObjectReference(), Duration.ofSeconds(lifetime))` 生成 URL
  - 写 audit（成功 SUCCEEDED / 拒绝 DENIED）
  - `ClipAsset.getObjectReference()` 当前 package-private，同包可直接调
- `ClipAssetPlaybackController`：`POST .../playback-grants`，`@RestControllerAdvice` 异常处理（模式同 `ClipAssetDeletionController`）

### 6.2 有效期配置

`platform.playback.grant-lifetime-seconds`，默认 **300**（5 分钟），可配置。前端在 `expires_at` 到期前如需续播重新 POST 申请（每次申请都写审计，天然形成「观看时长」证据链）。

### 6.3 有效期与 `<video>` 的 Range 问题

`<video>` 播放会发多个带 `Range` 头的 GET。AWS SigV4 预签名默认**不签 Range 头**，故同一 URL 在有效期内可承载多次 Range 请求，5 分钟对「审核看一段」足够。若实际对象存储实现把 Range 纳入签名导致 403，属部署配置问题，需在 CORS/Range 验证（§8）确认。

## 7. 前端实现要点（apps/web）

仅改 `AssetDetailView.vue` + `api/catalog.ts`（新增 `createPlaybackGrant`）。删除 UI 已存在，不动。

1. **入口**：新增「查看视频」按钮，仅当 `projectRole === 'ADMIN'` 且资产 `INGESTED` 且未删除时可点。
2. **延迟加载**：点击前页面**不含任何 `<video>`**；点击后调 `createPlaybackGrant`，拿到 `playback_url` 才 `new`/渲染 `<video controls :src="playback_url">`。`preload="none"`，无 `autoplay`。
3. **状态展示**：
   - 媒体探测结果复用现有详情字段展示（duration/resolution/codec 等）；
   - 播放失败（`<video>` `error` 事件）→ 显示「视频加载失败，可能授权已过期」，附「重新获取」按钮（重新 POST）；
   - 403/404 → 「无权限或视频不存在」；
   - 409 → 「视频尚未完成入库探测，暂不可播放」；
   - 410 → 「视频已删除，不可播放」。
4. **授权过期**：记录 `expires_at`，过期后再次播放或进度条 seek 失败时提示并允许一键重新申请。
5. 不新增播放器依赖，用 Element Plus + 原生 `<video>`，符合 `web-ux-and-frontend-guidelines.md` §7.5（授权签发后才创建 `<video>`、分状态文案）。

## 8. 对象存储 CORS / Range 验证（部署侧，非代码）

浏览器 `<video src=预签名URL>` 直连对象存储，跨域需对象存储允许。验证/配置项：

- **CORS**：SeaweedFS（或所用 S3）需配置允许前端源（开发 `http://127.0.0.1:4173`）的 `GET`，`AllowedHeaders` 含 `Range`，`ExposeHeaders` 含 `Content-Range`、`Accept-Ranges`、`Content-Length`。
- **Range**：确认对象存储对预签名 GET 支持 `Range` 请求且返回 `206 Partial Content`。
- **验证方式**：起本地 Postgres + 后端 + 前端，上传一个短视频，签发授权后用浏览器直接打开 `playback_url` 并 seek，确认 206 与拖动正常。此步为**联调验收**而非单元测试，结果记录于验收报告。

## 9. 测试清单

### 9.1 后端契约测试（`ClipAssetPlaybackContractTests`，Testcontainers + Postgres，offline `mvn.cmd -o test`）

1. 成功签发：INGESTED + ADMIN → 201，响应含 `playback_url`/`purpose=PRIVACY_REVIEW`/`expires_at`/`lifetime_seconds`，且断言 DB 新增一条 `CLIP_ASSET_PLAYBACK_GRANTED` audit（action/outcome/reasonCode/objectId 正确）。
2. 非 ADMIN（MEMBER）→ 403 `PROJECT_ACCESS_DENIED`。
3. 非项目成员 → 404 `PROJECT_NOT_FOUND`。
4. 资产不存在 → 404 `CLIP_ASSET_NOT_FOUND`。
5. 状态 UPLOADED → 409 `CLIP_ASSET_NOT_INGESTED`，且写 DENIED audit。
6. 状态 INGESTING / INGEST_FAILED → 409。
7. 已删除资产 → 410 `CLIP_ASSET_DELETED`，且写 DENIED audit。
8. purpose 传 `PLAYBACK` → 400 `UNSUPPORTED_PLAYBACK_PURPOSE`。
9. 缺认证 → 401/403（安全层）。
10. 预签名 URL 不写入任何 DB 字段 / 日志（断言 audit `details` 不含 URL，仅含 purpose/expiresAt）。

注：`MultipartObjectStorage` 在测试中以 stub/mock 注入（上传链路已有此模式），断言 `authorizeRead` 被以正确 `ObjectReference` 与 lifetime 调用即可，不真连 S3。

### 9.2 前端验收（联调）

- 点击「查看视频」前 DOM 无 `<video>`；点击后出现且 `preload="none"` 无自动播放。
- 四类错误状态文案分别可见（可用 mock 或制造非 INGESTED/已删除数据触发）。
- 授权过期后 seek 触发重新申请。
- 删除按钮在已删除后资产从列表消失（既有行为，回归确认）。

### 9.3 质量门禁

`mvn.cmd -o test` 全量（含 Flyway 校验 V1–V26）、Spring Modulith 边界、OpenAPI lint、Vue lint/type-check/build。

## 10. 交付清单与改动面

| 层 | 新增 | 修改 |
|---|---|---|
| 后端 Java | 6 个类（§6.1） | OpenAPI 版本、（可选）`ClipAsset.getObjectReference()` 可见性 |
| Flyway | 无 | 无 |
| OpenAPI | 1 端点 + 2 schema + 错误码 | 版本 0.27.0 |
| 前端 | `createPlaybackGrant` API + 播放区块 | `AssetDetailView.vue` |
| 测试 | `ClipAssetPlaybackContractTests`（10 例） | — |
| 部署文档 | CORS/Range 配置说明 | `infra/` README |

预计后端 ~6 新类 + 1 测试类，前端 1 文件改动，契约 1 端点。无新模块、无迁移、无 Worker、无新依赖。

## 11. 待评审决策点

1. **端点动词**：`POST .../playback-grants`（推荐，副作用+审计）vs `GET .../playback-url`。
2. **拒绝也写审计**：是否对 409/410 写 DENIED audit（推荐写，可追溯「谁试图看」），还是只写成功。
3. **有效期默认值**：300s 是否合适；是否需要支持客户端在请求体指定（建议不支持，服务端统一定）。
4. **Tag 归属**：OpenAPI 新 `Playback` tag 还是并入 `Clip Assets`。
5. **CORS/Range**：SeaweedFS 是否已允许 `Range`/跨域，若未配需运维侧先行（不影响代码合入，影响联调验收）。
