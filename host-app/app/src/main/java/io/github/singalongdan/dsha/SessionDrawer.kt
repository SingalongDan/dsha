package io.github.singalongdan.dsha

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话条目（UI 模型）。 */
@androidx.compose.runtime.Immutable
data class SessionItem(
    val id: String,
    val title: String,
    val running: Boolean,
    val updatedAt: Long,
    val model: String = "",
    val preset: String = "",
    val blank: Boolean = true,
    val createdAt: Long = 0,
)

/**
 * 会话抽屉（左滑）：dsh 图标行 + 工作区按钮栏 + 会话列表 + 设置入口。
 * 对齐 web Sidebar + WorkspaceBrowser。
 */
@Composable
fun SessionDrawer(
    sessions: List<SessionItem>,
    currentId: String?,
    onSwitch: (SessionItem) -> Unit,
    onNew: () -> Unit,
    onRename: (SessionItem) -> Unit,
    onStop: (SessionItem) -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: (String) -> Unit = {},
    sortMode: String = "updated",
    onSortMode: (String) -> Unit = {},
    groupMode: String = "flat",
    pinnedIds: Set<String> = emptySet(),
    onPin: (SessionItem) -> Unit = {},
    onFork: (SessionItem) -> Unit = {},
    onGroupMode: (String) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp)
    ) {
        var searchOpen by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        // 品牌行
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("d", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("DSH", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "本地构建",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }

        // 工作区按钮栏：工作区 | 搜索 | 分组 | 添加
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工作区", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Search, contentDescription = "搜索会话", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { onGroupMode(if (groupMode == "flat") "date" else "flat") },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (groupMode == "flat") Icons.Filled.Folder else Icons.Filled.List,
                    contentDescription = if (groupMode == "flat") "按日期分组" else "平铺显示",
                    tint = if (groupMode == "flat") MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onNew, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "新会话", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }

        // 排序切换行（最近更新 / 按时间）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (sortMode == "updated") "按最近更新排序" else "按创建时间排序",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onSortMode(if (sortMode == "updated") "created" else "updated") }
                    .padding(4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("${sessions.size} 个会话", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 搜索框（点击搜索图标展开）
        if (searchOpen) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; onSearch(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("搜索会话…", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // 会话列表（flat = 平铺；date = 按日期分组，对齐 web 分组视图）
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (groupMode == "date") {
                var lastBucket: String? = null
                sessions.forEach { s ->
                    val bucket = dateBucket(s.updatedAt)
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        item(key = "hdr:$bucket") {
                            Text(
                                bucket,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                    }
                    item(key = s.id) {
                        SessionRow(
                            s = s,
                            isCurrent = s.id == currentId,
                            onClick = { onSwitch(s) },
                            onLongClick = { onRename(s) },
                            onMenuStop = { onStop(s) },
                            pinned = s.id in pinnedIds,
                            onMenuPin = { onPin(s) },
                            onMenuFork = { onFork(s) },
                        )
                    }
                }
            } else {
                if (sessions.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("🗂", fontSize = 28.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (searchQuery.isNotEmpty()) "没有匹配的会话" else "还没有会话",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (searchQuery.isNotEmpty()) "换个关键词试试" else "点下方「新会话」开始",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(sessions, key = { _, s -> s.id }) { _, s ->
                    SessionRow(
                        s = s,
                        isCurrent = s.id == currentId,
                        onClick = { onSwitch(s) },
                        onLongClick = { onRename(s) },
                        onMenuStop = { onStop(s) },
                        pinned = s.id in pinnedIds,
                        onMenuPin = { onPin(s) },
                        onMenuFork = { onFork(s) },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // 底部：新会话 + 设置
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .clickable { onNew() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("新会话", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .clickable { onOpenSettings() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("设置", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: SessionItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuStop: () -> Unit,
    pinned: Boolean = false,
    onMenuPin: () -> Unit = {},
    onMenuFork: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 相对时间需要一个"心跳"才会走动：此前是 `remember(s.updatedAt) { relativeTime(...) }`，
    // 只要 updatedAt 不变就永远用第一次算出的值 —— "刚刚"会一直显示"刚刚"，"1 分钟前"
    // 过一小时还是"1 分钟前"，只有列表因其它原因刷新时才重算。
    // 这里每 30 秒 tick 一次（由 produceState 驱动，随组件离开组合自动停止）。
    val tick by androidx.compose.runtime.produceState(initialValue = 0L, s.updatedAt) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val timeStr = remember(s.updatedAt, tick) { relativeTime(s.updatedAt) }

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    when {
                        s.running -> MaterialTheme.colorScheme.tertiary
                        isCurrent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    CircleShape,
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Text("📌", fontSize = 10.sp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = s.title,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeStr + (if (s.model.isNotEmpty()) " · ${s.model}" else ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (s.running) {
                    Spacer(Modifier.width(6.dp))
                    Text("运行中", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "操作", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onLongClick() })
                DropdownMenuItem(
                    text = { Text(if (pinned) "取消置顶" else "置顶") },
                    onClick = { menuOpen = false; onMenuPin() },
                )
                // 引擎支持 session/fork（分叉出同源会话，便于从当前状态试另一条路）
                DropdownMenuItem(text = { Text("分叉新会话") }, onClick = { menuOpen = false; onMenuFork() })
                // **"停止运行"始终可用**：不再依赖 session/list 的 running 角标（该角标可能滞后），
                // 否则会出现"想停却找不到入口"。误点只是对已结束的会话发一次无效 cancel。
                DropdownMenuItem(text = { Text("停止运行") }, onClick = { menuOpen = false; onMenuStop() })
            }
        }
    }
}

/** 相对时间（手机会话列表更直观）：刚刚 / N分钟前 / N小时前 / N天前 / MM-dd。 */
private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 30L * 86_400_000 -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

/** 日期分组桶（分组视图表头）：今天 / 昨天 / 本周 / 更早。 */
private fun dateBucket(ts: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return "今天"
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        yesterday.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天"
    val diffDays = (System.currentTimeMillis() - ts) / 86_400_000
    return if (diffDays < 7) "本周" else "更早"
}
