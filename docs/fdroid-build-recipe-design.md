# F-Droid 构建配方设计（第 ④ 步）

> **状态：未完成**。本文是**设计文档**，不是可用的配方。
> 本文的目的：把"要在 F-Droid 构建机里从源码编出 bionic Node 运行时"这件事**拆解到可执行**，
> 并如实标注工作量、风险与未验证项。

---

## 1. 为什么配方非做不可

本应用随包一个 **bionic（Android libc）链接的 Node.js 运行时**。经核对，它**无法从任何白名单来源取得**：

| 来源 | 为什么不行 |
|---|---|
| nodejs.org 官方制品 | 白名单里的 "Node.js (current versions)" 确实存在，但官方**没有 android 目标**，其 `linux-arm64` 是 **glibc 链接** → 在 bionic 上加载失败 |
| Debian 仓库 | 白名单允许 Debian 下载，但 Debian 的 `nodejs` 同样是 **glibc** → 同样失败 |
| Termux 包仓库 | 唯一持续产出 bionic Node 的地方，但 **Termux 官方明令禁止复用其包仓库** |

**所以只能在构建机里自己编。** 这是第 ④ 步的全部内容，也是唯一的"硬骨头"。

## 2. 必须编出什么（据随包二进制的真实依赖）

对 `usr/bin/node` 做 `readelf -d`，其 `NEEDED` 列表就是配方的**交付清单**：

| 组件 | 库 | 说明 |
|---|---|---|
| **Node.js** 本体 | `node` | 需以 `--dest-os=android` 方式交叉编译（Termux 同款做法） |
| zlib | `libz.so.1` | 小 |
| c-ares | `libcares.so` | 小 |
| SQLite | `libsqlite3.so` | 中 |
| OpenSSL | `libcrypto.so.3` + `libssl.so.3` | 中；**不可省**（引擎要发 HTTPS 请求） |
| ICU | `libicui18n.so.78` + `libicuuc.so.78` + **`libicudata.so.78`（33 MB）** | **最大的一块**，见 §4.2 |
| libc++ | `libc++_shared.so` | NDK 自带，可从 NDK sysroot 取 |
| 系统库 | `libc.so` / `libm.so` / `libdl.so` | 平台提供，**不需要编** |

> 注意：这只是 **node 本体**的依赖。引擎还会 dlopen 我们自编译的两个原生插件
> （`pty.node`、`system.node`）—— 那两个已有可复现脚本（见 §3.3）。

## 3. 配方结构

### 3.1 F-Droid 元数据里的三个步骤

据官方快速入门指南，非 Gradle 工具链可用 `sudo` / `prebuild` / `build` 自定义：

```yaml
    sudo:
      - apt-get update
      - apt-get install -y <交叉编译所需宿主工具（cmake/ninja/python3 等）>
    prebuild: |
      # 获取各组件源码并解包（**不要在这里产出二进制** —— prebuild 的产物会被清理）
    build: |
      # ① 交叉编译依赖库（zlib → c-ares → sqlite3 → OpenSSL → ICU）
      # ② 交叉编译 Node（--dest-os=android，指向 ① 的产物）
      # ③ 交叉编译两个原生插件（见 §3.3）
      # ④ 把产物放进 app/src/main/assets/usr/ 后再执行 gradle
    output: app/build/outputs/apk/release/app-release-unsigned.apk
```

⚠️ **一个已知的 F-Droid 坑**：`prebuild` 的产物二进制会被清理，
**凡是产出二进制的步骤都必须放在 `build`**。

### 3.2 交叉编译通用配置（Android NDK）

统一使用（与我们在本机已验证成功的命令同源）：

```
TARGET=aarch64-linux-android24
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${TARGET}-clang
CXX=${CC}++
AR=$NDK/.../llvm-ar
# 关键：16KB 页大小对齐（见 §4.3），每个组件都要带
LDFLAGS="-Wl,-z,max-page-size=16384"
```

依赖库按 **zlib → c-ares → sqlite3 → OpenSSL → ICU → Node** 的顺序编译
（后者依赖前者的产物），统一 `--prefix` 到同一个 staging 目录。

