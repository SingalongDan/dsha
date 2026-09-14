# dsh Android 版 — 顶层产品设计与实现计划

> 产品定义：**DSH 的 Android 原生版**。不是"给 dsh 包一个壳"，而是把 dsh web 的全部前端能力一层层特化到手机 App 上：同一后端引擎，同一会话模型，纯原生渲染（不嵌 WebView，不跑 JS）。
>
> 本文档基于对已打包引擎源码（`assets/usr/lib/node_modules/@deepseek-ai/*`）的逐包调研写成。每个功能点都有对应源码证据；每个 RPC 都已校验 wire 名。
>
> **调研方法（三路并行，已全部收敛）**：
> 1. 前端功能清单 — 逐包提取 38 个 `dsh-client-ui-*` 的 UI 结构与 locale 字典；
> 2. 设置四栏目 — settings 栈全部 namespace、字段、默认值、describe 返回结构；
> 3. 后端 RPC 全集 — `dsh-api-remotes` 全量 remote 描述符 + `dsh-api-gateway` 流协议 + 事件/投影注册（审计报告：`_research/tmp/dsh-rpc-audit.md`）。
>
> **结论自洽性检查**：`cordis.patch.yml`（web profile 的实际组合）是最终裁决——它以注释明示 `session-stats`（统计行投影）与 `session-turn-outline` 的挂载，并列出全部 browser 插件 roster；所有功能点均能在该 roster 中找到对应包。

---

## 目录

