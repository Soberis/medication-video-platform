# 配药视频数据管理平台设计系统

> 状态：正式 / 永久前端视觉与布局基线

> 改版入口（2026-09-06）：[V1.30 需求基线](./requirements/baselines/V1.30/配药视频数据处理平台需求文档_V1.30.md) 提出浅色专业工作区与视频优先详情页。该方案尚未完成视觉验证；实施前将选定决策统一修订到本文，现有运行界面不因此被视为已改版。

本文件是配药视频数据管理平台前端设计的唯一视觉权威。业务需求、权限、状态机和 API
仍以正式需求、架构规范和契约为准；本文件约束这些事实如何被组织、表达和交互。

> All frontend UI changes must comply with this document unless the design system itself is deliberately revised.

任何前端 UI 变更开始前都必须阅读本文件。若业务需要与本文件不兼容，应明确修订本设计系统，
而不是在页面中增加另一套视觉语言、任意 Token 或一次性样式。

## Design Identity / 设计身份

**Official identity:** Linear-inspired Quiet Professional Workspace with Graphite + Medical Blue
visual theme.

This is the permanent design identity for the professional medication video management platform.
It is a governance baseline for future frontend work, not a page-specific visual treatment.

| 层级 | 定义 |
| --- | --- |
| Official name / 正式名称 | Linear-inspired Quiet Professional Workspace |
| Reference style / 参考风格 | Linear-inspired professional productivity tools |
| Design language / 设计语言 | Quiet Professional Workspace |
| Visual theme / 视觉主题 | Graphite + Medical Blue |
| Product positioning / 产品定位 | Professional medication video management platform |

以上术语描述同一设计系统的不同层次，不是相互替代的风格：

```text
Linear-inspired → Quiet Professional Workspace → Graphite + Medical Blue →
design tokens, components, layouts, interaction patterns
```

完整的设计身份是：**Linear-inspired Quiet Professional Workspace with Graphite + Medical Blue visual
theme.**

### 核心原则

产品遵循以下原则：

1. 清晰优先于装饰。
2. 工作流优先于视觉效果。
3. 信息层级优先于视觉噪声。
4. 一致性优先于页面特有的创意。
5. 语义含义优先于装饰性色彩。
6. 专业可信优先于追逐潮流的视觉效果。

界面应优化：

- 长期日常使用；
- 高信息密度；
- 快速工作流决策；
- 可靠的数据理解。

### Linear 风格原则

产品借鉴 Linear 风格专业工具的以下特点：

- 平静的界面；
- 精确的对齐；
- 紧凑的控件；
- 清晰的文字层级；
- 克制的色彩；
- 可预测的交互模式；
- 最少的装饰；
- 高效的工作流。

本项目**不是** Linear 的复制品，而是将这些原则适配到配药视频管理平台。

不要复制：

- 品牌形象；
- 完整 UI；
- 完全相同的颜色；
- 完全相同的组件。

### 医疗工作流要求

由于这是配药视频管理平台，设计必须传达：

- 信任；
- 精确；
- 稳定；
- 运行可靠；
- 工作流清晰。

界面应支持：

- 视频操作人员；
- 复核人员；
- 标注团队；
- 技术用户。

### 禁止的视觉方向

未来前端工作不得引入：

- 社交媒体信息流风格；
- 娱乐视频平台风格；
- AI 营销仪表盘风格；
- 过度的 KPI 仪表盘；
- 玻璃拟态；
- 渐变；
- 霓虹色；
- 不必要的动画；
- 过大的装饰性卡片；
- 随意的页面专属主题。

## 1. Product character / 产品性格

产品性格是上述设计身份在日常工作中的具体表现：

核心气质：

- 简单、平静、精确；
- 专业、中性、可信；
- 内容优先、面向操作；
- 可长期使用，不过度追逐潮流。

“大道至简”是这套性格的核心：界面应像可靠的配药视频作业台，不像 AI 生成的管理后台、通用 SaaS
模板、娱乐应用、社交产品、营销网站或视觉效果展示。

## 2. Information hierarchy / 信息层级

永久优先顺序：

1. 主要内容；
2. 当前主要任务；
3. 可执行或重要状态；
4. 辅助元数据；
5. 次级控件；
6. 装饰。

