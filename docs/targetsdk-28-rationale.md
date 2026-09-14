# 为什么本应用 `targetSdk = 28`（以及我们打算怎么办）

> 这份文档回答两个问题：**为什么**我们用一个"过时"的 targetSdk，以及**我们为此做了什么准备**。
> 它既是给审查者/贡献者的技术说明，也是我们自己的决策档案。

---

## 1. 原因：`targetSdk ≥ 29` 会禁止 `execve` 应用数据目录里的文件

本应用的引擎（Node.js）是**随 APK 分发、解包到应用私有目录、以应用自身 UID 执行**的。
而 Android 10（API 29）起，**目标 API ≥ 29 的应用失去了对自己数据目录内文件执行 `execve` 的能力** ——
这是有意的安全收紧（防止应用下载并执行任意代码）。

我们的直接证据（本机实测，非推测）：

```
$ readelf -l <bundled node>
NEEDED:  libc.so            ← bionic（glibc 会叫 libc.so.6）
NEEDED:  libc++_shared.so
解释器:  /system/bin/linker64
```

`process.platform` 在这套运行时上报告 `android`，且引擎的会话持久化依赖 POSIX `flock` ——
这些都说明它是一个**真正的 bionic 运行时**，必须能被"执行"，而不只是被"加载"。

**因此 `targetSdk = 28` 是为了落入 `untrusted_app_27` SELinux 域**，该域保留了
`allow untrusted_app_27 app_data_file:file execute_no_trans`。

## 2. 这不是我们独创 —— Termux 用的是同一个办法

Termux 官方公告（<https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant.html>）原文：

> As a workaround Termux is currently using `targetSdkVersion` `= 28` (Android `9`) to run in
> **backward compatibility mode**.

同一份公告也说明他们的长期方案叫 **APKLF（APK Library File）**，并警告该豁免可能在未来的
Android 版本被取消。

## 3. 关键事实：丢掉的**只有 `execve`，`dlopen` 仍然允许**

这一点决定了我们的迁移成本**远低于** Termux：

AOSP `app_neverallows.te` 的 neverallow 只限制 `execute_no_trans`（即 `execve`）：

```
neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app }
          { app_data_file privapp_app_data_file }:file execute_no_trans;
```

对应的 AOSP 提交说明原文：

> this change does not remove ... mmap(PROT_EXEC) ... **functionality like dlopen() on files in an
> app's home directory continues to work** even after this change.

**推论**：我们只需要把**唯一被 `execve` 的文件（`node`）**放进 `jniLibs`；
十几个 `.so` 与两个 `.node` 插件可以留在应用数据目录，继续由 `dlopen` 加载。

## 4. 与 Termux 的差异：我们的可执行文件只有十来个

| | Termux | DSHA |
|---|---|---|
| 需要放进原生库目录的文件 | **约 4000 个包**、海量二进制 | **1 个**（`node`） |
| "原生库目录只支持单层、不能嵌套"这一约束 | 灾难级（rootfs 深嵌套） | 基本无感 |
| 更新粒度问题（改一个包要重装 APK） | 严重 | 不存在（运行时本就随包分发） |

**所以 Termux 为 APKLF 头疼的那些问题，我们几乎都不存在。**

## 5. 迁移路径（已评估，成本 3–7 人日）

```
P0  验证（1–2 天）：把 node 复制为 jniLibs/arm64-v8a/libnode.so，
    开 useLegacyPackaging = true，从 ApplicationInfo.nativeLibraryDir exec；
    **仍发 targetSdk = 28**
   验收：node -v 能跑；自编译 .node require 成功；依赖 .so 加载成功；logcat 无 avc: denied
P1  正式化：symlink 保持路径兼容（$PREFIX/bin/node -> <nativeLibraryDir>/libnode.so），仍发 28
P2  内部构建升到新版 targetSdk，在 A14/15/16/17 上跑同一套验收；全通过才升正式版
P3  CI 预警探针（成本极低）：监控 AOSP 中
    ① MIN_INSTALLABLE_TARGET_SDK（安装下限）② PLATFORM_MIN_SUPPORTED_TARGET_SDK_VERSION（警告阈值）
    ③ seapp_contexts 里 minTargetSdkVersion=28 → untrusted_app_27 那条规则
    任何一条变动 → 立即触发迁移
```

**关键点：P0/P1 可以在 `targetSdk = 28` 下先做完**，之后升 SDK 只是改一个数字。

## 6. 当前的风险判断（如实）

| 风险 | 现状 | 依据 |
|---|---|---|
| **安装被阻断** | ❌ 短期不可能 | 安装下限 `MIN_INSTALLABLE_TARGET_SDK` = **24**（A15 才从 23 提到 24，A16/A17 未动） |
| **启动警告弹窗** | ⚠️ 我们是踩线的（阈值=28） | 警告阈值在 A14/15/16/17-main **均为 28**，从未提到 29 |
| **SELinux 规则被改** | ✅ 无迹象 | `seapp_contexts` 中该条自 Android 10 起未变动 |
| **OEM/ROM 自行限制** | ⚠️ 无法排除 | 部分 ROM 会对旧 targetSdk 弹警告；我们实测在 Android 16 上正常 |

**实测结论**：本应用在 **Android 16（SDK 36）** 上安装、启动、运行均正常 —— 这是实际验证过的，
不是推断。

## 7. 我们**没有**隐瞒的事

- 我们不认为 `targetSdk = 28` 是"永久方案"；
- 我们已查明长期方向（APKLF 思路）与自身成本（3–7 人日，因为我们只有 1 个可执行文件）；
- 一旦上游（Android）收紧，我们**已有可执行计划**，见 §5。

## 8. 相关但与 targetSdk **无关**的一个已知缺陷

**16KB 页大小**：我们随包的 Termux 前缀（旧版 bootstrap）中，**74 个 ELF 有 50 个未做 16KB 对齐**，
含 `bash` 与全部 coreutils → 在 16KB 页设备上 **agent 执行 shell 命令会失败**。
这与 `targetSdk` 无关（是设备/ABI 层面的问题），修法是等 Termux 发布已对齐的 bootstrap 后刷新前缀。
我们自己构建的部分（`node` 所在的链路、两个自编插件）**已完成对齐**。
详见 `RELEASE.md` §5.3.1。
