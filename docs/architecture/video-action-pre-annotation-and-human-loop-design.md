# 配药视频时序动作预标注与人机协同技术设计方案

> 版本：DRAFT / 方案提案<br>
> 日期：2026-09-09<br>
> 关联需求基线：[V1.34 需求基线](../requirements/baselines/V1.34/配药视频数据处理平台需求文档_V1.34.md)、[V1.33 需求基线](../requirements/baselines/V1.33/配药视频数据处理平台需求文档_V1.33.md)、[V1.30 需求基线](../requirements/baselines/V1.30/配药视频数据处理平台需求文档_V1.30.md)<br>
> 关联代码实现：[`AnnotationTaskService.java`](../../services/core-api/src/main/java/com/medicationvideo/platform/annotationreview/AnnotationTaskService.java)、[`CaptureGroupAnnotationPanel.tsx`](../../apps/web/src/features/capture-group/CaptureGroupAnnotationPanel.tsx)、[`HumanDemonstrationHdf5ExportService.java`](../../services/core-api/src/main/java/com/medicationvideo/platform/datasetexport/HumanDemonstrationHdf5ExportService.java)、[`workers/ai-worker`](../../workers/ai-worker)

---

## 1. 背景与业务目标

### 1.1 业务痛点
配药视频数据的核心标注诉求是：**在视频时序上，准确标记出各个时间段内具体发生了什么操作（“视频段内做了什么”）**。
传统纯人工从零标注面临严重的效率瓶颈：
* 标注员必须从第 0 秒开始逐帧寻找动作起止点、手动拖拉区间并输入动作标签；
* 单个 1~3 分钟的视频往往需要耗费 10~15 分钟以上的人工时间；
* 长时间机械标注极易产生“疲劳漏检”与起止时间手抖漂移，导致标注质量一致性难以保证。

### 1.2 方案目标与边界收敛
本方案旨在构建**“AI 自动生成初稿 + 人工界面快速核验微调 + 手部关键点配套导出”**的人机协同（Human-in-the-Loop）闭环：
1. **业务清晰解耦**：底层手部检测（MediaPipe Hand Landmarker）作为客观机器证据独立运行；标注任务纯粹聚焦于“动作时序打标”，两者不产生强耦合。
2. **大幅降低人工量**：将人工从“填空题（从零寻找并标注）”解放为“选择/判断题（基于 AI 建议快速确认或修正）”，以“节省 30% 以上人工工时”为基准目标。
3. **极简工程实现**：初期坚决不引入复杂的空间目标检测（如 YOLO）和多模型堆叠，采用单一大模型离线推理基线，通过错误归因驱动后续优化。
4. **配套导出打通**：复用平台现有的 HDF5 导出引擎，以时间戳为纽带，将手部 21 点 3D 骨骼轨迹与人工最终确认的动作标签逐帧对齐打包导出。

---

## 2. 动作标签体系与数据契约

### 2.1 单层互斥原子动作流（Primary Step）
对齐 Core API 服务端核心校验规则 `ANNOTATION_PRIMARY_STEP_OVERLAP`（主步骤时间区间严禁重叠），第一版标签体系严格限定为**单层、时间上连续且互斥的原子动作流**：

| 动作标识（`label_id`） | 显示名称 | 物理起止判据与说明 | 常见混淆点与消除规则 |
| :--- | :--- | :--- | :--- |
| `CHECK_INFO` | 核对信息 | 药师手持药单/处方或核对药瓶标签文字的开始至脱离 | 仅眼神看瓶不计入，必须伴随手部拿取或指读动作 |
| `OPEN_CAP` | 操作瓶盖 | 手指接触西林瓶盖/安瓿瓶颈部施力开始，至瓶盖脱落或开启完成 | 开盖后手部未放开药瓶时，开盖动作在盖子分离瞬间结束 |
| `DRAW_LIQUID` | 抽取药液 | 注射器针尖进入药瓶/安瓿，拉动活塞抽取药液开始至针头脱离 | 双手持握但未拉动活塞的等待过程计入该动作内部准备阶段 |
| `INJECT_MIX` | 注入混匀 | 注射器注入输液袋/另一药瓶或摇晃振荡混匀开始至动作结束 | 若注液后紧接着摇匀，视为同一连续阶段，不拆碎 |
| `LABEL_SEAL` | 贴签封包 | 打印/粘贴成品标签、套袋或装盒的开始至动作结束 | 操作完成双手脱离即为结束 |
| `IDLE`（隐式） | 空闲等待 | 无特定配药动作的过渡期，在时间轴上体现为未覆盖空白区 | 手部静止放桌面、整理非药品杂物、短暂离场等 |

