# DSHA — 原生插件交叉编译脚本（Android / AArch64 / bionic）
#
# 背景：随包的引擎需要两个原生插件，它们**没有 Android 预编译制品**，必须自己交叉编译：
#   1. node-pty 的 pty.node      —— 引擎的终端能力（上游无 android 预编译）
#   2. node-addon-system 的 system.node —— POSIX flock（新版引擎的会话持久化依赖它；
#      上游 flock.js 的平台判断还要放行 android，另见 assets 里的宿主补丁说明）
#
# 为什么这个脚本必须存在：
#   - 此前这两个 .node 是**手工编译后直接塞进 assets** 的，仓库里没有任何构建步骤
#     （build.gradle.kts 无 ndkVersion / externalNativeBuild / abiFilters），
#     导致**任何人 clone 下来都无法复现 APK**，也是 F-Droid 收录的硬性障碍。
#   - 本脚本把过程固定下来，可重复执行、结果可校验。
#
# 用法：
#   pwsh -File build-native-addons.ps1                       # 用默认路径
#   pwsh -File build-native-addons.ps1 -NodeHeaders <dir>    # 指定 node 头文件目录
#
# 依赖：Android NDK（默认取 android-sdk\ndk\26.1.10909125）、Node 头文件（未随仓库分发，
#       脚本会在缺失时提示下载地址）。

param(
    [string]$RepoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [string]$NdkVersion = "26.1.10909125",
    [string]$NodeVersion = "v24.18.0",
    [string]$NodeHeaders = ""
)

$ErrorActionPreference = "Stop"
$assets = Join-Path $RepoRoot "host-app\app\src\main\assets\usr\lib\node_modules"
$ndkBin = Join-Path $RepoRoot "android-sdk\ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
$clang = Join-Path $ndkBin "aarch64-linux-android24-clang.cmd"
$clangxx = Join-Path $ndkBin "aarch64-linux-android24-clang++.cmd"

Write-Host "=== DSHA 原生插件交叉编译 ===" -ForegroundColor Cyan
Write-Host "仓库:      $RepoRoot"
Write-Host "NDK:       $ndkBin"

if (-not (Test-Path $clangxx)) { throw "找不到 NDK 编译器: $clangxx（可用 -NdkVersion 指定已安装的版本）" }

# --- node 头文件：构建期依赖，不随仓库分发 ---
if (-not $NodeHeaders) {
    $candidates = @(
        (Join-Path $RepoRoot "_engine-upgrade\node-$NodeVersion"),
        (Join-Path $RepoRoot "_engine-upgrade\node-$($NodeVersion.TrimStart('v'))"),
        (Join-Path $env:TEMP "node-$NodeVersion"),
        (Join-Path $env:TEMP "node-$($NodeVersion.TrimStart('v'))")
    )
    $NodeHeaders = $candidates | Where-Object { Test-Path (Join-Path $_ "include\node\node_api.h") } | Select-Object -First 1
}
if (-not $NodeHeaders -or -not (Test-Path (Join-Path $NodeHeaders "include\node\node_api.h"))) {
    throw @"
缺少 Node $NodeVersion 头文件。请下载后重试（头文件体积大，不随仓库分发）：
  https://nodejs.org/dist/$NodeVersion/node-$($NodeVersion.TrimStart('v'))-headers.tar.gz
解压后传入： -NodeHeaders <解压目录>
"@
}
$inc = Join-Path $NodeHeaders "include\node"
Write-Host "Node 头:   $inc"

# --- node-addon-api（node-pty 用 C++ 包装，需要它）。它在 node_modules 里已随包存在 ---
$napiCandidates = Get-ChildItem -Path $assets -Recurse -Directory -Filter "node-addon-api" -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "napi.h") } | Select-Object -First 1
$napiInc = if ($napiCandidates) { $napiCandidates.FullName } else { $null }

$built = @()

# ============ 1) node-pty -> pty.node ============
$ptySrc = Join-Path $assets "node-pty\src\unix\pty.cc"
$ptyOut = Join-Path $assets "node-pty\prebuilds\android-arm64\pty.node"
if ((Test-Path $ptySrc) -and $napiInc) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ptyOut) | Out-Null
    # 16KB 页大小对齐：Android 15+ 的 16KB 页设备要求 LOAD 段对齐 >= 16384，
    # 否则 dlopen 直接失败（Termux 官方 F-Droid 版正因此在新设备上跑不起来）。
    $args = @("-shared", "-fPIC", "-O2", "-std=c++17", "-DNAPI_VERSION=8", "-D_FILE_OFFSET_BITS=64",
              "-I$inc", "-I$napiInc", $ptySrc, "-o", $ptyOut, "-llog",
              "-Wl,-z,max-page-size=16384")
    Write-Host "`n[1/2] 编译 node-pty ..." -ForegroundColor Yellow
    & $clangxx @args
    if ($LASTEXITCODE -ne 0) { throw "node-pty 编译失败" }
    $built += $ptyOut
} else {
    Write-Host "[1/2] 跳过 node-pty（缺源码或缺 node-addon-api）" -ForegroundColor DarkYellow
}

# ============ 2) node-addon-system -> system.node ============
# 注意：flock.js 在 Android 上**不走** platform === 'linux' 分支，因此要的是 bin/system.node，
# 不是 bin/musl/system.node（放错会报 Cannot find module .../bin/system.node）。
$sysDir = Join-Path $assets "@deepseek-ai\node-addon-system\src"
$sysOutDir = Join-Path $assets "@deepseek-ai\node-addon-system-android-arm64\bin"
if (Test-Path (Join-Path $sysDir "main.c")) {
    New-Item -ItemType Directory -Force -Path $sysOutDir | Out-Null
    $sysOut = Join-Path $sysOutDir "system.node"
    Write-Host "`n[2/2] 编译 node-addon-system (flock) ..." -ForegroundColor Yellow
    & $clang "-shared", "-fPIC", "-O2", "-DNAPI_VERSION=8",
             "-I$inc",
             (Join-Path $sysDir "main.c"), (Join-Path $sysDir "flock.c"),
             "-o", $sysOut, "-llog", "-Wl,-z,max-page-size=16384"
    if ($LASTEXITCODE -ne 0) { throw "node-addon-system 编译失败" }
    $built += $sysOut
    # 兼容性副本：若将来 flock.js 改为按 libc 选择目录，这两个路径也已就位
    Copy-Item $sysOut (Join-Path $sysOutDir "musl\system.node") -Force -ErrorAction SilentlyContinue
    Copy-Item $sysOut (Join-Path $sysOutDir "glibc\system.node") -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "[2/2] 跳过 node-addon-system（缺 src/main.c）" -ForegroundColor DarkYellow
}

# ============ 结果与校验 ============
Write-Host "`n=== 产物 ===" -ForegroundColor Cyan
foreach ($f in $built) {
    $h = (Get-FileHash $f -Algorithm SHA256).Hash
    $size = (Get-Item $f).Length
    Write-Host ("  {0}`n    {1} 字节  SHA256 {2}" -f $f.Replace($RepoRoot, "."), $size, $h)
}
Write-Host "`n提示：产物已直接写入 assets，构建 APK 时会被打包。" -ForegroundColor Green
Write-Host "      校验一致性：与上次构建的 SHA256 比对；不同属正常（编译器/路径会进入二进制），" -ForegroundColor DarkGray
Write-Host "      但**功能应一致**，装到真机跑一轮对话即可验证。" -ForegroundColor DarkGray
