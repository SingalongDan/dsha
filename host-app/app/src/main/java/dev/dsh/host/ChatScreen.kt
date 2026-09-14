package dev.dsh.host

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.host.TranscriptNode.NodeKind
import org.json.JSONObject

/**
 * 对话流（Compose 版）。节点按 key 惰性重组；流式节点文本变化由
 * LazyColumn item key + animateContentSize 平滑过渡。
 */
@Composable
fun ChatScreen(
    nodes: List<TranscriptNode>,
    running: Boolean,
    runningSince: Long = 0L,
    aborted: Boolean = false,
    turnStats: Map<Int, SessionStreamController.TurnStat> = emptyMap(),
    requestMeta: Triple<String, String, Long> = Triple("", "", 0L),
    stats: SessionStreamController.SessionStats,
    queue: List<org.json.JSONObject>,
    todos: List<org.json.JSONObject>,
    goal: org.json.JSONObject?,
    commands: List<String>,
    fileCandidates: List<FileCandidate>,
    attachments: List<org.json.JSONObject>,
    sendMode: String,
    permissionLabel: String,
    modelLabel: String,

    onPermission: () -> Unit,
    onModel: () -> Unit,

    onAttach: () -> Unit,
    onAttachRemove: (Int) -> Unit,
    onCommand: (String) -> Unit,
    onAtQuery: (String) -> Unit,
    onAtPick: (String) -> Unit,
    onSendMode: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onToolClick: (TranscriptNode) -> Unit,
    onApproval: ((TranscriptNode, Boolean) -> Unit)? = null,
    onFeedback: ((TranscriptNode, String) -> Unit)? = null,
    onQueueRemove: (String) -> Unit = {},
    onQueueSteer: (String) -> Unit = {},
    // 挂起的宿主交互（提问/审批）——作为对话流的一部分内联渲染（非模态）
    pendingEvent: SessionStreamController.PendingEvent? = null,
    /** 点「待处理」记录行 → 重新打开底部浮层。 */
    onOpenPending: () -> Unit = {},
    onApprovalDecision: (String) -> Unit = {},
    onQuestionSubmit: (org.json.JSONArray) -> Unit = {},
    onPendingCancel: () -> Unit = {},
    onLongPress: ((TranscriptNode) -> Unit)? = null,
    /** 外部注入输入框的文本（长按 → 引用消息）。 */
    injectText: String? = null,
    onInjectConsumed: () -> Unit = {},
    /** 需要聚焦的节点 key（轨迹 → 会话反向跳转；滚到该节点并短暂高亮）。 */
    focusNodeKey: String? = null,
    onFocusConsumed: () -> Unit = {},
    /** 点击统计行（查看完整统计）。 */
    onStatsClick: (() -> Unit)? = null,
    /** 消息正文字号（设置 → 通用 → 字号）。 */
    messageFontSize: Int = 15,
    /** 思考强度胶囊（与模型分开）。 */
    effortLabel: String = "",
    onEffort: (() -> Unit)? = null,
    /** 新建时的「模式」（Agent 预设）选择；仅 hero 展示（对齐 web）。 */
    presetLabel: String = "标准",
    onPreset: (() -> Unit)? = null,
    enterBehavior: String = "send",
    /** 左滑（内容向左推）= 去轨迹页；右滑 = 打开会话抽屉。 */
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val lastNode = nodes.lastOrNull()
    var initialized by remember { mutableStateOf(false) }
    // 用量/用时面板（点击轮尾行打开）
    val usagePanel = remember { mutableStateOf<SessionStreamController.TurnStat?>(null) }
    val scope = rememberCoroutineScope()
    // 跟随意图（对齐 web at-bottom 语义）：用户上滚 → 停止跟随；回到底部 → 恢复跟随。
    // 不能用 canScrollForward 瞬时值判断：新内容到达会立即把它变真，导致永不跟随。
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val footerCount = if (running || aborted) 1 else 0
    val lastIndex = (nodes.size - 1 + footerCount).coerceAtLeast(0)

    // 用户手动滚动时更新跟随意图
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling) follow = !canForward
            }
    }

    // 反向跳转：从轨迹行定位到会话中的对应节点（按 toolCallId / key 匹配），滚到并短暂高亮
    var highlightKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusNodeKey, nodes.size) {
        val k = focusNodeKey ?: return@LaunchedEffect
        val idx = nodes.indexOfFirst { it.meta["toolCallId"] == k || it.key == k }
        if (idx >= 0) {
            follow = false
            listState.scrollToItem(idx)
            highlightKey = k
            onFocusConsumed()
            kotlinx.coroutines.delay(1600)
            highlightKey = null
        }
    }

    // 真正滚到列表末尾（末项可能很长：scrollToItem 只对齐其顶部，再 scrollBy 补到底）
    suspend fun scrollToEnd() {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) return
        listState.scrollToItem(total - 1)
        listState.scrollBy(500_000f)
    }

    // 初次加载：直接滚到底部（历史回放显示最新）
    LaunchedEffect(nodes.size) {
        if (!initialized && nodes.isNotEmpty()) {
            scrollToEnd()
            initialized = true
        }
    }

    // 流式跟随：内容变化且处于跟随态时贴底
    LaunchedEffect(lastNode?.text, lastNode?.toolArgs, lastNode?.toolResult, lastNode?.reasoning, running, nodes.size) {
        if (!initialized) return@LaunchedEffect
        if (follow) {
            scrollToEnd()
        }
    }

    // 每轮最后一条助手消息的索引（轮尾行挂载点）
    val lastAssistantIdxByTurn = remember(nodes) {
        val m = HashMap<Int, Int>()
        nodes.forEachIndexed { i, n ->
            if (n.kind == TranscriptNode.NodeKind.ASSISTANT) m[n.turn] = i
        }
        m
    }
    // 轮次导航：turn → 首个节点索引（对齐 web TurnNavigator）
    val turnIndexes = remember(nodes) {
        val m = LinkedHashMap<Int, Int>()
        nodes.forEachIndexed { i, n -> if (n.turn >= 0 && !m.containsKey(n.turn)) m[n.turn] = i }
        m
    }
    // 当前可视轮次（轨道高亮）
    val currentTurn = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
        ?.let { idx -> nodes.getOrNull(idx)?.turn } ?: -1

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 手势导航：左滑 → 轨迹页；右滑 → 会话抽屉。
                // 用 detectHorizontalDragGestures：只有横向滑动被认领，纵向滚动仍归 LazyColumn。
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                        onDragEnd = {
                            val threshold = 60.dp.toPx()
                            if (total <= -threshold) onSwipeLeft()
                            else if (total >= threshold) onSwipeRight()
                        },
                    )
                }
        ) {
            // Hero 空会话提示（对齐 web「探索未至之境」；STATUS/权限芯片不算内容）
            val hasContent = nodes.any {
                it.kind == TranscriptNode.NodeKind.USER ||
                it.kind == TranscriptNode.NodeKind.ASSISTANT ||
                it.kind == TranscriptNode.NodeKind.TOOL
            }
            if (!hasContent) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // 关键：本 Box 里 LazyColumn 声明在后、覆盖同区域并参与命中测试，
                        // 会把点击全部吞掉 → 提升 zIndex 让 hero 的胶囊可点（zIndex 同时影响命中测试）。
                        .zIndex(1f)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text("✳️", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "探索未至之境",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "描述你想要构建的内容，在下方输入并发送",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    // 新建时的选择（对齐 web：hero 相位渲染 workspace + agentPreset，
                    // 而对话中不再出现预设选择 —— 见 dsh-web-vs-android.md §2.1）
                    if (onPreset != null || onPermission != null) {
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onPreset != null) {
                                HeroChoice("模式 · $presetLabel", onPreset)
                            }
                            if (onPermission != null) {
                                HeroChoice(permissionLabel, onPermission)
                            }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp, end = if (turnIndexes.size >= 2) 18.dp else 0.dp),
            ) {
                itemsIndexed(nodes, key = { _, n -> n.key }) { index, node ->
                    // 去重：存在待应答审批时，隐藏时间线里"未决"的审批卡，
                    // 否则同一审批会同时出现「等待审批」卡与内联「需要你确认」卡。
                    val suppressApproval =
                        pendingEvent?.event == "approval/request" &&
                            node.kind == TranscriptNode.NodeKind.APPROVAL &&
                            node.meta["decided"] != "1"
                    if (!suppressApproval) {
                        val hl = highlightKey != null &&
                            (node.meta["toolCallId"] == highlightKey || node.key == highlightKey)
                        Box(
                            if (hl) Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                            else Modifier
                        ) {
                            NodeView(node, onToolClick, onApproval, onFeedback, onLongPress, messageFontSize)
                        }
                    }
                    // 轮尾行（对齐 web TurnTailNodeView）：每轮最后一条助手消息下显示用时/用量，点击看详情
                    val st = turnStats[node.turn]
                    if (st != null && node.kind == TranscriptNode.NodeKind.ASSISTANT &&
                        lastAssistantIdxByTurn[node.turn] == index
                    ) {
                        TurnTailRow(st) { usagePanel.value = st }
                    }
                }
                // 运行状态行（web TurnStatus：深度求索中… + ≥15s 计时）
                if (running) {
                    item(key = "turn-status") { TurnStatusRow(runningSince) }
                }
                // 待应答交互（提问/审批）：**对话流里只留一条紧凑记录**，
                // 真正的操作在从下方弹出的浮层里完成（点完即关闭消失）——见 PendingInteractionSheet。
                // 这样常驻的大卡片不再占屏，回看时也仍有"这里曾等待过什么"的记录。
                if (pendingEvent != null) {
                    item(key = "pending-row:${pendingEvent.eventId}") {
                        PendingRecordRow(pe = pendingEvent, onOpen = onOpenPending)
                    }
                }
                if (!running && aborted) {
                    item(key = "stopped-badge") { StoppedChip() }
                }
            }
            // 会话页右缘：**小点导航**（对齐 web：每轮一个小点，当前轮高亮、运行轮脉动、点击跳转）
            if (turnIndexes.size >= 2) {
                val maxTurn = turnIndexes.keys.maxOrNull() ?: -1
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .width(16.dp)
                        .fillMaxHeight(0.7f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    turnIndexes.forEach { (turn, idx) ->
                        val isCurrent = turn == currentTurn
                        val busy = running && turn == maxTurn
                        val dotSize by androidx.compose.animation.core.animateDpAsState(
                            if (isCurrent) 6.dp else 4.dp,
                            animationSpec = androidx.compose.animation.core.tween(140),
                        )
                        val alpha = if (busy) {
                            val t = rememberInfiniteTransition()
                            val a by t.animateFloat(
                                initialValue = 1f, targetValue = 0.3f,
                                animationSpec = infiniteRepeatable(
                                    tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                    RepeatMode.Reverse,
                                ),
                            )
                            a
                        } else if (isCurrent) 1f else 0.4f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(13.dp)
                                .semantics {
                                    contentDescription = "第 $turn 轮"
                                    role = Role.Button
                                }
                                .clickable {
                                    follow = false
                                    scope.launch { listState.scrollToItem(idx) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(dotSize)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                                        androidx.compose.foundation.shape.CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
            // 回到底部浮按钮（对齐 web IconChevronDownOutline14；非跟随态显示）
            if (!follow && nodes.size > 3) {
                val scope = rememberCoroutineScope()
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .clickable {
                            follow = true
                            scope.launch { scrollToEnd() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↓", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                }
            }
        }
        GoalBar(goal)
        TodoBar(todos)
        QueueBar(queue, running, onQueueRemove, onQueueSteer)
        AttachRail(attachments, onAttachRemove)
        // 上下文占用胶囊（web ContextMeter；点击看明细）
        val ctxWindow = requestMeta.third
        val ctxUsed = stats.inputTokens + stats.cacheRead + stats.outputTokens
        var ctxOpen by remember { mutableStateOf(false) }
        if (ctxOpen) {
            ContextMeterDialog(ctxUsed, ctxWindow, requestMeta.first, requestMeta.second) { ctxOpen = false }
        }
        // 轮次用量/用时面板（点击轮尾行打开）
        usagePanel.value?.let { st ->
            TurnUsageDialog(st) { usagePanel.value = null }
        }
        ComposerBar(
            running = running,
            permissionLabel = permissionLabel,
            modelLabel = modelLabel,
            effortLabel = effortLabel,
            onEffort = onEffort ?: {},
            commands = commands,
            fileCandidates = fileCandidates,
            sendMode = sendMode,
            onPermission = onPermission,
            onModel = onModel,
            onAttach = onAttach,
            onCommand = onCommand,
            onAtQuery = onAtQuery,
            onAtPick = onAtPick,
            onSendMode = onSendMode,
            onSend = onSend,
            onStop = onStop,
            enterBehavior = enterBehavior,
            injectText = injectText,
            onInjectConsumed = onInjectConsumed,
        )
        StatsBar(
            stats = stats,
            onExpand = onStatsClick,
            contextUsed = ctxUsed,
            contextWindow = ctxWindow,
            onContextClick = { ctxOpen = true },
        )
    }
}

/** 节点视图：按 kind 分派。 */
@Composable
private fun NodeView(
    node: TranscriptNode,
    onToolClick: (TranscriptNode) -> Unit,
    onApproval: ((TranscriptNode, Boolean) -> Unit)?,
    onFeedback: ((TranscriptNode, String) -> Unit)?,
    onLongPress: ((TranscriptNode) -> Unit)? = null,
    fontSize: Int = 15,
) {
    when (node.kind) {
        NodeKind.USER -> UserBubble(node, onLongPress, fontSize)
        NodeKind.ASSISTANT -> AssistantBubble(node, onFeedback, onLongPress, fontSize)
        NodeKind.REASONING -> ReasoningBlock(node)
        NodeKind.TOOL -> ToolCard(node, onToolClick)
        NodeKind.APPROVAL -> ApprovalCard(node, onApproval)
        NodeKind.STATUS -> StatusChip(node)
        NodeKind.ERROR -> StatusChip(node)
        NodeKind.DISCLOSURE -> DisclosureRow(node)
        else -> StatusChip(node)
    }
}

/**
 * 披露行（对齐 web DisclosureRow）：默认折叠，一行 = 标题 · 摘要 + chevron；
 * 点击整行展开正文。
 */
@Composable
private fun DisclosureRow(node: TranscriptNode) {
    var expanded by remember(node.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "已展开" else "已折叠"
                }
                .clickable { expanded = !expanded }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(node.disclosureTitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node.disclosureSummary.isNotEmpty()) {
                Text(
                    "·",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Text(
                    node.disclosureSummary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                if (expanded) "▴" else "▾",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && node.body.isNotEmpty()) {
            Text(
                node.body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }
    }
}

/** 运行状态行（对齐 web TurnStatus）：「深度求索中…」shimmer 渐变字 + ≥15s 显示计时。 */
@Composable
fun TurnStatusRow(runningSince: Long) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(runningSince) {
        while (true) {
            elapsed = System.currentTimeMillis() - runningSince
            kotlinx.coroutines.delay(1000)
        }
    }
    val transition = rememberInfiniteTransition()
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
    )
    val base = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(dim, base, dim),
        start = androidx.compose.ui.geometry.Offset(shift * 260f - 130f, 0f),
        end = androidx.compose.ui.geometry.Offset(shift * 260f + 130f, 0f),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "深度求索中...",
            fontSize = 13.sp,
            style = androidx.compose.ui.text.TextStyle(brush = brush),
        )
        if (elapsed >= 15_000) {
            Spacer(Modifier.width(8.dp))
            Text("${elapsed / 1000}s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 待应答交互卡（提问 / 审批）——**对话流内联**，不是模态弹窗。
 * 对齐 web：approval / user-questions 卡片就在会话流里，可滚动、可回看。
 */
@Composable
fun PendingInteractionCard(
    pe: SessionStreamController.PendingEvent,
    onApprovalDecision: (String) -> Unit,
    onQuestionSubmit: (org.json.JSONArray) -> Unit,
    onCancel: () -> Unit,
) {
    val req = pe.request
    if (pe.event == "approval/request") {
        val reason = req.optString("reason").ifEmpty { req.optString("message") }
        val tool = req.optString("toolName").ifEmpty { req.optString("tool") }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(6.dp))
                Text("需要你确认", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                if (tool.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Text(tool, fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(
                reason.ifEmpty { "工具请求越权执行" },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onApprovalDecision("rejected") }) { Text("拒绝", fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onCancel) { Text("取消本轮", fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { onApprovalDecision("allowed-once") }, modifier = Modifier.height(34.dp)) {
                    Text("允许一次", fontSize = 12.sp)
                }
            }
        }
        return
    }

    // 用户提问：questions[{id,header,question,options[{label,description}],multi_select}]
    val questions = req.optJSONArray("questions") ?: org.json.JSONArray()
    val selections = remember(pe.eventId) { hashMapOf<String, MutableSet<String>>() }
    var tick by remember(pe.eventId) { mutableStateOf(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("需要你选择", fontWeight = FontWeight.Bold, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(6.dp))
        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i) ?: continue
            val qid = q.optString("id")
            val header = q.optString("header")
            val multi = q.optBoolean("multi_select", false)
            if (header.isNotEmpty()) {
                Text(header, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                q.optString("question"),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            val chosen = selections.getOrPut(qid) { mutableSetOf() }
            val options = q.optJSONArray("options")
            if (options != null) {
                for (o in 0 until options.length()) {
                    val opt = options.optJSONObject(o) ?: continue
                    val label = opt.optString("label")
                    val desc = opt.optString("description")
                    val on = label in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (multi) { if (on) chosen.remove(label) else chosen.add(label) }
                                else { chosen.clear(); chosen.add(label) }
                                tick++
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (on) "◉" else "○",
                            fontSize = 13.sp,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                label,
                                fontSize = 13.sp,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (desc.isNotEmpty()) {
                                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (multi) Text("（可多选）", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("取消本轮", fontSize = 12.sp) }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    val answers = org.json.JSONArray()
                    for (i in 0 until questions.length()) {
                        val q = questions.optJSONObject(i) ?: continue
                        val qid = q.optString("id")
                        answers.put(
                            JSONObject().put("id", qid)
                                .put("selected", org.json.JSONArray(selections[qid]?.toList() ?: emptyList<String>()))
                        )
                    }
                    onQuestionSubmit(answers)
                },
                modifier = Modifier.height(34.dp),
            ) { Text("提交答案", fontSize = 12.sp) }
        }
        tick // 订阅选择变化以触发重组
    }
}

/**
 * 彩色操作轨迹条（对齐 web 轨迹视图的直观性）：
 * 把消息流的每个操作按类型着色，画成一条**从上到下**的迷你地图；
 * 半透明矩形表示当前视口位置；点击任意处跳转到对应操作。
 */
@Composable
fun OperationTrack(
    nodes: List<TranscriptNode>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onJump: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val trackW = 14.dp

    // 操作类型 → 颜色（工具=琥珀、助手=蓝、用户=紫、推理=灰蓝、审批/提问=粉、失败=红、注入=浅灰）
    fun colorOf(kind: TranscriptNode.NodeKind): Color = when (kind) {
        TranscriptNode.NodeKind.TOOL -> Color(0xFFE0A32E)
        TranscriptNode.NodeKind.ASSISTANT -> Color(0xFF4D6BFE)
        TranscriptNode.NodeKind.USER -> Color(0xFF8B5CF6)
        TranscriptNode.NodeKind.REASONING -> Color(0xFF7C8AA5)
        TranscriptNode.NodeKind.APPROVAL -> Color(0xFFE86A8A)
        TranscriptNode.NodeKind.ERROR -> Color(0xFFEC1313)
        TranscriptNode.NodeKind.DISCLOSURE -> Color(0xFFB9C0CC)
        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    val visible = listState.layoutInfo.visibleItemsInfo
    val firstVisible = visible.firstOrNull()?.index ?: 0
    val lastVisible = visible.lastOrNull()?.index ?: 0

    Box(
        modifier
            .padding(end = 2.dp, top = 8.dp, bottom = 8.dp)
            .width(trackW)
            .fillMaxHeight()
            .pointerInput(nodes.size) {
                detectTapGestures { offset ->
                    val h = size.height.toFloat()
                    if (h <= 0f || nodes.isEmpty()) return@detectTapGestures
                    val idx = ((offset.y / h) * nodes.size).toInt().coerceIn(0, nodes.size - 1)
                    onJump(idx)
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val n = nodes.size
            if (n == 0) return@Canvas
            val h = size.height
            val segH = (h / n).coerceAtLeast(1.2f)
            nodes.forEachIndexed { i, node ->
                val y = i * segH
                drawRect(
                    color = colorOf(node.kind),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                    size = androidx.compose.ui.geometry.Size(size.width, (segH - 0.8f).coerceAtLeast(0.8f)),
                )
            }
            // 视口指示：半透明覆盖 + 边界线
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

/** 「已停止」胶囊（对齐 web stopped badge）。 */@Composable
private fun StoppedChip() {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            "已停止",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * 轮尾行（对齐 web TurnTailNodeView）：用时 X · 输入 Y · 输出 Z（点击查看用量/用时详情）。
 */
@Composable
fun TurnTailRow(stat: SessionStreamController.TurnStat, onClick: () -> Unit) {
    fun dur(ms: Long): String = when {
        ms <= 0 -> "—"
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
    fun tok(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1000 -> "${n / 1000}K"
        else -> n.toString()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, top = 1.dp, bottom = 4.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "用时 ${dur(stat.durationMs)} · 输入 ${tok(stat.inputTokens + stat.cacheRead)} · 输出 ${tok(stat.outputTokens)}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 本轮用量 / 本轮用时和速度（对齐 web TurnUsagePanel + TurnTimePanel）。 */
@Composable
fun TurnUsageDialog(stat: SessionStreamController.TurnStat, onClose: () -> Unit) {
    fun dur(ms: Long): String = when {
        ms <= 0 -> "—"
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
    fun tok(n: Long): String = java.text.NumberFormat.getIntegerInstance().format(n)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("本轮用量", fontSize = 16.sp) },
        text = {
            Column {
                UsageRow("提供方 / 模型", listOf(stat.provider, stat.model).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { "—" })
                UsageRow("缓存命中", if (stat.cacheRead > 0) String.format("%.1f%%", stat.cacheHitPercent) else "—")
                UsageRow("未缓存输入", tok(stat.inputTokens))
                if (stat.cacheRead > 0) UsageRow("缓存读取", tok(stat.cacheRead))
                if (stat.cacheWrite > 0) UsageRow("缓存写入", tok(stat.cacheWrite))
                UsageRow("输出", tok(stat.outputTokens) +
                    if (stat.reasoningTokens > 0) "（其中推理 ${tok(stat.reasoningTokens)}）" else "")
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                UsageRow("用时", dur(stat.durationMs))
                UsageRow("首 token 用时（TTFT）", dur(stat.ttftMs))
                UsageRow("输出速度（TPS）", if (stat.tps > 0) String.format("%.1f tok/s", stat.tps) else "—")
                UsageRow("LLM / 工具", "${dur(stat.llmMs)} / ${dur(stat.toolMs)}")
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp)
    }
}

/** 上下文占用表（对齐 web ContextMeter：使用量 / 窗口 + 近似分项）。 */
@Composable
fun ContextMeterDialog(
    usedTokens: Long,
    contextWindow: Long,
    provider: String,
    model: String,
    onClose: () -> Unit,
) {
    fun tok(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.2fM", n / 1_000_000.0)
        n >= 1000 -> String.format("%.1fK", n / 1000.0)
        else -> n.toString()
    }
    val pct = if (contextWindow > 0) (usedTokens.toDouble() / contextWindow * 100) else 0.0
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("上下文占用", fontSize = 16.sp) },
        text = {
            Column {
                UsageRow("使用量", "${tok(usedTokens)} / ${if (contextWindow > 0) tok(contextWindow) else "—"}")
                UsageRow("占比", if (contextWindow > 0) String.format("%.1f%%", pct) else "—")
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                UsageRow("提供方 / 模型", listOf(provider, model).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { "—" })
                Text(
                    "剩余 ${if (contextWindow > 0) tok((contextWindow - usedTokens).coerceAtLeast(0)) else "—"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("关闭") } },
    )
}

/** 上下文占用小胶囊（顶栏/输入栏上方；点击打开明细）。 */
@Composable
fun ContextMeterPill(usedTokens: Long, contextWindow: Long, onClick: () -> Unit) {
    val pct = if (contextWindow > 0) (usedTokens.toDouble() / contextWindow * 100) else 0.0
    Text(
        if (contextWindow > 0) "上下文 ${String.format("%.0f", pct)}%" else "上下文",
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 审批卡：工具请求越权执行 → 允许一次 / 拒绝（web 语义：无"允许并记住"）。 */
@Composable
private fun ApprovalCard(node: TranscriptNode, onApproval: ((TranscriptNode, Boolean) -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "等待审批",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = node.text,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            if (node.meta["decided"] == "1") {
                // 已决策：**只读记录**，不再提供按钮（否则点一下会重复发送一次决策）
                Text(
                    "已处理",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontSize = 12.sp,
                )
            } else {
            OutlinedButton(
                onClick = { onApproval?.invoke(node, false) },
                modifier = Modifier.height(32.dp),
            ) { Text("拒绝", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onApproval?.invoke(node, true) },
                modifier = Modifier.height(32.dp),
            ) { Text("允许一次", fontSize = 12.sp) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(node: TranscriptNode, onLongPress: ((TranscriptNode) -> Unit)? = null, fontSize: Int = 15) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text = node.text,
            modifier = Modifier
                .widthIn(max = 300.dp)
                // 用户气泡：白字对比度 5.5:1（原 #4D6BFE 为 4.34:1，低于 WCAG AA 正文 4.5:1）
                .background(Color(0xFF4159E8), RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .then(
                    if (onLongPress != null) Modifier.combinedClickable(
                        onClick = {}, onLongClick = { onLongPress(node) },
                    ) else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = fontSize.sp,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    node: TranscriptNode,
    onFeedback: ((TranscriptNode, String) -> Unit)? = null,
    onLongPress: ((TranscriptNode) -> Unit)? = null,
    fontSize: Int = 15,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.Start) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    // 定稿后才对尺寸做动画：流式期间每 chunk 触发一次布局动画会掉帧
                    .then(if (node.streaming) Modifier else Modifier.animateContentSize())
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                    .then(
                        if (onLongPress != null) Modifier.combinedClickable(
                            onClick = {}, onLongClick = { onLongPress(node) },
                        ) else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (node.streaming) {
                    // 流式中：纯文本渲染。避免每个 chunk 都全量重解析 Markdown/代码高亮
                    // （长回答下这是主要的掉帧来源），定稿后再切到 Markdown 渲染。
                    Text(
                        text = node.text + " ▍",
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.45).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    MarkdownText(text = node.text, fontSize = fontSize)
                }
            }
        }
        if (false && !node.streaming && onFeedback != null && node.text.isNotEmpty()) {   // 反馈按钮已移除（用户不需要）
            Row(Modifier.padding(start = 26.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FeedbackButton("👍", onClick = { onFeedback(node, "positive") })
                FeedbackButton("👎", onClick = { onFeedback(node, "negative") })
            }
        }
    }
}

@Composable
private fun FeedbackButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable { onClick() }
            .semantics { contentDescription = if (label == "👍") "反馈赞" else "反馈踩" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun ReasoningBlock(node: TranscriptNode) {
    // 对齐 web ReasoningRow：默认折叠（useState(false)）；摘要流式中取最后一行、完成取第一行；
    // 折叠行 = 🧠 思考 · 摘要（ellipsis）+ chevron；流式中右缘扫光动画；点击整行展开。
    var expanded by remember(node.key + node.reasoning.length) { mutableStateOf(node.streaming) }
    val lines = node.reasoning.trim().lineSequence().filter { it.isNotBlank() }.toList()
    val summary = if (node.streaming) lines.lastOrNull() ?: "" else lines.firstOrNull() ?: ""
    val foldSummary = summary.take(36) + if (summary.length > 36) "…" else ""

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 思考", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Text("·", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    if (!expanded) foldSummary else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 流式扫光动效（对齐 web sweep animation：右缘渐变条）
                if (node.streaming) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(6.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), Color.Transparent),
                                ),
                            )
                    )
                }
                Text(if (expanded) "▴" else "▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(
                    node.reasoning,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(node: TranscriptNode, onToolClick: (TranscriptNode) -> Unit) {
    // 运行中工具的运行时长（长命令时用户才知道任务还活着）
    val startedAt = node.meta["startedAt"]?.toLongOrNull() ?: 0L
    var elapsedSec by remember(node.key) { mutableStateOf(0L) }
    val isRunningNow = node.toolStatus.contains("运行中")
    // 折叠状态（对齐 dsh web）：**运行中默认展开**看实时进度，**结束后默认折叠**成一行摘要；
    // 点击卡片切换展开/收起（详情弹窗移到右上角 ⓘ）。
    var expanded by remember(node.key) { mutableStateOf(isRunningNow) }
    // 计时只在**确实运行中**时推进；`isRunningNow` 作为 key 的一部分，
    // 状态一旦离开"运行中"该协程立即被取消（此前只 key 在 node.key 上，节点状态没归并时会一直计时）。
    if (isRunningNow && startedAt > 0) {
        LaunchedEffect(node.key, isRunningNow) {
            while (isRunningNow) {
                elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val failed = node.toolStatus.contains("失败")
            val running = node.toolStatus.contains("运行中") || node.toolStatus.contains("等待")
            Text(
                text = "⚙ ${node.toolName.ifEmpty { node.toolStatus }}",
                modifier = Modifier.weight(1f),
                color = when {
                    failed -> MaterialTheme.colorScheme.error
                    running -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            // 状态胶囊：失败=错误底色，运行中=主色底 + 脉动点 + 计时，其余中性
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    val t = rememberInfiniteTransition()
                    val a by t.animateFloat(
                        initialValue = 1f, targetValue = 0.25f,
                        animationSpec = infiniteRepeatable(
                            tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            RepeatMode.Reverse,
                        ),
                    )
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = a), CircleShape),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (isRunningNow && elapsedSec > 0) "${node.toolStatus} · ${elapsedSec}s" else node.toolStatus,
                    color = when {
                        failed -> MaterialTheme.colorScheme.onErrorContainer
                        running -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(
                            when {
                                failed -> MaterialTheme.colorScheme.errorContainer
                                running -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                // 点击卡片已改为折叠/展开，详情弹窗改由此入口进入
                Text(
                    "详情",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onToolClick(node) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    if (expanded) "▴" else "▾",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (!expanded) {
            // 折叠态（对齐 dsh web）：只留**一行摘要**，历史里的工具调用不再占满屏幕
            val summary = node.toolArgs.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        } else {
            if (node.toolArgs.isNotEmpty()) {
                CollapsibleMono(
                    text = node.toolArgs,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    maxLines = 6,
                )
            }
            if (node.toolResult.isNotEmpty()) {
                CollapsibleMono(
                    text = node.toolResult,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    maxLines = 8,
                )
            }
        }
    }
}

/**
 * 等宽折叠文本：渲染后超出 maxLines 时折叠，显示"展开全部/收起"。
 * 用 `hasVisualOverflow` 判断（按**换行后**的实际行数），
 * 否则单行超长 JSON（逻辑 1 行、视觉 20 行）不会被折叠。
 */
@Composable
private fun CollapsibleMono(text: String, modifier: Modifier = Modifier, maxLines: Int = 6) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                if (expanded) "收起" else "展开全部",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun StatusChip(node: TranscriptNode) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            text = (if (node.kind == NodeKind.ERROR) "⚠ " else "") + node.text,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (node.kind == NodeKind.ERROR) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 12.sp,
        )
    }
}

/** Hero（新建会话）里的选择胶囊：对齐 web 只在 hero 相位的 workspace/preset 选择。 */
@Composable
private fun HeroChoice(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text("▾", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/**
 * 待应答交互的**紧凑记录行**（替代原先常驻的大卡片）：
 * 对话流里只占一行，点它重新打开底部浮层处理。
 */
@Composable
private fun PendingRecordRow(pe: SessionStreamController.PendingEvent, onOpen: () -> Unit) {
    val req = pe.request
    val isApproval = pe.event == "approval/request"
    val tool = req.optString("toolName").ifEmpty { req.optString("tool") }
    val label = if (isApproval) {
        "⏳ 等待你确认" + if (tool.isNotEmpty()) "：$tool" else ""
    } else {
        "⏳ 等待你选择"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(10.dp))
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Spacer(Modifier.weight(1f))
        Text("处理", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary)
    }
}