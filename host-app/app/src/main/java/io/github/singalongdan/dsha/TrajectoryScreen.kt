package io.github.singalongdan.dsha

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 轨迹行（从 Timeline 的 turn/step 派生）。
 */
@androidx.compose.runtime.Immutable
data class TrajectoryRow(
    val key: String,
    val kind: RowKind,
    val title: String,          // 行首摘要（工具名/消息/推理）
    val detail: String = "",    // 详情（参数/结果/输出）
    val turn: Int,
    val step: Int,
    val durationMs: Long = 0,
    val status: RowStatus = RowStatus.DONE,
    val callId: String = "",    // 工具调用 id（工具卡 → 轨迹定位）
) {
    enum class RowKind { SYSTEM, USER, ASSISTANT, TOOL, REASONING, STEP, COMPACTION, ERROR }
    enum class RowStatus { RUNNING, DONE, FAILED, WAITING }
}

/**
 * 轨迹视图：按 turn 分组，行 = turn/step 事件（对齐 web Trajectory 表的核心语义）。
 * 数据直接从 Timeline 折叠产出（非独立 API——web 也是从会话流投影的）。
 */
@Composable
fun TrajectoryScreen(
    rows: List<TrajectoryRow>,
    onRowClick: (TrajectoryRow) -> Unit,
    focusRow: String? = null,
    onSwipeRight: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }

    // 筛选：类型 + 关键词（标题/正文）
    val filtered = remember(rows, query, filter) {
        rows.filter { r ->
            val typeOk = when (filter) {
                "tool" -> r.kind == TrajectoryRow.RowKind.TOOL
                "msg" -> r.kind == TrajectoryRow.RowKind.USER || r.kind == TrajectoryRow.RowKind.ASSISTANT
                "reason" -> r.kind == TrajectoryRow.RowKind.REASONING
                "fail" -> r.kind == TrajectoryRow.RowKind.ERROR || r.status == TrajectoryRow.RowStatus.FAILED
                else -> true
            }
            val q = query.trim()
            typeOk && (q.isEmpty() || r.title.contains(q, true) || r.detail.contains(q, true))
        }
    }

    // 聚焦行：滚动到对应 index
    LaunchedEffect(focusRow, filtered.size) {
        val idx = filtered.indexOfFirst { it.callId == focusRow || it.key == focusRow }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    Column(
        Modifier
            .fillMaxSize()
            // 手势：右滑返回会话页（与轨迹页对称）
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                    onDragEnd = { if (total >= 60.dp.toPx()) onSwipeRight() },
                )
            }
    ) {
        // 搜索 + 类型筛选（长轨迹下定位工具调用）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索工具名 / 内容…", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                modifier = Modifier.weight(1f).height(52.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (query.isEmpty() && filter == "all") "${rows.size} 行" else "${filtered.size}/${rows.size}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("all" to "全部", "tool" to "工具", "msg" to "消息", "reason" to "推理", "fail" to "失败")
                .forEach { (key, label) ->
                    val on = filter == key
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (on) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            )
                            .clickable { filter = key }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 22.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(filtered, key = { _, r -> r.key }) { _, row ->
                    TrajectoryRowView(row, onRowClick, highlighted = row.callId == focusRow)
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (rows.isEmpty()) "暂无轨迹" else "没有匹配的行",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            // 彩色操作轨迹条（轨迹页专属）：按行类型着色，自上而下映射整条轨迹
            if (filtered.size >= 4) {
                TrajectoryTrack(
                    rows = filtered,
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onJump = { idx -> scope.launch { listState.scrollToItem(idx) } },
                )
            }
        }
    }
}

