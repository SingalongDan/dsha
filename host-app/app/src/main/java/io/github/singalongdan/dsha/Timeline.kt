package io.github.singalongdan.dsha

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话时间线折叠引擎（移植 web 端 LocationTimeline 语义）：
 *   turns[]：按 firstSeq 排序，不可变引用（未受影响的 turn 原样保留）
 *   each turn: {start, end, status, steps[], data}
 *   each step: {start, end, status, data}
 *   node data: 渲染节点（text/reasoning/tool/...）-由 TranscriptNode 承载
 *
 * 用法：feedFollow帧 -> applySnapshot() / applyEvent()；UI 侧按 turn.key 比较引用
 *
 * 线程约定：单写（Socket 线程串行投递）+ 单读（UI 通过 StateFlow<版本> 读取），
 * 变更时 bump version()。
 */
class Timeline {

    class Step(val turn: Int, val step: Int, var start: JSONObject?, var end: JSONObject?, val key: String = "$turn:$step")

    class Turn(
        val turn: Int,
        val firstSeq: Int,
        var start: JSONObject?,
        var end: JSONObject?,
        val steps: MutableList<Step>,
        val key: String = "turn:$turn",
    ) {
        val status: String get() = if (end != null) "closed" else if (start != null) "open" else "unknown"
    }

    private val turnMap = LinkedHashMap<Int, Turn>()
    private var order = mutableListOf<Int>()
    private var currentTurn: Int? = null
    private var currentStep: Int? = null
    private val versionCounter = AtomicInteger(0)

    /** 可见节点（按 seq 顺序排布），由 TranscriptBuilder 填充 */
    val nodes = mutableListOf<TranscriptNode>()

    fun version(): Int = versionCounter.get()

    @Synchronized fun bump() { versionCounter.incrementAndGet() }

    @Synchronized fun turns(): List<Turn> = order.mapNotNull { turnMap[it] }

    @Synchronized fun clear() {
        turnMap.clear(); order.clear(); nodes.clear()
        currentTurn = null; currentStep = null
        bump()
    }

    /**
     * 应用一条 follow 事件：更新 turn/step 坐标系 + 交给 TranscriptBuilder 产出节点。
     * @return 是否产生了 UI 可见变化（调用方用于触发重组）
     */
    @Synchronized
    fun applyEvent(event: JSONObject, builder: TranscriptBuilder): Boolean {
        val type = event.optString("type")
        val seq = event.optInt("seq", -1)
        val data = event.optJSONObject("data") ?: JSONObject()

        when (type) {
            "turn/start" -> { currentTurn = data.optInt("turn"); currentStep = null }
            "step/start" -> {
                currentTurn = data.optInt("turn")
                currentStep = data.optInt("step")
                ensureTurn(currentTurn!!, seq)
                ensureStep(currentTurn!!, currentStep!!)
            }
            "turn/end" -> {
                val t = currentTurn
                if (t != null) {
                    turnMap[t]?.end = event
                    currentTurn = null; currentStep = null
                }
            }
            "step/end" -> {
                val t = currentTurn; val s = currentStep
                if (t != null && s != null) {
                    turnMap[t]?.steps?.firstOrNull { it.step == s }?.end = event
                    currentStep = null
                }
            }
            else -> {
                // 普通事件：数据本身带 turn/step 坐标
                val turn = data.optInt("turn", -1)
                val step = data.optInt("step", -1)
                if (turn >= 0) {
                    ensureTurn(turn, seq)
                    if (step >= 0) ensureStep(turn, step)
                }
            }
        }
        return builder.append(event)
    }

    private fun ensureTurn(turn: Int, seq: Int): Turn {
        return turnMap.getOrPut(turn) {
            if (turnMap.isEmpty()) order = mutableListOf(turn)
            else {
                order = mutableListOf<Int>().apply { addAll(order); add(turn); sort() }
            }
            bump()
            Turn(turn, seq, null, null, mutableListOf())
        }
    }

    private fun ensureStep(turn: Int, step: Int): Step {
        val t = turnMap[turn] ?: error("turn $turn missing")
        return t.steps.firstOrNull { it.step == step } ?: Step(turn, step, null, null).also {
            t.steps.add(it)
            t.steps.sortBy { s -> s.step }
            bump()
        }
    }
}

