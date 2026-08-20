# 时间基准与多相机同步

> 文档状态：`STRATEGIC_HOLD` 设计参考；不属于 V1.24 P0 runtime 交付，V1.22 继续 DRAFT / DEFERRED
> 适用数据：多相机 RGB 视频；深度、IR、点云和三维标定不属于 P0  
> 最后更新：2026-08-12

## 1. 目标

本文定义 stream 时间、session 时间、UTC 时间、多相机 offset/drift、帧定位和同步质量的统一语义，确保：

- Step 在 session 时间轴上只标一次；
- Object 能稳定定位到具体 stream 帧；
- 可变帧率、缺帧和容器时间戳不会被 `frame_index / fps` 简化破坏；
- 同步阈值有可测量误差和版本依据；
- 历史标注和冻结数据集不因重新同步被原地改写。

## 2. 时间概念

| 名称 | 类型/单位 | 定义 |
| --- | --- | --- |
| `record_start_utc` | RFC 3339 UTC | 采集端声明的一路录制开始时间，保留来源和可信等级 |
| `server_receive_time` | RFC 3339 UTC | 云端接收事件时间，不用于代替视频采集时间 |
| `t_stream_ms` | int64 ms | 解复用后按媒体时间基准换算的 stream 相对时间 |
| `frame_index` | int64 | 按规范化展示顺序从 0 开始的帧序号，只作辅助定位 |
| `t_session_ms` | int64 ms | 映射到 CaptureSession 统一时间轴后的时间 |
| `session_origin_utc` | RFC 3339 UTC/nullable | session 0ms 对应 UTC；来源不可信时允许为空 |
| `reference_stream_id` | ID | session 同步参考流 |
| `sync_model_version` | string | offset/drift/anchor 算法和参数版本 |

UTC 用于跨系统事件关联，session/stream 相对时间用于视频标注。两者不能混用，也不能用服务器接收时间推断精准动作边界。

`capture-session-metadata-v1` 中的 `device_time_utc`、`record_start_utc`、`time_offset_ms` 和
`sync_status` 均属于边端声明。平台在 Session 组装时原样保存并标明来源，但不会把这些字段直接
提升为媒体 PTS、验证后的同步等级或 `StreamSyncRevision`；后者必须经过媒体探测或明确的同步校准流程。

## 3. 媒体时间提取

### 3.1 PTS 优先

- 使用 ffprobe/解复用器读取 stream `time_base` 和每帧 presentation timestamp（PTS）。
- Object 和抽帧清单以换算后的 PTS 时间为主键语义，frame index 为辅助字段。
- 不使用 `frame_index / nominal_fps` 作为正式时间，除非媒体确实没有可用时间戳且已标记降级来源。
- DTS 只用于解码顺序诊断；展示和标注使用 PTS。

### 3.2 时间归一化

记录原始：

```text
container_start_time
stream_start_time
time_base_num/time_base_den
first_pts
last_pts
nominal_frame_rate
average_frame_rate
```

规范化：

```text
t_stream_ms(frame) = round((pts(frame) - pts_origin) × time_base × 1000)
```

`pts_origin` 的选择必须进入 `time_base_version`；默认使用首个可展示视频帧 PTS，而不是假定容器从 0 开始。

### 3.3 异常时间戳

| 异常 | 处理 |
| --- | --- |
| PTS 缺失 | 尝试受控重封装/解码生成派生 variant；raw 报告保留缺陷 |
| PTS 非单调 | 按解码器输出和 PTS 规范化；记录重复/回退数量，严重时 WARNING/REJECT |
| 相同 PTS 多帧 | 保留 frame ordinal；导出键使用 stream ID + timestamp + ordinal |
| 可变帧率 | 正常支持，以每帧 PTS 为准 |
| 长间隔/缺帧 | 保存 gap interval，不用插值伪造原始帧 |
| 音视频时间基准不同 | 分别记录；视频标注以视频 stream 为准，音频只作隐私/同步辅助证据 |

## 4. Session 映射模型

### 4.1 仿射映射

每路 stream 使用带锚点的仿射模型：

