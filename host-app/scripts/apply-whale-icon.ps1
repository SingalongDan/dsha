# 把选定的 A 方案（v3 侧视实心鲸）落成完整图标资源
#
# 关键点：A.png 是由贝塞尔控制点绘制而成的，因此可以把**同一组控制点**直接转成
# Android VectorDrawable（等价于 SVG path），而不是描图 —— 矢量在任何尺寸都清晰。
#
# 产出：
#   1. ic_launcher_foreground.xml  —— 自适应图标前景（白色鲸鱼，已缩放进安全区）
#   2. icon.png (512×512)          —— F-Droid 用（品牌蓝底 + 白鲸）
#   3. 各密度 legacy PNG           —— API 24–25 用（自适应图标是 26+）
#
# 配色沿用现有品牌：底 #4159E8、前景 #FFFFFF
Add-Type -AssemblyName System.Drawing

$root = Resolve-Path "$PSScriptRoot\.."
$res = Join-Path $root "app\src\main\res"
$cand = Join-Path $PSScriptRoot "icon-candidates"

# ── 1) 原始几何（与 v3 Draw-A 完全一致，512 视口）──
# 每段 = @(起点x,起点y, c1x,c1y, c2x,c2y, 终点x,终点y)；第一段的起点即 path 的 M
$body = @(
  @(88,284,  92,234,  126,206,  176,200),
  @(176,200, 246,192, 300,196,  348,208),
  @(348,208, 376,214, 396,216,  412,212),
  @(412,212, 440,186, 462,172,  478,172),
  @(478,172, 484,186, 478,202,  462,216),
  @(462,216, 452,226, 440,232,  428,234),
  @(428,234, 450,252, 464,276,  470,298),
  @(470,298, 456,306, 440,300,  424,284),
  @(424,284, 404,272, 392,268,  380,270),
  @(380,270, 350,296, 286,322,  212,324),
  @(212,324, 146,324,  96,314,   88,284)
)
$fin = @(
  @(272,196, 288,174, 304,164, 316,162),
  @(316,162, 316,178, 314,192, 312,202)
)
$flipper = @(
  @(186,302, 200,332, 230,346, 252,336),
  @(252,336, 230,318, 216,306, 204,296)
)

# ── 2) 变换：缩放进自适应图标安全区并居中 ──
# 自适应图标 108dp 画布中，安全区约为中央 66%（直径 ~72dp）→ 内容需落在 ~61% 内
$s = 0.76                     # 缩放系数（留出圆/方遮罩余量）
$bx0 = 88.0; $bx1 = 478.0; $by0 = 162.0; $by1 = 346.0
$bcx = ($bx0 + $bx1) / 2.0; $bcy = ($by0 + $by1) / 2.0     # 几何中心
$tx = 256.0 - $bcx * $s; $ty = 256.0 - $bcy * $s            # 平移使中心落在画布中心

function T($v) { return [math]::Round($v, 1) }
function PX($x) { return (T ($x * $s + $tx)) }
function PY($y) { return (T ($y * $s + $ty)) }

function Segs-To-Path($segs, [bool]$close) {
    $d = ""
    for ($i = 0; $i -lt $segs.Count; $i++) {
        $g = $segs[$i]
        if ($i -eq 0) { $d += "M $(PX $g[0]),$(PY $g[1]) " }
        $d += "C $(PX $g[2]),$(PY $g[3]) $(PX $g[4]),$(PY $g[5]) $(PX $g[6]),$(PY $g[7]) "
    }
    if ($close) { $d += "Z" }
    return $d.Trim()
}

$dBody = Segs-To-Path $body $true
$dFin = Segs-To-Path $fin $true
$dFlip = Segs-To-Path $flipper $true

# 眼：白色环 + 蓝瞳。中心 (148,260) 半径 21 / 瞳 9 —— 同样缩放
$ecx = PX 148; $ecy = PY 260
$er = T (21 * $s); $pr = T (9 * $s)
$dRing = "M $($ecx - $er),$ecy a $er,$er 0 1,0 $($er*2),0 a $er,$er 0 1,0 -$($er*2),0 Z"
$dPupil = "M $($ecx - $pr),$ecy a $pr,$pr 0 1,0 $($pr*2),0 a $pr,$pr 0 1,0 -$($pr*2),0 Z"

