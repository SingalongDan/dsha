# 最小验证：Node 交叉编译要多久？（Linux 环境执行）

> **为什么需要这份清单**：F-Droid 构建配方的最大风险是**构建超时** ——
> 默认 `timeout: 7200`（= 2 小时），而 Termux 自述其 Node 单架构构建**约 2 小时**，
> 何况前面还有 OpenSSL 与 ICU。**在拿到真实耗时数据之前，不应投入 2–6 周写完整配方。**
>
> **本机无法执行**：开发机是 Windows，**无 docker、WSL 无发行版、无 bash**
> → 因此本清单设计为**可复制到任何 Linux 机器/云主机执行**。
>
> 目标：**只编 Node 一个组件**，量出耗时与峰值内存。不涉及 ICU/OpenSSL 的取舍、不涉及应用构建。

---

## 0. 前置

- Linux x86_64（Ubuntu 22.04+ 推荐，贴近 F-Droid 构建机）
- **Android NDK r26+**（F-Droid 构建机通常已提供；本地可下载 `android-ndk-r26d-linux.zip`）
- 约 **20 GB 磁盘**、**8 GB 内存**（若想复现 F-Droid 的 1 CPU / 2 GB 条件，见 §4）
- 网络可访问 nodejs.org

## 1. 一次性准备

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r26d          # ← 改成你的路径
export API=24
export TARGET=aarch64-linux-android$API
export TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export CC="$TOOLCHAIN/bin/${TARGET}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"

# 关键：16KB 页大小对齐（我们所有自建产物都要带，见 RELEASE.md §5.3.1）
export CFLAGS="-O2 -fPIC -Wl,-z,max-page-size=16384"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-Wl,-z,max-page-size=16384"

mkdir -p /tmp/nv && cd /tmp/nv
curl -fsSLO https://nodejs.org/dist/v24.18.0/node-v24.18.0.tar.gz
tar xf node-v24.18.0.tar.gz
```

## 2. 配置（先不依赖外部库，纯验证构建系统能否跑通）

```bash
cd node-v24.18.0
./configure \
  --dest-os=android \
  --dest-cpu=arm64 \
  --without-snapshot \
  --without-intl \          # 先用无 ICU 模式量基线耗时；ICU 是最耗时的一块
  --without-ssl \           # 同上：先量基线。真实配方**必须**带 shared-openssl
  --prefix=/tmp/nv/prefix
```

> 说明：这里刻意用"最小配置"先量**基线耗时**。真实配方还要加
> `--shared-openssl --shared-zlib --shared-cares --shared-sqlite --shared-icu`（见
> `fdroid-build-recipe-build.sh`），那会显著增加时间。

## 3. 构建并计时

```bash
/usr/bin/time -v make -j"$(nproc)" 2>&1 | tee /tmp/nv/build.log
```

**记录这三项**（就是本清单要的答案）：

```bash
grep -E "Elapsed \(wall clock\)|Maximum resident set size" /tmp/nv/build.log
ls -la out/Release/node 2>/dev/null || ls -la out/Release/
# 校验产物是对的架构 + 16KB 对齐
"$TOOLCHAIN/bin/llvm-readelf" -h  out/Release/node | grep -E "Machine|Type"
"$TOOLCHAIN/bin/llvm-readelf" -l  out/Release/node | grep LOAD      # Align 应 >= 0x4000
"$TOOLCHAIN/bin/llvm-readelf" -d  out/Release/node | grep -E "NEEDED|interpreter"
```

**判定标准**：
- `Machine: AArch64`、`解释器 /system/bin/linker64`、`LOAD Align ≥ 0x4000` → 产物形态正确
- 与 Termux 自述的 ~2 小时对比 → 决定 F-Droid 的 `timeout` 是否够

## 4. 复现 F-Droid 的资源条件（可选但强烈建议）

F-Droid 构建 VM 默认 **1 CPU / 2048 MB**。上一步是在你的机器上跑的，可能快得多。
用 cgroup / `systemd-run` 或容器限制资源后重跑一次：

```bash
# 例：限制到 1 CPU、2GB 内存（用 systemd-run）
sudo systemd-run --scope -p AllowedCPUs=0 -p MemoryMax=2G \
  bash -c 'cd /tmp/nv/node-v24.18.0 && make clean && /usr/bin/time -v make -j1' \
  2>&1 | tee /tmp/nv/build-1cpu.log
```

**这一步的数据才是真正决定性的** —— 它才对应 F-Droid 构建机的实际条件。

## 5. 把结果回填到设计文档

拿到数据后，更新 `docs/fdroid-build-recipe-design.md` 的 §4.1（构建超时）：
- 实测耗时（`nproc` 全速 / 1 CPU）
- 峰值内存
- 是否超过 `timeout: 7200`

**如果 1 CPU 条件下远超 2 小时** → 需要走官方指南里那条路：
「对于需要的资源比运行器可提供的资源来得多的应用，**它们可以使用本地机器来准备并测试元数据**」
—— 即与 F-Droid 打包者沟通资源问题，而不是硬塞进默认 timeout。

## 6. 备选：直接跑 F-Droid 的构建容器

如果执行环境**有 docker**，可直接用官方镜像验证（更贴近真实）：

```bash
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver
sudo docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z \
  -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver
```
（容器内 `fdroid build <appid>` 需要先有可用的 metadata；本清单 §1–§3 的独立验证更轻量，
建议先做独立验证再上容器。）

---

## 这份清单**不**回答的问题（避免误读）

- ❌ ICU / OpenSSL 的交叉编译是否能成功（本清单刻意跳过，先量基线）
- ❌ `bash` / coreutils 是否纳入配方（**策略未定**，见设计文档 §4.4）
- ❌ F-Droid 是否会接受这样的配方（需提交 MR 后由其打包者确认）
