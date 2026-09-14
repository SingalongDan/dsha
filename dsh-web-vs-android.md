# dsh web 操作逻辑 × DSHA(Android) 操作逻辑 对照

> 目的：**先把 web 的真实操作逻辑摊开，再逐项对照我们的实现**，避免"凭印象改 UI"。
> 依据全部来自可核查的来源，不写推测。凡未验证的一律标注。

## 0. 依据与方法

| 类型 | 来源 |
|---|---|
| web 客户端源码 | 应用内置资产 `assets/usr/lib/node_modules/@deepseek-ai/dsh-client-ui-*`（与引擎同版本打包，非猜测） |
| web 文案字典 | 各包的 i18n `zh` 字典（`dsh-client-ui-conversation` / `-chat` / `-trajectory` / `-settings-models`） |
| web 样式令牌 | `dsh-client-ui-theme/lib/client.js` 的 CSS 变量（`--dsw-static-*` / `--dsw-alias-*`） |
| 引擎能力 | 对设备内引擎做 RPC 实测（`session/modelCatalog` 等） |
| 我们的实现 | `host-app/app/src/main/java/dev/dsh/host/*`（本次会话逐文件核对） |

溯源标注方式：`[conversation:L13298]` 表示 `dsh-client-ui-conversation/lib/client.js` 第 13298 行附近。

---

## 1. 信息架构

| 维度 | dsh web | DshHost | 判定 |
|---|---|---|---|
| 顶层视图 | **对话 / 轨迹** 两个视图 `view.chat`="对话"[chat]、`view.trajectory`="轨迹"[trajectory] | **会话 / 轨迹** | ⚠️ 术语不一致：web 叫「对话」，我们叫「会话」 |
| 视图切换 | 顶部 **tabs**（`wSkVaW_tabs`，36px 间距、下划线指示）[conversation CSS] | 顶部**分段控件** + 左右滑手势 | ✅ 平台适配合理（手机加了手势） |
| 侧栏 | 独立侧栏包 `dsh-client-ui-sidebar` + 工作区 `-workspace` | 抽屉（左滑/汉堡） | ✅ 平台适配 |
| 空会话页 | **hero** 相位：内容居中、输入栏居中放大、**工作区 + Agent 预设只在此处出现** [conversation:L14405-14448] | Hero 页（✳️ 探索未至之境 + 提示语） | ⚠️ 我们的 hero **缺预设/权限选择**，且预设被放到了对话页顶栏 |
| 会话头部 | `data-phase` 状态机（hero / active / settling）[conversation CSS] | 无显式相位机 | 记录差异（不影响使用） |

---

## 2. 关键操作逻辑逐项对照

### 2.1 Agent 预设（模式）

| | dsh web | DshHost（改前） |
|---|---|---|
| 呈现位置 | **只在 hero**：`hero && heroWorkspaceRow`，其中含 `renderSlot("conversation.hero.agentPreset", {})` [conversation:L14405-14430] | 对话页**顶栏胶囊**「标准模式」，随时可切 |
| 结论 | 预设 = **新建时的选择** | ❌ **未对齐**（你说得对） |

### 2.2 思考强度（reasoning effort）

| | dsh web | DshHost（改前） |
|---|---|---|
| 是否存在独立控件 | **刻意没有**。原文：*"There is deliberately no reasoning-effort control... effort is a **per-MODEL** capability, and the models under one provider disagree about it"* [settings-models:L1127] | 无 |
| 正确位置 | *"The **composer's model picker** offers each model its own levels instead."* [settings-models:L1130, L1380] | ❌ 未实现 |
| 数据来源（引擎实测） | `session/modelCatalog`（参数 `{}`）每个模型带：<br>`reasoning.efforts = [{id:"off"},{id:"low"},{id:"high"},{id:"max"}]`、`defaultEffort:"high"`，每档含 `name`/`description` | 我的 `selectModel(sessionId, provider, model, reasoningEffort)` **已支持该参数**，只缺 UI |
| 结论 | 强度是**模型自带档位**，放进模型选择器 | ⚠️ 需按此实现（不要做成独立全局滑块） |

### 2.3 权限 / 访问模式

| | dsh web | DshHost（改前） |
|---|---|---|
| 胶囊文案 | `input.accessMode` = **"访问模式，当前：{name}"** [conversation:L13299 附近] | 胶囊只写「权限」二字 |
| 取值文案 | `access.preset.readOnly`=**只可查看**、`workspaceWrite`=**工作区内修改**、`fullAccess`=**完全权限** [conversation:L13332-13334] | 显示原始英文枚举 |
| 结论 | 直接显示权限名（中文） | ❌ **未对齐**（你说得对） |

### 2.4 排队 / 插话