> **设计准则**：不将宏观复合概念（如“配药阶段”）与微观操作（如“抽取药液”）混入同一标签层级。所有动作区间均满足 $0 \le Start\_ms < End\_ms \le MediaDuration\_ms$ 且相邻区间不重叠。

### 2.2 标注数据模型与 API 结构

#### 服务端存储与 API 契约（对齐现有契约）
```typescript
export interface AnnotationSegment {
  start_ms: number;  // 起始毫秒（包含）
  end_ms: number;    // 结束毫秒（不包含）
  label: string;     // 动作标签 ID（必须在项目已生效 LabelSchema 中）
}
```

#### AI 候选证据模型（拟增 `aicandidate` 存储结构）
```json
{
  "schema_version": "action-candidate.v1",
  "clip_asset_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "model_name": "Qwen3-VL-8B-Instruct",
  "model_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "prompt_version": "prompt-action-sop.v1",
  "inference_time_ms": 8420,
  "segments": [
    {
      "start_ms": 10200,
      "end_ms": 25100,
      "label": "DRAW_LIQUID",
      "confidence": 0.92,
      "evidence_summary": "右手持注射器抽取西林瓶药液，活塞后移明显"
    },
    {
      "start_ms": 25100,
      "end_ms": 42000,
      "label": "INJECT_MIX",
      "confidence": 0.88,
      "evidence_summary": "针头刺入输液袋并注入液体，随后轻微摇晃"
    }
  ]
}
```

---

## 3. 算法模型与硬件部署方案

### 3.1 硬件与模型基线选型

| 维度 | 方案选型 | 决策依据与边界 |
| :--- | :--- | :--- |
| **计算硬件** | **单卡 NVIDIA RTX 4090 (24GB VRAM)** | 拥有完整的 Ada 架构第 4 代 Tensor Core，原生支持 BF16 计算。 |
| **基础模型** | **`Qwen/Qwen3-VL-8B-Instruct`** | 具备原生视觉时序理解与时间戳对齐能力；Instruct 版本严格遵循 JSON 指令输出，不选用产生长推理链的 Thinking 版本。 |
| **推理精度** | **BF16（原生无损）** | 8B 权重固定占用 ~16GB 显存，无需折腾复杂的量化编译，保留最高时序敏感度。 |
| **部署方式** | Python + Transformers 离线运行 | 100% 局域网本地运行，符合医疗数据离线脱敏与安全隔离要求；严禁在运行时在线自动下载模型。 |
| **降级兜底** | `Qwen3-VL-4B-Instruct` | 若长视频显存紧张或需要更高吞吐，作为同批次对照降级方案。 |

### 3.2 显存预算与防 OOM 工程参数

在 24GB 显存总容量下：
* 8B BF16 权重：~16.0 GB
* CUDA 运行时与 PyTorch 上下文：~1.2 GB
* **安全可用余量**：~6.8 GB（用于承载视频 Token、KV Cache 和 Activation）

为确保显存绝对安全（峰值控制在 20GB ~ 22GB 内），离线推理必须严格约束四个参数：
1. **视频片段预算**：单次推理输入控制在 **15 ~ 30 秒片段**。若超过 30 秒，Worker 内部采用步长 20 秒、重叠 10 秒的滑动窗口处理。
2. **采帧率（FPS）**：固定为 **1 fps**（即每秒采 1 帧），30 秒片段仅 30 帧，足以捕获动作级区间。
3. **单帧像素上限**：设置 `max_pixels = 448 * 28 * 28`，防止原画高分辨率导致视觉 Token 爆炸。
4. **注意力加速**：显式指定 `attn_implementation="flash_attention_2"`，避免传统 Attention 随序列长度呈二次方内存增长。

---

## 4. 人机协同（Human-in-the-Loop）交互体验设计

### 4.1 核心交互状态流转

