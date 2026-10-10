# 平台导出数据规范与目录布局 (Export Artifacts Directory)

本目录为 `medication-video-platform` 平台标准化导出成果的统一归宿。包含正式批准导出的临床配药机器人具身智能 HDF5 数据集、质量检验报表及视觉抽检产物。

所有导出的大文件（如 `.hdf5`、`.mp4`、`.jpg` 等）均已被根目录 `.gitignore` 忽略，不会提交到代码仓库。

---

## 目录结构规划

```text
exports/
├── hdf5/                     # 正式导出的 HDF5 具身智能数据集
│   ├── human_demonstration_hdf5_v2_project_*.hdf5
│   ├── medication_dataset_v2_60demos.hdf5
│   └── ...
├── reports/                  # 自动化分析脚本生成的质量评估报表 (Markdown / CSV / HTML)
│   ├── dataset_analysis_summary.md
│   ├── action_distribution.csv
│   └── ...
├── inspection/               # 视觉抽检与真值校验多模态快照
│   ├── truth_<action>_<ms>.jpg
│   ├── skeleton_overlay_<demo>.jpg
│   └── sample_<clip>.mp4
└── README.md                 # 本规范说明文件
```

---

## 子目录职责说明

### 1. `exports/hdf5/` (核心数据集产出)
* **内容**：通过平台 Core API (`/api/v1/projects/{projectId}/human-demonstrations/hdf5/v2/download`) 导出的标准具身智能演示数据集文件。
* **数据契约**：严格遵循 `human-demonstration-hdf5.v2` 规范：
  * 手部姿态：多机位等权 21 点骨骼张量 `(T, 2, 21, 3)` (10 FPS 采样)；
  * 动作标签：时序动作区间与标准业务动作语义代码；
  * 元数据：机位对应、校准、输入版本与 provenance 溯源链路。
* **命名约定**：
  * 平台默认生成格式：`human_demonstration_hdf5_v2_{scope}_{timestamp}.hdf5`
  * 冻结交付版本格式：`medication_dataset_v2_{count}demos_{date}.hdf5`

### 2. `exports/reports/` (质检与统计报表)
* **内容**：由数据分析与审计脚本（如 `tools/analyze_exported_dataset.py`）全量扫描 HDF5 数据集后自动生成的质量量化报表。
* **核心报表**：
  * 动作频次、耗时均值/中位数分布统计表；
  * 双手在场率（Visibility Ratio）、单手率、全 0 缺失率与跳帧异常明细；
  * 综合质量评估 Markdown 摘要报告。

### 3. `exports/inspection/` (抽检视觉证据)
* **内容**：由质检回放脚本（如 `tools/inspect_truth.py`）生成的抽检快照，将 MediaPipe 21 手部关键点与动作区间叠画在真实视频帧上，作为人工抽检和科学质量量化核验的直观佐证。

---

## 跨项目协同与调用指引

在多工程协同架构下（如与 `medication-dataset-tools` 或模型训练脚本协同），**严禁在不同项目间机械复制大体积 HDF5 文件**，推荐采用以下两种轻量化方式读取本目录：

### 方式 A：通过命令行绝对/相对路径直接引用
```bash
python tools/analyze_exported_dataset.py --hdf5 "d:/Dev/Projects/medication-video-platform/exports/hdf5/medication_dataset_v2_60demos.hdf5"
```

### 方式 B：在消费工程中创建 Windows Junction 目录软链接
在 `medication-dataset-tools/data/` 下直接建立链接，实现零复制实时同步：
```powershell
cmd /c mklink /J "d:\Dev\Projects\medication-dataset-tools\data\exports" "d:\Dev\Projects\medication-video-platform\exports"
```