| | dsh web | DshHost（改前） |
|---|---|---|
| 选择形式 | **不是常驻控件**，而是**设置项**：`settings.enter.title`="**繁忙时** Enter 键行为"、`settings.enter.description`="**仅在智能体运行时生效**；Cmd/Ctrl+Enter 使用另一行为" [conversation:L13298 附近] | 输入栏常驻「排队 / 插话」两枚胶囊，空闲时也在 |
| 取值 | `settings.enter.queue`="排队发送" / `settings.enter.steer`="插话发送" | 同上两态 |
| 运行中行为 | 排队消息进 **queue dock**，每行带「插话」按钮 + 一键「插话发送全部排队消息」；`steer` 在无运行时返回 `session/steer-unavailable`（客户端静默忽略）[conversation:L12371-12387] | 有队列栏，但入口常驻 |
| 发送按钮 | `input.send`="发送消息" / `input.stop`="停止生成" —— 同一按钮随状态切换 | 发送按钮 + 独立停止 |
| 结论 | 队列/插话**只与"繁忙"有关** | ❌ **未对齐**（你说得对） |

### 2.5 输入栏视觉

| | dsh web | DshHost（改前） |
|---|---|---|
| 基调 | **蓝灰中性**色阶：`bluish-00 #fff … 50 #f9fafb / 75 #f1f3f5 / 150 #e9ecf2 / 700 #61666b / 950 #151517 / 1000 #0f1115` [theme] | Material 3 默认**紫灰**（深色 surface `#141218`） |
| 深色分层 | `bg-base=bluish-950 **#151517**`、`bg-layer-1=875 **#232324**`、`layer-2=850 **#2c2c2e**`、`layer-3=800 **#353638**` [theme dark rule] | 未分层，输入框用深色块 |
| 描边 | `border-l1 #ffffff0f`(6%)、`l2 #ffffff1f`(12%)、`l3 #ffffff29`(16%) [theme dark] | 无/单一描边 |
| 文字层级 | `label-primary=#f9fafb`、`secondary=#cfd3d6`、`tertiary=#adb2b8`、`caption=#81858c` [theme dark] | onSurface/onSurfaceVariant 两级 |
| 品牌色 | `state-business-primary = deepseek-400 **#679efe**` [theme dark] | 自定 `#679EFE`（已接近 ✅） |
| 悬浮细节 | composer **sticky 底部 + 36px 渐变遮罩**（透明→bg-base），`--dsh-composer-stack-gap:6px` [conversation CSS] | 无渐变遮罩 |
| 结论 | 你说"深色不美观"根因是**用了 M3 紫灰而非 web 的蓝灰分层** | ❌ 需按 web 令牌改造 |

### 2.6 轨迹视图

| | dsh web | DshHost |
|---|---|---|
| 工具条 | 时长模式（**实际时长 / 等宽操作**）、**展开/收起所有轮次**、**展开/收起所有调用**、搜索轨迹 [trajectory 字典] | 搜索 + 类型筛选（全部/工具/消息/推理/失败） |
| 行类型 | `kind`: 系统/用户/上下文/已压缩/消息/助手/工具/**子工具**/子项 | 工具调用/助手消息/用户消息/推理/步骤/上下文压缩/错误/系统 |
| 结论 | 我们缺"时长模式切换"和"批量展开/收起"；类型命名与 web 有出入 | ⚠️ 部分未对齐（本轮不改，登记） |

### 2.7 其他（已核对，无需改）

| 项 | web | 我们 |
|---|---|---|
| 图片上限文案 | `image.tooMany` 一条消息最多 {count} 张 / `image.fileTooLarge` 单张不能超过 {size} | 4 张 / 2MB ✅ 语义一致 |
| 停止 | `input.stop`="停止生成" | 「停止」/「取消本轮」 ✅ |
| 上下文 | `context.used`="上下文已用" | 「上下文 n%」 ✅ |
| 目标 | `hint.goal.active`="当前目标进行中。可输入 edit 修改 / pause 暂停 / resume 继续 / clear 清除" | 有 GoalBar ⚠️ 命令词未在 UI 提示（登记） |

---

## 3. 差异分类

**A. 平台适配差异（合理，不改）**
- 视图切换：web 用顶部 tabs，手机加左右滑手势
- 侧栏：web 常驻侧栏，手机用抽屉
- 快捷键：web 用 Cmd/Ctrl+Enter 区分排队/插话，手机无修饰键 → 需要一个可点的开关

**B. 未对齐（本轮修）**
1. Agent 预设出现在对话页 → **移到 hero（新建）**
2. 缺思考强度选择 → **按 web 放进模型选择器**（每模型自带档位）
3. 「权限」胶囊不显示权限名 → **显示中文权限名**
4. 排队/插话常驻 → **仅运行中出现**
5. 输入栏用 M3 紫灰 → **改用 web 蓝灰分层令牌**

