package dev.dsh.host

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * dsh 引擎 /api 的 JSON-RPC 客户端（HttpURLConnection + 手动 cookie + org.json，零额外依赖）。
 *
 * 协议（已实测）：
 *   - 鉴权：GET /?token=... → 303 + Set-Cookie（dsh-auth-*），不跟随重定向直接取 303 的 Set-Cookie
 *   - RPC：POST /api/<ns>/<method>，携带 Cookie
 *     body  {"type":"client-request","rpcId":"<uuid>","method":"<ns>/<method>","payload":{"args":{...}}}
 *     resp  {"type":"server-response","rpcId":"...","result":{"ok":true,"value":...} | {"ok":false,"error":{...}}}
 *
 * 参数与描述符（typert wire）一一对应：单隐参方法用 value.get<paramWire>()，
 * 零参方法 payload.args 必须为空对象 {}。
 */
class DshApiClient(private val base: String = "http://127.0.0.1:3080") {

    private var cookie: String? = null

    fun hasCookie(): Boolean = cookie != null

    /** 供 WS 流复用同一鉴权会话。 */
    fun cookieValue(): String? = cookie

    /** 鉴权：GET /?token=...，不跟随重定向，从 303 的 Set-Cookie 取 cookie。 */
    fun authenticate(token: String) {
        val conn = (URL("$base/?token=$token").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5000
            readTimeout = 5000
        }
        try {
            conn.connect()
            val setCookie = findSetCookie(conn.headerFields)
            cookie = setCookie?.substringBefore(';')
            android.util.Log.d("DshApi", "auth code=${conn.responseCode} cookie=${cookie?.take(24)}")
        } finally {
            conn.disconnect()
        }
    }

    private fun findSetCookie(headerFields: Map<String, List<String>>): String? {
        for ((key, values) in headerFields) {
            if (key != null && key.equals("Set-Cookie", true)) return values.firstOrNull()
        }
        for (values in headerFields.values) {
            for (v in values) if (v.startsWith("dsh-auth-", ignoreCase = true)) return v
        }
        return null
    }

    // ---------------- 基础 RPC ----------------

    /** 通用 RPC：payload.args = {<paramName>: <arg>}，返回 result.value；ok=false 抛异常。 */
    private fun rpc(method: String, paramName: String, arg: JSONObject): Any? =
        rpcRaw(method, JSONObject().put(paramName, arg))

    /** 零参 RPC：payload.args = {}（typert 描述符 parameters 为空）。 */
    private fun rpcZero(method: String): Any? = rpcRaw(method, JSONObject())

