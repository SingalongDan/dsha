# 从矢量定义复现应用图标为 PNG（F-Droid 要求正方形 PNG）
# 依据 host-app/app/src/main/res 里的原始定义：
#   mipmap-anydpi-v26/ic_launcher.xml → background @color/ic_launcher_background = #4159E8
#                                       foreground @drawable/ic_launcher_foreground = 8 角星
#   前景矢量：viewport 108x108，中心 (54,54)，8 条辐条（4 条过中心的线段），
#             strokeWidth 7、圆头，颜色 #FFFFFF
Add-Type -AssemblyName System.Drawing

$size = 512
$scale = $size / 108.0            # 108 视口 → 512 画布
$bg = [System.Drawing.ColorTranslator]::FromHtml("#4159E8")
$fg = [System.Drawing.Color]::White

$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear($bg)

$pen = New-Object System.Drawing.Pen($fg, [single](7 * $scale))
$pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

$cx = 54.0 * $scale
$cy = 54.0 * $scale
$r = 22.0 * $scale               # 辐条长度的一半（32→76 即半径 22）

# 4 条过中心的线段 = 8 条辐条（与矢量里的 path 一一对应）
foreach ($deg in @(0, 45, 90, 135)) {
    $rad = $deg * [Math]::PI / 180.0
    $dx = [Math]::Cos($rad) * $r
    $dy = [Math]::Sin($rad) * $r
    $g.DrawLine($pen,
        [single]($cx - $dx), [single]($cy - $dy),
        [single]($cx + $dx), [single]($cy + $dy))
}

$g.Dispose()
$out = Join-Path $PSScriptRoot "icon.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "已生成: $out ($size x $size)"
