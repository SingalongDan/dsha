#!/bin/sh
# DSHA — F-Droid 构建配方：`build:` 步骤的内容
#
# 用途：在 F-Droid 构建机（Linux，有网络）里，从源码交叉编译出应用所需的
#       **bionic（Android libc）版 Node.js 运行时**及其依赖库，然后构建 APK。
#
# ⚠️ 重要状态说明（勿误读）：
#   · 本脚本是**配方骨架**，依赖顺序与编译参数已按我们本机实测成功的命令推导，
#     但**整体从未在 F-Droid 构建机或等价环境里跑过** —— 属"未验证"。
#   · 第 ①②③ 段（依赖库 + Node + 原生插件）的**可行性未实测**，尤其耗时（见 §4.1）。
#   · 第 ④ 段（把产物放进 assets）之后才能跑 gradle。
#   · `bash` / coreutils 等其余前缀文件是否也纳入本脚本，**取决于尚未做出的策略决定**
#     （见 docs/fdroid-build-recipe-design.md §4.4），此处**故意不处理**。
#
# 参考：docs/fdroid-build-recipe-design.md

set -eu

# ─────────────────────────── 环境 ───────────────────────────
: "${ANDROID_NDK_HOME:?需要设置 ANDROID_NDK_HOME（F-Droid 构建机通常已提供 NDK）}"
API=24
TARGET=aarch64-linux-android${API}
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export CC="$TOOLCHAIN/bin/${TARGET}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"

WORK="$(pwd)/_fdroid_build"
PREFIX="$WORK/prefix"           # 所有依赖装到这里，供 Node 链接
mkdir -p "$WORK" "$PREFIX"

# 16KB 页大小对齐 —— **每个组件都必须带**，否则 16KB 设备上加载失败
# （我们现在随包的旧前缀就有 50/74 个 ELF 未对齐，见 RELEASE.md §5.3.1；
#   本配方从源码编，正好顺手全部对齐）
export CFLAGS="-O2 -fPIC -Wl,-z,max-page-size=16384"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-Wl,-z,max-page-size=16384"

# 单架构：只做 arm64（应用本来就只打包 arm64）
# 只编共享库，不编静态库，减小体积
CONFIGURE_COMMON="--host=$TARGET --prefix=$PREFIX --disable-static --enable-shared"

fetch() {
    # $1 = url, $2 = 期望的文件名
    if [ ! -f "$2" ]; then
        echo "== 下载 $2"
        curl -fsSL -o "$2" "$1"
    fi
}

# ─────────────────── ① 依赖库（顺序即依赖顺序）───────────────────
echo "== ① zlib"
fetch https://zlib.net/zlib-1.3.1.tar.gz zlib.tar.gz
tar xf zlib.tar.gz
( cd zlib-1.3.1 && CFLAGS="$CFLAGS" ./configure --prefix="$PREFIX" --static=0 && make -j"$(nproc)" && make install )

echo "== ② c-ares"
fetch https://c-ares.org/download/c-ares-1.34.4.tar.gz cares.tar.gz
tar xf cares.tar.gz
( cd c-ares-1.34.4 && ./configure $CONFIGURE_COMMON && make -j"$(nproc)" && make install )

echo "== ③ SQLite"
fetch https://sqlite.org/2024/sqlite-autoconf-3460100.tar.gz sqlite.tar.gz
tar xf sqlite.tar.gz
( cd sqlite-autoconf-3460100 && ./configure $CONFIGURE_COMMON && make -j"$(nproc)" && make install )

echo "== ④ OpenSSL（引擎要发 HTTPS，不可省）"
fetch https://www.openssl.org/source/openssl-3.3.2.tar.gz openssl.tar.gz
tar xf openssl.tar.gz
( cd openssl-3.3.2 && ./Configure android-arm64 -D__ANDROID_API__=$API \
    --prefix="$PREFIX" --openssldir="$PREFIX/etc/tls" shared no-tests \
    && make -j"$(nproc)" && make install_sw )

echo "== ⑤ ICU（最大的一块：libicudata 单体约 33MB，编译也最耗时）"
# ⚠️ 待评估：Node 可用 --with-intl=small-icu 大幅减小体积与构建时间，
#    但引擎是否依赖完整 ICU **未核实** → 见设计文档 §4.2。
#
# ICU 交叉编译的正确做法：**先做一份宿主机构建**（host build），再让交叉构建
# 用 --with-cross-build 指向它 —— 因为 ICU 在构建过程中需要运行自己生成的工具
# （genrb/gencnval 等）来处理数据，交叉编译出的工具在宿主机上跑不了。
fetch https://github.com/unicode-org/icu/releases/download/release-76-1/icu4c-76_1-src.tgz icu.tgz
tar xf icu.tgz

