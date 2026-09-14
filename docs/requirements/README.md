# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前需求方向（DRAFT）

- [V1.34 配药视频动作预标注与人工修正](./baselines/V1.34/配药视频数据处理平台需求文档_V1.34.md)：动作候选与手部检测独立，人工编辑、复核后复用 HDF5；明确 RTX 4090 推理试验配置与减负验收，继承 V1.33，待实现验证及用户验收。
- [V1.33 配药视频数据处理平台需求基线](./baselines/V1.33/配药视频数据处理平台需求文档_V1.33.md)：导出独立读取项目全部人工候选，单选、多选和全选统一携带明确 ID，保留服务端最终准入；继承 V1.32，待用户验收。
- [V1.32 配药视频数据处理平台需求基线](./baselines/V1.32/配药视频数据处理平台需求文档_V1.32.md)：统一视频工作入口、集中导出选择、全局标签库与布局收敛；继承 V1.31 的 HDF5 导出与标签库边界，尚未完成验收。
- [V1.31 配药视频数据处理平台需求基线](./baselines/V1.31/配药视频数据处理平台需求文档_V1.31.md)：标签库与 `human-demonstration-hdf5.v1` P0 导出；保留视频、机器检测和人工标签的独立事实边界，尚未完成验收。

## 前一需求方向（DRAFT）

- [V1.30 配药视频数据处理平台需求基线](./baselines/V1.30/配药视频数据处理平台需求文档_V1.30.md)：浅色专业工作区与视频优先体验重构已实施并通过自动化回归，继承 V1.29 未变更的业务边界；用户验收与前序 runtime 审计仍未完成，保持 DRAFT。实施证据见第 7–8 节。

## 前序需求方向（DRAFT）

- [V1.29 配药视频数据处理平台需求基线（数据组主线收敛与 Runtime Simplification）](./baselines/V1.29/配药视频数据处理平台需求文档_V1.29.md)

V1.29 是前序 DRAFT 需求方向：平台以由上游明确归属的 `CaptureGroup` 数据组为对象，默认链为接收、媒体检查、MediaPipe evidence 与导出；人工质量、任务、Revision、Review 和标签适用性校验只在项目需要 human ground truth 时使用。DRAFT 不授权删除或改变 runtime，具体收缩必须等待代码、契约、数据和测试审计。

## 上一正式基线

- [V1.28 配药视频数据管理平台需求基线（单一 CaptureGroup 路径与唯一手部检测导出）](./baselines/V1.28/配药视频数据管理平台需求文档_V1.28.md)

V1.28 仍是上一正式运行时基线，直到后续版本满足其冻结条件。V1.28 定义的唯一公开导出为按当前 CameraStream/ClipAsset 和 MediaPipe Candidate 生成的 `hand-landmarks.v1` detection evidence CSV；V1.30 曾增加人工标注 CSV，V1.31 随后收敛为唯一 `human-demonstration-hdf5.v1`，V1.33 调整选择范围，V1.34 继承该边界，不恢复旧 CSV。真实 UAT、生产和医院部署证据仍待完成。

## 历史与迁移参考

历史版本不定义当前范围；仅在迁移、兼容性或审计任务需要时读取[历史索引](./history-index.md)。


## 归档与完整性

所有历史基线保存在 [`baselines/`](./baselines/)；摘要按 UTF-8 文件字节计算 SHA-256（CRLF 统一为 LF）；当前 DRAFT、当前正式基线与前一版本的摘要由 [requirements-manifest.json](./requirements-manifest.json) 记录。更新基线时必须重新计算摘要、更新 manifest，并同步 `AGENTS.md`、当前技术设计和当前追踪矩阵。
