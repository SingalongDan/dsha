# NOTICE — 第三方组件与许可

本文件列出 DSHA 随 APK 分发的第三方组件及其许可，供合规审查与再分发使用。

---

## 1. 上游引擎：**MIT**（不是 AGPL）

| 组件 | 版本 | 许可 | 依据 |
|---|---|---|---|
| `@deepseek-ai/dsh`（引擎本体 + Web 客户端） | 0.1.5-rc.2 | **MIT** | 随包 `package.json` 的 `"license": "MIT"`，且包内 `LICENSE` 正文为 `MIT License, Copyright (c) 2026 DeepSeek` |
| `@deepseek-ai/*` 子包（cordis 等） | 随引擎 | MIT | 各包 `package.json` 抽查一致 |

> ⚠️ **更正记录**：本项目早期文档（`README.md`、`RELEASE.md`、设计文档）曾写"上游是 AGPL-3.0 / 与本项目一致"，
> 这是**事实错误**。经查随包制品的 `LICENSE` 正文与 `package.json`，上游引擎是 **MIT**。
> 因此：**上游不带来任何 AGPL 义务**，本项目选择 AGPL-3.0 是**自己的决定**，不是被上游"传染"的结果。

## 2. 随包 Node.js 运行时与 bionic 前缀

| 组件 | 许可 | 说明 |
|---|---|---|
| Node.js（AArch64 / bionic） | MIT | 上游 Node.js 项目许可 |
| Termux 前缀内的库（libicu / openssl / c-ares / libc++ / zlib / libffi / sqlite 等） | 各自原始许可（ICU: Unicode-3.0；OpenSSL: Apache-2.0；c-ares: MIT；libc++: Apache-2.0 WITH LLVM-exception；zlib: Zlib；libffi: MIT；SQLite: Public Domain） | 来自 Termux 的移植包；**注意：Termux 官方要求不得复用其包仓库**（我们随包分发，不使用其仓库） |

## 3. 自编译的原生插件

| 组件 | 来源 | 许可 |
|---|---|---|
| `node-pty/prebuilds/android-arm64/pty.node` | 由 `node-pty` 的 C++ 源码用 Android NDK 交叉编译（本地构建） | MIT（node-pty） |
| `@deepseek-ai/node-addon-system-android-arm64/bin/system.node` | 由 `@deepseek-ai/node-addon-system` 的 `src/{main.c,flock.c}` 用 NDK 交叉编译 | MIT |

## 4. npm 依赖总体分布（随包 `node_modules`，共 **490** 个包）

| 许可 | 包数 |
|---|---|
| MIT | 400 |
| Apache-2.0 | 55 |
| BSD-3-Clause | 15 |
| ISC | 11 |
| BSD-2-Clause | 2 |
| 0BSD | 1 |
| Unlicense | 1 |
| Python-2.0 | 1 |
| Apache-2.0 AND LGPL-3.0-or-later AND MIT | 1 |
| 未声明（父包的构建产物目录） | 3 |

**生成方法**（可复现）：遍历随包 `node_modules`（深度 2，排除嵌套 `node_modules`）的 `package.json`，
提取 `name` / `version` / `license` 字段后汇总。任何人可重跑以核对。

## 5. 需要留意的两项

### 5.1 `@img/sharp-wasm32@0.35.4` — 唯一含 copyleft 的组件
- 声明：`Apache-2.0 AND LGPL-3.0-or-later AND MIT`
- 用途：sharp（图像处理）在无原生二进制平台上的 **WASM 回退**；由本项目为 Android 主动补入
- LGPL-3.0 的义务：保留许可与版权声明；并保证该组件**可被替换**（LGPL 允许与其它代码链接而不传染整体）
- 待办：在应用内「关于」页与本文档中列出，并在发布说明中披露

### 5.2 三个"未声明许可"的目录
- `web-streams-polyfill-es2018` / `web-streams-polyfill-es6` / `web-streams-ponyfill`
- 它们是父包 `web-streams-polyfill`（**MIT**）的**构建产物目录**，`package.json` 里没有 `license` 字段
- 结论：按父包 MIT 处理，无需额外动作

## 6. 本项目自身的许可

- **本项目（DSHA 自有代码）：AGPL-3.0**（见 `LICENSE`）—— 这是**项目所有者的选择**，
  与上游 MIT 无冲突（MIT 兼容 AGPL，可被 AGPL 项目包含）。
- 若将来希望改为更宽松的许可（例如 MIT），**上游不构成任何障碍**（因为上游是 MIT）。
  ⚠️ 但**许可证变更需所有者决定**，且一旦发布后变更会影响已有用户，应在首次对外发布前确定。

---

## 附：分发形态对许可的影响（备忘）

| 分发形态 | 需要额外做的事 |
|---|---|
| GitHub Release 侧载 | 保留本 NOTICE + `LICENSE` + 第三方许可文本 |
| F-Droid（源码构建） | 需能**从源码**复现全部二进制（含自编译原生件），见 `RELEASE.md` 的 F-Droid 章节 |
| 任何形态 | 应用内「关于」页应可见许可信息（当前已有「许可」行） |
