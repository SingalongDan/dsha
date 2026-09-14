package io.github.singalongdan.dsha

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 待办面板（对齐 web TodoPanel）：会话有 todo 时显示，
 * 行 = ☐/✓ 状态 + 内容（done 灰色删除线语义）。
 */
@Composable
fun TodoBar(todos: List<JSONObject>) {
    if (todos.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            "任务清单（${todos.count { it.optString("status") == "done" }}/${todos.size}）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        todos.forEach { t ->
            val content = t.optString("content")
            val status = t.optString("status")
            val done = status == "done"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    if (done) "☑" else "☐",
                    fontSize = 12.sp,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    content,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/** 目标栏（GoalBar：goal 存在时显示）。 */
@Composable
fun GoalBar(goal: JSONObject?) {
    if (goal == null) return
    val title = goal.optString("title", goal.optString("goal", ""))
    if (title.isEmpty()) return
    val status = goal.optString("status", "active")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🎯 ", fontSize = 12.sp)
        Text(
            title,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.weight(1f))
        Text(
            when (status) { "paused" -> "已暂停"; "completed" -> "已完成"; else -> "进行中" },
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
