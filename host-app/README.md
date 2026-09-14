# DSHA —— DeepSeek Harness 的 Android 原生客户端

把 DSH（DeepSeek Harness）从"PC 上的终端/浏览器工具"变成**一部手机就能独立跑起来的原生 Agent App**：
**引擎装在手机里**（bionic 前缀 + node，同 UID，无 proot/VM），**界面用 Jetpack Compose 原生渲染**（非 WebView）。

- 与 web 端是**同一后端的对等端**：同一会话模型、同一套工具、同一权限体系、同一份设置——数据经同一引擎读写，
  不是"简化客户端"，也不是"套壳浏览器"。
- 体验按手机重写：单屏 + 会话/轨迹双视图 + 左滑手势 + 抽屉；web 的交互语义逐项用 Compose 重写。

---

## 能力一览

**实时会话**
- `session/follow` 打字机流式渲染（流式中走纯文本，定稿后切 Markdown，避免逐 chunk 重解析）
- 轮次导航：会话页右缘 **小点**（每轮一点，当前轮高亮、运行轮脉动，点击跳转）
- 轮尾行「用时 · 输入 · 输出」→ 点击看**本轮用量面板**（缓存命中 / 未缓存输入 / 输出含推理 / TTFT / TPS / LLM·工具耗时）
- 输入区上方 **上下文占用胶囊**（使用量 / 窗口 / 占比 / 剩余）
- 深度求索中…（shimmer + ≥15s 计时）、已停止胶囊、回到底部浮钮、自动跟随（用户上滚即停止跟随）

**轨迹视图**
- 右缘**彩色操作轨迹条**：每个操作按类型着色（工具/助手/用户/推理/审批/失败/注入）+ 视口指示，点击跳转
- 搜索框 + 类型筛选（全部/工具/消息/推理/失败），筛选结果同时作用于轨迹条
- 行详情：类型（中文）/ 状态 / 位置 / 耗时 / 调用 ID + **「在会话中查看」**（与会话→轨迹形成双向导航）

**消息与内容**
- Markdown 原生渲染：标题/列表/引用/代码块（横向滚动不折行）/表格（列对齐 + 表头）
- 工具卡：状态色胶囊（完成/运行中/失败）+ 运行中**脉动点与实时计时**；参数与结果长文本**折叠可展开**
- 上下文注入 / 压缩 / 重试 / 系统提示词 → **默认折叠的披露行**（点击展开）
- 长按消息：复制 / 引用到输入框 / 从此处分叉新会话 / **分享到其它 App** / 查看本轮用量

**会话管理**
- 抽屉：搜索（与排序/置顶统一由单一出口落地）/ 按日期分组 / 相对时间 / 置顶 📌 / 分叉 / 重命名 / 停止运行
- 现场恢复：进程被杀后回到**上次所在会话与页签**

**手机原生集成**
- **分享进来**：任意 App「分享」文本 → 新建会话并预填；**分享文件**（`content://`）→ 复制进工作区 `shared/` 并把路径交给 Agent
- **后台通知**：回合完成 / **需要你确认或选择**（Agent 会一直阻塞等待，切走必须能收到提醒），点击回到 App；回前台自动取消；
  设置里体现**通知的真实可用状态**（未授权／被系统关闭／已开启，并可一键跳系统设置）
- **品牌图标**：自适应图标（8 角星 + 品牌蓝，含 monochrome 层）+ 通知单色图标
- **可调字号**：设置 → 通用 → 字号，直接作用于消息正文（流式与 Markdown 同步）
- 手势导航：会话页**左滑**→轨迹、**右滑**→抽屉；轨迹页**右滑**→会话
- 现场恢复：进程被杀后回到**上次所在会话与页签**
- 无障碍：自定义控件（小点轨道、彩色轨迹条、折叠行）均带语义，状态变化用 `liveRegion` 播报

**设置（六栏目）**
通用（权限默认/语言/外观/字号/对话显示/回车行为/繁忙时 Enter）· 模型（提供方列表+状态圆点+编辑器+添加/自定义/删除/恢复默认）·
插件（真实插件清单）· Agent 预设 · Android 宿主（Root 模式/自启引擎/前台通知/API Key/引擎日志/重启引擎）· 关于

---

## 安装与首次运行

1. 侧载 APK：`adb install -r app-release.apk`（或直接点击安装）
2. 首次启动：应用会把内置引擎资源（约 130 MB）解包到私有目录并拉起 node
   （实测在 Redmi Note 12 Turbo 上 60 秒内完成；视机型约半分钟到一分钟），前台服务常驻守护
3. 首启引导会说明四件事：引擎在手机里 / 左右滑切换视图 / 长按消息有操作 / API Key 与权限在哪里改
4. API Key：**本应用不内置任何密钥**：请到 **设置 → Android 宿主 → API Key** 填入你自己的密钥后使用

## 构建

