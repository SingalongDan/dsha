package dev.dsh.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 附件轨（对齐 web AttachmentRail）：待发缩略图横排，每张带 × 移除。
 * 缩略图**异步 + 降采样**解码：解码是重活，放在组合线程会掉帧；全尺寸解码会吃掉几十 MB 内存。
 */
@Composable
fun AttachRail(
    images: List<org.json.JSONObject>,
    onRemove: (Int) -> Unit,
) {
    if (images.isEmpty()) return
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(images) { idx, img ->
            Box(Modifier.size(56.dp)) {
                val data = img.optString("data")
                val bmp = if (data.isNotEmpty()) rememberLazyBitmapAsync(data) else null
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = "附件",
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    // 解码中或失败：占位避免闪烁
                    Box(
                        Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("🖼", fontSize = 18.sp) }
                }
                // × 移除
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .clickable { onRemove(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "移除附件", tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/**
 * 异步解码缩略图：IO 线程解码 + `inSampleSize` 降采样（缩略图只有 56dp，无需全尺寸）。
 * 返回 ImageBitmap（Compose 可直接画）。
 */
@Composable
private fun rememberLazyBitmapAsync(data: String): androidx.compose.ui.graphics.ImageBitmap? {
    val state = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        data,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val target = TARGET_THUMB_PX * 2   // 留 2 倍余量给高密度屏
                while (bounds.outWidth / (sample * 2) >= target ||
                    bounds.outHeight / (sample * 2) >= target
                ) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                android.util.Log.d(
                    "DshPerf",
                    "thumb decode: ${bounds.outWidth}x${bounds.outHeight} → sample=$sample " +
                        "(${bmp?.width}x${bmp?.height}, ${((bmp?.byteCount ?: 0) / 1024)}KB)",
                )
                bmp?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

/** 缩略图目标边长（像素；56dp @ ~2.75x ≈ 154px）。 */
private const val TARGET_THUMB_PX = 160
