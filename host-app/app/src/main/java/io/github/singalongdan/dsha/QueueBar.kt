package io.github.singalongdan.dsha

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * 队列任务栏（对齐 web QueueDock）：有排队消息才显示。
 * 行 = 缩略文本 + 删除 + 插话发送（仅运行中）。
 */
@Composable
fun QueueBar(
    queue: List<JSONObject>,
    running: Boolean,
    onRemove: (String) -> Unit,
    onSteer: (String) -> Unit,
) {
    if (queue.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text(
            if (queue.size > 1) "${queue.size} 条排队消息" else "排队消息",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        queue.forEach { item ->
            val id = item.optString("id")
            val msg = item.optJSONObject("message")
            val text = excerptText(msg)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (running) {
                    IconButton(onClick = { onSteer(id) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Send, contentDescription = "插话发送", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    }
                }
                IconButton(onClick = { onRemove(id) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private fun excerptText(msg: JSONObject?): String {
    if (msg == null) return ""
    val content = msg.optJSONArray("content") ?: return msg.optString("text", "")
    return buildString {
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> append(b.optString("text"))
                "image" -> append("[图片]")
            }
        }
    }.let { it.take(40) + if (it.length > 40) "…" else "" }
}