```mermaid
stateDiagram-v2
    [*] --> 未标注状态: 打开标注任务
    未标注状态 --> 载入AI草稿: 点击【载入 AI 建议】(或自动预填)
    载入AI草稿 --> 草稿编辑态: 转换为可交互 DraftSegments
    未标注状态 --> 草稿编辑态: 手动新增首个片段
    
    state 草稿编辑态 {
        [*] --> 巡检中
        巡检中 --> 边界微调: 拖动色块左右把手
        巡检中 --> 标签修改: 下拉选择或快捷键改类别
        巡检中 --> 片段切分: 播放头定位按 S 键
        巡检中 --> 片段合并: 选中相邻色块合并
        巡检中 --> 空白补标: 点击虚线空白区新建
        巡检中 --> 误检删除: 选中色块按 Delete
    }
    
    草稿编辑态 --> 提交待复核: 点击【提交审核】(校验通过)
    提交待复核 --> 已复核通过: 管理员审核 APPROVED
    提交待复核 --> 草稿编辑态: 管理员驳回 REJECTED (退回返工)
```

### 4.2 防漏检机制（Anti-False-Negative）
不能仅让人工审核 AI 已标出的片段（避免“确认偏误”导致遗漏）：
1. **未覆盖空白区显性化（Gap Highlighting）**：
   * 时间轴上大于 2.0 秒未被任何动作覆盖的空白段，以灰色斜虚线纹理显式呈现，明确标识为 `[未分配区间]`；
   * 点击空白区，可一键将其转为新动作块。
2. **顺序全流程巡检流（Sequential Inspection Loop）**：
   * 标注员按键盘 `Tab` 键时，播放焦点按时间戳在 **`[动作片段1] -> [空白区间1] -> [动作片段2] -> [空白区间2]`** 顺序跳转，保证视线全覆盖；
   * 配合空格键（播放/暂停）和方向键（微调），实现不离键盘的高速审查。

### 4.3 完整键盘交互与快捷键规范

| 键位 / 组合键 | 作用范围 | 功能说明 |
| :--- | :--- | :--- |
| `Space` | 全局 | 播放 / 暂停当前视频 |
| `J` / `K` / `L` | 全局 | 倒放（-1x/-2x） / 暂停 / 快进（1x/2x） |
| `←` / `→` | 全局 | 逐帧回退 / 逐帧前进（步进 1 帧约 33ms） |
| `[` / `]` | 选中片段 | 将当前视频播放头位置设为当前片段的 `start_ms` / `end_ms` |
| `Tab` | 时间轴 | 切换焦点至下一个动作片段或空白未标区间 |
| `Shift + Tab` | 时间轴 | 切换焦点至上一个动作片段或空白未标区间 |
| `S` | 时间轴 | 在当前播放头位置切分（Split）当前选中的片段 |
| `Delete` / `Backspace` | 选中片段 | 删除当前选中的误检片段 |
| `1` ~ `5` | 选中片段 | 一键切换当前片段类别为对应的预设动作标签 |
| `Ctrl + Z` | 草稿编辑 | 撤销上一步编辑操作 |

---

## 5. 系统架构与模块集成

严格遵守平台 [AGENTS.md](../../AGENTS.md) 的 Spring Modulith 模块所有权规范与数据流边界：

```mermaid
sequenceDiagram
    autonumber
    participant Ingest as assetcapture
    participant MQ as RabbitMQ
    participant Worker as workers/ai-worker (Python)
    participant Candidate as aicandidate
    participant UI as Web Client (React)
    participant Review as annotationreview
    participant Export as datasetexport

    Note over Ingest: 视频上传并完成 Media Probe
    Ingest->>MQ: 发布 platform.ai.action-candidate.v1
    MQ->>Worker: 消费作业 (获取视频流 URL & 元数据)
    Note over Worker: Qwen3-VL-8B 离线推理 (1fps, BF16)
    Worker->>Candidate: POST /api/v1/internal/candidate-annotations (上报时序候选)
    Candidate-->>Worker: 确认入库成功 (生成 CandidateEvidence)

    Note over UI: 标注员进入工作台 CaptureGroupAnnotationPanel
    UI->>Candidate: GET /api/v1/projects/{pId}/clip-assets/{cId}/action-candidates
    Candidate-->>UI: 返回 AI 候选片段建议
    Note over UI: 预填为 DraftSegments，人工拖拽微调
    UI->>Review: POST /annotation-tasks/{tId}/revisions (带 candidate_provenance)
    Note over Review: 保存正式 AnnotationRevision

    Note over Review: 管理员复核 APPROVED
    UI->>Export: GET /human-demonstrations/hdf5/v1/download
    Export->>Review: 读取最新通过的动作时间段
    Export->>Candidate: 读取 MediaPipe 手部 21 点关键点
    Note over Export: 执行 segmentAt(orderedSegments, timestampMs) 逐帧盖章
    Export-->>UI: 返回打包完成的 HDF5 数据集
```