装饰不得与内容竞争。正常运行状态保持安静；异常、失败、真实阻断或必须执行的状态才获得更高
注意力。页面先回答“对象是什么、现在是什么状态、下一步做什么”，再展示技术细节。

### Task simplicity / 任务简化

“大道至简”以完成正确任务所需的判断和点击最少为目标，不以删除必要事实为目标：

- 服务端已能唯一确定的对象和下一未决项直接呈现，不要求重复选择或普通确认；
- 同一业务上下文只有一个视觉主操作，完成并经服务端确认后再安全聚焦下一项；
- 等待或完成时动作区收敛为紧凑说明，不使用空卡片、重复摘要或假按钮填充布局；
- 默认只展示当前判断所需信息，高级筛选、技术、历史、审计和危险操作渐进披露；
- 自动聚焦、刷新和导航不得自动提交业务结论，也不得造成不可预测的键盘焦点跳转；
- 权限、人工专业判断、真实阻断、错误恢复和可追溯证据不得以“简化”为由隐藏或绕过。

## 3. Color system / 颜色系统

中性表面占主导，只保留一个主强调色。颜色按语义定义，不按页面或功能随意命名。

| 语义角色              | 基线值    | 用途                      |
| --------------------- | --------- | ------------------------- |
| `background`          | `#F6F7F9` | 应用工作区背景            |
| `surface`             | `#FFFFFF` | 常规内容面                |
| `surfaceElevated`     | `#FFFFFF` | 浮层、弹窗和检查器        |
| `foreground`          | `#161B22` | 标题与主要事实            |
| `foregroundSecondary` | `#4B5563` | 正文与次要操作            |
| `foregroundMuted`     | `#5F6B7A` | 元数据与辅助说明          |
| `border`              | `#D1D5DB` | 需要明确边界的控件        |
| `borderSubtle`        | `#E5E7EB` | 分隔与低权重边界          |
| `primary`             | `#315E9B` | 主要动作与选中状态        |
| `primaryForeground`   | `#FFFFFF` | 主色表面的文字            |
| `focusRing`           | `#3B82F6` | 键盘焦点                  |
| `success`             | `#12643B` | 已确认的成功语义          |
| `warning`             | `#B45309` | 需关注但未必阻断          |
| `destructive`         | `#B42318` | 失败、删除与高风险操作    |
| `information`         | `#315E9B` | 确有必要的处理中/信息状态 |

侧栏是应用唯一的大面积深色视觉锚点：背景 `#111827`，主要文字 `#F9FAFB`，次级文字
`#9CA3AF`；选中项使用 `rgb(49 94 155 / 20%)` 的低饱和底色和 `#3B82F6` 的 2px 活动轨。
侧栏之外不得复制深色导航主题，业务页面也不得使用它制造装饰区块。

规则：

- 真实值只在 `apps/web/src/theme/tokens.ts` 定义；上表是供设计评审使用的同步说明；
- 自定义 CSS 使用由 Token 映射出的 `--app-*` 语义变量；
- Ant Design 主题由同一份 Token 映射，不建立第二套主题；
- 语义颜色只表达含义，不使用随机功能色、彩色导航图标或装饰色；
- 禁止紫蓝 AI 渐变、装饰渐变和高饱和大面积状态色；
- 当前只支持浅色主题。出现经过批准的真实暗色需求前，不增加主题切换复杂度。

## 4. Typography / 字体

使用当前高质量系统无衬线字体栈，不下载额外字体。中文优先由 `Noto Sans SC`、`PingFang SC`
或 `Microsoft YaHei` 回退；ID、摘要和错误码使用系统等宽字体。

| 层级              | 尺寸    | 字重    | 用途             |
| ----------------- | ------- | ------- | ---------------- |
| Page title        | 20–24px | 600     | 每页唯一 `h1`    |
| Section title     | 15–16px | 600     | 主区块标题       |
| UI / body         | 13–14px | 400–600 | 正文、表格和控件 |
| Metadata / helper | 12–13px | 400–500 | 时间、ID、说明   |

应用内禁止 32–48px 的巨型标题、装饰字体和无意义的多级字重。文字层级通过尺寸、字重、颜色和
间距共同建立，不仅靠放大。

## 5. Spacing / 间距

基础节奏固定为：`4 / 8 / 12 / 16 / 24 / 32`。