/**
 * 渲染节点（不可变值类型）——UI 层每帧比较 equals/引用做重组。
 */
@androidx.compose.runtime.Immutable
data class TranscriptNode(
    val key: String,              // 稳定 key（工具 id / seq / 合成 key）
    val kind: NodeKind,
    val text: String = "",
    val toolName: String = "",
    val toolStatus: String = "",  // 运行中/完成/失败/等待
    val toolArgs: String = "",
    val toolResult: String = "",
    val reasoning: String = "",   // 推理全文（折叠）
    val turn: Int = -1,
    val step: Int = -1,
    val streaming: Boolean = false,
    val meta: Map<String, String> = emptyMap(),
    // 折叠行（对齐 web DisclosureRow：上下文注入 / 压缩 / 重试 / 系统提示词）
    val disclosureTitle: String = "",
    val disclosureSummary: String = "",
    val body: String = "",
) {
    enum class NodeKind {
        USER, ASSISTANT, REASONING, TOOL, TODO, PLAN, GOAL, SUBAGENT, STATUS, ERROR, APPROVAL,
        /** 默认折叠的披露行（上下文注入/压缩/重试/系统提示词） */
        DISCLOSURE,
    }
}

/**
 * 节点装配器：把事件流折叠为 TranscriptNode 列表（增量，幂等）。
 * 关键语义：
 *  - surfaceOp=replace{start,end}：替换对应表面区间的节点（压缩后的会话必须这么做）
 *  - chunk 行（chunkrow/text-chunks 等）按 (turn,step,index) 计数，只追加新增碎片
 *  - 未打包的 assistant/chunk 按 seq 去重
 */
class TranscriptBuilder {

    /** 历史回放模式：为 true 时 chunk 创建的节点不显示流式光标（终稿语义）。 */
    val finalMode = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 渲染节点与表面区间维护。 */
    class Surface(private val builder: TranscriptBuilder) {
        val nodes = mutableListOf<TranscriptNode>()
        /** surfaceOp: append / replace{start,end}。nodes 里每个元素带 surfaceStart（首 seq）。 */
        private data class Entry(val node: TranscriptNode, val startSeq: Int)

        private val entries = mutableListOf<Entry>()
        private val seen = HashSet<Int>()
        private val chunkTextSeen = HashMap<String, Int>()
        private val chunkReasonSeen = HashMap<String, Int>()
        private val chunkArgsSeen = HashMap<String, Int>()
        private val toolById = HashMap<String, Int>()   // callId -> entries idx