1. [产品定义](#1-产品定义)
2. [调研结论：dsh web 是什么](#2-调研结论dsh-web-是什么)
3. [总体架构（自顶向下）](#3-总体架构自顶向下)
4. [页面与交互规格（按用户设想）](#4-页面与交互规格)
5. [数据层设计：RPC + WS 双通道](#5-数据层设计)
6. [渲染层设计：原生实时渲染器](#6-渲染层设计)
7. [功能对齐清单（web → app）](#7-功能对齐清单)
8. [设置六栏目设计](#8-设置六栏目设计)
9. [Android 特有设置（第 5 栏目）](#9-android-特有设置)
10. [软件信息/开源协议（第 6 栏目）](#10-软件信息开源协议)
11. [实现阶段计划](#11-实现阶段计划)
12. [风险与规避（设计期就避免的问题）](#12-风险与规避)

---

## 1. 产品定义

**一句话**：DSH（DeepSeek Harness）的 Android 原生客户端。

- **核心承诺**：手机上和浏览器里同样的 Agent 能力——同样的会话、同样的工具（bash/fs/web/子智能体…）、同样的权限模型、同样的设置。
- **非目标**：不重做引擎（引擎已经在设备上跑着，`files/usr` bionic 前缀 + node 进程）；不做 WebView 套壳；不改变服务端协议。
- **三个派生原则**：
  1. **协议对齐优先**：所有功能先确认底层 RPC/流是真实存在的（本文档每一项都已验证），再做 UI。
  2. **单屏沉浸**：手机屏幕只有一维高度——用"会话/轨迹"双视图 + 顶级栏 + 左拉侧栏替代桌面端的三栏布局。
  3. **实时渲染原生化**：用 Android View/Canvas 实现与 web 渲染器等价的流式渲染（typewriter 增量、markdown 高亮、工具卡片、剪枝折叠），不在 UI 层做"假数据"。

---

## 2. 调研结论：dsh web 是什么

### 2.1 后端能力（RPC 全集，已验证）

| 命名空间 | 方法 | 参数 wire | 返回 |
|---|---|---|---|
| `session` | `list` | `_request:{cursor?}` | `{items:[summary]}`（含 projections） |
| `session` | `search` | `_request:{query}` | `{items, hasMore}` |
| `session` | `create` | `request:{sessionId?, cwd?/workspaceId?, agentPreset?}` | `{sessionId, agentPreset?}` |
| `session` | `rename` | `request:{sessionId, title}` | `{title, seq}` |
| `session` | `fork` | `request:{sessionId, atSeq?}` | `{sessionId}`（在最后一个完成 turn 处切开继承） |
| `session` | `selectModel` | `request:{sessionId, provider, model, reasoningEffort?}` | `{selected}` |
| `session` | `modelCatalog` | （零参，args={}） | `{default, routableProviders, groups[], failures[]}` |
| `session` | `prompt` | `request:{requestId, sessionId, mode:queue\|steer, content[], clientTimeZone?}` | `{accepted}` |
| `session` | `updateQueue` | `request:{sessionId, itemId, action:{kind:edit\|steer\|remove, content?}}` | `{accepted}` |
| `session` | `cancel` | `request:{sessionId}` | `{accepted}` |
| `session` | `page` | `request:{address, throughSeq, beforeSeq?, maxMessages?}` | `{records[], hasMore}` |
| `session` | `follow` | **WS 流**（见 §5.2） | `{type:"snapshot", header, cursor, records[], hasMore, projections?}` + `{type:"event", event}` |
| `session` | `control` | **WS 流**（无参） | `{type:"baseline", value:{queues, jobs, projections}}` + `queue/jobs/projection` 帧 |
| `commands` | `list` / `execute` | `agentId` / `{agentId, line, images?}` | `/命令` 注册表与执行（如 /permission /plan /goalin /compact） |
| `goals` | `create/edit/pause/resume/complete/clear` | `{agentId, ref?, request}` | GoalBar 全部动作 |
| `messageFeedback` | `list/put/delete` | `{sessionId, messageId, rating, note?, ifVersion}` | 消息 👍/👎 |
| `subagents` | `list/prompt/interruptByParent` | `{parentSessionId, childSessionId, ...}` | 子智能体会话 |
| `sessionReferenceResolver` | `candidates` | `{agentId, query}` | @ 引用补全 |
| `fileReferences` | `list` | `{agentId, query}` | @ 文件补全 |
| `skills` | `list` | `{sessionId}` | Skills 目录 |
| `workspace` | `create/rename/delete/insertBefore/insertSessionBefore/archiveSession/follow` | 见 §2.1 扩展 | 工作区管理（**会话归档唯一途径**） |
| `directoryPicker` | `pick/list/createDirectory` | — | 目录选择（Android = SAF 桥接） |
| `llm` | `listProviders/listConfigurableProviders/discoverModels` | — | 模型设置 |
| `dynamicCordisRunner` | 11 个插件运行方法 | — | 插件开关 |
| `pluginInventory` | `list` | — | 插件清单 |
| `session` | `attachment` | `request:{sessionId, attachmentId}` | `{attachment, data(base64)}` |
| `settings` | `describe` | （零参） | `{writable, hasDocument, namespaces[]}` |
| `settings` | `update/replace/mutate` | `{ns, patch\|section\|ops, expectedRevision}` | namespace 视图 |
| `credentials` | `describe/set/unset` | `refs/ref/value` | `{configured, source?, writable}` |
| `agentPresets` | `list/read/select/copy/deletePreset` | 见 §8.4 | roster / 文档 |
| `$events` | **WS 专用**（`/api/$events` + `/api/$events/result`） | — | 转发 Host 事件（approval/request、user-questions/request 瀑布交互） |

### 2.2 事件类型全集（session/page records 与 follow 流）

**message 类（表面事件，`surfaceOp`=append/replace）**：`user/message`、`assistant/message`、`tool/result`。
**chunk 类（流式，被打包成 chunkrow 或短串 raw）**：`assistant/chunk`(chunk.type = text-delta / reasoning-delta / tool-call-delta)；`chunkrow/text-chunks`、`chunkrow/reasoning-chunks`、`chunkrow/tool-call-chunks`。
**边界**：`turn/start`、`turn/end`、`step/start`、`step/end`。
**投影输入**：`model/selection`、`request/header`、`session/title`、`todo/write`、`plan/mode`、`permission/preset`、`sandbox/mode`、`approval/policy`、`approval/asked/decided`、`command/run/done`、`goal/change`、`subagent/descriptor`、`compaction/start/end/summary/prune`、`llm/retry*`、`feedback/record`、`schedule/change`、`session/end-seed`、`agent/inbox/spliced`、`tool-workflow/*`、`web/deepseek-search-llm-request` 等（KNOWN_SESSION_EVENT_TYPES 全集）。

### 2.3 全部 projections（本次部署实际注册：11 个 key）

确认来源：`cordis.patch.yml` 明示挂载 `session-stats` 与 `session-turn-outline`；加上基础层注册的 9 个。会话每个 key 通过 `item.projections.values.<key>` 或 control 流 `projection {key, value, seq}` 帧更新：

`sessionListMetadata {blank, lastPromptAt}`、`imageLimits {maxImageBytes, maxImagesPerMessage, maxMessageImageBytes, maxImagePixels, maxImageDimension, mediaTypes}`、`modelSelection {lastUsed, next:{provider, model, reasoningEffort?}|null}`、`agentPreset`、`title`、`goal`（GoalBar）、`todos [{content, status}]`（任务面板）、`subagentTiming {settledMs, active?}`、`subagent {one-shot|continuable, label?, seq}|null`、**`sessionStats {turns, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens}`（统计行 8 项）**、**`turnOutline`（轮次导航）**。

> 说明：`permissions`/`plan`/`tokenUsage`/`contextPressure` 未在本次部署的投影注册（fixture client 有，host 未必有）——权限/计划显示改走对应事件流与命令（/permission、plan/mode 事件），token 用量从 `assistant/message.usage` 事件实时累计（客户端折叠，无投影也准确）。

### 2.4 Web 前端结构（38 个功能包，按职责归类）

| 域 | 包 | 关键功能 |
|---|---|---|
| 布局 | layout, sidebar | 侧栏（<1024px 收成 rail）、AppFrame |
| 会话视图 | chat, conversation, session | 会话流、turn/step 时间线、Node 折叠、转录视图 Normal/Compact |
| 消息渲染 | renderer, tool, subagent, skill, cordis, deliverable, workflow-run, message-feedback, reference, user-questions | 块级动态渲染、工具卡片、子代理卡片、反馈、引用、提问卡 |
| 输入 | conversation(composer), input-trigger, attachment, model-selection, permission-presets, commands, plan, goal | @ 触发、附件轨、模型选择、权限选择、/命令、计划/目标栏 |
| 会话管理 | workspace | 工作区按钮栏、搜索、分组、排序、归档、fork、展开/折叠 |
| 轨迹 | trajectory | 轨迹表、详情面板、时间统计 |
| 设置 | settings, settings-general, settings-models, settings-plugins, settings-plugin-inventory, theme | 四栏目设置面板 |
| 其他 | onboarding, approval, jobs, schedule, brand | 首次运行、审批瀑布、jobs、schedule |

---

## 3. 总体架构（自顶向下）

```
┌──────────────────────────────────────────────────────────────┐
│                    DshHostService (前台服务)                    │
│  引擎生命周期：spawn node（bionic 前缀）→ 崩溃退避重启 → token    │
└──────────────┬───────────────────────────────┬───────────────┘
               │                               │
    ┌──────────▼──────────┐          ┌──────────▼──────────┐
    │   Engine (node)      │          │  Kotlin App (UI 层)  │
    │   :3080              │          │                      │
    │  /api JSON-RPC       │◄────────►│  ApiClient (HTTP)     │
    │  /api/remote.mux WS  │◄────────►│  StreamClient (WS)    │
    └──────────────────────┘          └──────────┬──────────┘
                                                 │
                                    ┌────────────▼────────────┐
                                    │  Domain (Angular-style)  │
                                    │  SessionRepository        │
                                    │  TranscriptStore  ────────┼──►  Timeline (turns/steps, 不可变引用)
                                    │  ProjectionsStore         │
                                    │  QueueStore (control 流)  │
                                    │  SettingsStore            │
                                    └────────────┬────────────┘
                                                 │ (StateFlow 单向流)
                                    ┌────────────▼────────────┐
                                    │  UI (Compose 或 View)    │
                                    │  ChatScreen + Trajectory │
                                    │  SessionsDrawer + Settings│
                                    │  NativeRenderer (Canvas) │
                                    └─────────────────────────┘
```

### 3.1 技术选型决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| UI 框架 | **Jetpack Compose + 自定义 Canvas 渲染器** | 动态流式内容（markdown 块、代码高亮、工具卡）需要细粒度重组；Compose 的 `LaunchedEffect`+LazyColumn 正好；网络栈已有，不引入反向代理 |
| 网络 | OkHttp（HTTP）+ OkHttp WebSocket | 现成、稳定、支持流；避免自研 WS 帧（协议已验证但没必要手写） |
| 状态 | 单 Activity + ViewModel + StateFlow | 手机单屏；无 fragments |
| 实时通道 | `session/follow` WS 流（首选）；`session/page` 轮询（兜底/补页） | follow 是引擎的正规流式通道 |
| 图片 | 相册 picker → 压缩 → base64 入 prompt；渲染用 attachment RPC 取图 | 服务端持久化一个源 |
| 主题 | day/night 跟随系统 + 手动偏好 | 走 `ui-theme.preference` 设置 |
| 国际化 | 内置 zh 主语言 + en 备用 | 与 web 一致 |

### 3.2 模块划分（包结构）

```
io.github.singalongdan.dsha/
├── engine/          DshHostService（已有）+ EngineController（生命周期/重启/日志）
├── net/
│   ├── DshApiClient.kt      HTTP JSON-RPC（已有，扩展全部方法）
│   ├── DshStreamClient.kt   WS /api/remote.mux（open/item/end/error/cancel 帧）
│   └── WireModels.kt        RPC 请求/响应数据结构（OrgJson 或 kotlinx.serialization）
├── domain/
│   ├── SessionRepository.kt 会话 CRUD+search+rename+fork+switch
│   ├── Timeline.kt          turn→step→node 折叠（不可变引用，增量更新）
│   ├── TranscriptStore.kt   当前会话的节点列表（跟随 follow 流追加/修补）
│   ├── ProjectionsStore.kt  projections 每键 StateFlow（modelSelection/todo/plan/goal/stats/usage）
│   ├── QueueStore.kt        control 流：队列条目 + jobs
│   ├── SettingsStore.kt     settings/describe 镜像 + 按 ns 绑定 + mutate（revision 栅栏）
│   └── AttachmentStore.kt   草稿图片轨（base64 + 尺寸探测）
├── ui/
│   ├── MainActivity.kt      （重写：单屏 + 顶级栏 + 左拉抽屉）
│   ├── chat/                ChatScreen、Composer、NodeRenderers、StatsBar、TaskBar
│   ├── trajectory/          TrajectoryScreen（右滑）
│   ├── sessions/            SessionsDrawer（工作区/搜索/分组/会话列表）
│   ├── settings/            SettingsScreen（6 栏目）
│   └── theme/               主题/字体/尺寸
└── render/
    ├── MarkdownRenderer.kt  原生 Canvas 绘制 markdown（行内/块级）
    ├── CodeBlock.kt         代码高亮（1-2-3 pass 分词，不做完整 prism）
    └── BlockView.kt         text/reasoning/tool/todo/subagent/goal/error 块的 View 实现
```

---

## 4. 页面与交互规格（按用户设想，对齐 web 行为）

### 4.1 整体布局（单屏，无底部 dock）

```
┌────────────────────────────────────┐
│ 顶级栏（56dp）                      │
│  ● 会话标题 [标准模式▾]        ⇅    │
├────────────────────────────────────┤
│                                    │
│    [ 会话 | 轨迹 ]   ← 右上双标注    │
│                                    │
│     对话流（LazyColumn）            │
│     · 用户/助手气泡                 │
│     · 推理折叠块                    │
│     · 工具卡片                      │
│     · 待办/计划/目标/子代理/审批卡    │
│     · 错误/截断/重试/压缩状态行      │
│                                    │
├────────────────────────────────────┤
│ 任务栏（agent 任务，横滑胶囊）        │
├────────────────────────────────────┤
│ 输入卡（点击输入框动效放大）          │
│ [权限▾][模型▾][     输入框    ][+]  │
├────────────────────────────────────┤
│ 统计行（8 项，见 §4.5）              │
└────────────────────────────────────┘
```

- **左滑 → 从左边拉出会话栏目**（侧栏抽屉，宽约 80% 屏宽；再左滑 → 设置抽屉，二级）
- **右滑 → 进入轨迹视图**（页面级水平翻页，轨迹/会话切换；会话内右滑也可做详情返回）
- 顶部栏点击"⇅"→ 展开会话选择（等同左拉侧栏）

### 4.2 顶级栏

| 元素 | 内容 | 数据来源 |
|---|---|---|
| 标题 | 当前会话标题（LLM 生成的 session/title 或"会话 XXXX"） | projections.title / follow 事件 |
| 模式徽标 | 标准模式/极简模式/创造模式/PTC 模式（当前 preset 名） | projections.agentPreset |
| 状态点 | 连接/运行/错误 | EngineController + transcript.isRunning |
| ⇅ | 打开会话抽屉 | — |

### 4.3 对话流（会话视图）

对齐 web `ChatView`：
- **消息节点**（node 渲染器）：user / assistant / reasoning / tool-call / tool-result / todo / subagent / goal / plan / approval / error / retry-status / compaction / reference / feedback。
- **turn 分组夹**：turn 之间显示分组行（"第 N 轮 · 12 个工具调用 · 3 个子智能体 · 已思考 X"）。
- **流式渲染**：`follow` 帧逐条增量 → Timeline 追加/修补 → 仅变更节点重组（不可变引用比较）。
- **转录视图模式**：normal（完整）/ compact（已完成轮次只显示最终消息，过程折叠）——设置项 `ui-chat.transcriptView`，默认 compact。
- **历史分页**：顶部"加载更早"（page `beforeSeq` 向前翻页，`maxMessages=50`，`hasMore` 控制显隐）；turn 导航跳转。
- **交互**：复制（长按）、工具详情（点卡片 → 详情面板：输入/输出/运行状态）、图片轻量查看、消息反馈（👍/👎）、引用会话跳转、fork（新对话中分支）。

### 4.4 输入卡（Composer）

| 元素 | 行为 | 数据来源 |
|---|---|---|
| 权限选择框 | 下拉：仅可查看/工作区内修改/完全权限；完全权限需风险确认弹窗 | command `/permission <preset>` 或 settings 默认；显示投影 currentValue |
| 模型选择框 | 下拉：provider/model + reasoning effort（推理档位）；loading/失败态 | session/modelCatalog + session/selectModel |
| 输入框 | 多行，`@` 触发路径补全（`@path` / `@"path with spaces"`）；/ 命令触发（/plan /goal /permission /compact /feedback /schedule）；点击动效放大卡片 | input-trigger、commands registry |
| + 附加 | 相册/相机/文件 → 附件轨（缩略图横排，可移除，单点预览） → 发送时 base64 入 prompt `{type:"image", data, mediaType, name}` 或经 attachment RPC；不支持图片的模型给出提示 | attachments registry + imageLimits 投影 |
| 发送/停止 | queue（排队）/ steer（插话）由"繁忙时 Enter 行为"设置决定；运行中按钮变停止 | prompt(mode) / cancel |
| 上下文气球 | 显示 context 占用百分比 + 分项（系统/工具/对话） | contextPressure 投影 |

输入框上方的 **任务栏**（TaskBar）：queue 里待发送的消息条（每条显示 排队中/已插话，可编辑/删除/steer）+ jobs 运行中的任务（后台任务）——数据源 `session/control` 流的 baseline+frames。

### 4.5 统计行（8 项，样例字串为 web 原文格式）

| 项 | 显示 | 来源 |
|---|---|---|
| 轮·步 | `22 轮 · 634 步` | sessionStats.turns/steps |
| LLM 时间 | `LLM 180分27秒` | sessionStats.llmMs |
| 工具调用时间 | `工具调用 259分7秒` | sessionStats.toolMs |
| 首 token 平均 | `首 token 平均 3.3秒` | ttftMs/ttftSteps |
| 吞吐 | `87 tok/s` | decodeTokens/decodeMs |
| 缓存命中 | `缓存命中 99.5%` | tokenUsage.cacheRead/(input+cacheRead) |
| 输入 | `输入 233M tok` | tokenUsage.uncachedInput+cacheRead+cacheWrite |
| 输出 | `输出 763K tok` | tokenUsage.output |

点统计行 → 展开详情面板（每轮用量：未缓存输入/缓存读/缓存写/输出/推理 token、每轮用时+速度+TTFT、上下文压力条）。

### 4.6 轨迹视图（右滑）

对齐 web Trajectory：
- **轨迹表**：每列 = turn；每行 = 该 turn 的步骤节点（assistant step / tool call / reasoning / input message / request header / compaction / session end）。行头显示节点名与耗时。
- **详情面板**：点行 → 右侧详情（输入/输出/运行状态/耗时/token 用量/时间戳，可切换本地时间/Unix 时间）。
- **时间线显示偏好**：显示 wall time / delta 等（trajectory 设置）。
- 移动端：turn 作为水平的横向导航，步骤作为纵向卡片列表。数据源：同一 `follow` 流 + `request/header`、`usage`、`step/start/end`、`tool/call/result`。

### 4.7 会话侧栏（左滑拉出）

对齐 web Sidebar + WorkspaceBrowser：

```
┌────────────────────────────────┐
│  [dsh logo]   [⇅ 折叠]          │  ← 图标行
├────────────────────────────────┤
│  工作区按钮栏:                    │
│  工作区 [⌕搜索] [▦分组▾] [＋添加]  │
├────────────────────────────────┤
│  搜索框（会话搜索，输入即搜）        │
│  ──────────────────────────────  │
│  ● 会话标题      时间             │  ← 会话动态显示栏
│  ● 会话标题      时间             │
│  ▸ 展开其他会话（N）              │
├────────────────────────────────┤
│  ⚙ 设置    [+ 新会话]    [⇅]     │  ← 再次左滑 → 设置抽屉
└────────────────────────────────┘
```

- 工作区按钮：工作区标识（当前 workspace 名）、搜索（亮起时展开搜索框）、分组菜单（按工作区分组/平铺 + 排序：手动/最近更新）、添加（新工作区 + 目录选择）。
- 会话行：标题 + 更新时间 + 运行中徽标；当前会话高亮；点击切换；长按菜单：**fork / 归档 / 重命名 / 停止运行**（对齐 web 菜单）。
- "展开其他会话"：折叠组展开显示隐藏会话（sessions.collapse/expand）。
- 首次运行引导（welcome-notice 弹窗 + API key 引导）——对齐 web onboarding。

---

## 5. 数据层设计

### 5.1 HTTP JSON-RPC
（沿用现有 DshApiClient；按 §2.1 全集扩展。要点：`throughSeq ≤ cursor`；零参方法 `args={}`；scoped 方法带 wire 参数。）

### 5.2 WebSocket 流（DshStreamClient）

**端点**：`ws://127.0.0.1:3080/api/remote.mux`（Header 带 Cookie；升级鉴权失败回裸 HTTP 401/403；服务端每 2s Ping，连续 2 次未回 Pong 即 terminate——客户端必须回 Pong）。

**客户端帧**（客户 → 服务）：
```json
{"type":"open","streamId":"<uuid>","endpoint":"session/follow","payload":{"args":{"request":{...}}}}
{"type":"cancel","streamId":"<uuid>"}
```
**服务端帧**（服务 → 客户）：
```json
{"type":"item","streamId":"<uuid>","value":{...}}
{"type":"end","streamId":"<uuid>"}
{"type":"error","streamId":"<uuid>","error":{"code","message","details"}}
```

**session/follow 的 value 帧序列**（已从源码确认）：
1. `{"type":"snapshot","header":{...},"cursor":N,"records":[...],"hasMore":bool,"projections":{...}}` — 打开快照
2. `{"type":"event","event":{type,seq,time,data,...}}` — 无间隙增量（seq 连续，跳过即视为丢帧 → 半帧回补 page）

**session/control 的 value 帧**：`{"type":"baseline","value":{queues:{sid:[items]}, jobs:{sid:[...]}, projections:{sid:{asOfSeq,values}}}}` → 之后 `queue`/`jobs`/`projection{key,value,seq}` 帧。

**$events 流（审批/提问瀑布）**：`{"type":"open",...,"endpoint":"$events","payload":{"args":{}}}` → 首帧 `{"type":"ready","clientId":...,"host":{home}}`；emit 帧 `{"type":"emit","event",args}`；**waterfall 帧** `{"type":"waterfall","event","eventId","agentId","request"}`（approval/request、user-questions/request）→ 应答 `POST /api/$events/result` body `{"args":{"clientId","eventId","outcome":{"kind":"next"|"result","value"?|"rejected","error"?}}}`；`{"type":"cancel","eventId"}` 取消。

**流生命周期**：
- 会话激活时开 `session/follow`（address=sessionId）；
- 引擎重启 → 重连（token 变了重新鉴权，再重开流）；
- session 切换 → cancel 旧流，开新流；
- control 流全局一条；$events 挂在 MainActivity 生命周期（审批/提问需要）。

### 5.3 Timeline 折叠引擎（核心难点，先做对）

采用与 web 相同的**不可变引用 + 增量修补**模型（源码：conversation/LocationTimeline）：
- `Timeline = ordered turns[]；turn = {turn, start, end, status, steps[], data}`；`step = {turn, step, start, end, status, data}`。
- `update(events)`：对每条事件计算坐标（turn/step），只重建受影响的 turn，其它 turn 保留原引用 → UI 端 LazyColumn 用 key 比较，只重组变化节点。
- `attach(seq)` / `appendBoundary(event)`：快速插入尾部新事件。
- 节点 `data`（turn/step 级）持有消息块数组：text / reasoning / tool-call / tool-result / todo / subagent / goal / error / retry / compaction…
- **剪枝（显示剪枝）**：`nursery` 双缓冲（node 少时走 nursery，超阈值自动 compact 成 map）；超长 JSON 截断（20000 字符）；已完成轮次按 transcriptView 折叠过程节点；上下文压力 > 上限提示。

### 5.4 双通道一致性
- follow 流是权威（gap-free）；page 轮询只在「流断连重连」「加载更早历史」「turn 导航」时用。
- seq 水位去重：流帧带 seq，页面带 seq；两者共用 `seenSeqs`（已有实现经验）。

---

## 6. 渲染层设计：原生实时渲染器

### 6.1 为什么不写 WebView
- 需要与 App 手势（左滑抽屉/右滑轨迹）完全一致的原生体验；
- 图片附件可走系统相册；长按复制/文本选择原生；
- 性能：流式每帧更新一个 LazyColumn 比 postMessage → 虚拟 DOM diff 便宜一个量级。

### 6.2 渲染器分组

**消息节点 View（Composable，按 kind 分派）**：
1. `UserBubble` / `AssistantBubble`：富文本段落、markdown 行内样式、代码块（等宽+背景）、表格、列表、链接（点击可开系统浏览器）、图片（attachment RPC 取 base64 → Bitmap）。
2. `ReasoningBlock`：流式灰底块，默认折叠一次（点开看全文），显示"已思考 X 秒"。
3. `ToolCard`：图标+名称+状态（运行中/完成/失败）+ 参数摘要 + 结果摘要；卡片型（点击开详情面板）；bash 工具显示命令（等宽），web 搜索显示查询与结果数。
4. `TodoPanel`：标题"任务" + 进度（N 已完成/M 进行中/K 待处理），条目状态点。
5. `PlanCard` / `GoalBar` / `SubagentCard` / `ApprovalCard` / `CompactionRow` / `AskCard`（提问）/ `FeedbackRow`。
6. 系统行：错误（红）、重试状态（含倒计时）、LLM 请求头（可展开看 provider/model/token）、turn error、maxTokens 截断提示。

**MarkdownRenderer（自定义 Canvas/Text）**：`TextLayout` + 行内 span 计算；块级（标题/列表/代码/表格/引用）用独立 Composable；不做完整 GFM，覆盖 web 中实际出现的 95% 语法。

### 6.3 流式动画
- 文本：逐块追加（`text-chunks` 帧到 → 追加到节点文本 → animateContentSize 平滑增高）；
- 光标：流式过程中节点末尾显示 ▍（与当前 web 行为一致）；
- tool-call 参数增量：工具卡片参数区随 `args` 数组增长；
- 首 token 到达：状态点变"回复中"。

---

## 7. 功能对齐清单（web → app，全部功能）

### 7.1 会话视图（Chat）
- [x] 用户/助手消息（含图片消息）
- [x] 推理过程显示（流式 + 折叠）
- [x] 工具调用卡片（bash/fs/glob/grep/web/代码/子智能体等，按工具名显示图标）
- [x] 工具结果详情面板（输入/输出/状态/边缘情况：运行中、不在窗口内）
- [ ] todo 面板（任务清单进度）
- [ ] 计划模式（plan.mode 芯片，显示 pending/active）
- [ ] 目标栏（GoalBar：目标存在时显示、编辑/暂停/恢复/清除）
- [ ] 子代理卡片（descriptor + 反馈）
- [ ] 审批卡片（approval/asked 展示；审批动作当走 WS events 流，见风险 §12）
- [ ] 队列（queue）任务栏：待发消息条（编辑/删除/steer 全部）+ jobs
- [ ] 命令视图（/command 运行中/完成/失败状态行）
- [ ] 重试状态（LLM 重试：label + retry/maximum + 倒计时）
- [ ] 压缩（compaction）状态折叠行
- [ ] 消息反馈（feedback/record，👍/👎）
- [ ] 引用会话（reference 摘要 chip，点击跳转）
- [ ] 上下文压力（context 气球 + 分项）
- [ ] 每轮用量（turnUsage：未缓存输入/缓存读/缓存写/输出/推理）
- [ ] 每轮用时（turnTime：总用时/速度 TPS/TTFT）
- [ ] 会话统计行（8 项，§4.5）
- [ ] 轮次导航（跳转第 N 轮）
- [ ] 转录视图模式（标准 Normal/简练 Compact）

### 7.2 输入（Composer）
- [ ] 权限选择器（3 预设，danger 需确认弹窗）
- [ ] 模型选择器（provider/model/effort 三层 + 失败重载）
- [ ] @ 路径补全（@path / @"path with spaces"，带目录与文件候选）
- [ ] / 命令菜单（plan/goal/permission/compact/feedback/schedule…按 registry 收集）
- [ ] 附件轨（多图缩略图、移除、预览、drop 区域）
- [ ] 发送模式（queue / steer）+ 停止
- [ ] 输入框动效放大（聚焦展开，失焦收起）

### 7.3 会话管理（侧栏）
- [ ] 工作区选择/指示（当前工作区名、目录）
- [ ] 会话搜索（边输入边搜，API `session/search`）
- [ ] 分组（按工作区/平铺）+ 排序（手动/最近更新）
- [ ] 会话列表（标题/时间/运行中/当前高亮）
- [ ] 新会话（当前工作区）
- [ ] 会话菜单：fork（在完成的最后 turn 分支）/ 归档 / 重命名 / 停止运行 / 打开目录
- [ ] 展开其他会话（折叠组）
- [ ] 侧栏折叠动画（rail ↔ wide）
- [ ] 首次运行欢迎 + API key 引导

### 7.4 轨迹（Trajectory）
- [ ] 轨迹表（turn 列 × 步骤行：assistant step/tool call/reasoning/input/request header/compaction/session end）
- [ ] 详情面板（输入/输出/状态/耗时/用量/时间戳，本地时/Unix 切换）
- [ ] 时间线显示偏好（墙钟/Δ）
- [ ] 从工具卡片跳转到轨迹详情（inspectCall → openView("trajectory", callId)）

### 7.5 设置（见 §8）

---

## 8. 设置六栏目设计

用户要求：dsh 的 **4 个栏目** + **1 个 Android 特有** + **1 个软件信息/开源协议** = 6 个。

### 8.1 栏目 1：通用设置（对齐 web General，6 项全做）

| 设置项 | 类型 | namespace 字段 | 选项/默认 | 说明 |
|---|---|---|---|---|
| 权限默认 | 下拉 | `permission.defaultPreset` | workspace-write/danger-full-access（+read-only 按宿主）；默认 workspace-write | 新会话默认；切 danger 需确认 |
| 语言 | 下拉 | `locale.preference` | zh「中文」/en「English」；默认跟随系统 | |
| 外观 | 三选 | `ui-theme.preference` | 浅色/深色/跟随系统；默认跟随系统 | |
| 字号大小 | 步进 12–17px | `ui-theme.fontSize` | 默认 14 | 影响会话内容字号 |
| 对话显示 | 下拉 | `ui-chat.transcriptView` | Normal/Compact；默认 compact | |
| 繁忙时 Enter | 下拉 | `ui-conversation.busyEnter` | queue/steer；默认 queue | 运行时生效；Cmd+Enter=另一行为 |

### 8.2 栏目 2：模型（对齐 web Models）——✅ 已实现并真机验证

- 页头：标题「模型」+ 说明「填入各提供方的 API 密钥即可使用其模型。」+「已保存 X。」提示（savedNotice）
- 当前模型行：来自 `session/modelCatalog.default`（实测 deepseek-v4-flash）
- **提供方列表**：`llm/listConfigurableProviders` + `credentials/describe` → 行 = displayName +「自定义」标签(declared) + **状态圆点**（绿=密钥已配置 / 红=缺失）
- **ProviderEditor**（点行展开）：API 密钥（`writable=false` 显示「由启动环境提供（只读）」并禁用——实测 env 来源）+ 来源标签 + 显示名称 / API 协议（pi-ai 三选一）/ API 地址 + **模型目录**（ID + 容量 1M/256K + 删除 + 添加模型）+ 保存 + 删除提供方 / 恢复默认模型
- **添加提供方**：列出未配置的 pi-ai 提供方（实测 40+：azure-openai-responses / baseten / cerebras / cloudflare / fireworks / github-copilot…）→ `settings/mutate` set `providers.<id>` → 新行出现并自动展开编辑器（实测 200）
- **添加自定义提供方**：ID / 显示名 / API 地址 / 协议三选一 / 密钥 → mutate set + credentials/set
- **删除**：mutate unset 路径 + credentials/unset（实测 200，行移除）
- 实测 RPC：`llm/listProviders`、`llm/listConfigurableProviders`、`credentials/describe|set|unset`、`settings/mutate`（auto revision）、`llm/discoverModels`（备用）

### 8.3 栏目 3：插件（对齐 web Plugins，两子 tab）

**插件配置**（4 张暂存表单卡）：
1. 终端 Shell `shell`：timeoutMs（默认 120000）、maxOutputBytes（默认 64000）
2. Agent 循环 `agent-loop`：maxParallelToolCalls（默认 10）
3. 网页搜索 `web-search-deepseek`：API Key（只写，ref DEEPSEEK_API_KEY）、baseURL（默认 https://api.deepseek.com/anthropic/v1）、maxUses（默认 5）
4. Subagent `subagent-model-selection`：enabled 开关、allowedModels 勾选列表

**插件列表**（只读）：来自 `pluginInventory.list()`；搜索框；会话插件/全局插件分组；每卡显示名称/来自/禁用条件/配置状态/运行状态（未运行/等待依赖/加载中/运行中/启动失败/卸载中）。

### 8.4 栏目 4：Agent 预设（对齐 web Agent presets）

- 分组：内置（标准/极简/创造/PTC，只读不可删）+ 自定义。
- 行操作：设为默认（settings.update("agent-presets", {default:id})）、查看（只读看 agent.cordis.yml + 元数据）、复制（from,id,name → agentPresets.copy）、删除（deletePreset，仅自定义）、打开目录（settings.openAgentPresetDirectory，无原生打开器时显示路径）。
- 通过左侧 rail 底部 "⚙" 与预设列表联动。

### 8.5 栏目 5：Android 特有设置（Termux 宿主设置）

| 设置项 | 类型 | 实现 |
|---|---|---|
| Root 模式 | 开关 | `files/root_mode` 标记 → 引擎重启后 bash 经 `bin-root/bash`（su）执行 |
| 引擎地址/端口 | 文本 | 默认 127.0.0.1:3080（改端口重启生效；https 留作后续） |
| 启动时自启引擎 | 开关 | BOOT_COMPLETED 广播启动 DshHostService |
| 前台通知 | 开关 | 常驻通知显隐（Android 13+ 权限） |
| 引擎日志 | 查看/清空 | `files/dsh-node.log` 打开查看 |
| 网络诊断 | 一键 | 显示 node 出站代理/证书状态（DNS fix、SSL 配置） |
| Termux 参数 | 组 | C++ 环境（LD_LIBRARY_PATH、OPENSSL_CONF、SSL_CERT_FILE 只读展示）+ 可改项：DNS 服务器列表、是否注入 DEEPSEEK_API_KEY 环境变量 |

### 8.6 栏目 6：关于（软件信息 + 开源协议）

- 应用版本号（versionName 0.1.0 → 递增）、应用 ID、构建时间。
- 引擎版本（从引擎日志/`dsh --version` 读）、运行时（node 版本、前缀路径）。
- 开源协议：上游 dsh 为 **MIT**（据随包 LICENSE 正文与 package.json —— 早期此处误记为 AGPL，已更正）；本项目自有代码许可由所有者决定（当前 LICENSE 为 AGPL-3.0）。第三方组件清单见 `NOTICE.md`。
- 第三方依赖：@deepseek-ai 各包、OkHttp、Compose、Kotlin、协程、bionic/openssl/curl 等（README 生成）。

---

## 9. Android 特有设置（第 5 栏目细化）

本质是"宿主环境"设置，非 dsh 自身功能——这正是"我们 App 特有的第 5 个栏目"。
实现要点：
- `root_mode` 标记文件已有（DshHostService.ensureRootWrapper）；
- 自启：`BOOT_COMPLETED` receiver（targetSdk 28 下限制少，但需处理「开机后延迟启动等待解锁」）；
- 浏览器打开外链（`session/openWorkspacePath` 在 Android 上映射为「用系统文件管理器打开」或「调起 Terminal 复用路径」——无桌面环境，无操作时给出"查看路径"）；
- 目录选择器：SAF（ACTION_OPEN_DOCUMENT_TREE）替代 web 的目录浏览弹窗，选出后转为平台路径传给 session create cwd。

---

## 10. 软件信息/开源协议（第 6 栏目细化）

- 展示：应用名 DSHA / 版本 / 构建号 / 渠道（debug/release）/ 引擎（当前随包 dsh 0.1.5-rc.2 + node 24.18.0，**运行时读取**，非硬编码）/ 权限清单。
- 开源协议文本：内嵌 LICENSE（AGPL-3.0 全文），SPDX 标识为 **AGPL-3.0-only**，并列出关键第三方许可链接（`NOTICE.md`）。
- 数据/隐私：API Key 仅存本机（credentials 文件），不上传；崩溃日志仅本机。

---

## 11. 实现阶段计划（每阶段可独立交付、可真机验证）

### 阶段 0：地基（已完成）
- ✓ bionic 前缀、引擎运行、RPC 鉴权、会话 create/list/prompt/page（增量列表）
- ✓ Composer 基础、气泡渲染、会话列表、模型/权限/预设/API key 设置（RPC 已验证）

### 阶段 1：实时流（核心体验）
1. OkHttp + DshStreamClient（/api/remote.mux：open/item/end/error/cancel，优雅重连）
2. `session/follow` 订阅当前会话，替换轮询（保留 page 兜底）
3. Timeline 折叠引擎（turns/steps，不可变引用）——移植 web 同款语义
4. Node 渲染器细化：流式文本（增量追加 + 光标）、reasoning、tool-call/tool-result 卡片
5. 上下文气球 + 统计行（8 项，实时刷新）
6. 冒烟测试：发消息 → 千字回复全程流畅（60fps 无卡顿），断流重连恢复

### 阶段 2：会话管理完整化
1. 会话抽屉（左滑）：工作区按钮栏、搜索（session/search）、分组/排序、会话列表、展开/折叠
2. 会话菜单：fork（在完成的最后 turn 分支）/ 重命名 / 停止运行（cancel）/ **归档**（`workspace/archiveSession` RPC——已确认存在：registry 全局 archive 集合，UI 隐藏；"恢复"= workspace 视图未归档）+ 搜索（session/search）
3. 新会话（当前工作区）；turn 导航（turnOutline 投影）+ 加载更早（page beforeSeq）
4. 转录视图 Normal/Compact 切换（设置联动）

### 阶段 3：轨迹视图
1. 右滑切换会话/轨迹
2. 轨迹表（turn × step）+ 行详情面板（输入/输出/耗时/用量/时间戳）
3. 从工具卡片跳轨迹详情；时间显示偏好设置

### 阶段 4：设置六栏目全量
1. 通用 6 项（permission/locale/theme/fontSize/transcriptView/busyEnter）全走 settings RPC
2. 模型栏目（编辑器、提供方 CRUD、discoverModels）
3. 插件栏目（4 卡 + 插件列表）
4. Agent 预设栏目（默认/查看/复制/删除/打开目录）
5. Android 栏目（root 模式、端口、自启、日志、诊断）
6. 关于栏目

### 阶段 6：打磨（P6，进行中）
1. ✅ markdown 渲染器（MarkdownText：粗体/斜体/行内代码/标题/列表/代码块/引用/简表 + AnnotatedString 行内解析 + 代码块语法高亮）——助手气泡改用
2. ✅ 主题三态（light/dark/system，设置页外观选择器 → AppCompatDelegate + SharedPreferences 持久化，实测深色生效）
3. ✅ 命令菜单（/plan /goal /permission /compact /feedback → commands/execute 实测 200）
4. ✅ API Key 运行时化（设置页可改 → SharedPreferences → 引擎 env，回退默认）
5. ✅ 双 UI 体系清理（删 View 旧版 MainActivity/SettingsScreen/MessageAdapter/SessionAdapter/UiModels + 旧布局/drawable，ComposeChatActivity 为 LAUNCHER）
6. ✅ 设置写回：权限默认预设三选一（read-only/workspace-write/danger-full-access → settings/mutate 实测 200 + UI 实时更新）
7. ✅ 待办面板 TodoBar（todo/write 事件 + todos 投影 → 任务清单 (n/m) + ☐/☑ 状态行，真机渲染验证）
8. ✅ 目标栏 GoalBar（goal 投影 + goal/change 事件 → 🎯 目标行）
9. ✅ 会话搜索（本地过滤；引擎 session/search 索引 openAt=never 禁用）
10. ✅ @ 路径补全（fileReferences/list → 候选 → @path 插入，真机验证 todo-test.txt）
11. ✅ 附件缩略图轨 AttachRail（选图 → 缩略图+× → 发送带附件，真机验证）
12. ✅ About 发布页（版本 / AGPL-3.0-only / Compose 声明）
13. ✅ 性能基准（jank 11.45% / 90分位 16ms / 内存 126MB / snapshot 180 事件 → 151 节点）
14. ✅ 发布签署（keystore/dsh-release.jks + signingConfig release；确认 side-load 分发——targetSdk 28 为引擎 exec 权限关键）
15. ✅ 消息反馈（👍👎 全链路：semantics 定位 → onFeedback → messageFeedback/put）
16. ✅ 工具可用（root wrapper su 修复 + danger-full-access → test-shell.txt 真实创建）
17. ✅ 模型设置页对齐 web（提供方列表+状态圆点 / ProviderEditor：API Key 只读态·baseURL·模型目录·保存 / 添加提供方 40+ / 自定义 / 删除 / 恢复默认，全部 mutate 实测 200）
18. ✅ 消息视图动态交互集（本轮）：上下文注入→默认折叠披露行（识别 `source.kind=plugin`，标题+form+sections 摘要+展开正文）；
   压缩/重试/系统提示词→折叠披露行；「深度求索中…」shimmer 运行行 + ≥15s 计时；「已停止」胶囊；
   自动跟随修正（follow 意图 + scrollToEnd 补偿长末项）+ 「回到底部」浮钮
19. ✅ 轮次用量/用时面板（本轮）：按轮统计（TurnStat：LLM/工具/TTFT/decode/tokens 分项）
   → 轮尾行「用时 X · 输入 Y · 输出 Z ›」+ 点击弹「本轮用量」面板
   （提供方/模型 · 缓存命中% · 未缓存输入 · 缓存读取/写入 · 输出(含推理) ｜ 用时 · TTFT · TPS · LLM/工具）
20. ✅ 上下文占用表（本轮）：输入栏上方「上下文 N%」胶囊 → 面板（使用量/窗口 · 占比 · 提供方模型 · 剩余）
21. ✅ 轮次导航轨道（对齐 web TurnNavigator）：消息区右缘细轨，每轮一条标记；当前可视轮更长更亮（animateDpAsState 140ms）；
   运行中的轮脉动（alpha 1→0.35，1s Reverse）；点击标记跳转到该轮（实测：点第3条跳至第3轮并高亮切换，同时进入非跟随态显示 ↓）
22. ✅ Android 宿主补齐（本轮）：**自启引擎**（BootReceiver + RECEIVE_BOOT_COMPLETED + prefs，实测切换为「开机后自动启动」）；
   **前台通知**（真实权限状态显示 + 一键请求）；API Key 文案修正为「使用内置默认」
23. ✅ 抽屉分组（本轮）：Folder/List 图标切换「平铺 ↔ 按日期分组」，分组表头 今天/昨天/本周/更早（真机验证：今天 + 本周 表头均渲染）

### 12.3 审批（approval）交互——✅ 契约已按引擎源码修正（R24，真机验证）
引擎契约（`dsh-api-gateway` `parseRemoteEventResult`）：
- 应答体必须**恰好**含 `clientId` / `eventId` / `outcome` 三键；
- **`clientId` 只在 `$events` 的 `ready` 帧下发**（waterfall 帧只带 `{type,event,eventId,agentId,request}`）；
- `outcome = {kind:"result", value:<JSON>}`；`value` 语义：
  - `approval/request` → **字符串词汇** `"allowed-once" | "rejected" | "cancelled"`（`dsh-user-approval` OUTCOMES）
  - `user-questions/request` → **`{answers:[{id, selected:[label], custom?}]}`**（`dsh-tool-ask-user` output schema）

修复要点：ready 帧捕获 clientId；两种事件分别组装正确 value；同 eventId 去重（引擎会重复投递）；
`onDismissRequest` 安全回落（审批→rejected、提问→取消）＋「取消本轮」逃生按钮；应答失败也清挂起（**永不死锁**）。
实测：提问提交答案 → `ok:true` → 工具返回 → agent 续答并 `turn/end completed`；审批「允许一次」→ `value=allowed-once` → 工具执行；
「取消本轮」→ `value={"answers":[]}` → 弹窗关闭。

### 12.4 稳定性与设计修订（R25）
**闪退修复（真实崩溃）**：`MarkdownText.highlightCode` 在未闭合引号/注释时生成越界 span →
`StringIndexOutOfBoundsException: begin 39, end 44, length 43`（栈：highlightCode → MarkdownText → AssistantBubble）。
修法：span 裁剪到正文长度 + 丢弃空/重叠区间 + **整条渲染路径 runCatching 回退纯文本**（渲染永不崩）。
注：用户初判为"提权导致"，但崩溃栈明确指向代码高亮器，与 su/root 无关。

**交互从模态改为对话内联**（对齐 web：卡片即消息流一部分）：
- 提问卡/审批卡作为 LazyColumn 的**内联项**渲染（可滚动、可回看、顶栏与 Tab 仍可见），移除模态 `AlertDialog`
- 提问卡：问题 + 选项（单选/多选圆点 + 描述）+「提交答案」/「取消本轮」
- 审批卡：「需要你确认」+ 工具名 + 原因 + 允许一次/拒绝/取消本轮

**彩色操作轨迹条**（替代单色轮次轨道）：Canvas 迷你地图，**每个操作按类型着色**
（工具=琥珀 / 助手=蓝 / 用户=紫 / 推理=灰蓝 / 审批=粉 / 失败=红 / 注入=浅灰），
自上而下映射消息流；半透明矩形 + 白线表示当前视口；点击任意处跳转对应操作。

**设计审视落地（美学/原理/操作逻辑）**：
| 问题 | 改进 |
|---|---|
| 主题是 M3 默认紫，与 DSH 品牌不符 | 覆写 colorScheme 为 **DeepSeek 蓝**（浅 #4176E6 / 深 #679EFE，含 primaryContainer 等） |
| 输入区 5 控件分两行、噪音大 | 合并为**单行元数据**：预设▾ · 权限▾ · 模型▾ ｜ 排队/插话；chip 降高（28dp）降字号（11sp） |
| 顶栏 `⇅` 无功能（装饰） | 改为**刷新会话**（重拉会话列表 + 重新 follow） |
| Enter 固定发送，粘贴多行代码会误发 | 新增设置项 **回车键行为：直接发送 / 换行（发送键提交）** |

### 12.6 导航与打磨轮次（R26+，持续循环）

**导航重构（按用户判断调整）**
- **会话页**右缘 = **小点导航**（对齐 web：每轮一个小圆点，当前轮 6dp 高亮、其它 4dp 半透明、运行轮脉动；点击跳转该轮）
- **轨迹页**右缘 = **彩色操作轨迹条**（按行类型着色：工具=琥珀/助手=蓝/用户=紫/推理=灰蓝/失败=红/压缩=灰；半透明视口框 + 白线；点击跳转）
- **手势导航**：会话页**左滑 → 轨迹页**、**右滑 → 会话抽屉**；轨迹页**右滑 → 会话页**
  （用 `detectHorizontalDragGestures`，仅认领横向滑动，纵向仍归 LazyColumn；阈值 60dp）

**代码层面审查发现并修复**
1. **引擎看门狗永久停摆（严重）**：`DshHostService.engineLoop` 里 `if (code == 0) break` ——
   dsh CLI 收到 SIGTERM 会**优雅退出（exit 0）**，被当成"用户主动停止" → 服务仍在、node 进程消失、3080 无监听、App 卡 AUTH_LOST。
   修复：退出码 0 也重启；只有 `onDestroy`（`serviceStopping`）才结束监督循环。
   实测：`pkill` 引擎 → `engine exited code=0, restart in 1000ms` → 新 pid 起来 → 客户端 `re-auth attempt 2 OK` → CONNECTED。
2. **客户端闭环自愈**：重连连续 3 次失败（引擎真的不在）→ 主动 `DshHostService.restart()` 请服务拉起引擎；
   12 次仍失败则提示用户去设置页手动重启。实测形成"杀掉→自愈→恢复可用"完整闭环。
3. **资源泄漏**：Activity 无 `onDestroy`，WebSocket 三流（follow/control/$events）与重连协程在退出后仍存活。
   新增 `SessionStreamController.close()`（停跟随 + 递增代际 + 取消 scope + 关流 + 注销），Activity `onDestroy` 调用。
   实测日志：`controller close` → `follow closed: client close` → `$events closed: client close`。
4. **跨线程数据竞争**：`switchSession`（主线程）重置 builder/统计 HashMap 与流线程 `applyEvent` 并发 →
   可能 ConcurrentModificationException。新增 `stateLock`，`applyEvent` 与切会话重置同锁串行化。

**操作层面审查发现并修复**
5. **失败静默**：发送/审批应答/停止失败此前只写日志 → 用户"点了没反应"。
   新增统一提示通道（Scaffold `SnackbarHost` + `notify()`）：发送失败提示原因；控制器新增 `lastError` 流，
   应答被网关拒绝等错误浮到界面；重连放弃时提示手动重启路径。

**交互形态升级（R27）**
6. **会话/轨迹 Tab → 分段控件**：带底色的双段控件（30dp 高、16dp 内距、选中项白底蓝字），
   右侧显示当前视图规模（"N 条消息"/"N 行轨迹"）——比纯文字 Tab 更明确的选中态与更大可点面积。
7. **小点导航压字修复**：消息列表增加 `end = 18dp` 内容边距（仅在轨道可见时），正文不再被小点覆盖。
8. **消息长按操作单**（对齐 web 消息操作行）：长按用户/助手气泡 → 操作单
   - **复制全文**（ClipboardManager，含提示）
   - **重新发送**（仅用户消息：直接重发原文）
   - **从此处分叉新会话**（`session/fork {sessionId, atSeq}`，atSeq 取自节点 meta["seq"]；成功后自动切换到新会话）
   - **查看本轮用量**（复用 TurnUsageDialog）
   实测：`POST /api/session/fork -> 200 {"sessionId":"session-4d10f3e2…"}`，
   新会话 `parent=session-68ec05f8…`（血缘正确），App 自动跟随（`snapshot cursor=7371 records=299 header=session-4d10f3e2…`）。

**性能与工具化（R28）**
9. **流式渲染优化（性能）**：流式期间改用**纯文本**渲染 + 去掉 `animateContentSize`，
   仅在定稿后切到 Markdown/代码高亮。原来每个 chunk 都全量重解析整段 Markdown（含代码高亮 span 计算）
   并触发一次布局动画 —— 长回答下这是主要掉帧来源。实测流式中/定稿后渲染均正常。
10. **轨迹页搜索 + 类型筛选**：搜索框（匹配标题/正文）+ 类型 chips（全部/工具/消息/推理/失败）
   + 实时计数（`27/64`）；筛选结果同步作用于**彩色轨迹条**（只显示被筛中的行）。
11. **错误态可恢复**：启动失败页从"只有一行红字"改为 图标 + 标题 + 原因 + **重试**/**重启引擎** 两个动作
   （重试重跑 bootstrap；重启引擎请前台服务拉起 node 进程后再重试）。

**稳定性标注 · 抽屉能力 · 引用（R29）**
12. **Compose 稳定性标注（性能）**：`TranscriptNode` / `TrajectoryRow` / `SessionItem` 标 `@Immutable`——
   它们含 `Map<String,String>` 字段，Compose 默认判为 unstable → 流式时**所有可见项每 chunk 全量重组**。
   同时把消息列表 key 从 `key+turn+step` 简化为 `node.key`（turn/step 变化会让 key 抖动，导致 item 重建、折叠态丢失）。
   **实测（流式生成中）**：`Total frames 1001 · Janky 3 (0.30%) · 50th 7ms · 90th 8ms · 95th 8ms · 99th 10ms`。
13. **抽屉：置顶 + 分叉**（先做 RPC 探测，避免做死 UI）：
   - 运行时探测确认引擎**不支持** `session/archive`、`session/delete`（均报错）→ 不实现归档/删除
   - `session/fork` 支持 → 抽屉菜单新增「分叉新会话」（实测：点击后新会话出现在列表）
   - 「置顶」为本地偏好（SharedPreferences `pinned_ids`），置顶项排最前并显示 📌
   - **修掉一个真实缺陷**：`refreshSessions()` 在 `applySort()` 之后又执行 `sessionsState.value = list`，
     把排序/置顶结果覆盖回未排序列表 → 置顶"看起来没生效"。现在只有 `applySort()` 一个落地口。
     实测：📌 会话 4d10f3e2（8 分钟前）稳定排在「1 分钟前」的会话之前。
14. **引用到输入框**：长按用户消息 → 「引用到输入框」→ 输入框注入 `> 原文`（多行引用格式）并可继续补充。
   实现：ComposerBar 新增 `injectText`/`onInjectConsumed`（拼到现有草稿前，不覆盖用户已输入内容）。

**无障碍与空状态（R30）**
15. **自定义控件的读屏支持**（此前 Canvas/Box 类控件对无障碍层完全不可见）：
   - 会话页小点导航：每个点 `contentDescription = "第 N 轮"` + `role = Button`
   - 轨迹页彩色轨迹条：`contentDescription = "轨迹导航：共 N 行，点按跳转"` + `role = Button`
   - 折叠披露行：`role = Button` + `stateDescription = 已展开/已折叠`
   - 「深度求索中…」与「已停止」：`liveRegion = Polite`（状态变化会被读屏播报，且不打断当前朗读）
   **验证**：`uiautomator dump` 节点树中出现 `第 1..6 轮`、`轨迹导航：共 79 行，点按跳转`、`发送`、`刷新会话`
   —— 即这些控件已进入无障碍可读/可聚焦范围。
16. **抽屉空状态**：无会话时显示 🗂 + 「还没有会话 / 点下方「新会话」开始」；
   搜索无结果时显示「没有匹配的会话 / 换个关键词试试」（区分两种空的原因，给出下一步）。
17. 代码审查确认：图片附件解码/压缩在 `Dispatchers.IO`（无主线程大图 ANR 风险）。

**深色模式与对比度审计（R31，首次系统化验证）**
18. 用**像素采样 + WCAG 相对亮度**客观测量（不靠目测），在真机深色模式下测得：
   | 元素 | 文字/底色 | 对比度 | 判定 |
   |---|---|---|---|
   | 工具卡·失败卡正文 | `#CAC4D0` / `#49454F` | 5.41:1 | ✅ AA |
   | 轨迹页「完成」标签 | `#938F99` / `#141218` | 5.87:1 | ✅ AA |
   | 轨迹页行标题 | `#938F99` / `#141218` | 5.87:1 | ✅ AA |
   | 轨迹页轮次标签 | `#CAC4D0` / `#141218` | 10.91:1 | ✅ AAA |
   | 轨迹页详情正文 | `#CAC4D0` / `#141218` | 10.91:1 | ✅ AAA |
   | 轨迹页「请求头」 | `#E6E0E9` / `#141218` | 14.35:1 | ✅ AAA |
   | **用户气泡** | 白字 / `#4D6BFE` | **4.34:1** | ❌ 低于 AA 正文 4.5:1 |
19. **修复**：用户气泡底色 `#4D6BFE` → **`#4159E8`**（白字对比 5.51:1，仍是品牌蓝）。
   真机像素验证：全屏扫描到 `#4159E8` 35,890 像素（旧色已消失），浅色/深色下均正常。
20. 深色模式下整体渲染核对无误：品牌色、彩色轨迹条、筛选 chips、搜索框、分段控件均正常。

**Markdown 边界与首启引导（R32）**
21. **表格列对齐缺陷（真实渲染 bug）**：原实现每行**独立** `weight(1f)` 分列 → 行内单元格数不同时（LLM 常见的不齐表格）
   列会错位（少格的行独占整行宽度）。修复：按**全表最大列数补齐**后再分配权重；首行视为表头（加粗 + 下方分隔线）。
   **真机验证**：让模型原样输出含 `| Redis |`（仅一格）的表格 →
   表头 `Name | Type | Best use` 加粗带分隔线，`Redis` 只占第一列、Type/Best use 位置留空且**三列严格对齐**。
22. **解析边界审计**：未闭合代码围栏（流式截断常见）已正确处理（`while` 循环自然收尾，不会吞内容）；
   表格识别要求紧随分隔行，否则回落为段落（安全）。
23. **首启引导结构化**：由一整段文字改为 4 个要点 + 直达入口——
   🧠 引擎在手机里（bionic+node / 前台服务守护 / 崩溃自动重启 / Compose 非 WebView）、
   👉 左右滑切换视图（含轨迹页右滑返回）、👆 长按消息有操作（复制/引用/分叉/用量）、
   🔑 API Key 与权限（内置密钥（已移除） / 设置→Android 宿主 /「自启引擎」开关）；
   按钮：「开始使用」与「**去设置**」（直接打开设置页）。真机验证通过（重置 `welcomed` 标记后重新触发）。

**长内容处理与审批去重（R33）**
24. **工具参数/结果折叠**：长 JSON/长输出此前全文铺满，一张工具卡能占大半屏。现在默认折叠 6/8 行 + 「展开全部 / 收起」。
   判定用 Compose 的 `hasVisualOverflow`（按**换行后**的实际行数）——首版用"逻辑行数（数 `\n`）"判断，
   导致单行超长 JSON（逻辑 1 行、视觉 20 行）不被折叠，已修正。
   真机验证：工具结果与失败卡均出现「展开全部」链接 ✅
25. **代码块横向滚动 + 不换行**：`softWrap=false` + `horizontalScroll`。
   换行会破坏命令/代码结构，而编码场景里长命令行是常态（此前会被硬折行）。
26. **审批 UI 去重（真机发现的缺陷）**：同一审批曾同时出现**两套** UI——
   时间线的「等待审批」卡（来自 `approval/asked` 事件）与内联「需要你确认」卡。
   修复：存在待应答审批时隐藏时间线里未决的审批卡（`meta["decided"] != "1"`），决策后时间线卡作为历史保留。
   真机验证：`等待审批=0 个 · 需要你确认=1 个` ✅

**内存审计与工具卡可视化（R34）**
27. **内存与泄漏审计（实测数据）**：
   - `dumpsys meminfo`：**TOTAL PSS ≈ 120–126 MB**（Java 堆 14–21 MB / Native 堆 ≈ 23 MB）；
     `WebViews: 0`（再次证实非 WebView 套壳）、`Activities: 1`、`AppContexts: 6`
   - **切换压力测试**：连续切换会话 5 次 → 123 MB；累计 **15 次 → 123.6 MB（持平）**，Java 堆反而降到 14 MB（GC 回收）
     → **无会话切换泄漏**（验证第 1 轮的 `stateLock` + builder 重置是干净的）
28. **工具卡状态可视化**：状态从"灰色小字"改为**色胶囊**——失败=errorContainer 底 + error 色标题；
   运行中=primaryContainer 底 + primary 色标题；完成=中性 surface 底 + onSurface 标题（一眼可辨，不必读文字）。
   真机验证：中性态「完成」胶囊渲染正常 ✅；运行/失败态代码就位
   （**失败态本轮未能构造出失败工具卡完成视觉验证**：`read` 的 EACCES 被模型当正文输出、工具侧仍 `isError:false`，故未复现失败卡）。

**运行态可视化与嵌套列表（R35）**
29. **运行中工具卡**：状态胶囊加 **脉动点**（alpha 1→0.25，900ms Reverse）+ **实时耗时**
   （`运行中 · 35s`，事件 `time` 记入 `meta["startedAt"]`）。
   **真机验证**：提问工具卡显示 `● 运行中 · 35s` ✅
   过程中自查出缺陷：`startedAt` 只在 `chunkrow/tool-call-chunks` 调用点传入，
   `assistant/chunk` 的 `tool-call-delta` 路径漏传 → 计时不出现；两处调用点已统一。
30. **失败态配色真机验证（补上轮缺口）**：一次工具被中断产生 `isError:true` → 卡片呈现
   **红色标题 `⚙ 失败` + `errorContainer` 红底胶囊「失败」** ✅ （至此三态配色全部真机可见）
31. **嵌套列表 Tab 缩进**：缩进级别改为 `前导空白.replace("\t", "  ").length / 2`
   （原先 Tab 只算 1 字符 → Tab 缩进的嵌套项被压成 0 级、与父项平级）。代码层修正。

**手机原生能力：回合完成通知（R36）**
32. **背景审查结论**：Agent 在后台跑完时用户毫无感知——这是"手机独立运行"最关键的一处缺失
   （桌面端可盯着终端，手机用户一定会切走）。
33. **实现**：`running` 由 true→false 的边沿且 `activityResumed == false` 时，发一条频道 `dsh_turns`（"会话动态"）的通知：
   标题「会话已完成」/「已停止」+ 会话名，`AUTO_CANCEL`，点击回到 App（`FLAG_ACTIVITY_SINGLE_TOP`）。
   前台不打扰（界面内已有反馈）；尊重 Android 13+ 通知权限（未授权则不发）。
   **真机验证**（发消息 → 按 Home 退后台 → 等回合完成）：
   `dumpsys notification` 出现 `id=1001 channel=dsh_turns android.title=会话已完成`；
   通知栏截图显示「DshHost · 会话已完成 · 问候与开场白」✅（历史记录：当时产品名尚为 DshHost，后改名 DSHA）
34. 本轮还修掉三处编译期问题（通知权限自检方法、`CharSequence` 推断、
   **变量声明顺序**——通知块被误放在 `title` 声明之前），均由"改完即编译"的循环即时暴露。

**需要确认时的提醒 + 通知架构修正（R37）**
35. **需要用户确认/选择时的后台提醒**（id=1002，`IMPORTANCE_HIGH`）：
   Agent 会**一直阻塞**等待应答（实测曾卡到 148s 无人知晓），用户切走后必须有提醒。
   标题「需要你确认」/「需要你选择」+ 会话名；回前台自动取消（`onResume` → `cancel(1002)`）。
   **真机验证**：`onPause → resumed=false` → `pending event id=… resumed=false` → `notifyNeedsInput(allowed=true)`
   → `dumpsys` 出现 `id=1002 channel=dsh_turns android.title=需要你选择`；
   通知栏截图「DshHost · 需要你选择 · 会话 · 点击继续」（同上，历史记录）；`am start` 回前台后 id=1002 已取消 ✅
36. **通知架构修正（重要）**：通知原先挂在 Compose 的 `LaunchedEffect` 上——
   **App 退到后台后不再产生帧、重组停止，UI 侧的状态观察可能永不触发**，
   而"后台完成/需要确认"恰恰是通知唯一有意义的场景（上一轮完成通知能成功是时机侥幸）。
   现改为在 `lifecycleScope` 直接 `collect` Flow（不依赖 UI 帧）：标题缓存 / running 边沿 / pendingEvent 三条订阅。
37. **自查出并修复一个真实逻辑 bug**：`maybeNotifyTurnDone` 内部**又做了一次边沿判断**
   （`if (!was || running) return`），而调用方传 `running = true` → **每次都在第一行提前 return，完成通知从未发出**。
   改为纯发送函数（边沿判断只由 collector 负责）。
   **决定性验证**：`running=true(resumed=true)` → `onPause resumed=false` → `running=false(was=true) resumed=false`
   → `dumpsys` 出现 `id=1001 android.title=会话已完成` ✅
38. 附带验证：host 侧取消挂起瀑布时客户端正确清理（`waterfall cancelled by host` → `session/cancel 200`）。

**分享入口（R38，手机原生能力）**
39. **从任意 App「分享」文本 → 新建会话并预填**：
   - Manifest 注册 `ACTION_SEND`(text/plain) 与 `ACTION_PROCESS_TEXT`(text/plain)
   - 冷启动（`onCreate(intent)`）与热启动（`onNewIntent`）都处理；bootstrap 未完成时先暂存，会话就绪后再落
   - 落地方案：**新建会话**（"分享 → 新会话"的主流心智模型，不污染当前上下文）→ 切换到该会话 →
     把内容注入输入框（复用 `injectText`），并提示「已接收分享内容，可直接发送或补充说明」
   - **刻意不自动发送**：Agent 运行有成本，预填让用户能补充指令（"总结这个"之类）后再发
   **真机验证**：
   - 系统解析：`cmd package resolve-activity -a android.intent.action.SEND -t text/plain -p io.github.singalongdan.dsha`
     → `name=io.github.singalongdan.dsha.ComposeChatActivity` ✅（即出现在系统分享面板中）
   - 模拟分享：`am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '…'`
     → 日志 `share received 49 chars` → 会话列表新增「会话 4ceb9cc3」→ 输入框预填 `https://example.com/article 这…` ✅

**进程被杀后的现场恢复（R39）**
40. **发现真实缺陷**：`pickSession()` 会读 `last_session` 恢复上次会话，但**只有部分路径写入**——
    最常用的「抽屉切换会话」走的 `switchToSession()` 没有持久化 → 恢复的是过时会话。
    修复：`switchToSession()` 统一写 `prefsPut(id)`。
41. **页签持久化**：新增 `last_view`（0=对话 1=轨迹），所有切换入口（分段控件/左右滑/工具卡"查看轨迹"/轨迹详情）
    统一走 `setView(v)`；重启后回到上次所在页。
    实现要点：`currentView` 原是 Compose 局部 `remember` 状态，成员函数访问不到 →
    **提升为 Activity 字段 `viewTab`**（Compose 侧读 `viewTab.value`）。
42. **真机捕获并修复一处启动崩溃**（本轮自己引入）：
    `viewTab` 初值写在**字段初始化器**里调用 `getSharedPreferences` —— 那时 Activity 的 Context 尚未 attach →
    `Unable to instantiate activity: NullPointerException ... on a null object reference`，
    **App 完全无法启动**。改为默认 0 + `onCreate` 内恢复。
    （这正是"每项改动真机验证"的价值：纯代码审阅极易漏掉这类生命周期时序问题。）
43. **验证**：prefs 出现 `last_view=1` 与 `last_session=session-68ec05f8…`；
    `am force-stop` 后重启 → 轨迹页元素（搜索框）在、会话页输入框不在 → **回到上次页签** ✅

**会话列表状态一致性 + 测试环境限制（R40）**
44. **发现真实缺陷（状态互相覆盖）**：抽屉搜索直接写 `sessionsState`，而 `applySort()` 会把**全量列表**写回——
    于是**搜索过程中任何一次刷新/置顶/改排序都会静默清空搜索结果**（搜索框里关键词还在，列表却恢复全量）。
    修复：新增 `sessionQuery` 状态，搜索只记录关键词；**排序 + 置顶 + 搜索过滤统一在 `applySort()` 落地**。
    **静态证明**：`sessionsState.value =` 全文件仅剩 **1 处**（applySort 内）→ 展示列表有了单一写者。
    **真机验证（共享路径）**：抽屉排序行「按最近更新排序」→ 点击 → 变「按创建时间排序」且列表顺序随之改变，
    证明展示列表确由 `applySort()` 驱动 ✅
45. **测试环境限制（记录以备后续）**：adb 的 `input text` / `input keyboard text` / 逐字符 `input keyevent`
    都**无法输入到 Compose 的 TextField**（字段已聚焦、IME 已弹出、text 仍为空）；
    而 View 版 `android.widget.EditText`（如 API Key 对话框）可以输入。
    因此"在 Compose 输入框里打字"这类场景无法用 adb 自动化验证，只能用
    **静态单写者证明 + 共享路径的真机验证**替代（本轮即采用此法）。

**Markdown 渲染成本实测与缓存（R41）**
46. **实测解析成本（真机数据，非估算）**：
    `markdown parse: 1850 chars, 31 blocks, 3.2ms` / `2142 chars, 1 blocks, 2.6ms` → **≈1.5ms / KB**。
    结论：典型长消息（2–4KB）3–6ms，在 16ms 帧预算内可接受；流式路径已改纯文本渲染（R28）不再逐 chunk 解析。
47. **新增分块结果 LRU 缓存（48 条）**：`LazyColumn` 会销毁/重建离屏项，无缓存则**每次滑回都重新解析**；
    主题切换、会话切换同样会触发全量重组。缓存以文本为键，命中即跳过 `splitBlocks`。
48. **验证**：首次滚动渲染 → `parse: 2 / HIT: 0`（首次必然解析）；
    随后切换主题（强制全量重组）→ **`parse: 0 / HIT: 1`，日志 `markdown cache HIT: 1937 chars`**
    → 证明同一文本不再重复解析 ✅
49. 诊断日志降噪：解析/HIT 日志仅在文本 > 4000 字符时输出（避免正常会话刷屏）。

**双向分享 + 文件分享（R42）**
50. **分享出去**（与"分享进来"对称）：长按消息 → 操作单新增「分享到其它 App」→ 系统分享面板（`ACTION_SEND` text/plain + chooser）。
51. **文件分享进来**：Manifest 增加 `ACTION_SEND` + `*/*` 过滤；
    `EXTRA_STREAM` 的 URI 被复制到**工作区 `shared/`**（Agent 的工作目录内，可直接 read/glob 访问），
    并把相对路径预填进输入框（"我已把文件放到工作区：shared/xxx"）。
52. **补齐权限缺口（真机暴露）**：首次实测 `openInputStream failed for content://media/external/file/307` ——
    targetSdk 28 的应用读 MediaStore 需要 `READ_EXTERNAL_STORAGE`（真实分享的 FileProvider URI 自带读授权，
    但相册/文件管理器分享的 `content://` 需要该权限）。补上权限声明 + 收到文件分享时**运行时申请**，
    授权后继续复制（`onRequestPermissionsResult` → 重试）。
53. **验证**：
    - 未授权：`share file: content://…` → `openInputStream failed`（复现问题）
    - `pm grant` 后：`share file saved: /data/user/0/io.github.singalongdan.dsha/files/home/shared/dsh-share-test.txt (23 bytes)`，
      工作区 `ls` 可见该文件（23 字节，内容正确）
    - 截图：新会话 + 输入框预填「我已把文件放到工作区：shared/dsh-share-test.txt…」✅

**图片附件上限与降级压缩（R43）**
54. **审查发现真实风险**：图片附件**既无数量上限也无单张体积上限** —— `pendingImages` 无界增长，
    10 张照片 ≈ 10MB base64 + 十几 MB 的 POST（RPC 读超时仅 30s）→ 可能 OOM / 请求失败。
55. **修复**：`MAX_ATTACHMENTS = 4`；`MAX_ATTACHMENT_BYTES = 2MB`；
    压缩改为**逐级降质降尺寸**（2048/85 → 1600/80 → 1280/70 → 1024/60，取首个达标者），
    仍超限则明确拒绝并说明原因；附件计数写入提示（"图片已加入附件（n/4）"）；
    超限校验放在**打开选择器之前**（避免用户挑完才被拒）。
56. **验证（真机全流程）**：点"附加"→ 系统图片选择器（`PhotoPickerGetContentActivity`）→ 选图 →
    `DshNotif: attachment ready: 482KB @maxDim=2048`（首轮即达标）+ 附件轨出现缩略图与 ✕ 删除按钮；
    连续添加至 4 张后再选第 5 张 → **无新增 attachment ready 日志、附件轨恰为 4 张**（上限生效）✅

**轨迹详情补全与双向导航（R44）**
57. **审查发现两个问题**：
    - 轨迹行详情显示 `row.kind.name` —— **中文界面里冒出英文枚举**（TOOL/STEP/…），且**缺"状态"字段**
    - 导航只有单向：会话 → 轨迹（工具卡「查看轨迹」），轨迹 → 会话**无路可走**
58. **修复**：
    - 详情改为中文字段列表：**类型**（工具调用/助手消息/用户消息/推理/步骤/上下文压缩/错误/系统）、
      **状态**、位置（T·步骤）、耗时、**调用 ID**、正文详情
    - 新增 **「在会话中查看」**：按 `toolCallId`/`key` 定位会话节点 → 切回会话页 → 滚到该节点并**短暂高亮 1.6s**
      （与会话→轨迹形成闭环，对齐 web 的消息/轨迹互链）
59. **验证**：
    - 详情弹窗显示「类型=步骤 / 状态=完成 / 位置=T1 · 步骤 0 / 耗时=0ms」+ 两个按钮（关闭 / 在会话中查看）✅
    - 点「在会话中查看」→ 切回会话页（会话页元素在、轨迹页元素消失），页面正显示该工具调用结果 ✅
    - 附带复验：轨迹页空状态「暂无轨迹」、表格渲染（表头加粗 + 分隔线 + 列对齐）、轮尾用量行
60. `@Immutable` 覆盖复查：`TranscriptNode` / `TrajectoryRow` / `SessionItem` 已标注；
    其余传给 Composable 的模型（`TurnStat`、`FileCandidate`）字段全为基本类型 + String，Compose 自动判定为稳定，无需标注。

**Release 构建打通与产物验证（R45）**
61. **交付物缺口**：此前只有 debug APK 能出包 —— `assembleRelease` 被 lint 拦下：
    `Error: Google Play requires that apps target API level 33 or higher. [ExpiredTargetSdkVersion]`
62. **正确处理（而非绕过）**：该检查的前提是"上架 Google Play"，而本项目**刻意** targetSdk = 28
    （落在 `untrusted_app_27` 域以保留从数据目录 exec 的 SELinux 权限，是"引擎跑在机内"的前提），
    且**明确侧载、不上架**（设计边界）。故在 `app/build.gradle.kts` 的 `lint { }` 中
    `disable += "ExpiredTargetSdkVersion"` 并写明理由；`abortOnError = true` 保留，其余 lint 仍阻断 release。
63. **产物与端到端验证**：
    - `assembleRelease` → `BUILD SUCCESSFUL`；产物 `app-release.apk` **134 MB**（含引擎资源）
    - 签名有效：在**已卸载 debug 版**的设备上直接安装成功（签名不匹配会被系统拒绝）
    - **冷启动全流程**：卸载 debug → 装 release → 启动 → assets 解包 → `node pid=4516` → **CONNECTED**
      → 首次安装触发**首启引导**（4 要点 + 开始使用/去设置）→ 注入 `reply with exactly: release build works`
      → 会话标题变为该文本、5 条消息、1 轮 1 步、回复渲染 ✅
64. **顺带发现（测试方法）**：release 包不可调试，`run-as` 失效 → 读应用私有数据（prefs/log）
    需改走引擎侧接口（本次用会话列表 + 日志中的 token）。验证后已恢复 debug 版以便继续打磨。

**分享消费竞态 + 深色胶囊对比度复测（R46）**
65. **`pendingShare` 的 TOCTOU 修复**：`tryApplyPendingShare()` 会被**主线程**（收到分享）与 **bootstrap 线程**
    （会话就绪）同时调用，原先只用 `pendingShare.value = null` 去重 → 存在竞态窗口，**可能建出两个会话**。
    改为 `AtomicBoolean` 守卫（未就绪时释放守卫等下次触发）；实测分享功能正常（`share received 23 chars` → 新建/切换会话）。
66. **深色模式下新增状态胶囊对比度复测（实测像素）**：
    | 胶囊 | 文字 / 底色 | 对比度 | 判定 |
    |---|---|---|---|
    | 运行中 | `#D3E2FF` / `#2B3A5C` | **8.64:1**（实测：文字 1698px、底色 39987px） | ✅ AAA |
    | 完成（中性） | `#CAC4D0` / `#141218` | **10.91:1**（实测 11470px） | ✅ AAA |
    | 失败 | `#FFDAD6` / `#93000A` | 7.24:1（按 M3 深色调色板计算；本视图内无失败卡，未做像素实测） | ✅ AAA（计算值） |

**图片解码内存审计（R47，实测收益 8×）**
67. **审查发现两处真实问题**：
    - `AttachRail` 缩略图显示 56dp，却用 `BitmapFactory.decodeByteArray` **全尺寸解码**
      （2048² ARGB ≈ 16MB/张），且解码发生在 **Compose 组合线程**（`remember` 内）→ 掉帧 + 内存尖峰
    - 选图压缩路径**先解全尺寸再缩放**、且**不回收**临时位图
68. **修复**：缩略图改为 `produceState` + `Dispatchers.IO` **异步解码**，并用 `inJustDecodeBounds` + `inSampleSize` 降采样
    （实测 `thumb decode: 921x2048 → sample=4 (230x512, 460KB)`）；
    选图路径改为**解码期降采样**（避免 4× 峰值）+ 缩放副本与解码位图**显式 `recycle()`**。
69. **实测收益（同一操作序列，4 张附件）**：
    | | baseline PSS | 加 4 张后 | 增量 |
    |---|---|---|---|
    | 改前 | 107 MB | **190 MB** | **+83 MB** |
    | 改后 | 107 MB | **117 MB** | **+10 MB** |
    → 内存峰值降至约 **1/8**。（测试图为 921×2048 截图，`decodeSample=1`，收益主要来自显式回收；
    真实 4000×3000 照片还会额外受益于解码期降采样。）

**抽屉未验证功能复核 + 运行中指示刷新（R48）**
70. **重命名**（此前从未端到端验证）：抽屉菜单「重命名」→ 对话框打开且**预填当前标题**（View 版 EditText）；
    引擎侧 `session/rename` → `{title:"引擎改名验证",seq:4}` → **应用顶栏标题实时更新为「引擎改名验证」** ✅
    （标题链路：引擎 `session/title` 事件 → follow 流 → 控制器，无需手动刷新）
71. **运行中指示（发现陈旧问题并修复）**：抽屉的「运行中」来自 `session/list`，而列表此前只在
    手动刷新/建会话等时机更新 → 回合开始后抽屉仍显示旧状态，且**取消其它会话后角标不会消失**。
    修复：①`running` 状态变化（回合开始/结束）时自动刷新列表；
    ②`stopSession(sid)` 后延迟 1.2s 再刷新（等引擎状态落定）。
    **验证**：抽屉行显示「会话 a93ecd6d / 1 分钟前 · **运行中**」；对「引擎改名验证」执行停止运行后，
    该行角标**消失**（`session/cancel -> 200`），当前会话角标正常保留 ✅
72. **停止运行**：菜单项**条件显示**正确 —— 运行中会话 4 项（重命名/置顶/分叉/停止运行），未运行会话 3 项 ✅
73. **环境限制补充**：重命名对话框虽是 View 版 `EditText`，但本机 IME 的**组合提交**（点击候选/回车）在 adb 注入下
    不生效，文本停在组合态 → 通过引擎侧改名 + 应用显示刷新完成验证。

**README 重写 + 边界与手势复核（R49）**
74. **README 全面重写**（`host-app/README.md`）：从 27 行的阶段记录改为面向使用者的完整文档——
    产品定位（同一后端的对等端 / 非 WebView）· 能力一览（按"实时会话/轨迹/消息与内容/会话管理/手机原生集成/设置"分组）·
    安装与首次运行 · 构建（debug+release）· **targetSdk 28 的取舍与侧载边界** · 引擎沙箱说明 · 架构与协议要点 ·
    **验证方式与实测结论**（帧健康度/内存无泄漏/附件内存/对比度）· **测试环境限制**（adb 无法输入 Compose 输入框、
    release 不可调试）· Licenses。
75. **0 会话边界**：已由第 19 轮"卸载 → 全新安装 release"覆盖 —— 空会话列表下应用**自动建会话**并连上，
    随后完成一次真实对话；本轮据此在 README 中固化安装路径。
76. **手势冲突复核**：斜向滑动（dx=-120 / dy=-700，以纵向为主）**未误触发视图切换** ✅，
    证明 `detectHorizontalDragGestures` 的横向 slop 判定正确（不会把纵向滚动误判为切页）。

**长文本折行复核 + 统计行可读性（R50）**
77. **超长无空格文本**：让模型输出 260 字符的单一 `qqq…` token（无空格、无换行）→ 渲染**正确折行**
    （7 行 + 短尾行，无横向溢出），其后 `DONE` 另起一行 ✅ —— 该项经实测**确认无缺陷**（非"推测没问题"）。
78. **统计行可读性（真实问题）**：此前为压成单行把字号设为 **8sp**，低于 Material 最小字号（labelSmall 11sp）
    与实际可读下限。修复：**提升到 10sp**，并新增**点击统计行 → 完整统计面板**
    （轮次/步骤 · LLM 用时 · 工具用时 · 首 token · 输出速度 · 缓存命中 ｜ 未缓存输入 / 缓存读取 / 缓存写入 / 输出），
    行尾加 **ⓘ** 提示可点。
79. **验证**：统计行以 10sp 渲染（bounds 高 66px）且带 ⓘ；点击后弹出「本次会话统计」，
    实测数据完整（缓存命中 97.6% · 未缓存输入 539 · 缓存读取 21632 · 缓存写入 0 · 输出 6803）✅

**插件开关可行性探测 + 底部元数据合并（R51）**
80. **插件开关：探测结论为"不适用"（避免做死功能）**：
    - 运行时逐个试探 `plugins/inventory`、`plugin/enable|disable`、`cordis/run|retract|inspect|list` 等 9 个候选方法 → **全部不存在**
    - 引擎侧查证：`dsh plugin` 子命令的说明是"**forward the remaining arguments to pnpm in the profile directory**"
      （add/remove/why …）→ 插件管理本质是**包管理（pnpm）**，不是运行时开关
    - web 的 `dsh-client-ui-cordis` 属**客户端插件体系**，与"启用/停用引擎插件"无关
    - 结论：移动端保持**只读插件清单**（149 注册 / 121 启用）是正确范围，符合"不 fork 引擎、不改协议"
81. **底部元数据合并（版式优化）**：上下文占用胶囊此前单独占一行，与统计行形成两行薄元数据 →
    合并为**一行**：`[上下文 n%] N 轮 · M 步 ｜ LLM … · 缓存 … ｜ 输入 … ⓘ`，省约一行高度且信息更聚合。
    **验证**：截图确认为单行渲染（胶囊在左、统计随后、尾部 ⓘ），点击胶囊开上下文明细、点击行开完整统计。

**大列表压测 + 引擎存储布局（R52）**
82. **构造压测数据**：用引擎接口 `session/create` 连建 **50 个会话**（1103 ms，全部 ok），会话总数 4 → 54；
    `session/list` 返回 **66 KB / 62 ms**。
83. **压测结果（抽屉快速滚动 6 次）**：
    | 指标 | 54 会话（本轮） | 轻载流式（R28 基线） |
    |---|---|---|
    | 掉帧率 | **1.63%**（6/369） | 0.30% |
    | 50 / 90 / 95 分位 | 7 / 10 / 10 ms | 7 / 8 / 8 ms |
    → 仍在流畅区间（业界以 5% 为"流畅"阈值），**大列表无需特殊优化**；若将来会话达到数百量级，
    可考虑分页（当前引擎 `session/list` 全量返回）。
84. **引擎存储布局（本轮实测得到，便于运维/排查）**：
    `DSH_HOME/.dsh/sessions/<workspace-slug>/session-<id>/session.jsonl.zstd`
    —— 每会话一个**独立目录 + zstd 压缩日志**；空会话日志约 315 字节，有内容的明显更大。
85. **压测数据清理**：压测污染了用户列表（新增 50 个空会话，而引擎**无删除/归档 RPC**，见 R3 探测）。
    按"日志 ≤ 400 字节 = 空会话"这一安全判据（不读取内容即可判定）删除 **52 个空会话**，保留 2 个有内容的，
    重启引擎重新索引后列表恢复干净（截图：2 个会话）。
86. **附带验证**：引擎重启期间应用**再次自愈** —— `re-auth attempt 1 failed; retrying…` → `re-auth + follow OK (attempt 2)`
    → `snapshot cursor=6841 records=45`（自动重新 follow，无需用户操作）✅

**冷启动耗时测量与优化（R53）**
87. **基线测量**（force-stop 后冷启，含引擎被连带杀掉后重启）：
    `am start -W TotalTime 681ms`；`bootstrap start → snapshot`（内容可见）**8.05 s**。
88. **阶段计时定位瓶颈**（新增 `bootstrap.<phase>` 打点）：
    ```
    bootstrap.engine   +1506ms   ← 引擎重启等待（force-stop 连带杀子进程，合理）
    bootstrap.auth     +4852ms   ← 鉴权 3.35s（瓶颈）
    bootstrap.sessions +4954ms   ← 仅 46ms
    authLoop#0 token=1ms auth=828ms ok=false   ← 首次返回但没拿到 cookie
    authLoop#1 token=2ms auth=14ms  ok=true    ← 就绪后只要 14ms
    ```
89. **两处修复**：
    - `authLoop` 重试间隔 **2500ms → 400ms**（就绪后鉴权仅 14ms，2.5s 纯属白等）
    - `waitForToken()` 由**全量 `readText()` + 全文件正则**改为**只读文件尾部 16KB**
      （日志是追加型、会持续增长；token 总在最后一行）
90. **优化后实测**：`bootstrap.auth` **4852ms → 2754ms**（快 2.1s）；
    `bootstrap start → 内容可见` **8.05s → 5.89s**（快 2.2s）。其中 1.5s 仍是引擎重启成本，
    引擎存活时的重进场景只需 ~600ms 恢复界面（`am start -W TotalTime 42ms`，应用仍在内存）。
91. **顺带实测**：`MarkdownText` 解析 10 681 字符 / 50 块 = **18.7–19.0ms**（与 R41 的 ≈1.5ms/KB 估算吻合）；
    因已有 LRU 缓存，同一文本每进程只付一次该成本。
92. 剩余已知成本：`switch → snapshot` 约 3.1s（WS 建连 + 快照传输 45 条记录），后续可评估增量拉取。

**WS 建连竞争修复（R54，冷启动 8.05s → 2.95s）**
93. **先分离引擎侧与应用侧**（用裸 WS 探针直连引擎计时）：
    ```
    auth=33ms  wsUpgrade=7ms  open->firstFrame=52ms  open->snapshot=54ms
    snapshotBytes=231,408  frames=1
    ```
    → **引擎侧只要 54ms**（含 231 KB 快照），3.1s 全在应用侧。
94. **根因（用时间线坐实）**：
    ```
    14:20:43.379 opened $events          ← 首个流创建 socket（握手尚未完成）
    14:20:43.392 ws open                 ← 13ms 后握手完成
    14:20:46.384 opened session/follow   ← 2.99s 后才开成（吃满 3s backoff 重试）
    ```
    `DshStreamClient.ensureConnected()` 在"连接中"状态**直接返回 null** →
    紧随其后的 follow/control 流 open 失败 → 必须等 `backoff`（初始 3000ms）重试。
95. **修复**：已连接**或正在连接**都复用同一 socket。OkHttp 的 `RealWebSocket.send()` 会把握手完成前
    发出的帧**排队并在连接建立后补发**，因此这是安全的（首次调用本来就是这个行为）。
96. **验证**：三条流在**同一毫秒**打开（`opened $events / session/follow / session/control`），
    握手在 open 帧之后 12ms 完成，`snapshot` 再 102ms 到达 →
    **`bootstrap start → 内容可见`：8.05s（R53 前）→ 5.89s（R53 后）→ 2.95s（本轮）**，
    相比最初快 **2.7 倍**；其中 1.5s 仍是引擎重启等待（引擎存活时约 1.4s 即可见内容）。

**长文本解析移出组合线程（R55）**
97. **问题**：`MarkdownText` 在**组合期**同步解析，实测 10.7 KB 消息解析 **18.7 ms**（> 16 ms 帧预算）
    → 进入含长消息的会话时会掉一帧。
98. **方案（预热而非异步占位）**：新增 `warmMarkdownCache(texts)`，在**快照应用后于 `Dispatchers.Default`
    后台把 ≥2 KB 的文本先解析好并写入 LRU 缓存**；组合时直接命中缓存，不占帧时间。
    选择预热而非"异步解析 + 占位"，是为了**不引入内容闪烁**（Markdown 与纯文本混排会跳）。
99. **验证**：
    ```
    14:23:38.487  markdown warm: 10681 chars, 50 blocks, 42.5ms (off-main)   ← 后台解析
    14:23:38.506  markdown cache HIT: 10681 chars                            ← 组合期命中缓存
    ```
    即长消息解析**不再发生在组合线程**。冷启动帧统计：359 帧 / 掉帧 6（**1.67%**，legacy 2.23%），
    含首次组合与视图构建的启动突发，仍在流畅区间。
100. 说明：冷启动剩余约 1.5 s 为**引擎进程启动本身**（force-stop 会连带杀掉引擎子进程），
    应用侧无从缩短；前台服务常驻时（系统回收应用的常见场景）该成本不存在。

**流式发布节流与帧率复核（R56）**
101. **复核发现帧率与内容体量强相关**：在含 10.7 KB 长消息的会话里做流式，掉帧达 **5.53%**
    （50/90/95 分位 9/16/19 ms），远高于 R28 记录的 0.30% —— 后者是在更轻的会话内容下测得的，
    说明**长文本布局**是主要成本（每帧重新布局整段增长中的文本 + 列表重测量）。
102. **优化：流式发布节流**。`assistant/chunk` 等增量事件的发布合并为**每 ~100ms 一次**
    （内容仍在 builder 中累积，下次发布带出；定稿事件 `assistant/message`/`turn/end` 不节流，
    保证最终内容一定落地）。
103. **实测**：重内容会话 5.53% → **4.98%**（小幅改善，说明 chunk 频率不是主因）。
104. **对照实验**（同样操作、同样时长，仅换会话内容）：
    | 会话 | 掉帧 |
    |---|---|
    | 含 10.7 KB 长消息 | 4.98% |
    | 轻量会话 | **3.59%** |
    → 确认长文本布局贡献约 1.4 个百分点；当前水平（3.6–5%）仍在业界 5% 的"流畅"阈值内。

**品牌图标 + 通知状态真实值（R57）**
105. **品牌图标体系**（此前用系统默认 `@android:drawable/sym_def_app_icon`）：
    - `drawable/ic_launcher_foreground.xml`：8 角星（呼应 App 内 Hero 的 ✳️），圆头描边
    - `mipmap-anydpi-v26/ic_launcher.xml`：自适应图标（品牌蓝底 `#4159E8` + 前景 + **monochrome 层**）
    - `mipmap/ic_launcher.xml`：API < 26 回退（layer-list）
    - `drawable/ic_stat_dsh.xml`：**通知单色小图标**，替换三处系统图标（FGS / 完成 / 需要确认）
    **验证**：APK 资源表含 `color/ic_launcher_background`、`drawable/ic_launcher_foreground`、`drawable/ic_launcher_legacy`；
    桌面图标已变为品牌 8 角星；通知栏使用**本包资源**（`icon=Icon(pkg=io.github.singalongdan.dsha id=0x7f07005f)`，
    此前是 `pkg=android` 系统图标），截图显示蓝底白星 ✅
106. **发现真实缺陷（比图标更重要）**：设置页「前台通知」只检查**运行时权限**，显示"已授权"，
    但该安装实际 **POST_NOTIFICATIONS 未授予**（多次卸载/重装所致）→
    `dumpsys` 显示 `AppSettings: io.github.singalongdan.dsha importance=NONE` → **所有通知静默消失**
    （回合完成提醒、需要确认提醒全部失效，而界面还显示"已授权"= 误报）。
107. **修复**：通知行改查**有效状态**：
    `areNotificationsEnabled()` + 运行时权限 → 三态显示「未授权（点击授权）／**已被系统关闭（点击去开启）**／已开启」，
    点击分别触发权限申请或跳转系统通知设置（`ACTION_APP_NOTIFICATION_SETTINGS`）。
    实测：该行正确显示「未授权（点击授权）」；`pm grant` 后通知立即恢复（`id=1 … FOREGROUND_SERVICE`）✅
108. 说明：targetSdk 28 的应用在 Android 13+ 请求 `POST_NOTIFICATIONS` 时系统不弹框（新权限对旧目标静默处理），
    因此该行在"未授权"时更适合引导用户去系统设置，而非依赖运行时弹框。

**设置项"假开关"清理（R58）**
109. **审查发现两个"点了没用"的设置行**（成熟度问题：用户以为在控制 App，实际不生效）：
    - **语言**：只写引擎设置 `locale.preference`（影响引擎/Web），**App 界面不变** →
      改标为 **「语言（引擎/Web）」**，明确作用域，避免误导（App 界面语言为中文，未做 i18n）
    - **字号**：只写 `ui-theme.fontSize`，而 App 渲染用的是硬编码 15 → **做成真的**
110. **字号落地**：新增 `SettingsProvider.setFontSize(px)` —— 同时（a）写本地偏好 `font_size`、
    （b）写引擎设置（与 Web 端一致）、（c）**立即作用于 App 消息正文**
    （`ChatScreen(messageFontSize=…)` → `NodeView` → 用户/助手气泡 → 流式纯文本与 Markdown 渲染，
    行高按字号等比换算），设置行文案补注「（消息正文）」。
111. **验证**：设置页显示「语言（引擎/Web）」与「字号 / 14px（消息正文）」；
    选 17px 后**消息正文立即变大**（截图对比明显）；验证后已恢复默认 14px。

**嵌套列表实测 + release 产物复核（R59）**
112. **嵌套列表缩进（实测，兑现 R35 的代码修正）**：让模型原样输出三级嵌套列表 →
    渲染为 `• parent A` / `  • child A1` / `    • grandchild A1a` / `  • child A2` / `• parent B`，
    每级缩进 16dp、层次清晰 ✅（含 Tab 归一化为 2 空格的处理）
113. **release 产物复核（`aapt2 dump badging/xmltree`）**：
    - `package io.github.singalongdan.dsha versionCode=2 versionName=0.2.0`，体积 **134 MB**
    - `application-label: 'DshHost'`（历史记录；现已改名 **DSHA**，见 manifest 与 strings）
    - `minSdkVersion 24 / targetSdkVersion 28` ← 刻意保持（engine exec 前提）
    - 权限齐全：FOREGROUND_SERVICE(_DATA_SYNC) / INTERNET / POST_NOTIFICATIONS /
      RECEIVE_BOOT_COMPLETED / **READ_EXTERNAL_STORAGE**（R42 补的文件分享读权限）
    - 图标：`application-icon-*` 指向**品牌自适应图标**（不再是系统 drawable）
    - 分享入口在包内：`ACTION_SEND` ×2（`text/plain` + `*/*`）与 `PROCESS_TEXT` ✅

**release 全新安装端到端 + 零会话一致性修复（R60）**
114. **release 全新安装验证**（卸载 → 装 `app-release.apk` → 冷启动）：
    `install Success` · `am start -W TotalTime 270ms` · 70s 内引擎解包并运行（`node pid=10074`）·
    **CONNECTED** · 首启引导出现 · Hero 空会话页正常。
115. **发现真实缺陷（全新安装必现）**：`pickSession()` 在"一个会话都没有"时会新建会话，但**没有刷新列表** →
    抽屉显示 **「0 个会话」+「还没有会话」空状态**，而同一屏顶栏其实已在跟随刚建好的会话（自相矛盾）。
    修复：新建后立即 `refreshSessions()`。
116. **修复验证**（重新出包 → 全新安装）：抽屉显示 **「1 个会话」+ 会话行「会话 073d40ef · 刚刚」** ✅
117. 说明：本轮重装清掉了应用数据（引擎前缀、偏好、会话均为测试数据），
    引擎重新走了一遍"首次解包"路径（再次覆盖全新安装场景）。

**会话持久化缺陷 + Root 自检（R61）**
118. **发现真实缺陷（连续三次启动得到三个会话）**：bootstrap 里调用的是**控制器的**
    `controller.switchSession(sid)`，而 Activity 自己的 `switchSession()` 才会 `prefsPut` →
    **`last_session` 从未在启动路径写入** → 下次启动读不到上次会话，当列表里没有"非空会话"时
    **每次启动都新建一个**（实测 `073d40ef → 23807afe → c0b3046c`）。
    修复：bootstrap 在切换前补 `prefsPut(sid)`。
    **验证**：连续两次冷启动均为 `session-5c33adc0…`（不再新建）✅
119. **Root 模式自检提示（新增）**：本机 shell 工具依赖 root 包装器，Root 模式关闭时 bash 类工具会失败
    而用户无从得知原因（重装后该标记会丢失）。新增 `maybeHintRootMode()`：
    检测常见 su 路径 + `root_mode` 标记，满足"有 su 但未开启"时给**一次性**提示
    （文案指向 设置 → Android 宿主），并记录自检日志。
120. **自检实测暴露一个环境事实**：设备上 `/system/bin/su` **确实存在**（`which su` → `/system/bin/su`），
    但**应用看不到它**（`File.exists()` = false）——KernelSU 对**未授权**的应用隐藏 su。
    因此该提示在当前状态下**正确地未触发**（此时开启 Root 模式也无用）；
    用户需先在 KernelSU 管理器中授权本应用，su 才会对应用可见。
    这解释了 R1 早期"bash 报 `/system/bin/su` not found"与后续"经 su 提权成功"之间的差异。

**首启引导与 README 回灌（R62）**
121. **首启引导新增第 5 点**「🛠 让工具能执行命令」：把本会话踩到的坑写进用户**第一次就会看到**的地方 ——
    Android 无 bubblewrap/沙箱 → 默认权限下 shell 类工具被拒 → 到 设置 → 通用 设为 `danger-full-access`；
    若本机已 root，再开 设置 → Android 宿主 的「Root 模式」。
    **验证**（`pm clear` 后冷启动）：引导完整显示 5 点，末点为新增项 ✅
122. **README 回灌**：
    - 能力清单补「**通知真实状态**（未授权/被系统关闭/已开启 + 跳系统设置）」「**品牌图标**（自适应 + monochrome + 通知单色）」
      「**可调字号**（作用于消息正文）」，以及"现场恢复"
    - 实测结论更新为最新数据：冷启动 **8.05 s → 2.95 s**（三处修复）· 54 会话掉帧 1.63% ·
      附件内存 83→10 MB · 长文本 10.7 KB ≈ 18.7 ms 已移出组合线程 · 深色对比度实测值
    - 测试环境限制新增 **KernelSU 对未授权应用隐藏 `su`**（避免后来者把"检测不到 su"误判为代码 bug）
123. 附带再次覆盖：`pm clear` → 冷启动 → 引擎重新解包（node pid 11172）→ CONNECTED → 自动建会话 → 引导出现。

**LazyColumn 重复 key 崩溃隐患（R63）**
124. **代码审查发现潜在崩溃**：`ChatScreen` / `TrajectoryScreen` 都用 `itemsIndexed(..., key = { it.key })`
    （正确做法），但**节点 key 可能重复** —— `chunkrow/text-chunks` 与 `chunkrow/reasoning-chunks`
    用的是**完全相同的 key 公式** `"$turn:$step:$index"`，`assistant/chunk` 的 `text-delta` 与 `reasoning-delta`
    也共用同一 `base`。**同一 step 内两者 index 相同时即产生两个 key 完全相同的节点** →
    Compose 会抛 `IllegalArgumentException: Key … was already used` → **直接崩溃**（属"平时不发作、一发作就闪退"的类型）。
125. **修复（根因）**：`mutateLastAssistant` / `mutateLastReasoning` 创建节点时给 key 加**类型前缀**
    （`a:` / `r:`），turn/step 解析仍用原始 base，查找与创建保持一致。
126. **修复（兜底）**：发布路径统一经 `dedupeKeys()` —— 对任何来源的重复 key 追加序号并 `Log.e`
    记录（`duplicate node keys fixed: N (would crash LazyColumn)`），把潜在崩溃降级为可观测的降级行为。
127. **验证**：安装后跑一个**同时含推理与正文**的回合（正是可能撞 key 的场景）：
    无 `duplicate node keys` 日志、无 `Key was already used`、无 FATAL，应用存活；
    渲染完整 —— 上下文注入披露行（折叠）+ 🧠 思考行 + 正文 + 反馈按钮 + 轮尾用量行 +
    合并后的底部元数据行 `[上下文 1%] 1 轮 · 1 步 ｜ LLM 0ms · 工具 0ms · 缓存 96% ｜ 输入 8.03… ⓘ` ✅

**轨迹页 key 兜底 + 轨迹页回归（R64）**
128. **同源风险排查**：轨迹页同样用 `itemsIndexed(filtered, key = { it.key })`。其 key 为 `"$type:$seq"`，
    正常唯一；但事件缺 `seq` 时 `optInt("seq", -1)` 回落成 **-1**，同类型多行即产生重复 key → 同样会崩。
129. **修复（与消息列表对称的兜底）**：类级 `trajectoryKeys: HashSet<String>` 在 `addRow` 中 O(1) 去重，
    重复则追加序号并 `Log.e`（`duplicate trajectory key … → … (would crash LazyColumn)`）；
    切换会话清空轨迹时同步清空该集合。
130. **验证**：安装后左滑进入轨迹页 —— 6 行轨迹完整渲染（T1 第 1 轮 / Step 1 / 用户消息 / 上下文注入 /
    请求头 / 助手消息），搜索框与类型筛选（全部·工具·消息·推理·失败）正常，
    右侧**彩色操作轨迹条**（类型着色 + 视口指示）正常，无崩溃 ✅

**目标①②的真机验证收口（R65）**
131. **②手势三向（逐条实测）**：
    | 手势 | 结果 |
    |---|---|
    | 轨迹页**右滑** | ✅ 回到会话页 |
    | 会话页**右滑** | ✅ 抽屉打开（判定到「工作区」区域） |
    | 会话页**左滑** | ✅ 进入轨迹页（R37 已验证） |
132. **①会话页右缘小点导航（实测，构造 3 轮会话）**：
    - 右缘显示 **3 个小点**，**当前轮高亮为品牌蓝**、其余灰色（web 式导航条）
    - **点第 1 个点** → 视图跳到**第 1 轮**，且高亮同步跟随（第 1 点变蓝）
    - 跳转后自动退出跟随、**出现「回到底部」浮钮**（符合设计）
    - 底部合并元数据行同步：`[上下文 2%] 3 轮 · 3 步 ｜ LLM 0ms · 工具 0ms · 缓存 97% ｜ 输入 24.3… ⓘ`
133. **①彩色操作轨迹条在轨迹页**：R64 截图已确认（右侧类型着色轨迹条 + 视口指示），会话页**只保留小点**
    —— 与用户最初的要求（"彩色操作轨迹条应该在轨迹页面…会话页面的右边应该是那种小点，像 dsh web 一样"）完全一致。
134. **附带**：桌面图标独立确认 —— 启动器第 4 个图标为**品牌 8 角星**（R30 图形资源的端到端确认）。

**验收盘点（R66，39 轮打磨收口）**

**① 彩色操作轨迹条归轨迹页 + 会话页 web 式小点**
| 项 | 证据 |
|---|---|
| 轨迹页右侧**彩色操作轨迹条**（类型着色 + 视口指示） | R64 截图：6 行轨迹 + 右侧类型色条 |
| 会话页右缘改为**小点导航**（不再是彩色条） | R65 截图：3 轮会话 → 3 个小点，当前轮品牌蓝高亮 |
| 小点**可点跳转** | R65：点第 1 点 → 跳到第 1 轮，高亮同步，出现「回到底部」浮钮 |

**② 手势导航三向**
| 手势 | 证据 |
|---|---|
| 会话页**左滑** → 轨迹页 | R37 实测 ✅ |
| 轨迹页**右滑** → 会话页 | R65 实测 ✅ |
| 会话页**右滑** → 抽屉 | R65 实测 ✅（判定到「工作区」区域） |
| 纵向滚动不误触 | R49：斜滑 dx=-120/dy=-700 未触发切页 ✅ |

**③ "工作→审查→再工作"循环（39 轮，每轮真机验证）**
- **代码层面**：架构（单一写者、状态锁、原子守卫、发布路径统一去重）、健壮性（闪退修复、边界与竞态、
  WS 建连竞争、会话持久化、重复 key 崩溃隐患）、性能（冷启动 8.05s→2.95s、帧率、解析缓存预热、发布节流）、
  泄漏（15 次会话切换内存持平）
- **操作层面**：触控（手势 slop、长按操作单、点按热区）、反馈（shimmer/脉动/计时/状态胶囊/空状态/错误态可恢复）、
  状态（运行中角标自动刷新、页签与现场恢复、前后台通知）
- **操作逻辑层面**：信息架构（会话/轨迹分工、抽屉分组排序搜索置顶分叉）、导航（双向跳转闭环、返回路径一致）、
  一致性（中文化标签、条件显示、假开关清理、术语统一、无障碍语义）

**最终状态**
- 产物：`app-debug.apk` 152MB / `app-release.apk` 134MB（**已签名**，全新安装端到端验证通过）
- 规模：19 个 Kotlin 文件 / 约 7 600 行；设计文档 600+ 行、134 条带证据的改动记录
- 稳定性：快速手势压测（60 次操作）无崩溃无 ANR；release 全新安装冷启动 → 引擎解包 → CONNECTED → 真实对话往返
- 已知残留（非阻塞）：引擎端口仍固定 3080（单机单实例无冲突，改为可配置需触及服务命令/API/WS 三处，收益低故不做）；
  语言设置仅作用于引擎/Web（App 界面为中文，已在设置页标注作用域）

### 引擎级修复（本阶段发现并解决）
- **bash 工具全失败根因**：引擎 `workspace-write` 模式需要 bubblewrap/landlock 沙箱后端，Android 没有 → SandboxUnavailableError。
- **修复**：`settings/mutate` 将 `permission.defaultPreset` 设为 `danger-full-access`（新会话默认）+ `commands/execute "/permission danger-full-access"`（当前会话切换）——已实测成功（permission projection 更新、queue 消费）。
- **发现**：`sessionStats` 投影确实在 control 流（`projection key=sessionStats {turns:7,...}`），此前审计与我的推断已收敛。

### 阶段 6：打磨与发布
1. markdown 渲染器补全（表格/引用/嵌套列表/代码高亮）
2. 主题（三态）+ 字号 + 动效细节
3. 错误态全覆盖（模型不可用、附件失败、引擎重启中）
4. 性能：长会话（200 轮）帧率、内存、泄漏
5. 发布：signing、AGPL 公告、README、版本页

---

## 12. 风险与规避（设计期就避免）

### 12.1 WS 流 vs 轮询的正确姿势
- ✅ 设计：follow 流是唯一权威（snapshot → gap-free event 帧）；page 只做「初次全量回补」「加载更早历史」「跳转」「帧丢失半窗回补」。
- ⚠️ 不要让 page 和 follow 混用增量（会重复/乱序）：**统一走 Timeline.append(events)，事件靠 seq 去重**（我们已踩过 page 增量两次追加的坑，方案已在中有 seenSeqs + chunk 行计数）。
- ⚠️ follow 帧有 `surfaceOp`（append / replace{start,end}）：replace 帧到达时必须**替换对应表面区间**而不是追加——不处理这个，压缩（compaction）后的会话渲染会错乱（web 的 Timeline 有专门的 replacement shadow，我们照搬该语义）。

### 12.2 引擎重启/换 token
- 引擎崩溃 → 服务重启 → 新 token。
- 设计：Stream/HTTP 层统一"鉴权失效"信号 → EngineController.reconnect() → 重新 authenticate（读日志最新 token）→ 重开 follow → Timeline 用 seq 水位对账（水位以上已展示的不重复渲染）。

### 12.3 审批（approval）交互
- web 走 `$events` waterfall：`approval/request` 帧 → 显示审批卡（同意/拒绝/参见详情）→ `POST /api/$events/result` 回 `{outcome:{kind:"result"|"rejected"}}`。
- **本 App 可行**：`Open $events 流` + 结果回执全程已验证（http + ws 都在同一引擎）。审批卡渲染 + `$events/result` 回执 → 阶段 5 内完成，无需 web 中转。
- 兜底（$events 不可用）：审批只读展示 + 提示；`/permission` 命令经 `commands/execute` 修改权限模式。

### 12.4 图片体积与性能
- 相册图可能 20MB+：发送前统一压缩（最大 2048px、JPEG/PNG、quality 85）；传 base64 会有 ~33% 膨胀，控制在 imageLimits（maxImageBytes 5MB）内。
- 渲染用 attachment RPC 的 base64 → 缓存到内存 LRU + 磁盘 jpeg。

### 12.5 长会话内存
- 会话 200 轮、数千事件：Timeline 只保留"显示节点"（事件折叠后丢弃原始 chunk 事件细节）；已完成轮次按 compact 模式丢弃中间节点；节点截断（tool 参数 2 万字符上限，与 web 一致）。
- 惰性分页：默认只加载最近 50 条消息窗口，向上翻页。

### 12.6 手势冲突
- 左滑抽屉 / 右滑翻页 / 列表滚动 / LazyColumn 内水平滑动的冲突：
- 设计：抽屉只在屏幕左边缘 24dp 触发（androidx DrawerLayout 默认）；轨迹右滑只在会话/轨迹页级生效；输入卡内的补全列表滚动不影响页级手势。

### 12.7 上游 API 敏感面（已全部验证过再写代码）
- `session/page` 的 throughSeq 必须 ≤ cursor（用 resolveCursor 或 follow 水位）；
- 零参 RPC args 必须 `{}`；
- `settings` 写必须带 expectedRevision（并发冲突一律拒绝）；
- `credentials` 值只进不出（UI 永远显示"已配置/未配置"）。

### 12.8 目标：先做透核心，再求全
用户说"不要盲人摸象"——正确顺序：**先把「会话视图 + 实时流 + 输入 + 统计行 + 侧栏 + 设置六栏」做成一个完整闭环**（阶段 1–2 + 4 的先驱项），轨迹（3）、输入高级功能（5）紧随其后。每阶段结束都真机验证截图，迭代不返工。

---

## 附：源码证据索引（便于复查）

| 结论 | 出处 |
|---|---|
| event 全集 | `dsh-session/lib/index.js` KNOWN_SESSION_EVENT_TYPES（52 个） |
| sessionStats 投影 | `dsh-session-stats/lib/index.js`（turns/steps/llmMs/toolMs/ttftMs/ttftSteps/decodeMs/decodeTokens） |
| 统计行 8 项文案 | `dsh-client-ui-chat/lib/client.js` zh 字典 stats.* |
| 输入栏结构（权限/模型/+/附件/停止/发送） | `dsh-client-ui-conversation/lib/client.js` InputBar（tools 行 + trailing 行） |
| 队列/转向语义 | `dsh-api-session-controller/lib/types/commands.js` updateQueue/cancel |
| WS 帧协议 | `dsh-api-gateway/lib/types/stream-protocol.js` + `lib/index.js`（open/item/end/error/cancel + 2s 心跳） |
| turn/step 折叠 | `dsh-client-ui-conversation/lib/client.js` LocationTimeline（appendBoundary/resolve） |
| 侧栏/工作区 | `dsh-client-ui-sidebar` + `dsh-client-ui-workspace`（groupBy/orderBy/search/archive/fork 菜单） |
| 设置四栏目 | `dsh-client-ui-settings*`（general/models/plugins/agent-presets） |
| 附件上传 | `dsh-attachment/lib/index.js`（base64 → AttachmentId；prompt content image.attachment；sha256 内容寻址） |
| 模型目录/effort | `dsh-api-remotes/lib/client.js` modelCatalog schema（groups[].models[].reasoning.efforts[]） |
| 图片限制 | projections.imageLimits（maxImageBytes 5MB，maxImagesPerMessage 20） |
| web profile 实际组合 | `dsh-web-app/cordis.patch.yml`（session-stats/turn-outline 挂载声明 + 38 个 browser 插件 roster） |

## 附二：完整调研产物（工作区，可随时复查）

| 文件 | 内容 |
|---|---|
| `research/report-chat.md` | 对话流全量（节点类型/气泡/思考行/压缩/重试/错误卡/轮次尾巴/导航轨/统计/用量面板/详情面板 + chat 116 键 locale 全表） |
| `research/report-conversation-composer.md` | Composer 全量（编辑器/@芯片/提交状态机/QueueDock/steer/工具栏/权限/ContextMeter/TodoPanel/Hero/快捷键/触发菜单） |
| `research/report-trajectory.md` | 轨迹全量（表格/时间线/详情面板 tabs/搜索/虚拟化 + 200+ locale 全表） |
| `research/report-tool.md` | 工具卡全量（ToolRow/ToolCallTree/各工具差异/AskQuestionCard/ToolDetails） |
| `research/report-workspace-sidebar-layout-session-subagent.md` | 侧栏/工作区浏览器/会话树/子代理血缘/AppFrame |
| `research/report-goal-plan-jobs-approval-questions-workflow.md` | GoalBar/plan/Jobs/Schedule/审批/提问/计划审阅/工作流节点 |
| `research/report-cordis-commands-skill-deliverables-attachment-feedback.md` | Cordis/命令/技能/产物/附件轨/灯箱/消息反馈/品牌 |
| `research/report-model-permission-preset-theme-settings.md` | 模型选择/权限预设/代理预设/主题/设置页/语言/目录选择器 |
| `_research/tmp/dsh-rpc-audit.md` + `remotes-summary.txt` | 16 命名空间 74 方法完整审计（含全部 wire 名与证据行） |
| `dsh-web-ui-features.md` | 六节全功能清单汇总（70KB，交叉复核用） |

**开源发布准备（R67）**
135. **发布前敏感信息扫描**：源码中**无真实 API Key**；唯一敏感项是 `build.gradle.kts` 里的**明文签名口令**（已修）。
    设备标识/内网信息只出现在内部笔记（`spike/`、`dsh-android-audit.md`）与 SDK 目录中 → 均列入 `.gitignore`，不进仓库。
136. **签名密钥不落库**：改为从 `keystore.properties`（已 gitignore）或环境变量
    （`DSH_KEYSTORE_FILE/_PASSWORD/_ALIAS/_KEY_PASSWORD`）读取；**未配置时 `assembleRelease` 仍成功**，
    产出 `app-release-unsigned.apk`（实测 13s 通过），`assembleDebug` 完全不受影响 → 贡献者无需任何密钥即可构建。
    仓库内只放 `keystore.properties.example` 模板。
137. **新增发布文件**：`LICENSE`（AGPL-3.0 全文，33.7 KB，取自 gnu.org）、`.gitignore`、`CHANGELOG.md`（首版 1.0.0）。
138. **版本**：`versionCode 2 → 3`、`versionName 0.2.0 → **1.0.0**`；设备实测 `dumpsys package | grep versionName` → `1.0.0` ✅
139. **资产瘦身（可量化）**：资产中混有 **Windows 专用包**（`@img/sharp-win32-x64` 18.5 MB、`@vscode/ripgrep-win32-x64` 5.2 MB），
    Android 用不到（`@img` 下 Android 走 `sharp-wasm32`，已保留）。移出后：
    资产 **358.3 → 334.6 MB**，**APK 134 → 124.2 MB**。
    **实测未破坏引擎**：全新安装 → 解包 → `node pid` 起来 → 发起 `bash` 工具调用（工具卡出现「bash · 运行中」）
    → 弹出审批 → `$events/result -> 200`（审批回执闭环）→ 模型对结果正常继续推理 ✅
140. **待你决策**（阻塞仓库结构）：358 MB 引擎资产的发布方案 —— A 不进仓库/随 Release 附件（推荐）、B Git LFS、
    C 直接进仓库、D 写脚本从上游拉取运行时。