# ── 3) 写 VectorDrawable（前景 = 白鲸，叠在品牌蓝底上）──
$fgXml = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- DSHA 应用图标前景：小头鼠海豚（Vaquita）侧视剪影。
     选型理由：小头鼠海豚是自然界最小的鲸类（约 1.2–1.5 m）；造型保留其圆钝无喙的头、
     低矮后掠的背鳍与小圆胸鳍，与 DeepSeek 的蓝鲸剪影明显不同。
     几何由同一组贝塞尔控制点生成（与 design/ 下的候选图同源），非描图，任意尺寸清晰。
     已按自适应图标安全区（中央约 66%）缩放居中，避免被圆形/方形遮罩裁切。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="512"
    android:viewportHeight="512">
    <!-- 身体 -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="$dBody" />
    <!-- 背鳍 -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="$dFin" />
    <!-- 胸鳍 -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="$dFlip" />
    <!-- 眼周环（挖底色）+ 瞳：鼠海豚的签名特征 -->
    <path
        android:fillColor="#FF4159E8"
        android:pathData="$dRing" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="$dPupil" />
</vector>
"@
$fgPath = Join-Path $res "drawable\ic_launcher_foreground.xml"
[System.IO.File]::WriteAllText($fgPath, $fgXml, [System.Text.UTF8Encoding]::new($false))
Write-Host "已写入: $fgPath"

# ── 4) 渲染 512 PNG（品牌蓝底 + 白鲸）给 F-Droid ──
function Render-Png([string]$out, [int]$px) {
    $brand = [System.Drawing.ColorTranslator]::FromHtml("#4159E8")
    $white = [System.Drawing.Color]::White
    $bmp = New-Object System.Drawing.Bitmap($px, $px)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear($brand)
    $k = $px / 512.0
    function New-SegPath($segs) {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        for ($i = 0; $i -lt $segs.Count; $i++) {
            $gg = $segs[$i]
            $x1 = ($gg[0] * $s + $tx) * $k; $y1 = ($gg[1] * $s + $ty) * $k
            $c1x = ($gg[2] * $s + $tx) * $k; $c1y = ($gg[3] * $s + $ty) * $k
            $c2x = ($gg[4] * $s + $tx) * $k; $c2y = ($gg[5] * $s + $ty) * $k
            $x2 = ($gg[6] * $s + $tx) * $k; $y2 = ($gg[7] * $s + $ty) * $k
            if ($i -eq 0) { $p.StartFigure() }
            $p.AddBezier([single]$x1, [single]$y1, [single]$c1x, [single]$c1y, [single]$c2x, [single]$c2y, [single]$x2, [single]$y2)
        }
        $p.CloseFigure()
        return $p
    }
    $br = New-Object System.Drawing.SolidBrush($white)
    foreach ($segs in @($body, $fin, $flipper)) { $pp = New-SegPath $segs; $g.FillPath($br, $pp); $pp.Dispose() }
    # 眼：蓝环 + 白瞳（底为蓝，故环即底色）
    $rb = New-Object System.Drawing.SolidBrush($brand)
    $g.FillEllipse($rb, [single](($ecx - $er) * $k), [single](($ecy - $er) * $k), [single](2 * $er * $k), [single](2 * $er * $k))
    $pb = New-Object System.Drawing.SolidBrush($white)
    $g.FillEllipse($pb, [single](($ecx - $pr) * $k), [single](($ecy - $pr) * $k), [single](2 * $pr * $k), [single](2 * $pr * $k))
    $rb.Dispose(); $pb.Dispose(); $br.Dispose()
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

Render-Png (Join-Path $cand "A-final-512.png") 512
Render-Png (Join-Path $root "..\fastlane\metadata\android\en-US\images\icon.png") 512
Render-Png (Join-Path $res "mipmap-xxxhdpi\ic_launcher_legacy.png") 192
Render-Png (Join-Path $res "mipmap-xxhdpi\ic_launcher_legacy.png") 144
Render-Png (Join-Path $res "mipmap-xhdpi\ic_launcher_legacy.png") 96
Render-Png (Join-Path $res "mipmap-hdpi\ic_launcher_legacy.png") 72
Render-Png (Join-Path $res "mipmap-mdpi\ic_launcher_legacy.png") 48
Write-Host "已渲染 PNG（512/192/144/96/72/48）"
Write-Host ""
Write-Host "路径数据预览："
Write-Host "  body: $dBody"
Write-Host "  eye : $dRing"