### 3.3 两个原生插件（已有可复现脚本，需移植为 Linux/shell）

本仓库已有 `host-app/scripts/build-native-addons.ps1`，**已实测可用**
（`system.node` 可逐字节复现）。配方里需要它的 **POSIX shell 等价版本**：

```
$CC -shared -fPIC -O2 -DNAPI_VERSION=8 -I<node headers>/include/node \
    <src> -o <out>.node -llog -Wl,-z,max-page-size=16384
```
⚠️ **本机无 bash，shell 版本无法在本地验证** —— 必须在该阶段实测。

## 4. 风险与未决问题（如实）

### 4.1 构建超时（**最大风险**）
- Termux 自述其 Node 单架构构建约 **2 小时**，而 F-Droid 默认 `timeout: 7200`（正是 2 小时）
  —— **单是 Node 这一步就可能顶满**，何况前面还有 OpenSSL + ICU。
- 构建 VM 默认 **1 CPU / 2048 MB**。
- **缓解**：官方指南写明"对于需要的资源比运行器可提供的资源（存储、内存、时间）来得多的应用，
  **它们可以使用本地机器来准备并测试元数据**" → 即这是**流程内可解决**的问题，但需要与打包者沟通。
- **未验证**：实际耗时与是否会被接受，**必须实测**。

### 4.2 ICU 是最大的一块，但未必可省
- `libicudata.so.78` 单体 **33 MB**（占我们 assets 的相当比例），编译也最耗时。
- Node 可用 `--with-intl=small-icu` 或 `--without-intl` 显著减小体积与构建时间，
- **但**：引擎可能依赖完整 ICU 做文本/编码处理（未逐项核实）。
- **待办**：评估 `small-icu` 是否满足引擎需要 —— 若可以，收益很大（体积 + 构建时间双降）。

### 4.3 16KB 页大小对齐（**配方必须顺手解决**）
我们当前随包的 Termux 前缀里，**74 个 ELF 有 50 个未做 16KB 对齐**（含 `bash` 与全部 coreutils）
→ 16KB 设备上 shell 工具会失败（详见 `RELEASE.md` §5.3.1）。

**好消息**：如果配方真的从源码编译这些库，就可以**顺手全部对齐**（`-Wl,-z,max-page-size=16384`），
把 §5.3.1 那个缺陷一并解决。**这是配方的一项额外收益，不只是"为了上架"。**

### 4.4 前缀里的其它文件（bash、coreutils 等）怎么办？
我们的 `usr/` 不只含 node 与上述库，还有 `bash`、约 40 个 coreutils、CA 证书等（来自 Termux bootstrap）。
配方若只编 node，这些文件仍需来源：
- 选项 A：**只把 node 与它依赖的库改为源码编译**，其余保持在 assets 中（但它们仍未 16KB 对齐）；
- 选项 B：**连 bash/coreutils 一起编**（工作量大增）；
- 选项 C：**运行时改用 Android 自带的 toybox / mksh**，尽量减少对前缀的依赖（改产品行为，需评估）。

**这是配方设计里真正未决的部分**，需要先定下来才能估准工作量。

## 5. 工作量与建议

| 阶段 | 内容 | 估计 |
|---|---|---|
| 前置 | 定下 §4.4 的策略（A/B/C） | 1–2 天（决策） |
| 配方 v1 | 交叉编译 zlib/c-ares/sqlite3/OpenSSL/ICU/Node + 接线到 gradle | **1–3 周** |
| 本地验证 | 用 `fdroid build`（docker buildserver）在本机跑通 | 3–5 天 |
| 提交与迭代 | MR + 与打包者沟通资源问题 | 视反馈 |
| 合计 | | **2–6 周**（与先前估计一致） |

**建议的顺序**：
1. **先定 §4.4 的策略**（这决定配方范围，也决定要不要动 `bash`）
2. **先做一次"只编 node"的最小验证**（本机或 docker buildserver 里跑通一次交叉编译）
   —— 拿到真实耗时数据后再决定是否值得继续
3. 再补齐依赖库与 gradle 接线
4. 最后提交 MR

⚠️ **在 §4.1 的耗时数据出来之前，不建议承诺任何时间表。**
