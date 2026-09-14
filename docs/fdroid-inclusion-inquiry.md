# F-Droid 收录咨询稿（草稿）

> **用途**：在动手写构建配方**之前**，先向 F-Droid 询问政策可行性。
> **提交位置建议**（二选一或都发）：
> - F-Droid 论坛 <https://forum.f-droid.org/>（非正式、回复快，适合政策问题）
> - `fdroiddata` 的 issue（**不是 MR**）<https://gitlab.com/fdroid/fdroiddata/-/issues>
>
> ⚠️ 本文件是**草稿**，由项目所有者决定是否提交、如何措辞。提交前请通读一遍确认事实无误。

---

## English text (to be posted)

**Title**: Inclusion inquiry: app bundling a Node.js runtime — is a build recipe feasible?

### Summary

I maintain **DSHA** (<https://github.com/SingalongDan/dsha>), an Android app (AGPL-3.0-only) that runs a
Node.js-based AI agent engine **entirely on-device** — native Jetpack Compose UI, no WebView, no remote server.
The engine, a Node.js runtime and a trimmed Termux bionic prefix are **bundled inside the APK** and unpacked
into the app's private directory on first launch, then executed under the app's own UID.

Before writing a build recipe, I would like to confirm whether this is feasible for the F-Droid main
repository, and how you would prefer it to be handled.

### The core problem: a bionic Node.js cannot come from any whitelisted source

The engine needs a Node.js built against **Android's bionic libc** (the bundled `node` binary requests
`/system/bin/linker64` and links `libc.so` / `libc++_shared.so` — i.e. it is *not* a glibc build).

Per the [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/), binary dependencies must come from
source compilation or authorized trusted sources. The two whitelist entries that look relevant do **not**
work here:

| Whitelist entry | Why it cannot provide what we need |
|---|---|
| **Node.js (current versions)** | nodejs.org publishes **no Android target**, and its `linux-arm64` builds are **glibc-linked** — they cannot run on bionic |
| **Debian repository downloads** | Debian's `nodejs` (arm64) is likewise **glibc-linked** — same problem |

The only project that continuously produces bionic Node builds is **Termux**, and their FAQ explicitly
states: *"You cannot use our package repositories in your own project(s). Please build packages and host
them yourself."* We therefore do **not** use their repositories. We currently ship our own copy.

We also cross-compile two native addons ourselves from source with the Android NDK
(`node-pty`'s `pty.node`, and `@deepseek-ai/node-addon-system`'s `system.node` for POSIX `flock`),
using a script that is in the repository.

### What the app does NOT do

- **No downloads at runtime.** Everything is inside the APK; first launch only unpacks bundled assets.
- **No bundled API keys.** The user enters their own key; without it, the engine is started without any
  credential in its environment.
- **No network access except `http://127.0.0.1:<port>`** to its own local engine (a network security config
  permits cleartext for localhost only). Remote calls to the selected LLM provider are made *by the engine*,
  with the user's own credentials.

### What we have already prepared

- ✅ `gradle wrapper` in the repository; dependencies resolved **only** from `google()` + `mavenCentral()`
  (any regional mirror is behind an explicit opt-in flag and **not** in the default path)
- ✅ **`NOTICE.md`** with a full third-party inventory (490 bundled npm packages scanned; the only copyleft
  component is `@img/sharp-wasm32`, LGPL-3.0, disclosed with its obligations)
- ✅ **Reproducible native-addon build script** (`host-app/scripts/build-native-addons.ps1`) — verified: one of
  the two artifacts reproduces **byte-identically**
- ✅ 16 KB page-size alignment for everything we build ourselves
- ✅ Project license: **AGPL-3.0-only**, declared with an explicit SPDX identifier

### Questions

1. **Is a build recipe feasible at all** for an app that needs a bionic Node.js runtime? Concretely: would
   cross-compiling Node (plus its dependency libraries) inside the F-Droid build server be acceptable, or is
   there a preferred pattern we are missing?
2. **Build resources.** Termux reports their own Node build takes roughly **2 hours** for a single
   architecture, which appears to exceed the default `timeout: 7200`; the default build VM is
   1 CPU / 2048 MB. Can the timeout/resources be raised for such an app, and what would you need from us?
3. **Would it be preferable** to keep the runtime out of the APK entirely (i.e. fetch it on first launch with
   explicit user consent), and if so, what are the policy requirements for the artifact's origin?

### Additional context we want to be upfront about: `targetSdk = 28`

Our app currently targets **API 28**. This is deliberate: apps targeting API ≥ 29 lose the ability to
`execve` files in their own data directory, which is exactly what running a bundled engine requires. This is
the **same workaround Termux uses** (Termux's own announcement describes API 28 as "backward compatibility
mode"). We are aware of, and have assessed, the long-term direction:

- We have verified that only `execve` is lost — `dlopen` of files in the app data directory remains permitted
  (`app_neverallows.te` restricts only `execute_no_trans`).
- We therefore already have a concrete migration path: putting the single executable (`node`) into
  `jniLibs` (SELinux context `apk_data_file`, where execution is allowed regardless of `targetSdk`), leaving
  libraries and native addons where they are. Estimated effort: **3–7 person-days**, and it can be done while
  still targeting API 28, so a later `targetSdk` bump is a one-line change.

We are happy to provide any further information, or to adjust our approach if you would prefer a different
structure. Thank you for maintaining F-Droid.

---

## 提交前的自查清单（给我们自己）

- [ ] 通读确认每一句陈述都与仓库现状一致（尤其"已准备"那一节的五项）
- [ ] 确认链接可访问（仓库地址、政策地址）
- [ ] 决定提交渠道：论坛 / fdroiddata issue（或两者）
- [ ] **不要**把它写成 MR —— 政策问题先问，不要直接提配方代码
- [ ] 记录提交日期与链接，便于后续跟进（可写入 `RELEASE.md`）

## 我们**不**想在这封里做的事

- ❌ 不承诺时间表（可行性未定，2–6 人周的投入取决于他们的答复）
- ❌ 不淡化 bionic Node 这个约束（白名单两条豁免都不适用，这是事实，必须让他们看见）
- ❌ 不隐瞒 `targetSdk = 28`（主动说明 + 附上已评估的迁移路径，比被问出来好得多）