```text
scale = 1 + drift_ppm / 1,000,000

t_session_ms =
    anchor_session_ms
    + round((t_stream_ms - anchor_stream_ms) × scale)
```

定义：

- `anchor_stream_ms`：该流上的锚点时间；
- `anchor_session_ms`：同一可观察事件在 session 时间轴上的时间；
- `drift_ppm > 0`：随着 stream 时间增加，映射后的 session 时间增长更快；
- `drift_ppm < 0`：映射后的 session 时间增长更慢。

使用锚点而非从绝对 0 直接乘 drift，可降低长视频数值误差并消除“offset 的参考时刻”歧义。

为兼容查询可派生：

```text
time_offset_ms_at_origin =
    anchor_session_ms - round(anchor_stream_ms × scale)
```

该派生值不能替代锚点字段作为事实来源。

帧选择必须先把 Capture/session target 精确反向映射到 stream time，在源 PTS 中选择最近帧，
再把选中帧正向映射回 Capture/session time。同步误差定义为：

```text
sync_error = mapped_session_time(selected_source_frame) - target_session_time
```

不得直接用归一化 stream PTS 减 session target。每路 `pts_origin` 归零只建立该 stream 的相对
时间，不证明不同 stream 的首帧同时发生。没有可信 common clock 或 anchor 映射时属于 S0，
不具备多机位同步条件；不得以首帧对齐静默降级。

### 4.2 drift 可用条件

- 只有一个可靠锚点时，只估计 offset，`drift_ppm` 记为 null/unknown，不伪装成 0 已测量漂移。
- 至少两个时间间隔足够、来源明确的锚点才能估计 drift。
- 多锚点拟合必须保存方法、残差、异常点规则和置信/误差指标。
- 短 session 中 drift 小于测量噪声时可只使用 offset，但必须由 profile 明确。

## 5. 同步来源和可信等级

| 等级 | 来源 | P0 用途 |
| --- | --- | --- |
| S0 | 无同步信息 | 只能独立播放；默认 session WARNING/REJECT |
| S1 | 设备/NTP 时间和边端自报 offset | 成组和粗同步预览；需人工验证 |
| S2 | 可观察共同事件的人工锚点 | Step 复核和 P0 对齐的主要可接受方法 |
| S3 | 多锚点拟合并有残差统计 | 长视频或漂移明显场景 |
| S4 | 经验证的硬件同步/共同触发 | 未来高精度融合；不属于 P0 必需 |

共同事件可以是画面中各视角可见的同一动作或批准的采集事件日志。不得为了同步新增未授权硬件动作；系统只消费已有数据和人工标记。

## 6. 同步记录模型

每个 session 保存：

```text
session_id
reference_stream_id
session_origin_utc
session_origin_source
sync_model_version
sync_rule_set_digest
```

每路保存不可变的 `StreamSyncRevision`：

```text
sync_revision_id
session_id
stream_id
reference_stream_id
anchor_stream_ms
anchor_session_ms
drift_ppm
method
source
anchor_count
residual_p50_ms
residual_p95_ms
max_residual_ms
measurement_error_ms
created_at/created_by
supersedes_sync_revision_id
```

重新同步创建新 revision。已有 AnnotationRevision 和 DatasetVersion 继续引用原 sync revision，除非显式创建新标注/数据集版本。

## 7. 同步阈值

阈值属于版本化 `SyncRuleSet`，至少包含：

```text
warning_threshold_ms
reject_threshold_ms
max_measurement_error_ms
max_drift_ppm
max_gap_ms
required_sync_level
applicable_camera_roles
applicable_task_types
```

阈值确定依据：

- 实际帧率和 VFR 分布；
- Step 边界允许误差；
- Object 抽帧/跨视角复核用途；
- 人工锚点测量误差；
- residual P50/P95/P99；
- camera role 和关键证据可见性。

500ms 只能作为试运行同步警告参考值，不能被描述为普适精确同步标准。阈值修改产生新 SyncRuleSet，不追溯改变旧 session 结论。

## 8. Session/stream 同步判定

### 8.1 Stream