echo "    ⑤a 宿主机构建（提供交叉构建要用的工具 + 数据）"
( cd icu/source \
    && ./configure --prefix="$WORK/icu-host" \
    && make -j"$(nproc)" && make install )

echo "    ⑤b 交叉构建（--with-cross-build 指向 ⑤a）"
# 注意：⑤b 必须在一个**干净的源码副本**里做（ICU 不支持同目录先宿主后交叉）
fetch https://github.com/unicode-org/icu/releases/download/release-76-1/icu4c-76_1-src.tgz icu-cross.tgz
mkdir -p icu-cross && tar xf icu-cross.tgz -C icu-cross
( cd icu-cross/icu/source \
    && ./configure $CONFIGURE_COMMON --with-cross-build="$WORK/icu-host" \
    && make -j"$(nproc)" && make install )
# ⚠️ 本步**未实测**：ICU 的 cross-build 路径与工具要求随版本变化，需按实际报错调整。

# ───────────────────────── ② Node.js ─────────────────────────
echo "== ⑥ Node.js（--dest-os=android，Termux 同款做法）"
fetch https://nodejs.org/dist/v24.18.0/node-v24.18.0.tar.gz node.tar.gz
tar xf node.tar.gz
( cd node-v24.18.0 && ./configure \
    --dest-os=android \
    --dest-cpu=arm64 \
    --without-snapshot \
    --openssl-use-def-ca-store \
    --shared-openssl --shared-openssl-includes="$PREFIX/include" --shared-openssl-libpath="$PREFIX/lib" \
    --shared-zlib --shared-zlib-includes="$PREFIX/include" --shared-zlib-libpath="$PREFIX/lib" \
    --shared-cares --shared-cares-includes="$PREFIX/include" --shared-cares-libpath="$PREFIX/lib" \
    --shared-sqlite --shared-sqlite-includes="$PREFIX/include" --shared-sqlite-libpath="$PREFIX/lib" \
    --shared-icu --with-intl=full-icu \
    --prefix="$PREFIX" \
    && make -j"$(nproc)" )
# ⚠️ 参数为**推导值，未实测**：Node 的交叉编译开关在不同版本间有差异，
#    需要按实际报错调整（尤其 ICU 与 OpenSSL 的 shared 模式）。

# ─────────────────── ③ 两个原生插件（已有本机实测脚本）───────────────────
echo "== ⑦ node-pty 与 node-addon-system"
NM="$REPO_ROOT/host-app/app/src/main/assets/usr/lib/node_modules"
NAPI_INC="$(find "$NM" -type d -name node-addon-api | head -1)"
"$CC" -shared -fPIC -O2 -std=c++17 -DNAPI_VERSION=8 -D_FILE_OFFSET_BITS=64 \
    -I"$PREFIX/include/node" -I"$NAPI_INC" \
    "$NM/node-pty/src/unix/pty.cc" -o "$NM/node-pty/prebuilds/android-arm64/pty.node" \
    -llog -Wl,-z,max-page-size=16384
"$CC" -shared -fPIC -O2 -DNAPI_VERSION=8 -I"$PREFIX/include/node" \
    "$NM/@deepseek-ai/node-addon-system/src/main.c" \
    "$NM/@deepseek-ai/node-addon-system/src/flock.c" \
    -o "$NM/@deepseek-ai/node-addon-system-android-arm64/bin/system.node" \
    -llog -Wl,-z,max-page-size=16384
# 注意：flock 的原生件必须放 bin/system.node（**不是** bin/musl/system.node）——
#       Android 不走 flock.js 里 platform === 'linux' 的分支。

# ─────────────────── ④ 放进 assets 并构建 APK ───────────────────
echo "== ⑧ 布置运行时到 assets"
USR="$REPO_ROOT/host-app/app/src/main/assets/usr"
mkdir -p "$USR/bin" "$USR/lib"
cp "$PREFIX/bin/node" "$USR/bin/node"
cp -f "$PREFIX"/lib/*.so* "$USR/lib/" 2>/dev/null || true
"$STRIP" --strip-unneeded "$USR/bin/node" || true
# ⚠️ 未处理：bash / coreutils / CA 证书等其余前缀文件（策略未定，见 §4.4）

echo "== ⑨ 构建 APK"
cd "$REPO_ROOT/host-app"
./gradlew assembleRelease