- 共享布局和组件必须使用 `appTokens.spacing` 或对应 CSS 变量；
- 页面不得为相同关系发明不同间距；
- 额外值只允许用于真实布局约束，并在样式附近说明原因；
- 留白用于建立信息层级，不用于制造空泛的“高级感”；
- 工作区默认紧凑，宽屏增加有效内容面积，不放大基础字号或装饰。

## 6. Border radius / 圆角

产品应精确，不应显得气泡化。

| Token | 值   | 用途               |
| ----- | ---- | ------------------ |
| `xs`  | 4px  | 紧凑控件、微型状态 |
| `sm`  | 6px  | 按钮、输入、导航项 |
| `md`  | 8px  | 普通分组与浮层     |
| `lg`  | 10px | 大型媒体面或模态框 |

禁止任意增加 14/18/20/24px 圆角。药丸形只用于确有集合、开关或紧凑状态语义的控件，不能成为
默认按钮或容器形状。

## 7. Borders and shadows / 边框与阴影

工作区结构优先依靠间距、对齐、细分隔线、低权重边框和表面对比。

- 常规内容面默认无阴影；
- 下拉、Popover、Command menu、Modal 与浮动检查器可使用 `floating` 或 `overlay` 阴影；
- 禁止大面积 SaaS 卡片阴影、发光描边和用阴影代替结构；
- 同一平面内优先使用一条分隔线，不重复叠加边框。

## 8. Cards / 卡片

Card 不是默认页面布局原语。只有当内容代表独立对象或真实分组时才使用。

优先选择：平面 Section、分隔线、行、列表、表格、字段组和有目的的留白。禁止 Card 套 Card，
也禁止在没有真实运营价值时使用 KPI 卡片优先的 Dashboard 布局。

## 9. Buttons and controls / 按钮与控件

按钮层级固定为：`primary`、`secondary`、`ghost`、`destructive`、`icon-only`。

- 每个局部上下文只有一个主操作；
- 服务端成功前不自动前进；成功后如下一对象唯一，可自动刷新并聚焦，避免无意义的“下一步”按钮；
- 次要操作不得与主操作竞争；
- 图标按钮必须有可访问名称和 Tooltip 或原生 `title`；
- 不使用随机颜色、无必要的大按钮或装饰性图标按钮；
- 标准按钮、Input 与 Select 高度为 32px；紧凑控件和图标按钮为 28px；大型控件仅为 36px；
- 控件高度由 `appTokens.control` 与 Ant Design Theme 统一映射。

高风险动作必须与普通动作分隔，并明确对象、影响和结果；不能只问“确定吗”。

## 10. Icons / 图标

统一使用 Lucide React，默认 `16px`、约 `1.8` stroke，单色展示。

- 不使用 Emoji、彩色图标块或混合图标族；
- 图标只用于帮助定位、扫描或辨别动作，不用于装饰；
- 仅在语义明确时使用成功、警告或危险色；
- 图标不能代替文字状态；图标单独出现时必须提供可访问名称。

## 11. Status language / 状态语言

状态展示应克制，例如 Draft、Processing、Ready、Published、Failed，并在当前产品中显示对应的
准确中文含义。优先形式是文字、点 + 文字、紧凑指示器；只有快速扫描明显受益时才使用低饱和
语义 Badge。

- 正常成功状态安静；失败、警告和真实阻断才提高视觉权重；
- 不把时间、用户名、技术 ID 或所有元数据都做成 Badge；
- 不堆叠多个状态标签；详细处理状态进入更深层视图；
- 状态不能只靠颜色表达，也不能由浏览器猜测。

## 12. Motion / 动效

动效只解释状态变化，不承担装饰。

允许细微透明度、短距离位移、菜单/对话框进入退出与轻量加载反馈。禁止弹跳、发光、装饰性脉冲、
戏剧化缩放、动画渐变和无意义的页面入场。所有非必要动效必须尊重 `prefers-reduced-motion`。

## 13. Copywriting / 文案

文案简洁、具体、面向操作。使用用户识别的对象和主动动词，并让动作名称在按钮、确认与结果中
保持一致。

推荐：“上传视频”“重试”“已发布”“处理失败”“搜索视频”“保存更改”。

禁止：“赋能工作流”“解锁下一代效率”“AI 智能体验”“轻松实现内容变革”等营销语言。失败态
说明发生了什么以及如何恢复；空态说明为何为空和可执行的下一步。

## 14. Canonical AppShell / 标准应用壳层