    /** 通用 RPC：payload.args 直接给定。返回 result.value；ok=false 抛异常。 */
    private fun rpcRaw(method: String, args: JSONObject): Any? {
        val rpcId = UUID.randomUUID().toString()
        val url = "$base/api/$method"
        val body = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", method)
            .put("payload", JSONObject().put("args", args))

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            android.util.Log.d("DshApi", "POST $url -> $code: ${text.take(120)}")
            if (code !in 200..299) throw DshRpcException("HTTP $code: $text")
            val resp = JSONObject(text)
            val result = resp.getJSONObject("result")
            if (result.optBoolean("ok") != true) {
                val err = result.optJSONObject("error")
                throw DshRpcException(err?.optString("message") ?: "unknown rpc error")
            }
            if (result.isNull("value")) null else result.get("value")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 会话 ----------------

    /** session/list → items 数组 */
    fun listSessions(): JSONArray {
        val value = rpc("session/list", "_request", JSONObject()) as JSONObject
        return value.optJSONArray("items") ?: JSONArray()
    }

    /** session/create → {sessionId, agentPreset} */
    fun createSession(cwd: String? = null, agentPreset: String? = null): JSONObject {
        val arg = JSONObject()
        if (cwd != null) arg.put("cwd", cwd)
        if (agentPreset != null) arg.put("agentPreset", agentPreset)
        return rpc("session/create", "request", arg) as JSONObject
    }

    /** session/rename → {title, seq} */
    fun rename(sessionId: String, title: String): JSONObject {
        val arg = JSONObject().put("sessionId", sessionId).put("title", title)
        return rpc("session/rename", "request", arg) as JSONObject
    }

    /** session/cancel → {accepted:true} */
    fun cancel(sessionId: String) {
        rpc("session/cancel", "request", JSONObject().put("sessionId", sessionId))
    }

    /**
     * session/fork → 从某会话的"已完成轮次前缀"分叉出新会话。
     * @param atSeq 锚点事件 seq（省略则取当前末尾）。
     */
    fun forkSession(sessionId: String, atSeq: Int? = null): JSONObject {
        val arg = JSONObject().put("sessionId", sessionId)
        if (atSeq != null && atSeq >= 0) arg.put("atSeq", atSeq)
        return rpc("session/fork", "request", arg) as JSONObject
    }

    /** commands/execute（wire: agentId/line/images 平铺）→ {commandId, result} */
    fun executeCommand(sessionId: String, line: String) {
        val arg = JSONObject()
            .put("agentId", sessionId)
            .put("line", line)
            .put("images", JSONArray())
        rpcRaw("commands/execute", arg)
    }

    /** commands/list（wire: agentId）→ [{name, description}] */
    fun listCommands(sessionId: String): JSONArray {
        val value = rpcRaw("commands/list", JSONObject().put("agentId", sessionId)) as JSONArray
        return value
    }

    /** fileReferences/list（wire: agentId/query）→ @ 文件补全候选 */
    fun fileReferences(sessionId: String, query: String): JSONArray {
        val arg = JSONObject().put("agentId", sessionId).put("query", query)
        return rpcRaw("fileReferences/list", arg) as JSONArray
    }

    /** session/search（wire: request:{query}）→ {items, hasMore} 会话搜索 */
    fun searchSessions(query: String): JSONArray {
        val value = rpcRaw("session/search", JSONObject().put("request",
            JSONObject().put("query", query)))
        return if (value is JSONObject) value.optJSONArray("items") ?: JSONArray()
        else JSONArray()
    }

    /** messageFeedback/list（wire: request:{sessionId}）→ {items:[{messageId, rating}]} */
    fun messageFeedbackList(sessionId: String): JSONArray {
        val value = rpcRaw("messageFeedback/list",
            JSONObject().put("request", JSONObject().put("sessionId", sessionId)))
        val items = (value as? JSONObject)?.optJSONArray("items") ?: JSONArray()
        return items
    }

    /** messageFeedback/put（wire: request:{sessionId, messageId, rating, ifVersion}）→ 最新 */
    fun messageFeedbackPut(sessionId: String, messageId: String, rating: String, ifVersion: Int = 0) {
        val arg = JSONObject()
            .put("request", JSONObject()
                .put("sessionId", sessionId)
                .put("messageId", messageId)
                .put("rating", rating)
                .put("ifVersion", ifVersion))
        rpcRaw("messageFeedback/put", arg)
    }

    /** session/updateQueue → {accepted}（action.kind: edit/steer/remove） */
    fun updateQueue(sessionId: String, itemId: String, action: JSONObject) {
        rpc("session/updateQueue", "request",
            JSONObject().put("sessionId", sessionId).put("itemId", itemId).put("action", action))
    }

    /** session/prompt mode: "queue" | "steer" → {accepted} */
    fun prompt(sessionId: String, text: String, mode: String = "queue") {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        val arg = JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("sessionId", sessionId)
            .put("mode", mode)
            .put("content", content)
        rpc("session/prompt", "request", arg)
    }

    /**
     * session/prompt 带图片：images = [{data: base64, mediaType, name?}]。
     * 文本与图片按 content 顺序组成（服务端将图片转为持久 attachmentId）。
     */
    fun promptWithImages(sessionId: String, text: String, images: List<org.json.JSONObject>, mode: String = "queue") {
        val content = JSONArray()
        for (img in images) {
            content.put(JSONObject()
                .put("type", "image")
                .put("data", img.optString("data"))
                .put("mediaType", img.optString("mediaType", "image/jpeg"))
                .apply { if (img.has("name")) put("name", img.optString("name")) })
        }
        if (text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", text))
        val arg = JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("sessionId", sessionId)
            .put("mode", mode)
            .put("content", content)
        rpc("session/prompt", "request", arg)
    }

    /** session/page：throughSeq 必须 ≤ 当前游标（用 assertCursor 取），否则报 "past cursor N"。 */
    fun page(sessionId: String, throughSeq: Int, maxMessages: Int = 60): JSONObject {
        val arg = JSONObject()
            .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", throughSeq)
            .put("maxMessages", maxMessages)
        return rpc("session/page", "request", arg) as JSONObject
    }

    /** 通过 "past cursor N" 错误消息反推当前日志游标（page 的 throughSeq 上界）。 */
    fun resolveCursor(sessionId: String): Int {
        return try {
            page(sessionId, Int.MAX_VALUE, 1)
            Int.MAX_VALUE
        } catch (e: Exception) {
            Regex("past cursor (-?\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toInt() ?: -1
        }
    }

    // ---------------- 模型 ----------------

    /** session/modelCatalog（零参）→ {default, groups:[{id,name,models:[{id,name,reasoning}]}], failures} */
    fun modelCatalog(): JSONObject = rpcZero("session/modelCatalog") as JSONObject

    /** session/selectModel → {selected:{provider,model,reasoningEffort?}} */
    fun selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null): JSONObject {
        val arg = JSONObject()
            .put("sessionId", sessionId)
            .put("provider", provider)
            .put("model", model)
        if (reasoningEffort != null) arg.put("reasoningEffort", reasoningEffort)
        return rpc("session/selectModel", "request", arg) as JSONObject
    }

    // ---------------- Agent 预设 ----------------

    /** agentPresets/list（零参）→ {presets:[{id,trust,isDefault,name,description,broken}], authorable} */
    fun agentPresetsList(): JSONObject = rpcZero("agentPresets/list") as JSONObject

    /** agentPresets/select（scoped by agentId=sessionId）→ preset id */
    fun agentPresetsSelect(sessionId: String, agentPreset: String): String {
        val arg = JSONObject().put("agentId", sessionId).put("agentPreset", agentPreset)
        return rpcRaw("agentPresets/select", arg) as String
    }

    // ---------------- 设置 / 凭据 ----------------

    /** settings/describe（零参）→ {writable, hasDocument, namespaces:[{ns,schema,value,applies,secrets,revision}]} */
    fun settingsDescribe(): JSONObject = rpcZero("settings/describe") as JSONObject

    /** pluginInventory/list（零参）→ {entries:[{entryId,moduleName,enabled,fiberPhase}]} */
    fun pluginInventory(): JSONObject = rpcZero("pluginInventory/list") as JSONObject

    /** llm/listProviders（零参）→ [{id,name}] */
    fun llmListProviders(): JSONArray = rpcZero("llm/listProviders") as JSONArray

    /** llm/listConfigurableProviders（零参）→ [{provider,displayName,settingsNs,settingsPath,declared?}] */
    fun llmListConfigurableProviders(): JSONArray = rpcZero("llm/listConfigurableProviders") as JSONArray

    /** llm/discoverModels(settingsNs, {provider?,baseURL?,api?,apiKey?}) → [{id,name?,contextWindow?,maxTokens?}] */
    fun llmDiscoverModels(
        settingsNs: String,
        provider: String?,
        baseURL: String?,
        api: String?,
        apiKey: String?,
    ): JSONArray {
        val req = JSONObject()
        if (!provider.isNullOrEmpty()) req.put("provider", provider)
        if (!baseURL.isNullOrEmpty()) req.put("baseURL", baseURL)
        if (!api.isNullOrEmpty()) req.put("api", api)
        if (!apiKey.isNullOrEmpty()) req.put("apiKey", apiKey)
        val arg = JSONObject().put("settingsNs", settingsNs).put("request", req)
        return rpcRaw("llm/discoverModels", arg) as JSONArray
    }

    /** settings/mutate（自动取 revision）：ops = [{op:"set"|"unset", path:[...], value?}] */
    fun settingsMutateAuto(ns: String, ops: JSONArray): JSONObject {
        var revision = 0L
        runCatching {
            val d = settingsDescribe()
            for (i in 0 until (d.optJSONArray("namespaces")?.length() ?: 0)) {
                val n = d.optJSONArray("namespaces")?.optJSONObject(i) ?: continue
                if (n.optString("ns") == ns) revision = n.optLong("revision", 0)
            }
        }
        return settingsMutate(ns, ops, revision)
    }

    /** settings/mutate → namespace view */
    fun settingsMutate(ns: String, ops: JSONArray, expectedRevision: Long): JSONObject {
        val arg = JSONObject()
            .put("ns", ns)
            .put("ops", ops)
            .put("expectedRevision", expectedRevision)
        return rpcRaw("settings/mutate", arg) as JSONObject
    }

    /** credentials/describe → {ref:{configured,source?,writable}} */
    fun credentialsDescribe(refs: List<String>): JSONObject {
        val arg = JSONObject().put("refs", JSONArray(refs))
        return rpcRaw("credentials/describe", arg) as JSONObject
    }

    /** credentials/set（值只进不出） */
    fun credentialsSet(ref: String, value: String) {
        val arg = JSONObject().put("ref", ref).put("value", value)
        rpcRaw("credentials/set", arg)
    }

    /** credentials/unset */
    fun credentialsUnset(ref: String) {
        rpcRaw("credentials/unset", JSONObject().put("ref", ref))
    }

    // ---------------- $events 转发流回执 ----------------

    /**
     * $events waterfall 回执：返回 `next`（继续下一个事件）/ `result`（业务答案）/ `rejected`。
     * HTTP 路径为 /api/$events/result。
     */
    fun eventsResult(clientId: String, eventId: String, outcome: JSONObject) {
        val arg = JSONObject()
            .put("clientId", clientId)
            .put("eventId", eventId)
            .put("outcome", outcome)
        rpcRaw("\$events/result", arg)
    }

    class DshRpcException(message: String) : Exception(message)
}