### 5.1 候选隔离与人工真值（Provenance 溯源）
* **AI 产物绝不越权**：AI 模型的输出只能写入 `aicandidate` 模块作为 `CandidateAnnotation` 暂存，**绝不允许直接生成正式标签、绝不允许自动通过质量复核**；
* **可审计的人工责任链**：标注员从 Candidate 载入草稿并修改确认后提交，生成由当前用户签名的 `AnnotationRevision`；系统在审计快照中记录 `referenced_candidate_annotation_id` 与模型哈希，确保科研和监管的完整溯源闭环。

### 5.2 配套导出机制（以时间戳对齐）
复用 [`HumanDemonstrationHdf5ExportService.java`](../../services/core-api/src/main/java/com/medicationvideo/platform/datasetexport/HumanDemonstrationHdf5ExportService.java)：
* MediaPipe 手部关键点每一帧包含高精度时间戳 `frame_timestamp_ms` 及 21 个 3D 骨骼坐标；
* 导出引擎通过 `segmentAt(orderedSegments, timestampMs)` 判定该帧所属的人工审核区间：
  ```java
  private static HumanAnnotationExportSegment segmentAt(List<HumanAnnotationExportSegment> segments, long timestampMs) {
      return segments.stream()
          .filter(segment -> timestampMs >= segment.segmentStartMs() && timestampMs < segment.segmentEndMs())
          .findFirst()
          .orElse(null);
  }
  ```
* 最终导出的 HDF5 文件同时包含完整手部时序轨迹与对齐的配药动作标签。

---

## 6. 离线 PoC 验证脚本实现参考

在不侵入生产主工程的前提下，先通过独立的 Python 脚本在 RTX 4090 上对合成视频进行摸底：

```python
"""
tools/poc-qwen-video/poc_action_detect.py
离线单卡 PoC 测试脚本：验证 Qwen3-VL-8B-Instruct BF16 的推理显存、耗时与动作时间戳精度
"""
import json
import time
import torch
from transformers import AutoModelForCausalLM, AutoProcessor

def run_poc():
    model_path = "/path/to/approved/Qwen3-VL-8B-Instruct"  # 离线受控目录
    video_path = "test_data/medication_demo_30s.mp4"
    
    print("=== [1/4] 正在加载模型 (BF16, FlashAttention-2) ===")
    processor = AutoProcessor.from_pretrained(model_path, max_pixels=448 * 28 * 28)
    model = AutoModelForCausalLM.from_pretrained(
        model_path,
        torch_dtype=torch.bfloat16,
        attn_implementation="flash_attention_2",
        device_map="cuda",
    )
    
    prompt_text = """你是一个专业配药动作标注质检专家。请分析视频中的配药操作，严格按照候选标签输出动作时序区间：
- CHECK_INFO: 核对信息
- OPEN_CAP: 操作瓶盖
- DRAW_LIQUID: 抽取药液
- INJECT_MIX: 注入混匀
- LABEL_SEAL: 贴签封包

约束要求：
1. 仅输出合法 JSON 数组，严禁任何额外解释；
2. 每个对象包含 start_sec, end_sec, label, confidence 四个字段；
3. 区间按时间升序排列且不可重叠。"""

    messages = [
        {
            "role": "user",
            "content": [
                {"type": "video", "video": video_path, "fps": 1.0},
                {"type": "text", "text": prompt_text},
            ],
        }
    ]
    
    print("=== [2/4] 构建输入并开始推理 ===")
    start_time = time.time()
    torch.cuda.reset_peak_memory_stats()
    
    inputs = processor.apply_chat_template(
        messages, tokenize=True, add_generation_prompt=True, return_tensors="pt", return_dict=True
    ).to("cuda")
    
    output_ids = model.generate(**inputs, max_new_tokens=512)
    output_text = processor.decode(output_ids[0][len(inputs.input_ids[0]):], skip_special_tokens=True)
    
    elapsed_sec = time.time() - start_time
    peak_vram_gb = torch.cuda.max_memory_allocated() / (1024 ** 3)
    
    print("=== [3/4] 性能指标 ===")
    print(f"推理耗时: {elapsed_sec:.2f} 秒")
    print(f"显存峰值: {peak_vram_gb:.2f} GB / 24.0 GB")
    
    print("=== [4/4] 模型输出结果 ===")
    print(output_text)
    
    # 验证 JSON 合法性
    try:
        parsed = json.loads(output_text.strip())
        print(f"成功解析出 {len(parsed)} 个动作片段！")
    except Exception as e:
        print(f"JSON 解析失败，需优化 Prompt 约束: {e}")

if __name__ == "__main__":
    run_poc()
```

