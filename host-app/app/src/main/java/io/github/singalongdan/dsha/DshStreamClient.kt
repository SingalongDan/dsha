package io.github.singalongdan.dsha

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh 引擎 WebSocket 多路复用客户端（/api/remote.mux）。
 *
 * 协议（源码 dsh-api-gateway/lib/types/stream-protocol.js）：
 *   客户端帧: {"type":"open","streamId","endpoint","payload":{"args":{...}}}
 *             {"type":"cancel","streamId"}
 *   服务端帧: {"type":"item","streamId","value"}
 *             {"type":"end","streamId"}
 *             {"type":"error","streamId","error":{"code","message","details"}}
 *   保活: 服务端每 2s Ping；连续 2 次未回 Pong 即断开 → 客户端自动回 Pong。
 *
 * 用法：
 *   val conn = DshStreamClient(baseUrl, cookieProvider) { frame ->
 *       frame 为已解析的 JSONObject（type/item/end/error）
 *   }
 *   conn.open("session/follow", payloadArgs) // → streamId
 *   conn.cancel(streamId)
 *   conn.close()
 */
class DshStreamClient(
    private val baseWs: String, // ws://127.0.0.1:3080
    private val cookie: () -> String?,
) {

    companion object {
        private const val TAG = "DshStream"
        private const val HEARTBEAT_MS = 2_000L
    }

    interface Listener {
        /** 任何一帧到达（item/end/error/ready）。在 OkHttp 线程，勿做重活。 */
        fun onFrame(streamId: String, frame: JSONObject)
        /** 连接断开（含服务端关闭/网络错误）。 */
        fun onClosed(streamId: String, reason: String?)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private val streams = java.util.concurrent.ConcurrentHashMap<String, Listener>()
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    private class StreamListener(private val client: DshStreamClient) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "ws open")
            client.connected.set(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "raw frame: ${text.length}B streams=${client.streams.size}")
            val frame = try { JSONObject(text) } catch (e: Exception) {
                Log.e(TAG, "bad frame: ${text.length}B", e); return
            }
            val streamId = frame.optString("streamId")
            Log.d(TAG, "dispatch streamId=${streamId.take(8)} registered=${client.streams.containsKey(streamId)}")
            client.streams[streamId]?.let { it.onFrame(streamId, frame) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "ws closed: $code $reason")
            client.connected.set(false)
            client.resetSocket(webSocket)
            client.dispatchClosed(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "ws failure: ${t.message}", t)
            client.connected.set(false)
            client.resetSocket(webSocket)
            client.dispatchClosed(t.message)
        }
    }

    private fun dispatchClosed(reason: String?) {
        val pending = streams.values.toList()
        streams.clear()
        for (l in pending) l.onClosed("", reason ?: "closed")
    }

    /** 死套接字退出后允许重连时新建（避免等一把已死的 socket 干等）。 */
    @Synchronized
    private fun resetSocket(socket: WebSocket) {
        if (ws === socket) ws = null
    }

    /** 打开一条逻辑流。返回 streamId（供 cancel）。payloadArgs 即 payload.args。 */
    fun open(endpoint: String, payloadArgs: JSONObject, listener: Listener): String? {
        if (closed.get()) {
            Log.w(TAG, "open on closed client")
            return null
        }
        val streamId = UUID.randomUUID().toString()
        val connect = ensureConnected() ?: return null
        // 先注册 listener 再发 open 帧：服务端可能在 open 后即刻回帧（snapshot/ready），
        // 若发送后才注册会丢失首帧。
        streams[streamId] = listener
        val frame = JSONObject()
            .put("type", "open")
            .put("streamId", streamId)
            .put("endpoint", endpoint)
            .put("payload", JSONObject().put("args", payloadArgs))
        if (!connect.send(frame.toString())) {
            Log.e(TAG, "open send failed")
            streams.remove(streamId)
            return null
        }
        Log.d(TAG, "opened $endpoint -> $streamId")
        return streamId
    }

    /** 发送 cancel 帧；返回 false 表示 socket 不可用、该帧已被丢弃（调用方据此可知取消未送达）。 */
    fun cancel(streamId: String): Boolean {
        streams.remove(streamId)
        val sent = ws?.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString()) ?: false
        if (!sent) Log.w(TAG, "cancel frame dropped (no socket): streamId=${streamId.take(8)}")
        return sent
    }

    fun close() {
        closed.set(true)
        connected.set(false)
        ws?.close(1000, "client close")
        dispatchClosed("client close")
    }

    /** 取可用 socket：已连接复用；从未创建则新建；创建中/失败中返回 null（调用方重试）。 */
    @Synchronized
    private fun ensureConnected(): WebSocket? {
        if (closed.get()) return null
        // 已连接**或正在连接**都复用同一个 socket：
        // OkHttp 的 RealWebSocket.send() 会把握手完成前发出的帧排队，连接建立后自动补发。
        // 此前在"连接中"直接返回 null，导致紧随其后的第二条流（follow/control）open 失败，
        // 只能等 backoff 重试 —— 实测冷启动白等 3.0s（$events 建连与 follow open 的竞争）。
        ws?.let { return it }
        val cookieValue = cookie()
        val req = Request.Builder()
            .url("$baseWs/api/remote.mux")
            .apply { if (cookieValue != null) header("Cookie", cookieValue) }
            .build()
        val socket = http.newWebSocket(req, StreamListener(this))
        ws = socket
        return socket
    }

    /** 便捷重连循环：外层 scope 中不断尝试 open 直到成功。 */
    fun <T> autoRetry(
        scope: CoroutineScope,
        endpoint: String,
        payloadArgs: () -> JSONObject,
        listener: Listener,
        intervalMs: Long = 3000,
    ): Job = scope.launch {
        while (isActive) {
            val sid = open(endpoint, payloadArgs(), listener)
            if (sid != null) {
                Log.d(TAG, "autoRetry opened $endpoint")
                return@launch // open 成功即返回（断线由外层 onClosed 调度重开）
            }
            delay(intervalMs)
        }
    }
}