```bash
# 调试包
JAVA_HOME=<jdk17> gradle assembleDebug
# 发布包（已配置签名）
JAVA_HOME=<jdk17> gradle assembleRelease
# 安装并启动
adb install -r host-app/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.dsh.host/.ComposeChatActivity -f 0x20
```
产物：`app-debug.apk`（约 152 MB）、`app-release.apk`（约 134 MB，含引擎资源）。

## 发布与分发（重要取舍）

- **刻意保持 `targetSdk = 28`**：引擎依赖 `untrusted_app_27` 域，才能从应用数据目录 exec 二进制
  （免 root、免 sepolicy 规则）——这是"引擎跑在机内"的前提。
- 因此**不符合 Google Play 的 targetSdk ≥ 33 要求**，采用 **side-load 分发**。
  release 构建中显式豁免了 `ExpiredTargetSdkVersion`（其前提是上架），其余 lint 仍会阻断构建。
- 签名：`keystore/dsh-release.jks`（开发用；正式发布请更换并妥善保管私钥）。

## 引擎沙箱说明

Android 无 bubblewrap/landlock，`workspace-write` 模式会拒绝 shell 执行。恢复工具能力：
`settings/mutate permission.defaultPreset=danger-full-access`（新会话默认）
或 `commands/execute "/permission danger-full-access"`（当前会话）。
Root 模式（设置 → Android 宿主）会让 bash 经 `su` 提权执行。

## 架构

```
DSHAService            前台服务：解包引擎 → spawn node → 崩溃/退出退避重启（含 exit 0）
DshApiClient              HTTP JSON-RPC（手动 cookie 鉴权）
DshStreamClient           WS /api/remote.mux（open/item/end/error + 2s 心跳）
SessionStreamController   follow + control + $events 三流
                          + Timeline 折叠（turns/steps/surfaceOp replace/chunk 增量）
                          + 按轮统计 / 轨迹 / 队列 / 挂起瀑布
Compose UI                ChatScreen · TrajectoryScreen · SessionDrawer · SettingsDialog
                          ComposerBar · MarkdownText · StatsBar · QueueBar · TodoBar · AttachRail
```

契约细节（均以引擎源码/运行时探测为准）：
- `$events` 的 **clientId 只在 `ready` 帧下发**；waterfall 帧为 `{type,event,eventId,agentId,request}`
- 审批应答 `outcome.value` 必须是 `allowed-once|rejected|cancelled`；提问应答是 `{answers:[{id,selected[]}]}`
- `session/page` 用 `{request:{…}}` 包裹；`commands/execute` 为扁平 `{agentId,line,images}`

## 验证方式（本项目的工程约定）

每项改动都要求**真机验证**：`screencap` 看呈现、`logcat` 看行为、`dumpsys` 看系统状态
（通知记录、内存 PSS、Activity 状态）。已积累的实测结论（详见 `../dsh-android-app-design.md`）：
- 冷启动到内容可见：**8.05 s → 2.95 s**（鉴权重试间隔、日志尾部读取、WS 建连竞争三处修复）
- 流式渲染帧健康度：轻内容 `Janky 3/1001 (0.30%)`；含 10 KB 长消息的重内容 3.6–5%（仍在 5% 阈值内）
- 大列表：**54 个会话**下抽屉快速滚动掉帧 **1.63%**（50/90/95 分位 7/10/10 ms）
- 会话切换**无泄漏**：切换 5 次 123 MB → 累计 15 次 123.6 MB（持平）
- 图片附件内存：解码降采样 + 显式回收后，4 张附件增量 **83 MB → 10 MB**
- 长文本解析：10.7 KB ≈ 18.7 ms，已改为**快照后后台预热缓存**，不再占用组合线程
- 深色模式对比度：状态胶囊 8.64:1 / 10.91:1（实测），用户气泡由 4.34:1 修至 5.51:1

**测试环境限制（避免误判）**
- adb 的合成输入（`input text` / `input keyevent`）**无法输入 Compose 的 TextField**，
  View 版 `EditText` 可输入但本机 IME 的**组合提交**不生效 → 这类场景改用"引擎侧改动 + 应用显示刷新"验证
- release 包不可调试（`run-as` 失效），读应用私有数据需走引擎接口
- **KernelSU 会对未授权的应用隐藏 `su`**：即使 `/system/bin/su` 存在，应用进程内 `File.exists()` 仍为 false
  → 想用 Root 模式执行 shell 工具，需先在 KernelSU 管理器中授权本应用

## Licenses

- 本项目：AGPL-3.0（尊重 dsh 上游）
- 上游：`@deepseek-ai/dsh-*`（**MIT**，据随包 LICENSE 正文与 package.json）、bionic/openssl/Termux 运行时
- 设计文档：`../dsh-android-app-design.md`（完整功能清单 + 源码证据索引 + 每轮打磨记录）
