# Synthetic V1.25 场景

所有场景都生成合成 MP4，并通过显式 `capture_group.expected_camera_numbers` 和每路 `camera_number` 绑定视图。

| 场景 | expected / present | Receipt | View / Group Quality | Candidate | 后续预期 |
| --- | --- | --- | --- | --- | --- |
| `n1-complete` | `[1]` / `[1]` | `COMPLETE` | 1 `QUALIFIED` / `QUALIFIED` | 不请求 | `APPROVED` → Cleaning `PASS` → v3 |
| `n2-complete` | `[1,2]` / `[1,2]` | `COMPLETE` | 全部 `QUALIFIED` / `QUALIFIED` | 不请求 | `APPROVED` → Cleaning `PASS` → v3 |
| `n5-complete` | `[1,2,3,4,5]` / 全部 | `COMPLETE` | 全部 `QUALIFIED` / `QUALIFIED` | 不请求 | `APPROVED` → Cleaning `PASS` → v3 |
| `n5-missing-camera` | `[1,2,3,4,5]` / `[1,3,4,5]` | `INCOMPLETE` | present `QUALIFIED` / `QUALIFIED_WITH_EXCLUSIONS` | 不请求 | Camera 2 retained/missing；Camera 3 不重编号；`PASS` |
| `n5-rejected-view` | `[1,2,3,4,5]` / 全部 | `COMPLETE` | Camera 2 `REJECTED` / `QUALIFIED_WITH_EXCLUSIONS` | 不请求 | Camera 2 retained/excluded；`PASS` |
| `receiving-before-finalize` | `[1,2]` / `[1,2]` | 先 `RECEIVING` | 不进入质量 | 不请求 | 验证 finalize 前不可假定完整 |
| `media-probe-failure` | `[1]` / `[1]` | `COMPLETE` | 不进入质量 | 不请求 | 生成字节 Probe `FAILED`，Receipt 仍 `COMPLETE` |
| `no-hand-detected` | `[1]` / `[1]` | `COMPLETE` | `QUALIFIED` / `QUALIFIED` | `SUCCEEDED + NO_HAND_DETECTED` | AI 不阻塞 human path，继续至 `PASS` |
| `candidate-failure` | `[1]` / `[1]` | `COMPLETE` | `QUALIFIED` / `QUALIFIED` | `FAILED` | 需受控 Worker failure fixture；AI 不改变 human path |
| `review-rejected` | `[1]` / `[1]` | `COMPLETE` | `QUALIFIED` / `QUALIFIED` | 不请求 | Revision 1 `REJECTED`；无 Cleaning `PASS`、不导出 |
| `review-rejected-rework` | `[1]` / `[1]` | `COMPLETE` | `QUALIFIED` / `QUALIFIED` | 不请求 | 同一 Task 的 Revision 1 reject、Revision 2 approve、`PASS` |
| `review-input-change` | `[1,2]` / `[1,2]` | `COMPLETE` | 初始 `QUALIFIED`；输入变化后 `QUALIFIED_WITH_EXCLUSIONS` | 不请求 | 同一 Task 在 View/Group input 变化后 reopened；Revision 2 approve、`PASS` |
| `group-needs-review` | `[1,2,3,4,5]` / 全部 | `COMPLETE` | 全部 `QUALIFIED` / `NEEDS_REVIEW` | 不请求 | 不创建 AnnotationTask |
| `view-needs-review` | `[1,2,3,4,5]` / 全部 | `COMPLETE` | Camera 2 `NEEDS_REVIEW` / `NEEDS_REVIEW` | 不请求 | 保留逐视图待复核状态；不创建 AnnotationTask |
| `group-rejected` | `[1,2,3,4,5]` / 全部 | `COMPLETE` | 全部 `QUALIFIED` / `REJECTED` | 不请求 | 不创建 AnnotationTask |
| `zero-qualified` | `[1,2,3]` / 全部 | `COMPLETE` | 全部 `REJECTED` / 尝试 `QUALIFIED` | 不请求 | API 拒绝 `CAPTURE_GROUP_QUALITY_NO_QUALIFIED_VIEW` |

“缺失”与“REJECTED”是两种不同事实：前者没有 `CameraStream`，后者有已接收且保留的媒体。两者都不会被工具改名、删除或伪造成 Primary interval。