**C. 登记待办（本轮不动）**
- 视图术语：web 叫「对话」，我们叫「会话」（改词会影响既有文档与肌肉记忆，先登记）
- 轨迹工具条缺"时长模式/批量展开收起"
- 轨迹行类型命名与 web 的 `kind.*` 不完全一致
- Goal 命令词（edit/pause/resume/clear）未在 UI 提示

---

## 4. 本轮改动清单（逐项真机验证）

| # | 改动 | 依据 | 验证方式 | 状态 |
|---|---|---|---|---|
| 1 | 预设移出对话页 → hero 选择；对话页顶栏只读展示 | §2.1 | 截图：hero 出现「模式 · 标准 ▾」+「工作区内修改 ▾」；有内容后自动消失；输入栏不再有预设；点「模式」→ 选择模式弹窗 → 选极简模式 → logcat `agentPresets/select -> 200` | ✅ 已验证 |
| 2 | 模型选择器内提供该模型的强度档位（含默认标记） | §2.2 | 截图：`思考强度 · v4-pro` → 关闭思考/低/高（默认）/最高；logcat：`session/selectModel` 200；芯片变「v4-pro · 最高」 | ✅ 已验证 |
| 3 | 权限胶囊显示中文权限名 | §2.3 | 截图：胶囊显示「工作区内修改」 | ✅ 已验证 |
| 4 | 排队/插话胶囊仅运行时出现 | §2.4 | 截图：空闲时无；回合运行中出现「排队 / 插话」，发送钮变停止 | ✅ 已验证 |
| 5 | 输入栏改用 web 蓝灰分层令牌 + 顶边细分隔线 | §2.5 | 深色像素取样：背景 `#151517` = web `bg-base`、输入框/芯片 `#232324` = web `bg-layer-1` | ✅ 已验证 |

### 本轮顺带发现并修复的真实缺陷

- **输入栏的模型选择器一直是空的**：`modelCatalogCache` 从未被填充（`modelCatalog()` 只在设置页调用），
  点「模型」只弹出标题和"取消"。→ 改为 bootstrap 拉取 + 打开菜单兜底拉取。
- **强度标签依赖模型目录**：目录加载后输入栏才会显示 `v4-pro · 最高` 这类"模型 · 强度"标签。
- **hero 的胶囊点不动**（本轮验证时发现）：hero 的 `Column` 与空 `LazyColumn` 同处一个 Box，
  LazyColumn 声明在后、覆盖同区域并参与命中测试 → 点击被吞。修复：hero 加 `Modifier.zIndex(1f)`
  （Compose 中 zIndex 同时影响绘制与命中测试）。修复后「模式」「工作区内修改」均可点，
  弹窗与 `agentPresets/select -> 200` 均已实测通过。

### 未做（登记，见 §3.C）

- 视图术语「对话 vs 会话」；轨迹工具条的时长模式与批量展开/收起；轨迹行类型命名；Goal 命令词提示。


---

## 5. 随包引擎升级到 0.1.5-rc.2 后的复核（2026-09-14）

升级后做了一次**运行时事件对照**（用裸 WS 探针枚举一轮真实回合的全部事件类型）：

| 事件 | 应用是否处理 | 说明 |
|---|---|---|
| `turn/start` `step/start` `step/end` `turn/end` | ✅ | 轮次/步骤状态与计时 |
| `user/message` `assistant/message` | ✅ | 消息落定 |
| `snapshot` | ✅ | 跟随流首帧 |
| **`agent/inbox/spliced`** | ➖ **无需处理** | 新版的排队/插话注入事件；队列状态其实来自 **`session/control` 流**（baseline + `queue` 帧），该流在 0.1.5 下工作正常，因此队列 UI 不受影响 |
| `turn/end.reason` | ✅ | 已在 0.1.5 前实现（本次正是靠它定位到 `flock is not supported on android-arm64`） |

**结论**：0.1.5 未引入需要新适配的会话事件；应用的协议层无需改动。

**新版带来的宿主侧要求**（已解决，详见 git log 与 `CONTINUE.md`）：
1. `node-pty` 需 android 预编译件 → 自编译 `pty.node`
2. `koffi` 需 `@koromix/koffi-android-arm64`（npm 平台检查会拒绝，用 `npm pack` 取）
3. `sharp` 需 `@img/sharp-wasm32`
4. 会话持久化需 POSIX flock → 给 `node-addon-system/lib/flock.js` 补一行平台判断
   （接受 `android`），并用 NDK 自编译其 `src/{main.c,flock.c}` 为
   `node-addon-system-android-arm64/bin/system.node`
   ⚠️ 不可用 preload 改 `process.platform`：那会让 koffi 去解析 glibc 版 `koffi-linux-arm64` 而启动失败
