package dev.dsh.host

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话流控制器：把 session/follow 的 WS 帧转成 UI 状态。
 *
 * 状态对外暴露：
 *  - nodes: StateFlow<List<TranscriptNode>>（每次实质性变化发新列表，UI 按 key 重组）
 *  - running: StateFlow<Boolean>
 *  - cursor: StateFlow<Int>
 *  - connectionState: StateFlow<ConnectionState>（连接/重连中/断开/空闲）
 *
 * 断线重连：engine 崩溃 → stream 断开 → 触发 onAuthLost → 上层重新 authenticate 后
 * 调 reconnect()；重连后 follow 会带 snapshot（含 cursor 与 records），
 * 我们按 seq 水位对账（快照 records 已展示的不重复渲染）。
 */
class SessionStreamController(
    private val scope: CoroutineScope,
    private val wsBase: String,
    cookieProvider: () -> String?,
    private val api: DshApiClient,
    private val onAuthLost: () -> Unit = {},
) {

    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING, AUTH_LOST }

    private val stream = DshStreamClient(wsBase, cookieProvider)
    private val builder = TranscriptBuilder()
    private val timeline = Timeline()

    // 注意：switchSession 必须同时 timeline.clear() + builder.clear()，
    // 两者状态互为镜像，只清一个会造成 chunk 计数/工具映射跨会话残留。

    private val _nodes = MutableStateFlow<List<TranscriptNode>>(emptyList())
    val nodes: StateFlow<List<TranscriptNode>> = _nodes.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 本轮开始时间（TurnStatus 计时；未运行=0）。 */
    private val _runningSince = MutableStateFlow(0L)
    val runningSince: StateFlow<Long> = _runningSince.asStateFlow()

    /** 上一轮是否被中止（显示「已停止」胶囊）。 */
    private val _aborted = MutableStateFlow(false)
    val aborted: StateFlow<Boolean> = _aborted.asStateFlow()

    private val _cursor = MutableStateFlow(-1)
    val cursor: StateFlow<Int> = _cursor.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.IDLE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    /** 轨迹行（从事件流派生，增量）。 */
    private val _trajectory = MutableStateFlow<List<TrajectoryRow>>(emptyList())
    val trajectory: StateFlow<List<TrajectoryRow>> = _trajectory.asStateFlow()
    private val trajectoryItems = mutableListOf<TrajectoryRow>()
    private val trajectorySeen = HashSet<String>()
    /** 轨迹行的 item key 集合（LazyColumn 重复 key 会崩溃，用于兜底去重）。 */
    private val trajectoryKeys = HashSet<String>()

    /** 当前会话的真实模型/强度/权限（来自会话事件，供输入栏芯片显示）。 */
    private val _provider = MutableStateFlow("")
    private val _model = MutableStateFlow("")
    private val _effort = MutableStateFlow("")
    private val _permission = MutableStateFlow("")
    val provider: StateFlow<String> get() = _provider
    val model: StateFlow<String> get() = _model
    val effort: StateFlow<String> get() = _effort
    val permission: StateFlow<String> get() = _permission
    private val stepTimes = HashMap<String, Long>()      // "turn:step" -> start time
    private val callTimes = HashMap<String, Long>()      // callId -> start time

    /** 会话统计（sessionStats 语义：turns/steps/llmMs/toolMs/ttft/tps 客户端累计）。 */
    data class SessionStats(
        val turns: Int = 0,
        val steps: Int = 0,
        val llmMs: Long = 0,
        val toolMs: Long = 0,
        val ttftMs: Long = 0,
        val ttftSteps: Int = 0,
        val decodeMs: Long = 0,
        val decodeTokens: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheRead: Long = 0,
        val cacheWrite: Long = 0,
    )
    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()
    private var lastStepTime = 0L
    private var lastTurn = -1
    private val stepStartTimes = HashMap<String, Long>()
    private val decodeStartTimes = HashMap<String, Long>()
    private val firstChunkSeen = HashSet<String>()

    /** 按轮统计（web TurnUsage/TurnTime 面板）。 */
    data class TurnStat(
        val turn: Int,
        val startedAt: Long = 0,
        val endedAt: Long = 0,
        val llmMs: Long = 0,
        val toolMs: Long = 0,
        val ttftMs: Long = 0,
        val ttftCount: Int = 0,
        val decodeMs: Long = 0,
        val decodeTokens: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val reasoningTokens: Long = 0,
        val cacheRead: Long = 0,
        val cacheWrite: Long = 0,
        val provider: String = "",
        val model: String = "",
        val contextWindow: Long = 0,
    ) {
        val durationMs: Long get() = if (endedAt > 0 && startedAt > 0) endedAt - startedAt else 0
        val cacheHitPercent: Double
            get() {
                val total = inputTokens + cacheRead
                return if (total > 0) cacheRead.toDouble() / total * 100 else 0.0
            }
        val tps: Double get() = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else 0.0
    }

    private val _turnStats = MutableStateFlow<Map<Int, TurnStat>>(emptyMap())
    val turnStats: StateFlow<Map<Int, TurnStat>> = _turnStats.asStateFlow()

    /**
     * 挂起的 Host 瀑布事件（approval/request | user-questions/request）。
     * 契约（引擎源码 dsh-api-gateway parseRemoteEventResult）：
     *   clientId 只在 `$events` 的 ready 帧下发（waterfall 帧不带！），
     *   outcome = {kind:"result", value:<approval 词汇字符串 | {answers:[…]}>}。
     */
    data class PendingEvent(
        val clientId: String,
        val eventId: String,
        val event: String,          // approval/request | user-questions/request
        val request: JSONObject,
    )
    private val _pendingEvent = MutableStateFlow<PendingEvent?>(null)
    val pendingEvent: StateFlow<PendingEvent?> = _pendingEvent.asStateFlow()

    /** $events ready 帧下发的 client id（应答鉴权必需）。 */
    @Volatile private var eventsClientId: String = ""

    /** 最近一次操作失败原因（界面消费后清空，用于提示用户而非静默）。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun clearError() { _lastError.value = null }

    /** 最近一次请求的 provider/model/上下文窗口（ContextMeter 用）。 */
    private val _requestMeta = MutableStateFlow(Triple("", "", 0L))
    val requestMeta: StateFlow<Triple<String, String, Long>> = _requestMeta.asStateFlow()
    private var lastProvider = ""
    private var lastModel = ""
    private var lastContextWindow = 0L

    // 无锁的 read-modify-write 会在"流线程写统计"与"切会话清空"之间互相覆盖
    // （表现为某一轮的用量数字凭空消失）→ 纳入 stateLock。
    private fun updateTurn(turn: Int, f: (TurnStat) -> TurnStat) = synchronized(stateLock) {
        if (turn < 0) return@synchronized
        val cur = _turnStats.value.toMutableMap()
        val prev = cur[turn] ?: TurnStat(turn)
        cur[turn] = f(prev)
        _turnStats.value = cur
        _requestMeta.value = Triple(lastProvider, lastModel, lastContextWindow)
    }

    @Volatile private var followStreamId: String? = null
    private val following = AtomicBoolean(false)
    private val backoff = java.util.concurrent.atomic.AtomicLong(3000)
    private var reconnectJob: Job? = null

    /** 会话代际：切换会话时递增，旧流的回调到达后直接丢弃。 */
    private val sessionGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** 打开 follow 流（幂等）。sessionId 从 api 层读取。 */
    fun startFollow(sessionId: String) {
        if (following.get()) return
        following.set(true)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            while (following.get() && sessionIdRef == sessionId) {
                val ok = openFollow(sessionId)
                if (ok) return@launch
                _connection.value = ConnectionState.RECONNECTING
                delay(backoff.get())
            }
        }
        // $events 瀑布流（审批/提问）——全程开启
        if (eventsStreamId == null) {
            eventsStreamId = stream.open("\$events", JSONObject(), object : DshStreamClient.Listener {

                override fun onFrame(streamId: String, frame: JSONObject) {
                    val type = frame.optString("type")
                    if (type == "item") {
                        val value = frame.optJSONObject("value") ?: return
                        when (value.optString("type")) {
                            "waterfall" -> {
                                // 帧结构：{type,event,eventId,agentId,request}（不含 clientId）
                                val event = value.optString("event")
                                val eventId = value.optString("eventId")
                                val request = value.optJSONObject("request") ?: JSONObject()
                                val clientId = eventsClientId
                                if (clientId.isEmpty()) {
                                    Log.e(TAG, "waterfall $eventId arrived before \$events ready — cannot answer")
                                }
                                // 去重：同一 eventId 重复投递不重建（避免弹窗抖动）
                                if (_pendingEvent.value?.eventId != eventId) {
                                    Log.d(TAG, "waterfall: event=$event eventId=$eventId clientId=${clientId.take(8)}…")
                                    _pendingEvent.value = PendingEvent(clientId, eventId, event, request)
                                }
                            }
                            "ready" -> {
                                eventsClientId = value.optString("clientId")
                                Log.d(TAG, "\$events stream ready clientId=${eventsClientId.take(8)}…")
                            }
                            "cancel" -> {
                                if (value.optString("eventId") == _pendingEvent.value?.eventId) {
                                    Log.d(TAG, "waterfall cancelled by host")
                                    _pendingEvent.value = null
                                }
                            }
                        }
                    }
                }
                override fun onClosed(streamId: String, reason: String?) {
                    Log.w(TAG, "\$events closed: $reason")
                    synchronized(this@SessionStreamController) { eventsStreamId = null }
                }
            })
        }
        // control 流（队列/jobs/projection）——会话级，切换时重开
        synchronized(this@SessionStreamController) { controlStreamId = null }
        _queue.value = emptyList()
        startControl()
    }

    @Volatile private var eventsStreamId: String? = null
    @Volatile private var controlStreamId: String? = null

    /** 队列与 jobs（session/control 流）。 */
    private val _queue = MutableStateFlow<List<JSONObject>>(emptyList()) // {id, placement, message}
    val queue: StateFlow<List<JSONObject>> = _queue.asStateFlow()

    /** 待办（todos 投影：{content,status} 列表）与目标（goal 投影）。 */
    private val _todos = MutableStateFlow<List<JSONObject>>(emptyList())
    val todos: StateFlow<List<JSONObject>> = _todos.asStateFlow()
    private val _goal = MutableStateFlow<JSONObject?>(null)
    val goal: StateFlow<JSONObject?> = _goal.asStateFlow()

    /** 打开 control 流（session/control：baseline + queue/jobs/projection 帧）。 */
    private fun startControl() {
        if (controlStreamId != null) return
        controlStreamId = stream.open("session/control", JSONObject(), object : DshStreamClient.Listener {
            override fun onFrame(streamId: String, frame: JSONObject) {
                if (frame.optString("type") != "item") return
                val value = frame.optJSONObject("value") ?: return
                when (value.optString("type")) {
                    "baseline" -> {
                        val queues = value.optJSONObject("queues") ?: return
                        val items = queues.optJSONArray(sessionIdRef) ?: JSONArray()
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until items.length()) {
                            val it = items.optJSONObject(i) ?: continue
                            val msg = it.optJSONObject("message")
                            if (msg != null) list.add(msg.optJSONObject("id")?.let { it } ?: it)
                        }
                        _queue.value = list
                    }
                    "queue" -> {
                        val sid = value.optString("sessionId")
                        if (sid != sessionIdRef) return
                        val items = value.optJSONArray("items") ?: JSONArray()
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until items.length()) {
                            val it = items.optJSONObject(i) ?: continue
                            list.add(it)
                        }
                        _queue.value = list
                    }
                    "projection" -> {
                        val sid = value.optString("sessionId")
                        if (sid != sessionIdRef) return
                        val key = value.optString("key")
                        val v = value.opt("value")
                        when (key) {
                            "todos" -> {
                                val arr = (v as? JSONArray) ?: return
                                val list = mutableListOf<JSONObject>()
                                for (i in 0 until arr.length()) {
                                    val it = arr.optJSONObject(i) ?: continue
                                    list.add(it)
                                }
                                _todos.value = list
                            }
                            "goal" -> _goal.value = v as? JSONObject
                        }
                    }
                }
            }
            override fun onClosed(streamId: String, reason: String?) {
                synchronized(this@SessionStreamController) { controlStreamId = null }
            }
        })
    }

    /**
     * 应答审批：outcome value 必须是 ApprovalOutcome 词汇字符串
     * （"allowed-once" | "rejected" | "cancelled"），见 dsh-user-approval OUTCOMES。
     */
    fun respondApproval(outcome: String) {
        val v = when (outcome) {
            "allowed-once", "rejected", "cancelled" -> outcome
            else -> "rejected"
        }
        sendEventResult(v)
    }

    /**
     * 应答用户提问：outcome value = {answers:[{id, selected:[label], custom?}]}
     * （见 dsh-tool-ask-user 的 output schema）。
     */
    fun respondQuestions(answers: JSONArray) {
        sendEventResult(JSONObject().put("answers", answers))
    }

    /** 通用应答：value 为任意合法 JSON（approval 用字符串，questions 用对象）。 */
    private fun sendEventResult(value: Any?) {
        val pending = _pendingEvent.value ?: run {
            Log.w(TAG, "respond: no pending event")
            return
        }
        if (pending.clientId.isEmpty()) {
            Log.e(TAG, "respond: missing \$events clientId — 应答会被网关拒绝（invalid Remote event result）")
        }
        scope.launch(Dispatchers.IO) {
            val outcome = JSONObject().put("kind", "result").apply { if (value != null) put("value", value) }
            runCatching {
                api.eventsResult(pending.clientId, pending.eventId, outcome)
            }.onSuccess {
                if (_pendingEvent.value?.eventId == pending.eventId) _pendingEvent.value = null
                Log.d(TAG, "waterfall answered ${pending.event} ${pending.eventId} (${value.toString().length}B)")
            }.onFailure {
                // 失败时清掉挂起，避免 UI 永久锁定（用户可重新触发）
                if (_pendingEvent.value?.eventId == pending.eventId) _pendingEvent.value = null
                Log.e(TAG, "waterfall respond failed: ${it.message}")
                _lastError.value = "应答失败：${it.message ?: "网关拒绝"}"
            }
        }
    }

    /** 安全逃生：取消当前挂起瀑布（审批 → cancelled；提问 → 空答案集）。 */
    fun cancelPendingEvent() {
        val pending = _pendingEvent.value ?: return
        if (pending.event == "approval/request") respondApproval("cancelled")
        else respondQuestions(JSONArray())
    }

    private fun openFollow(sessionId: String): Boolean {
        val generation = sessionGeneration.get()
        _connection.value = if (_connection.value == ConnectionState.CONNECTED) ConnectionState.CONNECTED else ConnectionState.CONNECTING
        val sid = stream.open(
            "session/follow",
            JSONObject().put("request", JSONObject().put("address",
                JSONObject().put("kind", "session").put("sessionId", sessionId))),
            object : DshStreamClient.Listener {
                override fun onFrame(streamId: String, frame: JSONObject) {
                    Log.d(TAG, "follow onFrame gen=$generation cur=${sessionGeneration.get()} type=${frame.optString("type")}")
                    if (generation != sessionGeneration.get()) return // 旧会话代际，丢弃
                    when (frame.optString("type")) {
                        "item" -> {
                            _connection.value = ConnectionState.CONNECTED
                            val value = frame.optJSONObject("value") ?: return
                            Log.d(TAG, "item: ${value.optString("type")}")
                            when (value.optString("type")) {
                                "snapshot" -> applySnapshot(value)
                                "event" -> {
                                    val event = value.optJSONObject("event") ?: return
                                    applyEvent(event)
                                }
                            }
                        }
                        "end" -> {
                            Log.d(TAG, "follow end")
                            onStreamClosed(generation, false)
                        }
                        "error" -> {
                            Log.e(TAG, "follow error: ${frame.optString("error")}")
                            onStreamClosed(generation, false)
                        }
                    }
                }

                override fun onClosed(streamId: String, reason: String?) {
                    Log.w(TAG, "follow closed: $reason")
                    onStreamClosed(generation, true)
                }
            },
        ) ?: return false
        followStreamId = sid
        return true
    }

    private fun onStreamClosed(generation: Int, engineLikelyDead: Boolean) {
        if (generation != sessionGeneration.get()) return
        followStreamId = null
        if (!following.get()) return
        // 引擎崩溃场景：触达上层重新鉴权；否则自动重连（退避递增）
        if (engineLikelyDead) {
            backoff.set(3000)
            _connection.value = ConnectionState.AUTH_LOST
            onAuthLost()
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff.getAndSet((backoff.get() * 2).coerceAtMost(30_000)))
            if (following.get()) {
                val sid = sessionIdRef
                if (sid != null) startFollow(sid)
            }
        }
    }

    @Volatile private var sessionIdRef: String? = null

    /**
     * 共享可变状态的锁：Timeline 的 builder/Surface、统计用的 HashMap 都非线程安全。
     * 写入方是 OkHttp 流线程（applyEvent/applySnapshot），重置方是主线程（switchSession），
     * 两者并发会抛 ConcurrentModificationException 或产生错乱状态。
     */
    private val stateLock = Any()

    fun switchSession(sessionId: String) {
        sessionGeneration.incrementAndGet()
        sessionIdRef = sessionId
        stopFollow()
        followStreamId = null
        synchronized(stateLock) {
            timeline.clear()
            builder.clear()                       // 关键：重置 chunk 计数/工具映射，否则跨会话错乱
            stepStartTimes.clear(); decodeStartTimes.clear(); firstChunkSeen.clear()
            trajectoryItems.clear()
            trajectoryKeys.clear(); trajectorySeen.clear()
            stepTimes.clear(); callTimes.clear()
            lastStepTime = 0L
            lastTurn = -1
        }
        _nodes.value = emptyList()
        _running.value = false
        _cursor.value = -1
        _title.value = null                   // 切会话：标题/统计/瀑布全部清空
        _stats.value = SessionStats()
        _turnStats.value = emptyMap()
        _pendingEvent.value = null
        _todos.value = emptyList()
        _goal.value = null
        _trajectory.value = emptyList()
        backoff.set(3000)
        startFollow(sessionId)
    }

    fun stopFollow() {
        following.set(false)
        followStreamId?.let { stream.cancel(it) }
        followStreamId = null
        _connection.value = ConnectionState.IDLE
    }

    /**
     * 释放全部资源（Activity 销毁时调用）：
     * 停止跟随、取消 scope（停掉重连协程）、关闭 WebSocket、注销监听。
     * 不加这一步会让流与 OkHttp 线程在 Activity 销毁后继续存活（泄漏 + 无效回调）。
     */
    fun close() {
        Log.d(TAG, "controller close")
        following.set(false)
        sessionGeneration.incrementAndGet()   // 使在途回调失效
        runCatching { scope.cancel() }
        runCatching { stream.close() }
        followStreamId = null
        synchronized(this) { eventsStreamId = null; controlStreamId = null }
        _connection.value = ConnectionState.IDLE
    }

    // ---------------- 帧应用 ----------------

    private fun applySnapshot(snapshot: JSONObject) {
        val cursor = snapshot.optInt("cursor", -1)
        _cursor.value = cursor
        val records = snapshot.optJSONArray("records")
        Log.d(TAG, "snapshot cursor=$cursor records=${records?.length() ?: "null"} header=${snapshot.optJSONObject("header")?.optString("id")}")
        if (records != null) {
            builder.finalMode.set(true) // 历史回放：无流式光标
            try {
                var changed = false
                var appliedCount = 0
                for (i in 0 until records.length()) {
                    val rec = records.optJSONObject(i) ?: continue
                    val event = rec.optJSONObject("event") ?: continue
                    val c = applyEvent(event)
                    if (c) appliedCount++
                    changed = c || changed
                }
                Log.d(TAG, "snapshot applied=$appliedCount nodes=${_nodes.value.size}")
                publishIfChanged(changed)
                // 后台预热 Markdown 解析缓存：长消息（10KB+）在组合期同步解析会掉帧
                val longTexts = _nodes.value.asSequence()
                    .filter { it.text.length >= 2000 }
                    .map { it.text }
                    .toList()
                if (longTexts.isNotEmpty()) {
                    scope.launch(Dispatchers.Default) { warmMarkdownCache(longTexts) }
                }
            } finally {
                builder.finalMode.set(false)
            }
        }
        val header = snapshot.optJSONObject("header")
        if (header != null && _title.value == null && header.has("title")) {
            header.optString("title").ifEmpty { null }?.let { _title.value = it }
        }
    }

    private fun applyEvent(event: JSONObject): Boolean = synchronized(stateLock) {
        val changed = timeline.applyEvent(event, builder)
        // 同步"当前会话真实状态"到输入栏芯片（web：composer 的 access mode / model 选择器显示当前值）。
        // 引擎把这些作为会话事件下发，直接采用即可；没有对应的查询 RPC（已实测 5 个候选名均 not found）。
        when (event.optString("type")) {
            "model/selection" -> {
                val d = event.optJSONObject("data") ?: JSONObject()
                d.optString("provider").takeIf { it.isNotEmpty() }?.let { _provider.value = it }
                d.optString("model").takeIf { it.isNotEmpty() }?.let { _model.value = it }
                _effort.value = d.optString("reasoningEffort")   // 允许为空 = 用该模型默认档
            }
            "permission/preset" -> {
                val d = event.optJSONObject("data") ?: JSONObject()
                d.optString("preset").takeIf { it.isNotEmpty() }?.let { _permission.value = it }
            }
        }
        if (changed) {
            // 流式增量（chunk）：**合并发布**，每 ~100ms 一次。
            // 每个 chunk 都发布会让长回复（实测 10KB+）每帧重新布局整段文本 → 掉帧。
            // 定稿事件（assistant/message、turn/end 等）不节流，保证最终内容一定落地。
            if (event.optString("type").contains("chunk")) publishThrottled() else publishIfChanged(true)
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "applyEvent ${event.optString("type")} changed=$changed nodes=${_nodes.value.size}")
        }
        when (event.optString("type")) {
            "turn/start" -> {
                _running.value = true
                _runningSince.value = event.optLong("time", System.currentTimeMillis())
                _aborted.value = false
            }
            "turn/end" -> {
                _running.value = false
                _runningSince.value = 0L
                val kind = event.optJSONObject("data")?.optJSONObject("reason")?.optString("kind") ?: "completed"
                // 非 completed（aborted/cancelled/interrupted）→ 显示「已停止」胶囊
                _aborted.value = kind != "completed" && kind != "max-tokens"
            }
        }
        if (event.optString("type") == "session/title") {
            event.optJSONObject("data")?.optString("title")?.ifEmpty { null }?.let { _title.value = it }
        }
        if (event.optString("type") == "todo/write") {
            val content = event.optJSONObject("data")?.optString("content") ?: ""
            val status = event.optJSONObject("data")?.optString("status") ?: "pending"
            val list = _todos.value.toMutableList()
            val idx = list.indexOfFirst { it.optString("content") == content }
            if (idx >= 0) list[idx] = JSONObject().put("content", content).put("status", status)
            else list.add(JSONObject().put("content", content).put("status", status))
            _todos.value = list
        }
        if (event.optString("type") == "goal/change") {
            _goal.value = event.optJSONObject("data")
        }
        foldTrajectory(event)
        foldStats(event)
        return changed
    }

    /** 会话统计折叠（客户端计算，对齐 sessionStats 投影语义）。 */
    private fun foldStats(event: JSONObject) {
        val type = event.optString("type")
        val time = event.optLong("time", System.currentTimeMillis())
        val data = event.optJSONObject("data") ?: return
        val s = _stats.value
        when (type) {
            "turn/start" -> {
                val turn = data.optInt("turn")
                if (turn != lastTurn) {
                    _stats.value = s.copy(turns = s.turns + 1)
                    lastTurn = turn
                }
                updateTurn(turn) { it.copy(startedAt = time) }
            }
            "turn/end" -> {
                updateTurn(data.optInt("turn")) { it.copy(endedAt = time) }
            }
            "step/start" -> {
                lastStepTime = time
                stepStartTimes["${data.optInt("turn")}:${data.optInt("step")}"] = time
                firstChunkSeen.remove("${data.optInt("turn")}:${data.optInt("step")}")
            }
            "step/end" -> {
                if (lastStepTime > 0) {
                    _stats.value = _stats.value.copy(steps = _stats.value.steps + 1)
                    lastStepTime = 0
                }
                val key = "${data.optInt("turn")}:${data.optInt("step")}"
                val st = stepStartTimes.remove(key)
                if (st != null) {
                    updateTurn(data.optInt("turn")) { it.copy(llmMs = it.llmMs + (time - st).coerceAtLeast(0)) }
                }
            }
            "assistant/chunk" -> {
                // TTFT：本 step 首个 chunk
                val key = "${data.optInt("turn")}:${data.optInt("step")}"
                if (firstChunkSeen.add(key)) {
                    val st = stepStartTimes[key]
                    if (st != null) {
                        updateTurn(data.optInt("turn")) {
                            it.copy(ttftMs = it.ttftMs + (time - st).coerceAtLeast(0), ttftCount = it.ttftCount + 1)
                        }
                    }
                    decodeStartTimes[key] = time
                }
            }
            "assistant/message" -> {
                val usage = data.optJSONObject("usage")
                if (usage != null) {
                    val input = usage.optLong("inputTokens", 0)
                    val output = usage.optLong("outputTokens", 0)
                    val cr = usage.optLong("cacheReadTokens", 0)
                    val cw = usage.optLong("cacheWriteTokens", 0)
                    val reasoning = usage.optLong("reasoningTokens", 0)
                    _stats.value = _stats.value.copy(
                        inputTokens = _stats.value.inputTokens + input,
                        outputTokens = _stats.value.outputTokens + output,
                        cacheRead = _stats.value.cacheRead + cr,
                        cacheWrite = _stats.value.cacheWrite + cw,
                    )
                    val key = "${data.optInt("turn")}:${data.optInt("step")}"
                    val ds = decodeStartTimes.remove(key)
                    val decodeMs = if (ds != null) (time - ds).coerceAtLeast(0) else 0L
                    updateTurn(data.optInt("turn")) {
                        it.copy(
                            inputTokens = it.inputTokens + input,
                            outputTokens = it.outputTokens + output,
                            reasoningTokens = it.reasoningTokens + reasoning,
                            cacheRead = it.cacheRead + cr,
                            cacheWrite = it.cacheWrite + cw,
                            decodeMs = it.decodeMs + decodeMs,
                            decodeTokens = it.decodeTokens + output,
                        )
                    }
                }
            }
            "request/context" -> {
                val cw = data.optLong("contextWindow", 0)
                val prov = data.optString("provider")
                val model = data.optString("model")
                lastProvider = prov
                lastModel = model
                lastContextWindow = cw
                // 补写到当前轮
                val t = lastTurn
                if (t >= 0) updateTurn(t) { it.copy(provider = prov, model = model, contextWindow = cw) }
            }
            "request/header" -> {
                // 请求头里带 provider/model（request/context 只在变化时发，这里兜底）
                val cfg = data.optJSONObject("header")?.optJSONObject("config")
                val prov = cfg?.optString("provider").orEmpty()
                val model = cfg?.optString("model").orEmpty()
                if (prov.isNotEmpty()) lastProvider = prov
                if (model.isNotEmpty()) lastModel = model
                val t = lastTurn
                if (t >= 0 && (prov.isNotEmpty() || model.isNotEmpty())) {
                    updateTurn(t) {
                        it.copy(
                            provider = prov.ifEmpty { it.provider },
                            model = model.ifEmpty { it.model },
                            contextWindow = if (lastContextWindow > 0) lastContextWindow else it.contextWindow,
                        )
                    }
                }
            }
            "model/selection" -> {
                val prov = data.optString("provider").orEmpty()
                val model = data.optString("model").orEmpty()
                if (prov.isNotEmpty()) lastProvider = prov
                if (model.isNotEmpty()) lastModel = model
                val t = lastTurn
                if (t >= 0) {
                    updateTurn(t) {
                        it.copy(
                            provider = prov.ifEmpty { it.provider },
                            model = model.ifEmpty { it.model },
                            contextWindow = if (lastContextWindow > 0) lastContextWindow else it.contextWindow,
                        )
                    }
                }
            }
            "tool/call" -> {
                val callTime = callTimes.putIfAbsent(data.optString("callId"), time)
            }
            "tool/result" -> {
                val start = callTimes.remove(data.optString("callId"))
                if (start != null) {
                    val dur = (time - start).coerceAtLeast(0)
                    _stats.value = _stats.value.copy(toolMs = _stats.value.toolMs + dur)
                    updateTurn(data.optInt("turn", lastTurn)) { it.copy(toolMs = it.toolMs + dur) }
                }
            }
        }
    }

    /** 从事件流折叠轨迹行（增量，按 seq/type 去重）。 */
    private fun foldTrajectory(event: JSONObject) {
        val type = event.optString("type")
        val seq = event.optInt("seq", -1)
        val data = event.optJSONObject("data") ?: JSONObject()
        val key = "$type:$seq"
        if (seq < 0 || !trajectorySeen.add(key)) return

        val turn = data.optInt("turn", -1)
        val step = data.optInt("step", -1)
        val time = event.optLong("time", System.currentTimeMillis())

        fun addRow(row: TrajectoryRow) {
            // 轨迹行也用 key 作 LazyColumn item key：重复 key 会崩溃。
            // 正常情况 `"$type:$seq"` 唯一，但事件缺 seq 时会回落成 `-1` → 可能重复，故统一兜底。
            var r = row
            if (!trajectoryKeys.add(r.key)) {
                var i = 2
                var nk = "${r.key}#$i"
                while (!trajectoryKeys.add(nk)) { i++; nk = "${r.key}#$i" }
                Log.e(TAG, "duplicate trajectory key ${r.key} → $nk (would crash LazyColumn)")
                r = r.copy(key = nk)
            }
            trajectoryItems.add(r)
            _trajectory.value = trajectoryItems.toList()
        }

        when (type) {
            "turn/start" -> {
                stepTimes.clear()
                addRow(TrajectoryRow(
                    key = key, kind = TrajectoryRow.RowKind.STEP, turn = data.optInt("turn"),
                    step = -1, title = "第 ${data.optInt("turn")} 轮",
                ))
            }
            "step/start" -> {
                stepTimes["$turn:$step"] = time
                addRow(TrajectoryRow(
                    key = key, kind = TrajectoryRow.RowKind.STEP, turn = turn, step = step,
                    title = "Step $step", status = TrajectoryRow.RowStatus.RUNNING,
                ))
            }
            "step/end" -> {
                // 更新对应 step 行
                val idx = trajectoryItems.indexOfLast { it.kind == TrajectoryRow.RowKind.STEP && it.turn == turn && it.step == step }
                if (idx >= 0) {
                    val start = stepTimes["$turn:$step"] ?: time
                    trajectoryItems[idx] = trajectoryItems[idx].copy(
                        durationMs = (time - start).coerceAtLeast(0),
                        status = TrajectoryRow.RowStatus.DONE,
                    )
                    _trajectory.value = trajectoryItems.toList()
                }
            }
            "user/message" -> {
                val text = contentTextOf(data)
                addRow(TrajectoryRow(
                    key = key, kind = TrajectoryRow.RowKind.USER, turn = turn, step = step,
                    title = text.take(60) + if (text.length > 60) "…" else "",
                    detail = text.take(200),
                ))
            }
            "assistant/message" -> {
                val msg = data.optJSONObject("message") ?: data
                val text = contentTextOf(msg)
                if (text.isNotEmpty()) addRow(TrajectoryRow(
                    key = key, kind = TrajectoryRow.RowKind.ASSISTANT, turn = turn, step = step,
                    title = text.take(60) + if (text.length > 60) "…" else "",
                    detail = text.take(200),
                ))
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                callTimes[callId] = time
                addRow(TrajectoryRow(
                    key = key, kind = TrajectoryRow.RowKind.TOOL, turn = turn, step = step,
                    title = data.optString("name", "工具"),
                    detail = data.optString("arguments", "").take(200),
                    status = TrajectoryRow.RowStatus.RUNNING,
                    callId = callId,
                ))
            }
            "tool/result" -> {
                val callId = data.optString("callId")
                val idx = trajectoryItems.indexOfLast { it.key.startsWith("tool/call:") && it.title.isNotEmpty() }
                // 简化：更新最后一个 tool 行（结果行紧随调用行)
                val isError = data.optBoolean("isError", false)
                val lastTool = trajectoryItems.indexOfLast { it.kind == TrajectoryRow.RowKind.TOOL }
                if (lastTool >= 0) {
                    val start = callTimes[callId] ?: time
                    trajectoryItems[lastTool] = trajectoryItems[lastTool].copy(
                        durationMs = (time - start).coerceAtLeast(0),
                        status = if (isError) TrajectoryRow.RowStatus.FAILED else TrajectoryRow.RowStatus.DONE,
                    )
                    _trajectory.value = trajectoryItems.toList()
                }
            }
            "request/header" -> {
                addRow(TrajectoryRow(
                    key = key, kind = TrajectoryRow.RowKind.SYSTEM, turn = turn, step = step,
                    title = "请求头",
                ))
            }
            "compaction/start" -> addRow(TrajectoryRow(
                key = key, kind = TrajectoryRow.RowKind.COMPACTION, turn = turn, step = step,
                title = "正在压缩上下文…",
            ))
            "compaction/end" -> addRow(TrajectoryRow(
                key = key, kind = TrajectoryRow.RowKind.COMPACTION, turn = turn, step = step,
                title = "上下文已压缩",
            ))
            "approval/asked" -> addRow(TrajectoryRow(
                key = key, kind = TrajectoryRow.RowKind.SYSTEM, turn = turn, step = step,
                title = "等待审批", status = TrajectoryRow.RowStatus.WAITING,
            ))
            "llm/retry" -> addRow(TrajectoryRow(
                key = key, kind = TrajectoryRow.RowKind.ERROR, turn = turn, step = step,
                title = "LLM 重试", status = TrajectoryRow.RowStatus.RUNNING,
            ))
        }
    }

    private fun contentTextOf(message: JSONObject): String {
        val content = message.optJSONArray("content") ?: return ""
        return buildString {
            for (k in 0 until content.length()) {
                val b = content.optJSONObject(k) ?: continue
                when (b.optString("type")) {
                    "text" -> append(b.optString("text"))
                    "tool-call" -> append("[工具 ${b.optString("name")}]")
                    "tool-result" -> append("[工具结果]")
                }
            }
        }
    }

    @Synchronized
    // 发布统一在 stateLock 内取快照：builder.snapshot() 只是 entries.map{...}，
    // 若与另一线程的 entries.add/removeAll 重叠会抛 ConcurrentModificationException
    // （异常发生在 WS 回调线程 → 三条流一起死）。
    private fun publishIfChanged(changed: Boolean) {
        if (!changed) return
        synchronized(stateLock) { _nodes.value = dedupeKeys(builder.snapshot()) }
    }

    /**
     * 兜底：LazyColumn 用 node.key 作 item key，**重复 key 会直接抛异常崩溃**。
     * 正常路径已保证唯一（如正文/推理 chunk 的 key 带类型前缀），这里对任何来源再做一次防御，
     * 发现重复则改为追加序号并记录错误日志（把潜在崩溃降级为可观测的降级行为）。
     */
    private fun dedupeKeys(nodes: List<TranscriptNode>): List<TranscriptNode> {
        val seen = HashSet<String>(nodes.size)
        var fixed = 0
        val out = ArrayList<TranscriptNode>(nodes.size)
        for (n in nodes) {
            if (seen.add(n.key)) {
                out.add(n)
            } else {
                var i = 2
                var nk = "${n.key}#$i"
                while (!seen.add(nk)) { i++; nk = "${n.key}#$i" }
                fixed++
                out.add(n.copy(key = nk))
            }
        }
        if (fixed > 0) Log.e(TAG, "duplicate node keys fixed: $fixed (would crash LazyColumn)")
        return out
    }

    /** 流式发布节流：距上次发布 < 100ms 则跳过（内容仍在 builder 中累积，下次发布带出）。 */
    // 多个 WS 回调线程并发调用（follow/control/$events 各自的 OkHttp 线程）→ 必须原子
    private val lastStreamPublish = java.util.concurrent.atomic.AtomicLong(0L)
    private fun publishThrottled() {
        val now = System.currentTimeMillis()
        val last = lastStreamPublish.get()
        if (now - last < 100) return
        // CAS 失败说明另一线程刚发布过（更晚的快照已落地）→ 直接跳过，避免旧快照后写覆盖新内容
        if (!lastStreamPublish.compareAndSet(last, now)) return
        synchronized(stateLock) { _nodes.value = dedupeKeys(builder.snapshot()) }
    }

    /** 历史回补（重连后按 cursor 对账：未展示层面）：通常无需，因为这层已处理 seq。 */
    fun backfill(sessionId: String, maxMessages: Int = 200) {
        scope.launch(Dispatchers.IO) {
            try {
                val cursor = api.resolveCursor(sessionId)
                if (cursor <= _cursor.value) return@launch
                val page = api.page(sessionId, cursor, maxMessages)
                val records = page.optJSONArray("records") ?: return@launch
                var changed = false
                for (i in 0 until records.length()) {
                    val rec = records.optJSONObject(i) ?: continue
                    val event = rec.optJSONObject("event") ?: continue
                    changed = applyEvent(event) || changed
                }
                _cursor.value = cursor
                publishIfChanged(changed)
            } catch (e: Exception) {
                Log.e(TAG, "backfill failed", e)
            }
        }
    }

    companion object {
        const val TAG = "SessionStream"
    }
}