---

## 7. 实施路线与阶段验证

| 阶段 | 实施内容 | 交付产物与成功指标 |
| :--- | :--- | :--- |
| **阶段 0：离线单卡 PoC 摸底**<br>*(1~2 天)* | 1. 编写独立离线 Python 脚本 `tools/poc-qwen-video/poc_action_detect.py`；<br>2. 在单张 RTX 4090 上加载 `Qwen3-VL-8B-Instruct` (BF16)；<br>3. 选取 3~5 段 30 秒配药合成演示视频执行推理。 | 1. 显存峰值稳定在 22GB 以内，无 OOM；<br>2. 30 秒视频推理耗时在 15 秒以内；<br>3. 打印起止时间 JSON，肉眼比对视频，评估时序误差。 |
| **阶段 1：平台全链路闭环 (MVP)**<br>*(3~5 天)* | 1. `ai-worker` 增加 action-candidate 消费者；<br>2. `aicandidate` 模块支持时序候选入库；<br>3. 前端 [`CaptureGroupAnnotationPanel.tsx`](../../apps/web/src/features/capture-group/CaptureGroupAnnotationPanel.tsx) 增加【载入建议】按钮及可视化时间轴微调。 | 标注员可在界面一键载入 AI 建议，修改并提交，复核通过后能正常下载配套 HDF5 文件。 |
| **阶段 2：A/B 对照评估与决策**<br>*(1~2 天)* | 针对同一批测试视频，组织对照试验：<br>- 方式 A：纯人工从零标注；<br>- 方式 B：AI 生成草稿后人工微调。 | **核心 ROI 指标**：相同最终质量下，**每分钟视频人工标注时间减少 $\ge 30\%$**。达标则冻结正式基线，未达标则通过错误归因表针对性优化。 |

---

## 8. 错误归因与后续优化矩阵（Error Attribution）

若阶段 0 或阶段 2 出现准确率瓶颈，严格遵循如下“按需迭代”策略，严禁提前复杂化：

| 发现的主要问题 | 归因分析 | 针对性优化动作 | 严禁采取的动作 |
| :--- | :--- | :--- | :--- |
| **动作名称判定混乱** | Prompt 描述有歧义或动作范畴交叠 | 优化 Prompt，补充 1~2 个 Few-shot 正反例，明确 SOP 动作界限 | 盲目更换更大模型或引入额外分类器 |
| **动作正确但边界整体偏移** | 抽帧未精准对齐 PTS 时间戳，或模型时序粗糙 | 检查视频解码时间戳映射；微调提示词强调精准接触帧 | 盲目上马复杂的两阶段滑动窗口 |
| **快速短暂动作完全漏检** | 1 fps 采样跨度过大，漏掉动作帧 | 对特定短动作区间自适应提高至 2 fps | 盲目全视频按 10 fps 抽帧导致显存爆炸 |
| **药瓶与注射器等器械混淆** | 纯视觉大模型在小物体上分辨率不足 | 后续评估引入局部裁剪（Crop）或专用目标检测（YOLO） | 第一版就提前构建目标检测标注流水线 |