桌面结构固定为：

```text
┌──────────────┬──────────────────────────────────────┐
│              │                                      │
│   Sidebar    │              Workspace               │
│              │                                      │
└──────────────┴──────────────────────────────────────┘
```

需要上下文检查时：

```text
┌──────────────┬─────────────────────────┬─────────────┐
│              │                         │             │
│   Sidebar    │      Main content       │ Inspector   │
│              │                         │             │
└──────────────┴─────────────────────────┴─────────────┘
```

实现位于 `apps/web/src/components/layout/AppShell.tsx`，由以下最小组合原语构成：

- `AppShell`：Sidebar、Workspace 与可选 Inspector 的永久几何；
- `Sidebar`：品牌、真实路由导航、分组与账号区；
- `Workspace`：标准主内容滚动和宽度边界；
- `Inspector`：可选的上下文检查栏；
- `PageHeader`：现有页面标题、说明与紧凑操作区。

页面不应自行重建全屏容器或再包一层 AppShell。布局 API 使用 React composition，不以庞大配置
对象描述页面。

### Sidebar

- 紧凑、平静、精确对齐；使用 Lucide 图标 + 文字标签；
- 当前路由使用低饱和背景与 2px 活动轨，形成唯一且克制的产品识别；
- 只链接现有真实路由，不发明产品区域；
- 分组仅在帮助理解工作区与管理范围时出现；
- 禁止巨型 Logo、渐变背景、彩色图标块、过大纵向间距和过多分隔线。

### Workspace

- 支持紧凑 PageHeader / toolbar、主内容与可选 Inspector；
- 工作台页面不使用无必要的居中窄栏；正文、长表单和播放器可保留各自可读上限；
- 避免任意嵌套全屏容器和页面级横向滚动；表格确有需要时仅其容器横向滚动。

### Responsive shell

- 常规桌面显示完整侧栏；
- 较窄桌面自动收敛为图标轨，真实链接仍可访问且具有名称提示；
- 更窄视口使用紧凑横向导航回退；这不是完整移动端设计承诺；
- 工作区在 1920px 级视口使用 1680px 常规上限，2K 级使用 2080px 宽档，4K 级使用 2560px 超宽档；基础字号和控件尺寸不随档位放大；
- Inspector 在空间不足时隐藏，未来只有真实需求证明后才升级为 Drawer；
- 内容始终保持可用，不以 CSS `zoom` 或整体 transform 适配分辨率。

## 15. Shared layout rules / 共享布局规则

页面使用现有 `PageHeader`，仅在第二个真实消费者出现后新增共享原语。不要建立 `atoms / molecules /
organisms` 目录，也不要创建几十个预想组件。

常见页面顺序：

```text
PageHeader
├── Optional SummaryStrip
├── Main content (flat section / list / table / form)
└── Optional secondary or risk section
```

现有业务页可分阶段迁移。本次设计系统建立不会自动授权重做 Videos、Video Detail、Processing、
Upload 或 Dashboard；自然继承全局 Token 与 AppShell 的变化不等于页面重设计。

## 16. Accessibility and interaction / 可访问性与交互

- 使用语义 HTML、正确标题层级和表单标签；
- 所有动作可由键盘完成，焦点顺序可预测且焦点环清晰；
- 图标按钮有可访问名称，状态变化可被辅助技术感知；
- 文本和背景满足 WCAG 2.2 AA 对比目标，颜色不是唯一线索；
- 不自动播放视频，不使用闪烁内容；
- 弹窗打开与关闭必须正确管理焦点；
- 优先渐进披露，隐藏技术细节不能隐藏真实阻断或必要操作。

## 17. Implementation authority / 实现权威

`apps/web/src/theme/tokens.ts` 是唯一可执行 Token 来源，`apps/web/src/theme/theme.ts` 负责映射到
Ant Design 和 CSS 变量。新增语义必须先判断是否为跨页面真实概念；若是，先更新 Token 与本文，
再消费。页面 CSS 不得定义第二套颜色、圆角、间距、状态主题或控件高度。

未来 UI 变更的最低检查：

1. 阅读本文件和领域 UX 规范；
2. 确认使用真实业务对象、状态与路由；
3. 复用现有 Token、AppShell 和少量共享原语；
4. 检查键盘、窄屏和 reduced motion；
5. 运行 format、lint、type-check、测试和生产构建。