        fun append(event: JSONObject): Boolean {
            val type = event.optString("type")
            val seq = event.optInt("seq", -1)
            val data = event.optJSONObject("data") ?: JSONObject()
            val surfaceOp = event.opt("surfaceOp")
            // 事件时间（运行中工具计时用；缺失则回落到当前时间）
            val eventTime = event.optLong("time", System.currentTimeMillis())

            // surface replace：先按 start/end 裁剪 entries 再当作 append 处理
            if (surfaceOp is JSONObject && surfaceOp.optString("op") == "replace") {
                val start = surfaceOp.optInt("start", -1)
                val end = surfaceOp.optInt("end", -1)
                if (start >= 0 && end >= start) replaceRange(start, end)
            }

            when (type) {
                "chunkrow/text-chunks" -> {
                    val key = "${data.optInt("turn")}:${data.optInt("step")}:${data.optInt("index")}"
                    val texts = data.optJSONArray("texts") ?: return false
                    val seenCount = chunkTextSeen[key] ?: 0
                    if (texts.length() <= seenCount) return false
                    val sb = StringBuilder()
                    for (k in seenCount until texts.length()) sb.append(texts.optString(k))
                    chunkTextSeen[key] = texts.length()
                    return mutateLastAssistant(key, sb.toString(), finalDelta = builder.finalMode.get())
                }
                "chunkrow/reasoning-chunks" -> {
                    val key = "${data.optInt("turn")}:${data.optInt("step")}:${data.optInt("index")}"
                    val texts = data.optJSONArray("texts") ?: return false
                    val seenCount = chunkReasonSeen[key] ?: 0
                    if (texts.length() <= seenCount) return false
                    val sb = StringBuilder()
                    for (k in seenCount until texts.length()) sb.append(texts.optString(k))
                    chunkReasonSeen[key] = texts.length()
                    return mutateLastReasoning(key, sb.toString())
                }
                "chunkrow/tool-call-chunks" -> {
                    val id = data.optString("id")
                    val args = data.optJSONArray("args") ?: return false
                    val seenCount = chunkArgsSeen[id] ?: 0
                    if (args.length() <= seenCount) return false
                    val sb = StringBuilder()
                    for (k in seenCount until args.length()) sb.append(args.optString(k))
                    chunkArgsSeen[id] = args.length()
                    return mutateTool(id, data.optString("name"), sb.toString(), "运行中", streaming = true, startedAt = eventTime)
                }
                "assistant/chunk" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    val chunk = data.optJSONObject("chunk") ?: return false
                    val base = "${data.optInt("turn")}:${data.optInt("step")}:${chunk.optInt("index")}"
                    when (chunk.optString("type")) {
                        "text-delta" -> return mutateLastAssistant(base, chunk.optString("text"))
                        "reasoning-delta" -> return mutateLastReasoning(base, chunk.optString("text"))
                        "tool-call-delta" -> return mutateTool(
                            chunk.optString("id"), chunk.optString("name"),
                            chunk.optString("argumentsDelta"), "运行中", streaming = true,
                            startedAt = eventTime,
                        )
                        else -> return false
                    }
                }
                "user/message" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    val src = data.optJSONObject("source")
                    val srcKind = src?.optString("kind") ?: "user"
                    val turn = data.optInt("turn", -1)
                    if (srcKind != "user") {
                        // 插件注入（web ContextInjectionRow）：默认折叠的披露行
                        val form = src?.optString("form").orEmpty()
                        val plugin = src?.optString("plugin").orEmpty()
                        val sections = src?.optJSONArray("sections")
                        val names = mutableListOf<String>()
                        if (sections != null) {
                            for (i in 0 until sections.length()) {
                                sections.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
                            }
                        } else {
                            // 无 sections（如 recall）：从 content 里取来源标签
                            val firstLine = contentText(data).trim().lineSequence().firstOrNull().orEmpty()
                            if (firstLine.isNotEmpty()) names.add(firstLine.take(40))
                        }
                        val title = when {
                            form == "recall" -> "跨会话召回"
                            form == "notice" -> "上下文提示"
                            else -> "上下文注入"
                        }
                        return push(seq, TranscriptNode(
                            key = "inject:$seq", kind = TranscriptNode.NodeKind.DISCLOSURE,
                            text = "", turn = turn,
                            disclosureTitle = title,
                            disclosureSummary = listOf(
                                form.takeIf { it.isNotEmpty() } ?: "",
                                names.joinToString("、").take(48),
                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                            body = contentText(data),
                            meta = mapOf("plugin" to plugin, "form" to form),
                        ))
                    }
                    return push(seq, TranscriptNode(
                        key = "user:$seq", kind = TranscriptNode.NodeKind.USER,
                        text = contentText(data), turn = turn,
                    ))
                }
                "assistant/message" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    val msg = data.optJSONObject("message") ?: data
                    val text = contentText(msg)
                    val turn = data.optInt("turn", -1)
                    val step = data.optInt("step", -1)
                    // 找到同 (turn,step) 的流式助手节点，终稿替换；否则新建
                    val idx = findLast { it.node.kind == TranscriptNode.NodeKind.ASSISTANT &&
                        it.node.turn == turn && it.node.step == step }
                    val messageId = msg.optString("id", msg.optString("messageId", "m:$seq"))
                    if (idx >= 0) {
                        entries[idx] = Entry(replaceNode(entries[idx].node, text = text, streaming = false,
                            meta = entries[idx].node.meta + ("messageId" to messageId)), entries[idx].startSeq)
                    } else if (text.isNotEmpty()) {
                        return push(seq, TranscriptNode(
                            key = "assistant:$seq", kind = TranscriptNode.NodeKind.ASSISTANT,
                            text = text, turn = turn, step = step,
                            meta = mapOf("messageId" to messageId),
                        ))
                    } else return false
                    return true
                }
                "tool/result" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    val callId = data.optString("callId")
                    val msg = data.optJSONObject("message")
                    val isError = data.optBoolean("isError", false) ||
                        (msg?.optJSONArray("content")?.let { c ->
                            (0 until c.length()).any { k -> c.optJSONObject(k)?.optBoolean("isError", false) == true }
                        } ?: false)
                    val content = data.opt("content") ?: msg?.opt("content")
                    val existing = toolById[callId]
                        // 兜底：chunk 流用的 `id` 与结果的 `callId` 未必同名（实测出现过两者对不上 →
                        // 结果落到 else 分支生成**第二张卡**，而第一张永远停在"运行中"，计时也就永不停止）。
                        // 这里退化为"并入最后一个仍在运行的工具"，与 id 命名解耦。
                        ?: entries.indexOfLast { it.node.kind == TranscriptNode.NodeKind.TOOL && it.node.streaming }
                            .takeIf { it >= 0 }
                    if (existing != null) {
                        if (callId.isNotEmpty()) toolById[callId] = existing   // 补登记，后续沿用同一节点
                        entries[existing] = Entry(
                            replaceNode(entries[existing].node, toolStatus = if (isError) "失败" else "完成",
                                toolResult = summarize(content), streaming = false),
                            entries[existing].startSeq,
                        )
                        return true
                    } else {
                        // 罕见乱序：兜底展示
                        return push(seq, TranscriptNode(
                            key = "toolresult:$seq", kind = TranscriptNode.NodeKind.TOOL,
                            toolName = callId.take(12), toolStatus = if (isError) "失败" else "完成",
                            toolResult = summarize(content),
                        ))
                    }
                }
                "turn/start" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    push(seq, TranscriptNode(
                        key = "status:$seq", kind = TranscriptNode.NodeKind.STATUS, text = "第 ${data.optInt("turn")} 轮", turn = data.optInt("turn"),
                    ))
                    return true
                }
                "turn/end" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    var changed = false
                    for (i in entries.indices) if (entries[i].node.streaming) {
                        entries[i] = Entry(entries[i].node.copy(streaming = false), entries[i].startSeq)
                        changed = true
                    }
                    // **把本轮结束的原因呈现给用户**。
                    // 引擎在 turn/end 里带 `reason`：{kind:"completed"|"blocked"|"aborted"|"error"|"max-tokens", ...}
                    // 此前完全忽略它 → 回合失败时界面只显示"已停止"，用户不知道发生了什么。
                    val reason = data.optJSONObject("reason")
                    val kind = reason?.optString("kind").orEmpty()
                    val errMsg = reason?.optJSONObject("error")
                        ?.let { e -> e.optString("message").ifEmpty { e.optString("type") } }
                        .orEmpty()
                    val detail = when (kind) {
                        "error" -> errMsg.ifEmpty { "模型请求失败" }
                        "blocked" -> "本轮被阻止（可能是沙箱限制或审批未通过）"
                        "aborted" -> reason.optString("reason").ifEmpty { "本轮已中止" }
                        "max-tokens" -> "达到最大输出长度，本轮被截断"
                        else -> ""
                    }
                    if (detail.isNotEmpty()) {
                        push(seq, TranscriptNode(
                            key = "turnend:$seq",
                            kind = if (kind == "error") TranscriptNode.NodeKind.ERROR else TranscriptNode.NodeKind.STATUS,
                            text = detail.take(400),
                        ))
                        changed = true
                    }
                    return changed
                }
                "approval/asked" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    val id = data.optString("id")
                    val name = data.optString("name", "")
                    val reason = data.optString("reason", "")
                    val desc = data.optJSONObject("description")
                    val detail = buildString {
                        if (name.isNotEmpty()) append("工具: $name")
                        if (reason.isNotEmpty()) append(if (isNotEmpty()) "\n" else "").append("原因: $reason")
                        if (desc != null) {
                            val text = desc.optString("text")
                            if (text.isNotEmpty()) append(if (isNotEmpty()) "\n" else "").append(text)
                        }
                    }
                    entries.add(Entry(
                        TranscriptNode(
                            key = "approval:$id", kind = TranscriptNode.NodeKind.APPROVAL,
                            text = detail.ifEmpty { "工具请求越权执行，等待审批" },
                            meta = mapOf("approvalId" to id),
                        ), seq))
                    return true
                }
                "session/title", "model/selection", "permission/preset", "plan/mode",
                "todo/write", "goal/change", "subagent/descriptor",
                "command/run", "command/done", "llm/retry", "compaction/start",
                "compaction/end", "compaction/summary", "feedback/record",
                "request/header", "request/context", "schedule/change" -> {
                    if (seq < 0 || !seen.add(seq)) return false
                    // 折叠行（对齐 web DisclosureRow）
                    when (type) {
                        "compaction/start" -> return push(seq, TranscriptNode(
                            key = "disclose:$seq", kind = TranscriptNode.NodeKind.DISCLOSURE,
                            disclosureTitle = "正在压缩上下文…", disclosureSummary = "",
                        ))
                        "compaction/end", "compaction/summary" -> {
                            val summary = data.optString("summary")
                            val items = data.optInt("items", 0)
                            val tokens = data.optInt("tokens", 0)
                            return push(seq, TranscriptNode(
                                key = "disclose:$seq", kind = TranscriptNode.NodeKind.DISCLOSURE,
                                disclosureTitle = "上下文已压缩",
                                disclosureSummary = buildString {
                                    if (items > 0) append("$items 条历史")
                                    if (tokens > 0) append(if (isNotEmpty()) " · " else "").append("约 $tokens tokens")
                                    if (summary.isNotEmpty()) append(if (isNotEmpty()) " · " else "").append("点击查看摘要")
                                },
                                body = summary,
                            ))
                        }
                        "llm/retry" -> {
                            val retry = data.optInt("retry", 0)
                            val max = data.optInt("maximum", data.optInt("maxRetries", 0))
                            val reason = data.optString("message", data.optString("reason"))
                            return push(seq, TranscriptNode(
                                key = "disclose:$seq", kind = TranscriptNode.NodeKind.DISCLOSURE,
                                disclosureTitle = "正在重试模型请求",
                                disclosureSummary = "（$retry/${if (max > 0) "$max" else "∞"}）" +
                                    if (reason.isNotEmpty()) " · ${reason.take(40)}" else "",
                                body = reason,
                            ))
                        }
                        "request/header" -> {
                            val system = data.optString("system")
                            if (system.isEmpty()) return false
                            return push(seq, TranscriptNode(
                                key = "disclose:$seq", kind = TranscriptNode.NodeKind.DISCLOSURE,
                                disclosureTitle = "系统提示词",
                                disclosureSummary = "${system.length} 字符",
                                body = system,
                            ))
                        }
                        "request/context" -> return false // 元数据（provider/model/窗口），不单列一行
                    }
                    return statusNode(seq, type, data)
                }
                "approval/decided" -> {
                    // 审批已处理：从列表移除对应审批卡（保持简单：标记卡片状态）
                    if (seq < 0 || !seen.add(seq)) return false
                    // 找到最新 approval 节点，改为已处理状态
                    val idx = findLast { it.node.kind == TranscriptNode.NodeKind.APPROVAL }
                    if (idx >= 0) {
                        entries[idx] = Entry(
                            entries[idx].node.copy(meta = entries[idx].node.meta + ("decided" to "1")),
                            entries[idx].startSeq,
                        )
                        return true
                    }
                    return false
                }
                else -> return false
            }
            return false
        }

        // ---------- 内部 ----------

        private fun contentText(message: JSONObject): String {
            val content = message.optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (k in 0 until content.length()) {
                val b = content.optJSONObject(k) ?: continue
                when (b.optString("type")) {
                    "text" -> sb.append(b.optString("text"))
                    "tool-call" -> sb.append("[调用工具 ${b.optString("name")}]")
                    "tool-result" -> sb.append("[工具结果]")
                }
            }
            return sb.toString()
        }

        private fun summarize(content: Any?): String {
            var s = when (content) {
                null -> ""
                is String -> content
                is JSONObject -> content.toString()
                is JSONArray -> content.toString()
                else -> content.toString()
            }
            s = s.trim().replace(Regex("\\s+"), " ")
            return if (s.length > 600) s.take(600) + " …" else s
        }

        private fun push(seq: Int, node: TranscriptNode): Boolean {
            // 记录事件 seq（用于"从此处分叉"：session/fork 的 atSeq）
            val withSeq = if (node.meta["seq"] == null && seq >= 0)
                node.copy(meta = node.meta + ("seq" to seq.toString())) else node
            entries.add(Entry(withSeq, seq))
            if (withSeq.kind == TranscriptNode.NodeKind.TOOL) toolById[withSeq.key] = entries.size - 1
            return true
        }

        private fun replaceRange(start: Int, end: Int) {
            entries.removeAll { it.startSeq in start..end }
            // **必须重建工具索引**：entries 是 MutableList，删除后所有后续元素索引整体前移，
            // 而 toolById 里存的是绝对索引 → 之后任何按 id 命中的分支都会写到别人的节点上，
            // 甚至越界抛 IndexOutOfBoundsException（异常发生在 WS 回调线程 → follow/control/$events
            // 三条流一起死，界面永远停在"连接中"）。裁剪后重建，成本可忽略（列表仅数百项）。
            rebuildToolIndex()
        }

        /** 工具 callId → entries 下标 的全量重建（列表被裁剪后必须调用）。 */
        private fun rebuildToolIndex() {
            toolById.clear()
            for (i in entries.indices) {
                val n = entries[i].node
                if (n.kind == TranscriptNode.NodeKind.TOOL) {
                    val id = n.meta["toolCallId"] ?: n.key
                    toolById[id] = i
                }
            }
        }

        private fun findLast(pred: (Entry) -> Boolean): Int {
            for (i in entries.indices.reversed()) if (pred(entries[i])) return i
            return -1
        }

        /** 找到同 key 的助手节点并追加文本（无则新建）。finalDelta=true 时不带流式光标。 */
        private fun mutateLastAssistant(key: String, delta: String, finalDelta: Boolean = false): Boolean {
            // 节点 key 加**类型前缀**：正文与推理 chunk 共用 `turn:step:index` 公式，
            // 同一 step 内两者 index 相同时原本会产生**完全相同的 key** →
            // LazyColumn(itemsIndexed(key=…)) 会抛 "Key was already used" 直接崩溃。
            val nk = "a:$key"
            val idx = findLast { it.node.kind == TranscriptNode.NodeKind.ASSISTANT && it.node.key == nk }
            if (idx >= 0) {
                entries[idx] = Entry(entries[idx].node.copy(text = entries[idx].node.text + delta, streaming = !finalDelta), entries[idx].startSeq)
            } else {
                // 流式节点首次出现
                val node = TranscriptNode(
                    key = nk, kind = TranscriptNode.NodeKind.ASSISTANT, text = delta,
                    turn = key.split(":").getOrNull(0)?.toIntOrNull() ?: -1,
                    step = key.split(":").getOrNull(1)?.toIntOrNull() ?: -1,
                    streaming = !finalDelta,
                )
                entries.add(Entry(node, -1))
            }
            return true
        }

        private fun mutateLastReasoning(key: String, delta: String): Boolean {
            val nk = "r:$key"
            val idx = findLast { it.node.kind == TranscriptNode.NodeKind.REASONING && it.node.key == nk }
            if (idx >= 0) {
                entries[idx] = Entry(entries[idx].node.copy(reasoning = entries[idx].node.reasoning + delta), entries[idx].startSeq)
            } else {
                entries.add(Entry(TranscriptNode(key = nk, kind = TranscriptNode.NodeKind.REASONING, reasoning = delta), -1))
            }
            return true
        }

        private fun mutateTool(
            id: String,
            name: String,
            delta: String,
            status: String,
            streaming: Boolean,
            startedAt: Long = 0L,
        ): Boolean {
            // 防御：索引可能因列表裁剪而失效（越界或指向非 TOOL 节点）→ 视为未命中，走新建分支。
            // 这样即使将来有其它裁剪路径忘了重建索引，也只会退化成"多一张卡"，不会崩掉三条流。
            val idx = toolById[id]?.takeIf {
                it in entries.indices && entries[it].node.kind == TranscriptNode.NodeKind.TOOL
            } ?: run {
                val node = TranscriptNode(
                    key = id, kind = TranscriptNode.NodeKind.TOOL, toolName = name,
                    toolArgs = delta, toolStatus = status, streaming = streaming,
                    // startedAt 用于运行中计时（"运行中 · 12s"）
                    meta = buildMap {
                        put("toolCallId", id)
                        if (startedAt > 0) put("startedAt", startedAt.toString())
                    },
                )
                entries.add(Entry(node, -1))
                toolById[id] = entries.size - 1
                return true
            }
            val prev = entries[idx].node
            if (prev.streaming && prev.toolArgs.isEmpty()) {
                entries[idx] = Entry(prev.copy(toolArgs = delta), entries[idx].startSeq)
            } else {
                entries[idx] = Entry(prev.copy(toolArgs = prev.toolArgs + delta, toolStatus = status), entries[idx].startSeq)
            }
            return true
        }

        private fun statusNode(seq: Int, type: String, data: JSONObject): Boolean {
            val text = when (type) {
                "session/title" -> return false
                "model/selection" -> "模型: ${data.optString("model")}"
                "permission/preset" -> "权限: ${data.optString("preset", "")}"
                "plan/mode" -> "计划模式: ${data.optString("mode", data.optString("active", ""))}"
                "todo/write" -> "待办已更新"
                "goal/change" -> "目标已更新"
                "subagent/descriptor" -> "子智能体: ${data.optString("name", "未知")}"
                "approval/asked" -> {
                    // 已通过独立分支处理；这里兜底
                    "等待审批"
                }
                "command/run" -> "$ ${data.optString("name")} ${data.optString("args")}".trim()
                "command/done" -> if (data.optString("result") == "error") "指令失败" else "命令完成"
                "llm/retry" -> "LLM 重试中…"
                "compaction/start" -> "正在压缩上下文…"
                "compaction/end" -> "上下文已压缩"
                "compaction/summary" -> "压缩摘要可用"
                "feedback/record" -> "已记录反馈"
                "request/header" -> {
                    // 头部事件通常配合系统提示词/工具变更；在手机上折叠进 contextual chip，
                    // 仅当有可见变化时显示（config/usage 都不展示 → 返回空则跳过）
                    val config = data.optJSONObject("config")
                    val model = config?.optString("model") ?: ""
                    val provider = config?.optString("provider") ?: ""
                    val tools = data.optJSONArray("tools")
                    val systemLen = data.optString("system").length
                    val parts = mutableListOf<String>()
                    if (provider.isNotEmpty() && model.isNotEmpty()) parts.add("${provider}/${model}")
                    if (tools != null && tools.length() > 0) parts.add("${tools.length()} 工具")
                    if (systemLen > 0) parts.add("系统提示词 ${systemLen} 字符")
                    if (parts.isEmpty()) "" else "请求: " + parts.joinToString(" · ")
                }
                "request/context" -> "上下文注入"
                "schedule/change" -> "定时任务已更新"
                else -> type
            }
            if (text.isEmpty()) return false
            entries.add(Entry(TranscriptNode(
                key = "status:$seq", kind = TranscriptNode.NodeKind.STATUS, text = text, meta = mapOf("type" to type),
            ), seq))
            return true
        }

        fun snapshot(): List<TranscriptNode> = entries.map { it.node }

        /** 重置全部折叠状态（会话切换时调用）。 */
        fun reset() {
            entries.clear()
            seen.clear()
            chunkTextSeen.clear()
            chunkReasonSeen.clear()
            chunkArgsSeen.clear()
            toolById.clear()
        }
    }

    private val surface = Surface(this)
    @Synchronized fun snapshot(): List<TranscriptNode> = surface.snapshot()
    @Synchronized fun append(event: JSONObject): Boolean = surface.append(event)

    /** 会话切换/清空时重置全部折叠状态（entries/seen/chunk 计数/tool 映射）。 */
    @Synchronized fun clear() = surface.reset()
}

/** 生成带局部更新的新节点（Immutable copy）。 */
private fun replaceNode(node: TranscriptNode, text: String? = null, streaming: Boolean? = null,
                        toolStatus: String? = null, toolResult: String? = null,
                        meta: Map<String, String>? = null): TranscriptNode {
    return node.copy(
        text = text ?: node.text,
        streaming = streaming ?: node.streaming,
        toolStatus = toolStatus ?: node.toolStatus,
        toolResult = toolResult ?: node.toolResult,
        meta = meta ?: node.meta,
    )
}