/** 轨迹页的彩色操作轨迹条：每行一个色块（按类型），半透明视口框，点击跳转。 */
@Composable
private fun TrajectoryTrack(
    rows: List<TrajectoryRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onJump: (Int) -> Unit,
) {
    val visible = listState.layoutInfo.visibleItemsInfo
    val firstVisible = visible.firstOrNull()?.index ?: 0
    val lastVisible = visible.lastOrNull()?.index ?: 0
    Box(
        modifier
            .padding(end = 2.dp, top = 8.dp, bottom = 8.dp)
            .width(16.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = "轨迹导航：共 ${rows.size} 行，点按跳转"
                role = Role.Button
            }
            .pointerInput(rows.size) {
                detectTapGestures { offset ->
                    val h = size.height.toFloat()
                    if (h <= 0f || rows.isEmpty()) return@detectTapGestures
                    onJump(((offset.y / h) * rows.size).toInt().coerceIn(0, rows.size - 1))
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val n = rows.size
            if (n == 0) return@Canvas
            val h = size.height
            val segH = (h / n).coerceAtLeast(1.2f)
            rows.forEachIndexed { i, row ->
                drawRect(
                    color = trajectoryKindColor(row.kind),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, i * segH),
                    size = androidx.compose.ui.geometry.Size(size.width, (segH - 0.8f).coerceAtLeast(0.8f)),
                )
            }
            val vpTop = (firstVisible.toFloat() / n) * h
            val vpBottom = ((lastVisible + 1).toFloat() / n) * h
            drawRect(
                color = Color.Black.copy(alpha = 0.28f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, vpTop),
                size = androidx.compose.ui.geometry.Size(size.width, (vpBottom - vpTop).coerceAtLeast(2f)),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = androidx.compose.ui.geometry.Offset(0f, vpTop),
                end = androidx.compose.ui.geometry.Offset(size.width, vpTop),
                strokeWidth = 1.5f,
            )
        }
    }
}

/** 轨迹行类型 → 轨迹条颜色（会话页与轨迹页共用同一套语义色）。 */
internal fun trajectoryKindColor(kind: TrajectoryRow.RowKind): Color = when (kind) {
    TrajectoryRow.RowKind.TOOL -> Color(0xFFE0A32E)
    TrajectoryRow.RowKind.ASSISTANT -> Color(0xFF4D6BFE)
    TrajectoryRow.RowKind.USER -> Color(0xFF8B5CF6)
    TrajectoryRow.RowKind.REASONING -> Color(0xFF7C8AA5)
    TrajectoryRow.RowKind.ERROR -> Color(0xFFEC1313)
    TrajectoryRow.RowKind.COMPACTION -> Color(0xFFB9C0CC)
    else -> Color(0xFFCBD2DB)
}

@Composable
private fun TrajectoryRowView(row: TrajectoryRow, onRowClick: (TrajectoryRow) -> Unit, highlighted: Boolean = false) {
    val kindColor = when (row.kind) {
        TrajectoryRow.RowKind.USER -> MaterialTheme.colorScheme.primary
        TrajectoryRow.RowKind.ASSISTANT -> MaterialTheme.colorScheme.tertiary
        TrajectoryRow.RowKind.TOOL -> Color(0xFFB8860B)
        TrajectoryRow.RowKind.REASONING -> MaterialTheme.colorScheme.secondary
        TrajectoryRow.RowKind.SYSTEM, TrajectoryRow.RowKind.STEP, TrajectoryRow.RowKind.COMPACTION -> MaterialTheme.colorScheme.onSurfaceVariant
        TrajectoryRow.RowKind.ERROR -> MaterialTheme.colorScheme.error
    }
    val statusColor = when (row.status) {
        TrajectoryRow.RowStatus.RUNNING -> MaterialTheme.colorScheme.primary
        TrajectoryRow.RowStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onRowClick(row) }
            .padding(vertical = 3.dp)
            .background(
                when {
                    highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    row.turn % 2 == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "T${row.turn}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .background(kindColor, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.title,
                fontSize = 13.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (row.durationMs > 0) {
                Text(
                    text = formatDuration(row.durationMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = when (row.status) {
                    TrajectoryRow.RowStatus.RUNNING -> "运行中"
                    TrajectoryRow.RowStatus.FAILED -> "失败"
                    TrajectoryRow.RowStatus.WAITING -> "等待"
                    TrajectoryRow.RowStatus.DONE -> "完成"
                },
                fontSize = 10.sp,
                color = statusColor,
            )
        }
        if (row.detail.isNotEmpty()) {
            Text(
                text = row.detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
            )
        }
    }
}

internal fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val totalSec = ms / 1000
    if (totalSec < 60) return "${totalSec}s"
    val m = totalSec / 60
    val s = totalSec % 60
    return if (s == 0L) "${m}m" else "${m}m${s}s"
}