| 结果 | 条件示例 |
| --- | --- |
| PASS | 达到 required sync level；误差和残差均在 PASS 阈值内 |
| WARNING | 仅有粗同步、误差在 warning/reject 之间、少量 gap 或关键锚点不足 |
| REJECT | 无可用映射、严重时间跳变、误差超过 reject 且影响目标任务 |

### 8.2 Session

session 结论由所有 required evidence stream 组合得到：

- 辅助视角 WARNING 不必然导致 session REJECT；
- 关键证据视角无法对齐且 Step 无法人工判断时必须 REJECT；
- 某流 REJECT 时，该流不得进入正式训练输入；session 是否保留由任务 profile 决定；
- 人工 override 必须绑定任务类型、理由和 DatasetVersion，不改变原同步结论。

## 9. 标注语义

### 9.1 Step

- Step 在 `t_session_ms` 上标注，区间为 `[start_ms, end_ms)`。
- 保存 `evidence_stream_ids` 和每路引用的 sync revision。
- 标注 UI 显示统一 playhead，但每路 seek 到反向映射的 stream PTS。
- 若某路当前时间无帧，显示 gap/最近帧策略必须明确，不能静默伪装同步。

### 9.2 Object

每个 Object 至少保存：

```text
stream_id
frame_index
frame_ordinal_at_same_pts
stream_timestamp_ms
session_timestamp_ms
sync_revision_id
label
geometry
visibility
source_revision_id
```

导出 COCO 的图片必须能通过 `frame_manifest.jsonl` 追溯到 stream PTS、session time、sync revision 和图片 SHA-256。

### 9.3 区间边界

- 主 Step 默认不重叠；Event、Quality 和 secondary activity 可重叠。
- 小于 0.5 秒的过渡合并规则属于 LabelSchema/Guideline，不属于底层时间模型。
- UI 显示可采用 0.01 秒精度，数据库和契约仍使用整数毫秒，避免浮点比较。

## 10. 播放控制

多路同步播放器不追求逐帧锁步渲染。P0 建议：

1. 选择 reference stream 驱动 session playhead；
2. 按映射计算各路目标 stream timestamp；
3. 偏差小于 UI 容忍窗口时自由播放；
4. 超过窗口时 seek/微调播放速率；
5. 超过严重偏差时暂停并提示同步质量，不无限追赶；
6. 浏览器后台、解码拥塞和 seek 延迟单独计入播放器误差，不回写采集同步模型。

UI 同步误差与数据本身的同步测量误差必须分别统计。

## 11. 导出与复现

DatasetVersion 冻结：

- `sync_rule_set_digest`；
- 每路 `sync_revision_id`；
- 抽帧 sampling profile；
- 实际 frame PTS 清单；
- 软件/FFmpeg 镜像摘要。

重新转码可能改变帧序号或 PTS，必须创建新 AssetVariant 和新的 frame manifest，不能复用旧 frame index 假装相同。

## 12. 验证方法

### 12.1 合成测试

- 固定帧率、不同起始 offset；
- 可变帧率；
- 已知正/负 drift；
- PTS 从非零开始；
- B-frame 导致 DTS/PTS 不同；
- 重复 PTS、回退、gap 和缺帧；
- 不同时区和错误 device UTC；
- 单锚点与多锚点拟合。

### 12.2 Golden session

使用不含真实患者信息的合成多路视频，嵌入可验证共同事件，保存：

- 理论 offset/drift；
- 人工锚点基准；
- 预期映射；
- Step 边界；
- frame manifest。

每次 FFmpeg、OpenCV、浏览器播放器或同步算法升级都回归。

### 12.3 UAT 统计

- offset/drift residual P50/P95/P99；
- Step 边界误差 P50/P90/P95；
- seek 后 UI 对齐误差；
- 按 camera role、分辨率、帧率和遮挡分层；
- session 为统计聚类单元，避免把大量相邻帧当作独立样本夸大精度。

## 13. 待确认项

- 当前 camera set 的同步来源和可信等级；
- 试运行 warning/reject 阈值；
- 哪些 camera role 是各任务的 required evidence；
- 是否存在 VFR、音轨或边端生成 PTS；
- 多路播放器的浏览器和硬件解码能力；
- 是否有可批准使用的共同事件或采集日志作为锚点。
