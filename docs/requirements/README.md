# Requirements Baselines

本目录只维护需求基线的入口；当前范围不得从历史版本推断。

## 当前需求（唯一 Consolidated Baseline）

- [V1.35 Consolidated Baseline](./baselines/V1.35/配药视频数据处理平台需求文档_V1.35.md)：当前唯一完整产品需求，冻结产品边界、主链、AI/人工事实隔离和唯一 HDF5 导出语义；D1–D5 是实施前决策门禁。

旧版本只作为历史参考，不再是理解当前产品的必要前置。历史基线、审计和迁移资料统一通过[历史索引](./history-index.md)按需读取。

## 上一正式运行时基线

- [V1.28 配药视频数据管理平台需求基线（单一 CaptureGroup 路径与唯一手部检测导出）](./baselines/V1.28/配药视频数据管理平台需求文档_V1.28.md)

V1.28 是上一正式运行时基线；它的历史导出和旧流程不构成 V1.35 当前产品入口或兼容承诺。V1.35 的实现、契约、合成验收、用户验收和生产治理尚未全部完成，未完成项不得被本入口隐藏。

## 历史与迁移参考

历史版本不定义当前范围；仅在迁移、兼容性或审计任务需要时读取[历史索引](./history-index.md)。


## 归档与完整性

历史基线与草案保存在 [`baselines/`](./baselines/) 和 [`drafts/`](./drafts/)；摘要按 UTF-8 文件字节计算 SHA-256（CRLF 统一为 LF）；当前 Consolidated Baseline、上一正式运行时基线与前一版本的摘要由 [requirements-manifest.json](./requirements-manifest.json) 记录。更新基线时必须重新计算摘要、更新 manifest，并同步 `AGENTS.md`、当前技术设计和当前追踪矩阵。
