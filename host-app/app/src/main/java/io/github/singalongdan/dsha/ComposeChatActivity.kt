package io.github.singalongdan.dsha

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * P2 主界面（Compose）：会话抽屉 + 对话流 + 顶部栏 + 设置入口。
 * bootstrap 后自动打开上次会话的 follow 流。
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        /** 外部分享文件复制到工作区的体积上限（分享方可能是任意 App）。 */
        private const val MAX_SHARED_FILE_BYTES = 50L * 1024 * 1024
        /** 单条消息最多附件数（base64 全量驻留内存 + 随 POST 发送，需设上限）。 */
        private const val MAX_ATTACHMENTS = 4

        /** 单张图片压缩后的体积上限（超出则继续降质/降尺寸）。 */
        private const val MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024
    }

    private val api = DshApiClient()
    private var controller: SessionStreamController? = null

    /**
     * 销毁时释放：关闭跟随/事件/控制三条流并取消重连协程。
     * 引擎本身由前台服务持有，App 退出不中断会话。
     */
    override fun onDestroy() {
        runCatching { controller?.close() }
        controller = null
        super.onDestroy()
    }

    @Volatile private var sessionId: String? = null
    // **必须是 Compose 状态**：此前是普通 @Volatile 字段，写它不会触发重组。
    // 而 bootstrap 失败时 controller 的所有 StateFlow 都还是初值（没有任何新值）→
    // Compose 一直不重组 → 界面永远停在「正在连接引擎…」，ErrorView 与「重试/重启引擎」
    // 两个出口都不会出现，用户只能杀进程。
    private var bootError = mutableStateOf<String?>(null)

    // 会话列表状态（Compose State）
    private var sessionsState = mutableStateOf<List<SessionItem>>(emptyList())
    private var fullSessions: List<SessionItem> = emptyList()
    private var currentSessionId = mutableStateOf<String?>(null)
    private var pendingImages = mutableStateOf<List<org.json.JSONObject>>(emptyList())
    private var sendMode = mutableStateOf("queue")
    private var showWelcome = mutableStateOf(false)
    private var sortMode = mutableStateOf("updated")
    private var groupMode = mutableStateOf("flat")
    /** 回车键行为（设置→通用→回车键）：send=发送 / newline=换行。 */
    private var enterBehavior = mutableStateOf("send")

    /** 统一提示通道（操作失败/成功反馈；比 Toast 更贴近 Material 且不打断）。 */
    /** 长按消息的操作单（复制/分叉/重发）。 */
    private var messageActions = mutableStateOf<TranscriptNode?>(null)
    /** 从长按操作单打开的本轮用量面板。 */
    private var usageFromAction = mutableStateOf<SessionStreamController.TurnStat?>(null)
    /** 待注入输入框的引用文本（长按消息 → 引用）。 */
    private var injectToComposer = mutableStateOf<String?>(null)
    /** 置顶会话 id（本地偏好，置顶项排最前）。 */
    private var pinnedIds = mutableStateOf<Set<String>>(emptySet())

    private val snackbarHostState = androidx.compose.material3.SnackbarHostState()
    private fun notify(msg: String) {
        lifecycleScope.launch { runCatching { snackbarHostState.showSnackbar(msg) } }
    }

    /** 通知权限（Android 13+ 需 POST_NOTIFICATIONS）。 */
    private fun notificationsAllowed(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    /** 是否在前台（后台完成回合时发通知；前台用界面内反馈，避免打扰）。 */
    @Volatile private var activityResumed = false

    override fun onResume() {
        super.onResume()
        activityResumed = true
        Log.d("DshNotif", "onResume -> resumed=true")
        // 回到前台：挂起交互的提醒已无意义
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(1002)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onPause() {
        activityResumed = false
        Log.d("DshNotif", "onPause -> resumed=false")
        super.onPause()
    }

    /**
     * 通知订阅：**必须用 lifecycleScope 直接收集 Flow，不能挂在 Compose 的 LaunchedEffect 上**。
     * App 退到后台后不再产生帧、重组停止，Compose 侧的状态观察可能永不触发 →
     * "后台完成/需要确认"这类通知就会漏发（这正是通知最重要的场景）。
     */
    private fun startNotificationObservers() {
        val c = controller ?: return
        lifecycleScope.launch {
            c.title.collect { notifyTitleCache = it ?: notifyTitleCache }
        }
        lifecycleScope.launch {
            var last = false
            c.running.collect { r ->
                val was = last
                last = r
                Log.d("DshNotif", "running=$r (was=$was) resumed=$activityResumed")
                // 回合开始/结束都刷新会话列表：抽屉的"运行中"指示来自 session/list，
                // 不刷新就会一直显示旧状态（用户无法在多个会话间看出哪个在跑）。
                if (was != r) {
                    lifecycleScope.launch(Dispatchers.IO) { runCatching { refreshSessions() } }
                }
                if (was && !r && !activityResumed) {
                    maybeNotifyTurnDone(running = true, title = notifyTitleCache, aborted = c.aborted.value)
                }
            }
        }
        lifecycleScope.launch {
            var lastId: String? = null
            c.pendingEvent.collect { pe ->
                val id = pe?.eventId
                if (id != null && id != lastId) {
                    Log.d("DshNotif", "pending event id=$id resumed=$activityResumed")
                    if (!activityResumed) {
                        notifyNeedsInput(isApproval = pe.event == "approval/request", title = notifyTitleCache)
                    }
                } else if (id == null && lastId != null) {
                    runCatching {
                        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(1002)
                    }
                }
                lastId = id
            }
        }
    }

    /** 会话标题缓存（通知文案用；放在主线程外读取也安全）。 */
    @Volatile private var notifyTitleCache: String = "会话"

    /**
     * 当前页签（0=对话 1=轨迹）；提升为 Activity 状态以便持久化/恢复。
     * 注意：**不能在字段初始化器里读 SharedPreferences** —— 那时 Context 尚未 attach，
     * 会抛 "getSharedPreferences on a null object reference" 导致 Activity 无法实例化（已踩）。恢复在 onCreate 里做。
     */
    /** 待聚焦的聊天节点 key（轨迹 → 会话反向跳转用）。 */
    private var focusChatNode = mutableStateOf<String?>(null)
    /** 是否展示完整统计面板。 */
    private var showStatsDialog = mutableStateOf(false)

    /** 待应答浮层是否展开（点完即关闭；对话流只留一条紧凑记录）。 */
    private var pendingSheetOpen = mutableStateOf(false)

    /** 消息正文字号（设置 → 通用 → 字号）。 */
    private var messageFontSize = mutableStateOf(15)

    private var viewTab = mutableStateOf(0)

    /** 分享消费守卫（防止主线程与 bootstrap 线程重复应用同一次分享）。 */
    private val shareApplyGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 待处理的分享文本（冷启动时 bootstrap 未完成，先暂存）。 */
    private var pendingShare = mutableStateOf<String?>(null)

    /** 等待读权限后处理的分享文件 URI。 */
    private var pendingShareUri = mutableStateOf<android.net.Uri?>(null)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val uri = pendingShareUri.value
            pendingShareUri.value = null
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (uri != null) {
                if (granted) copyAndPrefillSharedFile(uri)
                else {
                    pendingShare.value = "（读取分享文件需要存储权限，已跳过）"
                    tryApplyPendingShare()
                }
            }
        }
    }

    /** 复制分享文件到工作区，并把路径预填进输入框。 */
    private fun copyAndPrefillSharedFile(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runCatching { copySharedFileToWorkspace(uri) }.getOrNull()
            runOnUiThread {
                pendingShare.value = if (saved != null) {
                    "我已把文件放到工作区：$saved（来自其它 App 的分享）。请阅读/处理它。"
                } else {
                    "（分享的文件复制失败，请手动提供路径）"
                }
                tryApplyPendingShare()
            }
        }
    }

    /**
     * 处理外部分享进来的文本（ACTION_SEND / ACTION_PROCESS_TEXT）。
     * 冷启动时会话尚未就绪，先暂存，等 bootstrap 完成后建新会话并预填输入框。
     */
    private fun handleShareIntent(intent: android.content.Intent?) {
        val action = intent?.action
        if (action != android.content.Intent.ACTION_SEND &&
            action != android.content.Intent.ACTION_PROCESS_TEXT
        ) return

        // 1) 文件分享（EXTRA_STREAM）：复制进工作区 shared/，把路径交给 Agent
        val stream = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri
        if (stream != null) {
            Log.d("DshNotif", "share file: $stream")
            // 相册/文件管理器分享的 content:// 需要读外部存储权限（targetSdk 28 运行时权限）
            if (android.os.Build.VERSION.SDK_INT < 33 &&
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pendingShareUri.value = stream
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1002)
                return
            }
            copyAndPrefillSharedFile(stream)
            return
        }

        // 2) 文本分享
        val text = when (action) {
            android.content.Intent.ACTION_SEND ->
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            android.content.Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim().orEmpty()
        if (text.isEmpty()) return
        Log.d("DshNotif", "share received ${text.length} chars")
        pendingShare.value = text
        tryApplyPendingShare()
    }

    /**
     * 把分享进来的文件复制到工作区 `shared/`（Agent 的工作目录内，可直接用 read/glob 访问）。
     * @return 相对工作区的路径；失败返回 null。
     */    private fun copySharedFileToWorkspace(uri: android.net.Uri): String? {
        val dir = File(File(filesDir, "home"), "shared").apply { mkdirs() }
        // 文件名：优先用 ContentResolver 的显示名，退回时间戳
        var name = "shared-${System.currentTimeMillis()}"
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { if (it.isNotBlank()) name = it }
            }
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").take(80)
        val out = File(dir, safe)
        val input = runCatching { contentResolver.openInputStream(uri) }.getOrElse { e ->
            Log.e("DshNotif", "openInputStream failed for $uri", e)   // 常见原因：缺少 URI 读授权
            return null
        } ?: run {
            Log.e("DshNotif", "openInputStream returned null for $uri")
            return null
        }
        runCatching {
            input.use { ins ->
                // **体积上限**：分享来源可能是任意 App，超大文件会耗尽存储
                var copied = 0L
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        copied += n
                        if (copied > MAX_SHARED_FILE_BYTES) {
                            throw IllegalStateException("分享文件超过 ${MAX_SHARED_FILE_BYTES / 1024 / 1024}MB 上限，已拒绝")
                        }
                        os.write(buf, 0, n)
                    }
                }
            }
        }.onFailure {
            Log.e("DshNotif", "copy failed", it)
            return null
        }
        Log.d("DshNotif", "share file saved: ${out.absolutePath} (${out.length()} bytes)")
        return "shared/$safe"
    }

    /**
     * API Key 自检：**本应用不再内置任何默认密钥**，未填写时明确告知用户去哪儿填。
     * 每次启动都提示（不写"已提示过"标记）：没有密钥就无法对话，静默失败会让人以为 App 坏了。
     */
    private fun maybeHintApiKey() {
        val key = SecurePrefs.apiKey(this).trim()
        if (key.isNotEmpty()) return
        if (SecurePrefs.lostDueToFailure()) {
            notify("API Key 解密失败（Keystore 可能已重置），请到 设置 → Android 宿主 重新填写")
            return
        }
        notify("尚未设置 API Key：请在 设置 → Android 宿主 → API Key 填入你自己的密钥后使用")
    }
    /**
     * Root 模式自检：本机有 su 但未开 Root 模式时给**一次性**提示。
     * 本机（无 bubblewrap/landlock）上 shell 工具依赖 root 包装器，关闭状态下 bash 类工具会失败，
     * 而用户无从得知原因（重装后该标记会丢失 —— R60 实测）。
     */
    /** 权限预设的中文名（取自 dsh web 词典 `access.preset.*`）。 */
    private fun permissionDisplayName(preset: String): String = when (preset) {
        "read-only" -> "只可查看"
        "workspace-write" -> "工作区内修改"
        "danger-full-access" -> "完全权限"
        else -> preset
    }

    /** 输入栏显示的权限：优先会话事件（真实状态），否则回退到全局默认。 */
    private val effectivePermission: String
        get() = controller?.permission?.value?.takeIf { it.isNotEmpty() }
            ?: currentPermission.value

    /**
     * 输入栏显示的模型标签：`模型 · 强度`。
     * 强度是 per-MODEL 能力（web 明确不做独立控件，见 `dsh-web-vs-android.md` §2.2），
     * 会话事件未带则显示该模型目录里的 `defaultEffort`。
     */
    private val composerModelLabel: String
        get() {
            val m = controller?.model?.value?.takeIf { it.isNotEmpty() }
                ?: sessionsState.value.firstOrNull { it.id == currentSessionId.value }?.model?.takeIf { it.isNotEmpty() }
                ?: modelCatalogCache.value?.optJSONObject("default")?.optString("model")?.takeIf { it.isNotEmpty() }
                ?: "deepseek-v4-flash"
            val short = m.substringAfterLast("/").removePrefix("deepseek-").take(12)
            val eff = controller?.effort?.value?.takeIf { it.isNotEmpty() } ?: defaultEffortOf(m)
            return if (eff.isNotEmpty()) "$short · ${effortDisplayName(eff)}" else short
        }

    /** 当前模型的短名（不含强度）。 */
    private val composerModelName: String
        get() = currentModelId().substringAfterLast("/").removePrefix("deepseek-").take(12)

    /** 当前思考强度名（会话事件优先，其次该模型目录默认档）。 */
    private val composerEffortName: String
        get() {
            val eff = controller?.effort?.value?.takeIf { it.isNotEmpty() } ?: defaultEffortOf(currentModelId())
            return if (eff.isNotEmpty()) effortDisplayName(eff) else "默认"
        }

    /** 当前模型 id（会话事件 → 会话列表 → 目录默认 → 兜底）。 */
    private fun currentModelId(): String =
        controller?.model?.value?.takeIf { it.isNotEmpty() }
            ?: sessionsState.value.firstOrNull { it.id == currentSessionId.value }?.model?.takeIf { it.isNotEmpty() }
            ?: modelCatalogCache.value?.optJSONObject("default")?.optString("model")?.takeIf { it.isNotEmpty() }
            ?: "deepseek-v4-flash"

    /** 从模型目录取该模型的默认强度档（`reasoning.defaultEffort`）。 */
    private fun defaultEffortOf(model: String): String {
        val groups = modelCatalogCache.value?.optJSONArray("groups") ?: return ""
        for (i in 0 until groups.length()) {
            val models = groups.optJSONObject(i)?.optJSONArray("models") ?: continue
            for (j in 0 until models.length()) {
                val mo = models.optJSONObject(j) ?: continue
                if (mo.optString("id") == model) {
                    return mo.optJSONObject("reasoning")?.optString("defaultEffort").orEmpty()
                }
            }
        }
        return ""
    }

    /** 从模型目录取该模型的强度档位列表：[(id, 中文名)]。 */
    private fun effortsOf(model: String): List<Pair<String, String>> {
        val groups = modelCatalogCache.value?.optJSONArray("groups") ?: return emptyList()
        for (i in 0 until groups.length()) {
            val models = groups.optJSONObject(i)?.optJSONArray("models") ?: continue
            for (j in 0 until models.length()) {
                val mo = models.optJSONObject(j) ?: continue
                if (mo.optString("id") != model) continue
                val arr = mo.optJSONObject("reasoning")?.optJSONArray("efforts") ?: return emptyList()
                val out = ArrayList<Pair<String, String>>()
                for (k in 0 until arr.length()) {
                    val e = arr.optJSONObject(k) ?: continue
                    out.add(e.optString("id") to effortDisplayName(e.optString("id")))
                }
                return out
            }
        }
        return emptyList()
    }

    /** 思考强度的中文名（取自引擎 modelCatalog 的 efforts[].id，未知则回退原值）。 */
    private fun effortDisplayName(id: String): String = when (id) {
        "off" -> "关闭思考"
        "low" -> "低"
        "high" -> "高"
        "max" -> "最高"
        else -> id
    }
    private fun maybeHintRootMode() {
        val prefs = getSharedPreferences("dsh_host", MODE_PRIVATE)
        if (prefs.getBoolean("root_hint_shown", false)) return
        val rootModeOn = File(filesDir, "root_mode").exists()
        val suAvailable = listOf("/system/bin/su", "/data/adb/ksu/bin/su", "/system/xbin/su")
            .any { File(it).exists() }
        Log.d("DshNotif", "root self-check: rootMode=$rootModeOn su=$suAvailable")
        if (rootModeOn || !suAvailable) return
        Log.d("DshNotif", "root hint fired (su available, root mode off)")
        prefs.edit().putBoolean("root_hint_shown", true).apply()
        notify("检测到本机有 root：可在 设置 → Android 宿主 开启「Root 模式」，shell 工具才能执行命令")
    }

    /**
     * 会话就绪后：**新建会话** + 把分享内容注入输入框（不自动发送，用户可补充说明）。
     * 采用"分享 → 新会话"的心智模型（与主流 App 一致），避免污染当前上下文。
     */
    private fun tryApplyPendingShare() {
        // 原子守卫：本函数会被主线程（收到分享）与 bootstrap 线程（会话就绪）同时调用，
        // 仅靠 `pendingShare.value = null` 存在 TOCTOU 窗口 → 可能建出两个会话。
        if (!shareApplyGuard.compareAndSet(false, true)) return
        val text = pendingShare.value
        if (text == null || sessionId == null || controller == null) {
            shareApplyGuard.set(false)      // 条件未就绪：放开守卫，等下次触发
            return
        }
        pendingShare.value = null
        lifecycleScope.launch(Dispatchers.IO) {
            // **finally 释放守卫**：异常路径也必须放开，否则 compareAndSet 永远失败 →
            // 本次及以后每次分享都会被静默丢弃（用户完全无感），直到杀进程。
            try {
                runCatching {
                    // 复用统一的建会话路径（引擎默认预设/权限）
                    val newId = createSessionDirect(preset = null)
                    refreshSessions()
                    runOnUiThread {
                        if (newId.isNotEmpty()) switchToSession(newId)
                        injectToComposer.value = text
                        notify("已接收分享内容，可直接发送或补充说明")
                    }
                }.onFailure {
                    Log.e("DshNotif", "share session create failed", it)
                    runOnUiThread {
                        injectToComposer.value = text     // 至少把内容交给输入框
                        notify("已接收分享内容（建会话失败，已放入输入框）")
                    }
                }
            } finally {
                shareApplyGuard.set(false)
            }
        }
    }

    /**
     * 需要用户确认/选择时的提醒（id=1002）。Agent 会**一直阻塞**等待应答，
     * 用户若已切走就再没有任何提示 —— 实测曾卡到 148s 无人知晓。
     */
    private fun notifyNeedsInput(isApproval: Boolean, title: String) {
        Log.d("DshNotif", "notifyNeedsInput approval=$isApproval allowed=${notificationsAllowed()}")
        if (!notificationsAllowed()) return
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "dsh_turns"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "会话动态", android.app.NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "会话完成 / 需要你确认时提醒" }
                )
            }
            val intent = android.content.Intent(this, ComposeChatActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = android.app.Notification.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(if (isApproval) "需要你确认" else "需要你选择")
                .setContentText("$title · 点击继续")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .build()
            nm.notify(1002, n)
        }.onFailure { Log.e("DshUi", "needs-input notification failed", it) }
    }

    /**
     * 回合完成通知：Agent 在后台跑完时用户往往已切走，需要一个能点回来的提示。
     * **边沿判断由调用方（running collector）负责**，这里只决定"是否该发 + 发送"。
     * （此前本函数内部又做了一次边沿判断，而调用方传 running=true → 永远提前 return，通知从不发出。）
     */
    private fun maybeNotifyTurnDone(running: Boolean, title: String, aborted: Boolean) {
        if (activityResumed) return
        if (!notificationsAllowed()) return
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "dsh_turns"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "会话动态", android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "会话完成 / 需要你确认时提醒" }
                )
            }
            val intent = android.content.Intent(this, ComposeChatActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = android.app.Notification.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(if (aborted) "已停止" else "会话已完成")
                .setContentText(title)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, n)
        }.onFailure { Log.e("DshUi", "turn-done notification failed", it) }
    }
    private var modelCatalogCache = mutableStateOf<org.json.JSONObject?>(null)
    private var currentPresetLabel = mutableStateOf("标准")

    /** 当前权限预设（用于胶囊显示中文权限名，web: access.preset.*）。 */
    private var currentPermission = mutableStateOf("workspace-write")

    /** 当前思考强度档位（per-MODEL 能力，见 dsh-web-vs-android.md §2.2）。 */
    private var currentEffort = mutableStateOf("")
    private var showModelMenu = mutableStateOf(false)

    /** 按当前排序模式整理会话列表（updated=最近更新/created=创建时间）。
     *  **排序 + 置顶 + 搜索过滤统一在这里落地**：此前搜索直接写 sessionsState，
     *  一旦发生刷新/置顶/改排序就被 applySort() 用全量列表覆盖 → 搜索结果被静默冲掉。 */
    private fun applySort() {
        val pin = pinnedIds.value
        val q = sessionQuery.value.trim()
        val base = if (q.isEmpty()) fullSessions else fullSessions.filter { s ->
            s.title.contains(q, ignoreCase = true) ||
                s.id.contains(q, ignoreCase = true) ||
                s.model.contains(q, ignoreCase = true)
        }
        sessionsState.value = base.sortedWith(
            compareByDescending<SessionItem> { it.id in pin }
                .thenByDescending { if (sortMode.value == "created") sessionCreatedAt(it) else it.updatedAt }
        )
    }

    /** 会话搜索关键词（与排序/置顶共同决定展示列表）。 */
    private var sessionQuery = mutableStateOf("")

    // 排序键：会话真实创建时间。
    // 此前是 `s.updatedAt - 1`（注释写"无 createdAt 字段时退化"）—— 但 createdAt **其实已经解析**
    // （见 session/list 的解析处），于是"按创建时间排序"与"按最近更新排序"的键只差 1ms，
    // 切换排序看不出任何变化，用户会以为按钮坏了。仅老数据缺 createdAt 时才回落到 updatedAt。
    private fun sessionCreatedAt(s: SessionItem): Long = if (s.createdAt > 0) s.createdAt else s.updatedAt
    private var openDrawer = mutableStateOf(false)
    private var openSettings = mutableStateOf(false)
    private var themePreference = mutableStateOf("system")

    /** 主题偏好（设置页写入，重启/切换即时生效）。 */
    private fun applyTheme(pref: String) {
        themePreference.value = pref
        val mode = when (pref) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** 当前是否暗色（主题偏好 + 系统）。 */
    private fun isDark(useSystem: Boolean): Boolean {
        val night = android.content.res.Configuration.UI_MODE_NIGHT_YES ==
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        return when {
            themePreference.value == "dark" -> true
            themePreference.value == "light" -> false
            else -> useSystem && night
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, DshHostService::class.java))

        // 主题偏好持久化（设置页写入 ui-theme preference 的本地镜像）
        applyTheme(getSharedPreferences("dsh_host", MODE_PRIVATE)
            .getString("theme_preference", "system") ?: "system")
        enterBehavior.value = getSharedPreferences("dsh_host", MODE_PRIVATE)
            .getString("enter_behavior", "send") ?: "send"
        viewTab.value = getSharedPreferences("dsh_host", MODE_PRIVATE).getInt("last_view", 0)
        messageFontSize.value = getSharedPreferences("dsh_host", MODE_PRIVATE).getInt("font_size", 15)   // 恢复页签（onCreate 里才安全）
        pinnedIds.value = getSharedPreferences("dsh_host", MODE_PRIVATE)
            .getStringSet("pinned_ids", emptySet())?.toSet() ?: emptySet()

        controller = SessionStreamController(
            scope = lifecycleScope,
            wsBase = "ws://127.0.0.1:3080",
            cookieProvider = { api.cookieValue() },
            api = api,
            onAuthLost = { authenticateSync() },
        )
        startNotificationObservers()
        handleShareIntent(intent)

        setContent {
            // 品牌色：DeepSeek 蓝（浅 #4176E6 / 深 #679EFE），替代 M3 默认紫
            val dark = isDark(true)
            val base = if (dark) androidx.compose.material3.darkColorScheme()
            else androidx.compose.material3.lightColorScheme()
            // 采用 dsh web 的**蓝灰中性**色阶与分层表面（`--dsw-static-neutral-bluish-*` /
            // `--dsw-alias-*`，取自 dsh-client-ui-theme），替代 M3 默认的紫灰调，
            // 使深色下的输入栏与卡片与 web 观感一致。
            //   深色：bg-base #151517 · layer-1 #232324 · layer-2 #2c2c2e · layer-3 #353638
            //        label: #f9fafb / #cfd3d6 / #adb2b8 / #81858c；border 白 6% / 12% / 16%
            //   浅色：bg-base #fff · overlay #e9ecf2 · label #0f1115 / #61666b / #979da6
            val branded = base.copy(
                primary = if (dark) androidx.compose.ui.graphics.Color(0xFF679EFE)
                else androidx.compose.ui.graphics.Color(0xFF4176E6),
                onPrimary = androidx.compose.ui.graphics.Color.White,
                primaryContainer = if (dark) androidx.compose.ui.graphics.Color(0xFF2B3A5C)
                else androidx.compose.ui.graphics.Color(0xFFE4EDFD),
                onPrimaryContainer = if (dark) androidx.compose.ui.graphics.Color(0xFFD3E2FF)
                else androidx.compose.ui.graphics.Color(0xFF1B3A78),
                secondary = if (dark) androidx.compose.ui.graphics.Color(0xFF9FB4D8)
                else androidx.compose.ui.graphics.Color(0xFF5686FE),
                tertiary = if (dark) androidx.compose.ui.graphics.Color(0xFF8B5CF6)
                else androidx.compose.ui.graphics.Color(0xFF7C5CF6),
                // —— 蓝灰分层 ——
                background = if (dark) androidx.compose.ui.graphics.Color(0xFF151517)
                else androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                onBackground = if (dark) androidx.compose.ui.graphics.Color(0xFFF9FAFB)
                else androidx.compose.ui.graphics.Color(0xFF0F1115),
                surface = if (dark) androidx.compose.ui.graphics.Color(0xFF151517)
                else androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                onSurface = if (dark) androidx.compose.ui.graphics.Color(0xFFF9FAFB)
                else androidx.compose.ui.graphics.Color(0xFF0F1115),
                // 输入框 / 卡片底色用 layer-1（深色 #232324，浅色 #F5F6F7）
                surfaceVariant = if (dark) androidx.compose.ui.graphics.Color(0xFF232324)
                else androidx.compose.ui.graphics.Color(0xFFF5F6F7),
                onSurfaceVariant = if (dark) androidx.compose.ui.graphics.Color(0xFFADB2B8)
                else androidx.compose.ui.graphics.Color(0xFF61666B),
                surfaceContainer = if (dark) androidx.compose.ui.graphics.Color(0xFF232324)
                else androidx.compose.ui.graphics.Color(0xFFF9FAFB),
                surfaceContainerHigh = if (dark) androidx.compose.ui.graphics.Color(0xFF2C2C2E)
                else androidx.compose.ui.graphics.Color(0xFFF1F3F5),
                surfaceContainerHighest = if (dark) androidx.compose.ui.graphics.Color(0xFF353638)
                else androidx.compose.ui.graphics.Color(0xFFEBEEF2),
                outline = if (dark) androidx.compose.ui.graphics.Color(0x1FFFFFFF)
                else androidx.compose.ui.graphics.Color(0x1F000000),
                outlineVariant = if (dark) androidx.compose.ui.graphics.Color(0x0FFFFFFF)
                else androidx.compose.ui.graphics.Color(0x0A000000),
            )
            MaterialTheme(colorScheme = branded) {
                val nodes by controller!!.nodes.collectAsState()
                val running by controller!!.running.collectAsState()
                val aborted by controller!!.aborted.collectAsState()
                val connection by controller!!.connection.collectAsState()
                val title by controller!!.title.collectAsState()
                val trajectoryRows by controller!!.trajectory.collectAsState()
                val pendingEvent by controller!!.pendingEvent.collectAsState()
                val turnStats by controller!!.turnStats.collectAsState()
                val statsForDialog by controller!!.stats.collectAsState()
                // 控制器错误 → 统一提示（应答失败/网关拒绝等）
                val ctrlError by controller!!.lastError.collectAsState()
                LaunchedEffect(ctrlError) {
                    ctrlError?.let {
                        snackbarHostState.showSnackbar(it)
                        controller?.clearError()
                    }
                }
                // 提问选项选择（questionId -> 已选 label 集合）+ 重组 tick
                val questionSelections = remember { hashMapOf<String, MutableSet<String>>() }
                val questionSelectionsTick = remember { mutableStateOf(0) }
                val sessions by sessionsState
                val current by currentSessionId

                val currentView = viewTab.value // 0=对话 1=轨迹（状态在 Activity，便于持久化）
                val trajectoryDetail = remember { mutableStateOf<TrajectoryRow?>(null) }
                val toolDetail = remember { mutableStateOf<TranscriptNode?>(null) }
                val fileCandidates = remember { mutableStateOf<List<FileCandidate>>(emptyList()) }
                var focusTrajectoryRow by remember { mutableStateOf<String?>(null) }

                val settingsProvider = remember {
                    object : SettingsProvider {
                        override fun settingsDescribe() = api.settingsDescribe()
                        override fun agentPresetsList() = api.agentPresetsList()
                        override fun agentPresetsSelect(sessionId: String, preset: String) = api.agentPresetsSelect(sessionId, preset)
                        override fun engineVersions(): Triple<String, String, String> {
                            // DSH 版本：直接读随包引擎的 package.json（升级引擎后自动跟随）
                            val dsh = runCatching {
                                val pj = File(filesDir, "usr/lib/node_modules/@deepseek-ai/dsh/package.json")
                                val t = pj.readText()
                                Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
                            }.getOrNull() ?: "未知"
                            // 运行时：实测 node --version（不写死，避免与随包运行时不一致）
                            val node = runCatching {
                                val p = ProcessBuilder("${filesDir.absolutePath}/usr/bin/node", "--version")
                                    .redirectErrorStream(true).start()
                                val out = p.inputStream.bufferedReader().readText().trim()
                                p.waitFor()
                                out
                            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "未知"
                            val app = runCatching {
                                val pi = packageManager.getPackageInfo(packageName, 0)
                                "DSHA v${pi.versionName}"
                            }.getOrNull() ?: "DSHA"
                            return Triple("DeepSeek Harness $dsh", "$node · Termux bionic 前缀", app)
                        }
                        override fun modelCatalog() = api.modelCatalog()
                        override fun selectModel(sessionId: String, provider: String, model: String) =
                            api.selectModel(sessionId, provider, model)
                        override fun currentSessionId() = sessionId
                        override fun currentModel(): String =
                            sessionsState.value.firstOrNull { it.id == currentSessionId.value }?.model ?: ""
                        override fun isRootMode() = File(filesDir, "root_mode").exists()
                        override fun setRootMode(on: Boolean) {
                            val f = File(filesDir, "root_mode")
                            if (on) { if (!f.exists()) f.createNewFile() }
                            else { if (f.exists()) f.delete() }
                        }
                        override fun restartEngine() = DshHostService.restart(this@ComposeChatActivity)
                        override fun apiKey(): String = SecurePrefs.apiKey(this@ComposeChatActivity)
                        override fun setApiKey(key: String) {
            if (!SecurePrefs.setApiKey(this@ComposeChatActivity, key)) {
                notify("加密存储不可用，密钥未保存")
            }
        }
                        override fun showApiKeyDialog(onSet: (Boolean) -> Unit) {
                            val et = android.widget.EditText(this@ComposeChatActivity).apply {
                                hint = "sk-…（留空恢复默认）"
                                isSingleLine = true
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            }
                            android.app.AlertDialog.Builder(this@ComposeChatActivity)
                                .setTitle("DEEPSEEK_API_KEY")
                                .setMessage("清空则不再向引擎注入密钥；保存后重启引擎生效")
                                .setView(et)
                                .setPositiveButton("保存") { _, _ ->
                                    val v = et.text.toString().trim()
                                    setApiKey(v)
                                    onSet(v.isNotEmpty())
                                    restartEngine()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        override fun showThemePicker(onSet: (String) -> Unit) {
                            val options = arrayOf("跟随系统", "浅色", "深色")
                            val values = arrayOf("system", "light", "dark")
                            android.app.AlertDialog.Builder(this@ComposeChatActivity)
                                .setTitle("外观")
                                .setItems(options) { _, which ->
                                    val v = values[which]
                                    getSharedPreferences("dsh_host", MODE_PRIVATE)
                                        .edit().putString("theme_preference", v).apply()
                                    applyTheme(v)
                                    onSet(v)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        override fun setDefaultPermission(preset: String): String {
                            // settings/mutate：permission.defaultPreset（描述符验证过）
                            val desc = api.settingsDescribe()
                            var revision = 0L
                            val ns = desc.optJSONArray("namespaces") ?: return ""
                            for (i in 0 until ns.length()) {
                                val n = ns.optJSONObject(i) ?: continue
                                if (n.optString("ns") == "permission") { revision = n.optLong("revision", 0) }
                            }
                            runCatching {
                                api.settingsMutate("permission",
                                    JSONArray().put(JSONObject()
                                        .put("op", "set").put("path", JSONArray().put("defaultPreset")).put("value", preset)),
                                    revision)
                            }
                            // 重读确认（请求成功与否均以引擎状态为准）
                            return runCatching {
                                val d = api.settingsDescribe()
                                for (i in 0 until (d.optJSONArray("namespaces")?.length() ?: 0)) {
                                    val n = d.optJSONArray("namespaces")?.optJSONObject(i) ?: continue
                                    if (n.optString("ns") == "permission")
                                        return n.optJSONObject("value")?.optString("defaultPreset") ?: preset
                                }
                                preset
                            }.getOrDefault(preset)
                        }
                        override fun showPermissionPicker(onSet: (String) -> Unit) {
                            val options = arrayOf("只读", "工作区写", "完全访问")
                            val values = arrayOf("read-only", "workspace-write", "danger-full-access")
                            android.app.AlertDialog.Builder(this@ComposeChatActivity)
                                .setTitle("权限默认预设")
                                .setItems(options) { _, which ->
                                    val v = values[which]
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        runCatching { setDefaultPermission(v) }
                                            .onSuccess { onSet(it) }
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        override fun pluginInventory() = api.pluginInventory()
                        override fun setGeneral(ns: String, path: String, value: String) {
                            // settings/mutate：动态 namespace/字段（describe 取 revision）
                            lifecycleScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val desc = api.settingsDescribe()
                                    var revision = 0L
                                    for (i in 0 until (desc.optJSONArray("namespaces")?.length() ?: 0)) {
                                        val n = desc.optJSONArray("namespaces")?.optJSONObject(i) ?: continue
                                        if (n.optString("ns") == ns) revision = n.optLong("revision", 0)
                                    }
                                    api.settingsMutate(ns,
                                        JSONArray().put(JSONObject()
                                            .put("op", "set").put("path", JSONArray().put(path)).put("value", value)),
                                        revision)
                                }.onFailure { Log.e("DshUi", "setGeneral $ns.$path failed: ${it.message}") }
                            }
                        }
                        override fun activityContext() = this@ComposeChatActivity
                        // 模型页（对齐 web settings→Models）
                        override fun llmConfigurableProviders() = api.llmListConfigurableProviders()
                        override fun credentialsDescribe(refs: List<String>) = api.credentialsDescribe(refs)
                        override fun credentialsSet(ref: String, value: String) = api.credentialsSet(ref, value)
                        override fun credentialsUnset(ref: String) = api.credentialsUnset(ref)
                        override fun settingsMutateOps(ns: String, ops: JSONArray): Boolean =
                            runCatching { api.settingsMutateAuto(ns, ops) }.isSuccess
                        // Android 宿主
                        override fun isAutostart(): Boolean =
                            getSharedPreferences("dsh_host", MODE_PRIVATE).getBoolean("autostart", false)
                        override fun setAutostart(on: Boolean) {
                            getSharedPreferences("dsh_host", MODE_PRIVATE).edit()
                                .putBoolean("autostart", on).apply()
                        }
                        override fun hasNotificationPermission(): Boolean =
                            if (android.os.Build.VERSION.SDK_INT >= 33)
                                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            else true
                        override fun requestNotificationPermission() {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                            }
                        }
                        override fun setFontSize(px: String) {
                            getSharedPreferences("dsh_host", MODE_PRIVATE).edit()
                                .putInt("font_size", px.toIntOrNull() ?: 15).apply()
                            messageFontSize.value = px.toIntOrNull() ?: 15
                            runCatching { setGeneral("ui-theme", "fontSize", px) }   // 与 Web 端保持一致
                        }
                        override fun areNotificationsEnabled(): Boolean {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                            return if (android.os.Build.VERSION.SDK_INT >= 24) nm.areNotificationsEnabled() else true
                        }
                        override fun openNotificationSettings() {
                            runCatching {
                                val i = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                                startActivity(i)
                            }.onFailure { notify("请到系统设置 → 应用 → DSHA → 通知 手动开启") }
                        }
                        override fun enterBehavior(): String =
                            getSharedPreferences("dsh_host", MODE_PRIVATE)
                                .getString("enter_behavior", "send") ?: "send"
                        override fun setEnterBehavior(v: String) {
                            getSharedPreferences("dsh_host", MODE_PRIVATE).edit()
                                .putString("enter_behavior", v).apply()
                            enterBehavior.value = v
                        }
                    }
                }

                val drawerState = rememberDrawerState(DrawerValue.Closed)
                // **抽屉打开时刷新会话列表**：角标（"运行中"）取自 session/list，而列表此前只在
                // "当前会话 running 边沿"刷新 —— 在 A 会话开始运行后切到 B，等 A 在后台跑完，
                // 打开抽屉时 A 仍带"运行中"角标，菜单里还挂着"停止运行"（点下去对已结束的会话发 cancel）。
                LaunchedEffect(drawerState.isOpen) {
                    if (drawerState.isOpen) runCatching { refreshSessions() }
                        .onFailure {
                            Log.e("DshStream", "refreshSessions failed", it)
                            runOnUiThread { notify("刷新会话列表失败：${it.message ?: "引擎未就绪"}") }
                        }
                }
                val drawerScope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                            SessionDrawer(
                                sessions = sessions,
                                currentId = current,
                                onSwitch = { s -> drawerScope.launch { drawerState.close() }; switchSession(s.id) },
                                onNew = { drawerScope.launch { drawerState.close() }; createSession() },
                                onRename = { s -> drawerScope.launch { drawerState.close() }; renameDialog(s) },
                                onStop = { s -> stopSession(s.id) },
                                onOpenSettings = { drawerScope.launch { drawerState.close() }; openSettings.value = true },
                                sortMode = sortMode.value,
                                onSortMode = { m ->
                                    sortMode.value = m
                                    // 搜索中不重排（保留过滤结果）
                                    applySort()
                                },
                                groupMode = groupMode.value,
                                onGroupMode = { m -> groupMode.value = m },
                                pinnedIds = pinnedIds.value,
                                onPin = { s ->
                                    val cur = pinnedIds.value.toMutableSet()
                                    if (!cur.add(s.id)) cur.remove(s.id)
                                    pinnedIds.value = cur
                                    getSharedPreferences("dsh_host", MODE_PRIVATE).edit()
                                        .putStringSet("pinned_ids", cur).apply()
                                    applySort()
                                },
                                onFork = { s ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        runCatching { api.forkSession(s.id, null) }
                                            .onSuccess { res ->
                                                val newId = res.optString("sessionId")
                                                refreshSessions()
                                                runOnUiThread {
                                                    notify("已分叉：")
                                                    if (newId.isNotEmpty()) switchToSession(newId)
                                                }
                                            }
                                            .onFailure { runOnUiThread { notify("分叉失败：") } }
                                    }
                                },
                                onSearch = { q ->
                                    // 本地过滤（引擎 session/search 索引 openAt=never 已禁用，见审核）
                                    // 只记关键词，展示列表统一由 applySort() 落地（排序/置顶/搜索一致）
                                    sessionQuery.value = q
                                    applySort()
                                },
                            )
                        }
                    },
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopBar(
                                sessionTitle = title ?: sessions.firstOrNull { it.id == current }?.title ?: "DeepSeek",
                                modeLabel = "标准模式",
                                conn = connection.name,
                                onMenu = { drawerScope.launch { drawerState.open() } },
                                onRefresh = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        refreshSessions()
                                        val sid = sessionId
                                        if (sid != null) controller?.switchSession(sid)
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            // 会话/轨迹 分段控件（成熟 App 的导航形态：可点面积大、当前项有底色）
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                androidx.compose.material3.Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                ) {
                                    Row(Modifier.padding(2.dp)) {
                                        listOf("会话" to 0, "轨迹" to 1).forEach { (label, idx) ->
                                            val selected = currentView == idx
                                            Box(
                                                Modifier
                                                    .height(30.dp)
                                                    .background(
                                                        if (selected) MaterialTheme.colorScheme.surface
                                                        else androidx.compose.ui.graphics.Color.Transparent,
                                                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                                    )
                                                    .clickable { setView(idx) }
                                                    .padding(horizontal = 16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    label,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (selected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                // 当前视图的规模提示（会话=轮数 / 轨迹=行数）
                                Text(
                                    if (currentView == 0) "${nodes.size} 条消息" else "${trajectoryRows.size} 行轨迹",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                // 视图切换动效：左滑进「轨迹」时新页自右滑入、旧页向左退场；右滑回「会话」时反向。
                                // 用 AnimatedContent（而非 AnimatedVisibility）以便两侧各自持有状态。
                                androidx.compose.animation.AnimatedContent(
                                    targetState = currentView,
                                    transitionSpec = {
                                        val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(260)
                                        val fadeInSpec = androidx.compose.animation.core.tween<Float>(200)
                                        val fadeOutSpec = androidx.compose.animation.core.tween<Float>(160)
                                        if (targetState > initialState) {
                                            (androidx.compose.animation.slideInHorizontally(slide) { it } +
                                                androidx.compose.animation.fadeIn(fadeInSpec)) togetherWith
                                                (androidx.compose.animation.slideOutHorizontally(slide) { -it / 3 } +
                                                    androidx.compose.animation.fadeOut(fadeOutSpec))
                                        } else {
                                            (androidx.compose.animation.slideInHorizontally(slide) { -it / 3 } +
                                                androidx.compose.animation.fadeIn(fadeInSpec)) togetherWith
                                                (androidx.compose.animation.slideOutHorizontally(slide) { it } +
                                                    androidx.compose.animation.fadeOut(fadeOutSpec))
                                        }
                                    },
                                    label = "viewSwitch",
                                ) { viewIdx ->
                                when {
                                    viewIdx == 1 -> TrajectoryScreen(
                                        rows = trajectoryRows,
                                        onRowClick = { row ->
                                            trajectoryDetail.value = row
                                        },
                                        focusRow = focusTrajectoryRow,
                                        onSwipeRight = { setView(0) },
                                    )
                                    bootError.value != null -> ErrorView(bootError.value!!)
                                    nodes.isEmpty() && connection == SessionStreamController.ConnectionState.IDLE ->
                                        LoadingView()
                                    else -> ChatScreen(
                                        nodes = nodes,
                                        running = running,
                                        runningSince = controller!!.runningSince.collectAsState().value,
                                        aborted = controller!!.aborted.collectAsState().value,
                                        turnStats = turnStats,
                                        requestMeta = controller!!.requestMeta.collectAsState().value,
                                        messageFontSize = messageFontSize.value,
                                        presetLabel = currentPresetLabel.value,
                                        onPreset = {
                                            // 模式（Agent 预设）：**只在新建（hero）时选择**（对齐 web）
                                            android.app.AlertDialog.Builder(this@ComposeChatActivity)
                                                .setTitle("选择模式")
                                                .setItems(arrayOf("标准模式", "极简模式", "PTC 模式", "创造模式")) { _, w ->
                                                    val ids = arrayOf("standard", "minimal", "ptc", "cordis")
                                                    val sid = sessionId ?: return@setItems
                                                    dialogSetPreset(sid, ids[w])
                                                }
                                                .setNegativeButton("取消", null)
                                                .show()
                                        },
                                        stats = statsForDialog,
                                        queue = controller!!.queue.collectAsState().value,
                                        todos = controller!!.todos.collectAsState().value,
                                        goal = controller!!.goal.collectAsState().value,
                                        commands = listOf("/plan", "/goal", "/permission", "/compact", "/feedback"),
                                        fileCandidates = fileCandidates.value,
                                        attachments = pendingImages.value,
                                        sendMode = sendMode.value,
                                        permissionLabel = permissionDisplayName(effectivePermission),
                                        modelLabel = composerModelName,
                                        effortLabel = "强度 · ${composerEffortName}",
                                        onEffort = {
                                            // 强度独立入口：按**该模型自带档位**列出（引擎 modelCatalog 的 efforts）
                                            val sid = sessionId
                                            val mid = currentModelId()
                                            val efforts = effortsOf(mid)
                                            if (sid == null) {
                                                Unit
                                            } else if (efforts.isEmpty()) {
                                                notify("该模型没有可选的思考强度档位")
                                            } else {
                                                val labels = efforts.map { (id, zh) ->
                                                    if (id == defaultEffortOf(mid)) "$zh（默认）" else zh
                                                }.toTypedArray()
                                                android.app.AlertDialog.Builder(this@ComposeChatActivity)
                                                    .setTitle("思考强度")
                                                    .setItems(labels) { _, w ->
                                                        val eff = efforts[w].first
                                                        val prov = controller?.provider?.value?.takeIf { it.isNotEmpty() } ?: ""
                                                        lifecycleScope.launch(Dispatchers.IO) {
                                                            runCatching { api.selectModel(sid, prov, mid, eff) }
                                                                .onSuccess { refreshSessions() }
                                                                .onFailure {
                                                                    Log.e("DshStream", "selectEffort failed", it)
                                                                    runOnUiThread { notify("切换思考强度失败：${it.message ?: "引擎未就绪"}") }
                                                                }
                                                        }
                                                    }
                                                    .setNegativeButton("取消", null)
                                                    .show()
                                            }
                                        },

                                        onPermission = {
                                            // 权限下拉（对齐 web PermissionSelect：三选一 → /permission 命令）
                                            android.app.AlertDialog.Builder(this@ComposeChatActivity)
                                                .setTitle("权限预设")
                                                .setItems(arrayOf("只读", "工作区写", "完全访问")) { _, w ->
                                                    val ids = arrayOf("read-only", "workspace-write", "danger-full-access")
                                                    val sid = sessionId ?: return@setItems
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        runCatching { api.executeCommand(sid, "/permission ${ids[w]}") }
                                                            .onFailure {
                                                                Log.e("DshStream", "permission preset failed", it)
                                                                runOnUiThread { notify("切换权限失败：${it.message ?: "引擎未就绪"}") }
                                                            }
                                                    }
                                                }
                                                .setNegativeButton("取消", null)
                                                .show()
                                        },
                                        onModel = {
                                            // 模型下拉（对齐 web ModelSelect：提供方分组 → 模型 → 该模型的强度档）
                                            if (modelCatalogCache.value == null) {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    runCatching { api.modelCatalog() }
                                                        .onSuccess { modelCatalogCache.value = it }
                                                }
                                            }
                                            showModelMenu.value = true
                                        },
                                        
                                        onAttach = { pickImage() },
                                        onAttachRemove = { idx ->
                                            pendingImages.value = pendingImages.value.filterIndexed { i, _ -> i != idx }
                                        },
                                        onSendMode = { m -> sendMode.value = m },
                                        onCommand = { cmd ->
                                            // / 命令：经 commands/execute 执行（agentId=sessionId）
                                            val sid = sessionId ?: return@ChatScreen
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                runCatching { api.executeCommand(sid, cmd) }
                                                    .onFailure { Log.e("DshStream", "command failed", it) }
                                            }
                                        },
                                        onAtQuery = { q ->
                                            // @ 补全：fileReferences/list 查询候选。
                                            // **必须防抖**：此前每敲一个字符就发一次且不可取消，
                                            // 若 @a 的响应晚于 @abc 到达，会用过期候选覆盖新候选。
                                            val sid = sessionId ?: return@ChatScreen
                                            latestAtQuery = q   // 结果落地前比对此值，避免过期候选覆盖新候选
                                            atQueryJob?.cancel()
                                            atQueryJob = lifecycleScope.launch(Dispatchers.IO) {
                                                kotlinx.coroutines.delay(220)      // 防抖窗口
                                                runCatching {
                                                    api.fileReferences(sid, q)
                                                }.onSuccess { arr ->
                                                if (q != latestAtQuery) return@onSuccess   // 过期响应，丢弃
                                                    val list = mutableListOf<FileCandidate>()
                                                    for (i in 0 until arr.length()) {
                                                        val it = arr.optJSONObject(i) ?: continue
                                                        val path = it.optString("path", it.optString("name", it.optString("ref", "")))
                                                        if (path.isNotEmpty()) list.add(FileCandidate(path, path))
                                                    }
                                                    fileCandidates.value = list
                                                }
                                            }
                                        },
                                        onAtPick = { path ->
                                            // 选中候选：显示路径提示（文本已插入输入框）
                                            fileCandidates.value = emptyList()
                                        },
                                        onSend = { onSend(it) },
                                        onStop = { stopTurn() },
                                        onToolClick = { node ->
                                            // 工具详情面板：名称/状态/输入/输出（对齐 web inspectCall）
                                            toolDetail.value = node
                                        },
                                        onApproval = { node, approved ->
                                            // 审批卡按钮 → 应答当前挂起瀑布（value 用 ApprovalOutcome 词汇）
                                            val pe = pendingEvent
                                            if (pe != null && pe.event == "approval/request") {
                                                Log.d("DshUi", "approval tap approved=$approved eventId=${pe.eventId}")
                                                controller?.respondApproval(if (approved) "allowed-once" else "rejected")
                                            } else {
                                                // **绝不能退化成 stopTurn()**。此前这里直接停止本轮：
                                                // 切走会话再切回时 pendingEvent 已被清空（而引擎不会重发同一 waterfall），
                                                // 于是卡片上的「允许一次」看起来可点、点下去却**取消了整个回合** ——
                                                // 审批既没被允许也没被拒绝，用户完全不知道发生了什么。
                                                // 正确行为：不动引擎状态，只如实告知该审批已失效/需在别处处理。
                                                Log.d("DshUi", "approval tap without pending waterfall — 已失效，仅提示")
                                                notify("该审批已失效或已在别处处理，未做任何操作")
                                            }
                                        },
                                        // 待应答交互（提问/审批）内联在对话流中
                                        pendingEvent = pendingEvent,
                                        onApprovalDecision = { outcome -> controller?.respondApproval(outcome) },
                                        onQuestionSubmit = { answers -> controller?.respondQuestions(answers) },
                                        onPendingCancel = { controller?.cancelPendingEvent() },
                                        // **必须传**：这是对话流里那行「⏳ 等待你确认：xxx　处理」的点击入口。
                                        // 不传会落到 ChatScreen 的默认空实现 → 浮层被划走后
                                        // （onDismissRequest 置 false，而 LaunchedEffect 只在 eventId 变化时
                                        // 才重新自动弹出）**再没有任何办法打开浮层**；未决审批卡又被
                                        // suppressApproval 隐藏 → 用户无法应答，引擎永久挂起、回合卡死。
                                        onOpenPending = { pendingSheetOpen.value = true },
                                        onLongPress = { n -> messageActions.value = n },
                                        focusNodeKey = focusChatNode.value,
                                        onFocusConsumed = { focusChatNode.value = null },
                                        injectText = injectToComposer.value,
                                        onInjectConsumed = { injectToComposer.value = null },
                                        onStatsClick = { showStatsDialog.value = true },
                                        enterBehavior = enterBehavior.value,
                                        onSwipeLeft = { setView(1) },
                                        onSwipeRight = { drawerScope.launch { drawerState.open() } },
                                        onFeedback = { node, rating ->
                                            Log.d("DshUi", "feedback tap rating=$rating msg=${node.meta["messageId"]}")
                                            // 消息反馈：messageFeedback/put（messageId=node.key 的 assistant 消息 seq）
                                            val sid = sessionId ?: return@ChatScreen
                                            val messageId = node.meta["messageId"] ?: node.key
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                runCatching { api.messageFeedbackPut(sid, messageId, rating) }
                                                    .onSuccess {
                                                        runOnUiThread { Toast.makeText(applicationContext,
                                                            if (rating == "positive") "已标记 👍" else "已标记 👎", Toast.LENGTH_SHORT).show() }
                                                    }
                                                    .onFailure { Log.e("DshStream", "feedback failed", it) }
                                            }
                                        },
                                        onQueueRemove = { itemId ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                runCatching {
                                                    api.updateQueue(sessionId ?: return@launch, itemId,
                                                        JSONObject().put("kind", "remove"))
                                                }
                                            }
                                        },
                                        onQueueSteer = { itemId ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                runCatching {
                                                    api.updateQueue(sessionId ?: return@launch, itemId,
                                                        JSONObject().put("kind", "steer"))
                                                }
                                            }
                                        },
                                    )
                                }
                                }
                            }
                        }
                    }
                }

                if (openSettings.value) {
                    SettingsDialog(
                        ctx = this@ComposeChatActivity,
                        onClose = { openSettings.value = false },
                        provider = settingsProvider,
                    )
                }

                // 模型下拉菜单（对齐 web ModelSelect：catalog 分组 → 选择 selectModel）
                if (showModelMenu.value) {
                    val cat = modelCatalogCache.value ?: JSONObject()
                    val groups = cat.optJSONArray("groups") ?: JSONArray()
                    val menuItems = mutableListOf<Triple<String, String, String>>() // provider, model, label
                    for (g in 0 until groups.length()) {
                        val group = groups.optJSONObject(g) ?: continue
                        val gId = group.optString("id")
                        val models = group.optJSONArray("models") ?: continue
                        for (m in 0 until models.length()) {
                            val model = models.optJSONObject(m) ?: continue
                            menuItems.add(Triple(gId, model.optString("id"), model.optString("name", model.optString("id"))))
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { showModelMenu.value = false },
                        title = { Text("选择模型") },
                        text = {
                            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                                items(menuItems.size) { i ->
                                    val (prov, mid, label) = menuItems[i]
                                    Text(
                                        label,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showModelMenu.value = false
                                                val sid = sessionId ?: return@clickable
                                                // 只切模型；强度由**独立的强度胶囊**负责（两者入口分开）
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    runCatching { api.selectModel(sid, prov, mid) }
                                                        .onSuccess { refreshSessions() }
                                                        .onFailure {
                                                            Log.e("DshStream", "selectModel failed", it)
                                                            runOnUiThread { notify("切换模型失败：${it.message ?: "引擎未就绪"}") }
                                                        }
                                                }
                                            }
                                            .padding(vertical = 10.dp),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showModelMenu.value = false }) { Text("取消") }
                        },
                    )
                }

                // 待应答交互：**从下方弹出的浮层**（而非对话流里常驻的大卡片）。
                // 新事件到达即自动弹出；用户点完决策后事件清空 → 浮层自动关闭消失。
                val pending = controller!!.pendingEvent.collectAsState().value
                LaunchedEffect(pending?.eventId) {
                    if (pending != null) pendingSheetOpen.value = true
                    else pendingSheetOpen.value = false
                }
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                if (pending != null && pendingSheetOpen.value) {
                    androidx.compose.material3.ModalBottomSheet(
                        onDismissRequest = { pendingSheetOpen.value = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        PendingInteractionCard(
                            pe = pending,
                            onApprovalDecision = { decision ->
                                controller!!.respondApproval(decision)
                                pendingSheetOpen.value = false
                            },
                            onQuestionSubmit = { answers ->
                                controller!!.respondQuestions(answers)
                                pendingSheetOpen.value = false
                            },
                            onCancel = {
                                controller!!.cancelPendingEvent()
                                pendingSheetOpen.value = false
                            },
                        )
                    }
                }
                usageFromAction.value?.let { st ->
                    TurnUsageDialog(st) { usageFromAction.value = null }
                }

                // 长按消息 → 操作单（复制 / 重发 / 从此处分叉）
                messageActions.value?.let { n ->
                    val ctx = applicationContext
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { messageActions.value = null },
                        title = { Text(if (n.kind == TranscriptNode.NodeKind.USER) "你的消息" else "助手消息", fontSize = 15.sp) },
                        text = {
                            Column {
                                ActionRow("复制全文") {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", n.text))
                                    messageActions.value = null
                                    notify("已复制")
                                }
                                // 分享出去（与"分享进来"对称：把消息带给其它 App）
                                ActionRow("分享到其它 App") {
                                    messageActions.value = null
                                    runCatching {
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, n.text)
                                        }
                                        startActivity(android.content.Intent.createChooser(send, "分享消息"))
                                    }.onFailure {
                                        Log.e("DshUi", "share out failed", it)
                                        notify("没有可分享的目标")
                                    }
                                }
                                if (n.kind == TranscriptNode.NodeKind.USER) {
                                    ActionRow("引用到输入框") {
                                        messageActions.value = null
                                        injectToComposer.value =
                                            n.text.lines().joinToString("\n") { "> $it" } + "\n\n"
                                        notify("已引用，可在输入框继续补充")
                                    }
                                }
                                if (n.kind == TranscriptNode.NodeKind.USER) {
                                    ActionRow("重新发送") {
                                        messageActions.value = null
                                        onSend(n.text)
                                        notify("已重新发送")
                                    }
                                }
                                val seq = n.meta["seq"]?.toIntOrNull() ?: -1
                                ActionRow("从此处分叉新会话") {
                                    messageActions.value = null
                                    val sid = sessionId
                                    if (sid == null) { notify("无会话"); return@ActionRow }
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        runCatching { api.forkSession(sid, if (seq >= 0) seq else null) }
                                            .onSuccess { res ->
                                                val newId = res.optString("sessionId")
                                                runOnUiThread {
                                                    notify("已分叉出新会话")
                                                    if (newId.isNotEmpty()) {
                                                        setView(0)
                                                        switchToSession(newId)
                                                    }
                                                }
                                            }
                                            .onFailure {
                                                runOnUiThread { notify("分叉失败：${it.message ?: "引擎拒绝"}") }
                                            }
                                    }
                                }
                                val st = turnStats[n.turn]
                                if (st != null) {
                                    ActionRow("查看本轮用量") {
                                        messageActions.value = null
                                        usageFromAction.value = st
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { messageActions.value = null }) { Text("关闭") }
                        },
                    )
                }
                // 首启引导：把"引擎在哪 / 手势 / 关键设置在何处"一次讲清，并提供直达入口
                if (showWelcome.value) {
                    AlertDialog(
                        onDismissRequest = { showWelcome.value = false },
                        title = { Text("欢迎使用 DSHA", fontSize = 17.sp) },
                        text = {
                            Column {
                                WelcomePoint(
                                    "🧠",
                                    "引擎在手机里",
                                    "DeepSeek Harness 以 bionic 前缀 + node 直接跑在本机，前台服务守护、崩溃自动重启。界面是 Jetpack Compose 原生渲染，不是 WebView。",
                                )
                                WelcomePoint(
                                    "👉",
                                    "左右滑切换视图",
                                    "会话页左滑进「轨迹」（看每个工具调用与彩色操作轨迹），右滑拉出会话抽屉；轨迹页右滑返回。",
                                )
                                WelcomePoint(
                                    "👆",
                                    "长按消息有操作",
                                    "复制 / 引用到输入框 / 从此处分叉新会话 / 查看本轮用量。",
                                )
                                WelcomePoint(
                                    "🔑",
                                    "API Key 与权限",
                                    "本应用不含任何内置密钥，请到 设置 → Android 宿主 → API Key 填入你自己的密钥；权限默认在 设置 → 通用 调整，那里还能开关「自启引擎」。",
                                )
                                WelcomePoint(
                                    "🛠",
                                    "让工具能执行命令",
                                    "Android 没有 bubblewrap/沙箱，默认权限下 shell 类工具会被拒绝。" +
                                        "可在 设置 → 通用 把权限默认设为「danger-full-access」；" +
                                        "若本机已 root，再到 设置 → Android 宿主 打开「Root 模式」，工具即可执行真实命令。",
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showWelcome.value = false
                                openSettings.value = true
                            }) { Text("去设置") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWelcome.value = false }) { Text("开始使用") }
                        },
                    )
                }

                // 完整统计面板（统计行被截断时的兜底）
                if (showStatsDialog.value) {
                    StatsDialog(statsForDialog) { showStatsDialog.value = false }
                }

                // 轨迹行详情（类型中文化 + 状态 + 调用 ID + 反向跳转到会话）
                trajectoryDetail.value?.let { row ->
                    AlertDialog(
                        onDismissRequest = { trajectoryDetail.value = null },
                        title = { Text(row.title, fontSize = 16.sp) },
                        text = {
                            Column {
                                DetailRow("类型", rowKindLabel(row.kind))
                                DetailRow("状态", rowStatusLabel(row.status))
                                DetailRow("位置", "T${row.turn} · 步骤 ${row.step}")
                                DetailRow("耗时", formatDuration(row.durationMs))
                                if (row.callId.isNotEmpty()) {
                                    DetailRow("调用 ID", row.callId)
                                }
                                if (row.detail.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(row.detail, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                            }
                        },
                        confirmButton = {
                            // 反向跳转：轨迹 → 会话中对应位置（与会话→轨迹形成闭环）
                            TextButton(onClick = {
                                trajectoryDetail.value = null
                                if (row.callId.isNotEmpty() || row.key.isNotEmpty()) {
                                    focusChatNode.value = row.callId.ifEmpty { row.key }
                                    setView(0)
                                }
                            }) { Text("在会话中查看") }
                        },
                        dismissButton = {
                            TextButton(onClick = { trajectoryDetail.value = null }) { Text("关闭") }
                        },
                    )
                }

                // 工具详情面板（inspectCall）
                toolDetail.value?.let { node ->
                    AlertDialog(
                        onDismissRequest = { toolDetail.value = null },
                        title = { Text("⚙ ${node.toolName.ifEmpty { node.toolStatus }}", fontSize = 16.sp) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text("状态: ${node.toolStatus}", fontSize = 12.sp, color = if (node.toolStatus == "失败") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                if (node.toolArgs.isNotEmpty()) {
                                    Text("输入", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(node.toolArgs, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (node.toolResult.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text("输出", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(node.toolResult, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { toolDetail.value = null }) { Text("关闭") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                val callId = node.meta["toolCallId"]
                                toolDetail.value = null
                                if (callId != null) {
                                    setView(1) // 切到轨迹
                                    focusTrajectoryRow = callId
                                }
                            }) { Text("查看轨迹") }
                        },
                    )
                }
            }
        }

        Thread(::bootstrap, "dsh-compress-boot").start()
    }

    /** 首启引导条目：图标 + 标题 + 说明。 */
    @Composable
    private fun WelcomePoint(icon: String, title: String, body: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(icon, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** 轨迹行类型 → 中文标签（此前直接显示英文枚举名，与中文界面不一致）。 */
    private fun rowKindLabel(kind: TrajectoryRow.RowKind): String = when (kind) {
        TrajectoryRow.RowKind.TOOL -> "工具调用"
        TrajectoryRow.RowKind.ASSISTANT -> "助手消息"
        TrajectoryRow.RowKind.USER -> "用户消息"
        TrajectoryRow.RowKind.REASONING -> "推理"
        TrajectoryRow.RowKind.STEP -> "步骤"
        TrajectoryRow.RowKind.COMPACTION -> "上下文压缩"
        TrajectoryRow.RowKind.ERROR -> "错误"
        TrajectoryRow.RowKind.SYSTEM -> "系统"
    }

    /** 轨迹行状态 → 中文标签。 */
    private fun rowStatusLabel(status: TrajectoryRow.RowStatus): String = when (status) {
        TrajectoryRow.RowStatus.RUNNING -> "运行中"
        TrajectoryRow.RowStatus.DONE -> "完成"
        TrajectoryRow.RowStatus.FAILED -> "失败"
        TrajectoryRow.RowStatus.WAITING -> "等待"
    }

    /** 详情弹窗里的一行（左标签右值）。 */
    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
            Text(value, fontSize = 12.sp)
        }
    }

    /** 操作单里的一行（整行可点）。 */
    @Composable
    private fun ActionRow(label: String, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp)
        }
    }

    /** 切换会话（长按分叉后跳转用）。 */
    /** 切换会话/轨迹页签并持久化（进程被杀后回到同一页）。 */
    private fun setView(v: Int) {
        viewTab.value = v
        getSharedPreferences("dsh_host", MODE_PRIVATE).edit().putInt("last_view", v).apply()
    }

    private fun switchToSession(id: String) {
        sessionId = id
        currentSessionId.value = id
        prefsPut(id)          // 统一持久化：抽屉切换/分叉跳转也要能被"进程被杀后恢复"读到
        // 模式胶囊要显示**该会话真正的预设**，否则切走再切回会回落成"标准"（标签只在
        // dialogSetPreset 里改过，切换会话时从不刷新）—— 用户会以为预设丢了。
        // session/list 已经把 preset 解析进 SessionItem，这里直接取用。
        runCatching {
            fullSessions.firstOrNull { it.id == id }?.preset?.takeIf { it.isNotEmpty() }?.let {
                currentPresetLabel.value = presetLabelOf(it)
            }
        }
        controller?.switchSession(id)
    }

    @Composable
    private fun TopBar(sessionTitle: String, modeLabel: String, conn: String, onMenu: () -> Unit, onRefresh: () -> Unit) {
        Surface(shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = "会话")
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sessionTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                modeLabel,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(connLabel(conn), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Sync, contentDescription = "刷新会话", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    @Composable
    private fun LoadingView() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在连接引擎…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ErrorView(message: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text("⚠️", fontSize = 34.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "启动引擎失败",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 重试：重新走一遍 bootstrap（多数情况是引擎还在启动/被系统限制）
                    Button(onClick = {
                        bootError.value = null
                        Thread(::bootstrap, "dsh-retry-boot").start()
                    }) { Text("重试") }
                    // 兜底：请前台服务重新拉起引擎进程
                    OutlinedButton(onClick = {
                        runCatching { DshHostService.restart(applicationContext) }
                        notify("已请求重启引擎，稍候自动重试")
                        bootError.value = null
                        Thread(::bootstrap, "dsh-retry-boot").start()
                    }) { Text("重启引擎") }
                }
            }
        }
    }

    // ---------------- bootstrap ----------------

    /**
     * bootstrap 防重入：冷启动、两个「重试」入口、以及重鉴权线程都可能触发它。
     * 并发执行会在"一个会话都没有"时建出多个空会话并互相覆盖 sessionId。
     */
    private val bootstrapping = java.util.concurrent.atomic.AtomicBoolean(false)

    /** @ 补全的在飞请求（防抖用：新查询到来时取消旧的）。 */
    private var atQueryJob: kotlinx.coroutines.Job? = null

    /** 最近一次 @ 查询串（用于丢弃乱序返回的旧候选）。 */
    @Volatile private var latestAtQuery: String = ""

    private fun bootstrap() {
        if (!bootstrapping.compareAndSet(false, true)) {
            Log.d("DshStream", "bootstrap already running, skip")
            return
        }
        val t0 = System.currentTimeMillis()
        fun phase(name: String) =
            Log.d("DshPerf", "bootstrap.$name +${System.currentTimeMillis() - t0}ms")
        Log.d("DshStream", "bootstrap start")
        try {
            waitForEngine(); phase("engine")
            authLoop(); phase("auth")
            refreshSessions(); phase("sessions")
            // 模型目录：输入栏的「模型 · 强度」标签与模型选择器都依赖它（此前从不拉取 → 菜单为空）
            runCatching { api.modelCatalog() }.onSuccess { modelCatalogCache.value = it }; phase("catalog")
            val sid = pickSession(); phase("pick")
            sessionId = sid
            currentSessionId.value = sid
            // 必须持久化"上次所在会话"：否则下次启动读不到 last_session，
            // 而列表里没有非空会话时会**每次启动都新建一个**（实测连续三次启动得到三个会话）。
            prefsPut(sid)
            controller?.switchSession(sid); phase("switch")
            tryApplyPendingShare()   // 分享进来的内容 → 新会话 + 预填
            maybeHintRootMode()      // root 可用但未开启时给一次性提示
            maybeHintApiKey()        // 未设置 API Key 时明确提示（本应用不再内置任何默认密钥）
            // 首启欢迎：标记不存在时触发
            val prefs = getSharedPreferences("dsh_host", MODE_PRIVATE)
            if (!prefs.getBoolean("welcomed", false)) {
                showWelcome.value = true
                prefs.edit().putBoolean("welcomed", true).apply()
            }
            Log.d("DshStream", "streaming session $sid")
            runOnUiThread { bootError.value = null }   // 成功即清错误态（给失败态一条自动恢复路径）
        } catch (e: Exception) {
            Log.e("DshStream", "bootstrap failed", e)
            runOnUiThread { bootError.value = "启动失败: ${e.message}" }
        } finally {
            bootstrapping.set(false)
        }
    }

    private fun authLoop() {
        for (i in 0 until 8) {
            val t0 = System.currentTimeMillis()
            val token = waitForToken()
            val t1 = System.currentTimeMillis()
            api.authenticate(token)
            val t2 = System.currentTimeMillis()
            Log.d("DshPerf", "authLoop#$i token=${t1 - t0}ms auth=${t2 - t1}ms ok=${api.hasCookie()}")
            if (api.hasCookie()) return
            // 就绪后鉴权只需 ~14ms；原先固定 sleep 2500ms 让冷启动白白多花 2.5s
            // （实测：首次 828ms 未拿到 cookie，第二次 14ms 成功）
            Thread.sleep(400)
        }
        throw IllegalStateException("鉴权失败（引擎仍不稳定）")
    }

    private fun authenticateSync() {
        Thread {
            // 引擎崩溃后可能仍在重启：持续重试（最多 60s，间隔退避），成功才切流
            for (attempt in 0 until 12) {
                val ok = runCatching {
                    authLoop()
                    refreshSessions()
                    true
                }.getOrDefault(false)
                if (ok) {
                    // 引擎活了 → 清错误态并确保有会话在跟随。
                    // 此前若 bootstrap 在 switchSession 之前失败，sessionId 为空 → 这里直接 return，
                    // 界面会永远停在"启动失败"，只有手动重试才能恢复（实测遇到）。
                    runOnUiThread { bootError.value = null }
                    val sid = sessionId ?: runCatching { pickSession() }.getOrNull()
                    if (sid != null) {
                        sessionId = sid
                        runOnUiThread { currentSessionId.value = sid }
                        controller?.switchSession(sid)
                    }
                    Log.d("DshStream", "re-auth + follow OK (attempt $attempt)")
                    return@Thread
                }
                Log.w("DshStream", "re-auth attempt $attempt failed; retrying…")
                // 连续 3 次仍连不上 → 大概率是引擎进程真的没了（而非正在启动）：
                // 主动请前台服务把它拉起来（服务侧看门狗 + 这里的显式请求形成闭环自愈）
                if (attempt == 2) {
                    Log.w("DshStream", "engine unreachable — asking host service to restart it")
                    runCatching { DshHostService.restart(applicationContext) }
                }
                try {
                    Thread.sleep(3000L * (attempt + 1))
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            Log.e("DshStream", "re-auth gave up after 12 attempts")
            runOnUiThread { notify("无法连接引擎，已请后台服务重启；若仍不行请到 设置 → Android 宿主 手动重启") }
        }.start()
    }

    private fun refreshSessions() {
        try {
            val items = api.listSessions()
            val list = mutableListOf<SessionItem>()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                list.add(sessionItemOf(it))
            }
            fullSessions = list
            // 只经 applySort 落地（此前这里紧跟一句 sessionsState.value = list，
            // 会把排序/置顶结果覆盖回未排序列表 → 置顶看起来"没生效"）
            applySort()
        } catch (e: Exception) {
            Log.e("DshStream", "refreshSessions failed", e)
        }
    }

    private fun sessionItemOf(item: org.json.JSONObject): SessionItem {
        val id = item.optString("sessionId")
        val proj = item.optJSONObject("projections")?.optJSONObject("values")
        val titleRaw = proj?.opt("title")
        val title = (titleRaw as? String)?.takeIf { it.isNotEmpty() }
            ?: "会话 ${id.removePrefix("session-").take(8)}"
        val modelSel = proj?.optJSONObject("modelSelection")?.optJSONObject("next")
        return SessionItem(
            id = id,
            title = title,
            running = item.optBoolean("running"),
            updatedAt = item.optLong("updatedAt", 0L),
            model = modelSel?.optString("model", "") ?: "",
            preset = proj?.optString("agentPreset") ?: "",
            blank = item.optBoolean("blank", false),
            createdAt = item.optLong("createdAt", 0L),
        )
    }

    private fun pickSession(): String {
        val prefs = getSharedPreferences("dsh_host", MODE_PRIVATE)
        val saved = prefs.getString("last_session", null)
        if (saved != null) {
            if (sessionsState.value.any { it.id == saved }) return saved
        }
        sessionsState.value.firstOrNull { !it.blank }?.let { return it.id }
        // 一个会话都没有：新建后**必须刷新列表**，否则抽屉仍显示空状态（"0 个会话"）、
        // 而应用其实已在跟随这个新会话 —— 全新安装时必现（R60 实测）。
        val created = createSessionDirect()
        runCatching { refreshSessions() }
        return created
    }

    private fun createSessionDirect(preset: String? = null): String {
        val created = api.createSession(
            cwd = File(filesDir, "home").absolutePath,
            agentPreset = preset,
        )
        return created.optString("sessionId")
    }

    // ---------------- 会话操作 ----------------

    private fun createSession() {
        Thread {
            try {
                // 对齐 web：创建即打开（引擎默认 preset 或显式 standard），模型/权限走引擎默认
                val sid = createSessionDirect(preset = null)
                prefsPut(sid)
                refreshSessions()
                sessionId = sid; currentSessionId.value = sid
                controller?.switchSession(sid)
            } catch (e: Exception) {
                Log.e("DshStream", "createSession failed", e)
                runOnUiThread { notify("新建会话失败：${e.message ?: "引擎未就绪"}") }
            }
        }.start()
    }

    /** 新建会话向导：选权限 + 模型后创建并应用。（保留备用；正常走 createSession 默认值） */
    private fun createSessionWith(permission: String, provider: String, model: String) {
        Thread {
            try {
                val sid = createSessionDirect(preset = null)
                // 应用模型选择
                runCatching { api.selectModel(sid, provider, model) }
                // 应用权限预设（会话级 /permission 命令）
                runCatching { api.executeCommand(sid, "/permission ${permission}") }
                prefsPut(sid)
                refreshSessions()
                sessionId = sid; currentSessionId.value = sid
                controller?.switchSession(sid)
            } catch (e: Exception) {
                Log.e("DshStream", "createSessionWith failed", e)
            }
        }.start()
    }

    /** 应用 Agent 预设（会话级 agentPresets/select；空会话时生效，首 turn 后引擎锁定）。 */
    /** 应用 Agent 预设：空白会话可 select；否则新建会话带该预设（对齐 web「预设随创建」）。 */
    private fun dialogSetPreset(sid: String, preset: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val label = presetLabelOf(preset)
            val res = runCatching { api.agentPresetsSelect(sid, preset) }
            res.onFailure {
                // **请求失败**与"预设已锁定"是两回事：此前一律当成后者 → 静默新建一个空会话并切走，
                // 用户看到的是"点了个预设，凭空多出一个空会话"，且原会话被丢在后面（M6）。
                Log.e("DshStream", "agentPresetsSelect failed", it)
                runOnUiThread {
                    notify("切换模式失败（会话未改动）：${it.message ?: "引擎未就绪"}")
                }
            }
            val changed = res.getOrNull()?.let { (it as? String) == preset } == true
            if (res.isFailure) return@launch     // 失败已提示，绝不退化成"新建空会话"
            if (changed) {
                currentPresetLabel.value = label
                refreshSessions()
            } else {
                // 会话预设已锁定：新建带该预设的会话
                Thread {
                    runCatching {
                        val newSid = createSessionDirect(preset = preset)
                        currentPresetLabel.value = label
                        prefsPut(newSid)
                        refreshSessions()
                        sessionId = newSid; currentSessionId.value = newSid
                        controller?.switchSession(newSid)
                    }
                }.start()
            }
        }
    }

    private fun switchSession(sid: String) {
        if (sid == sessionId) return
        Thread {
            prefsPut(sid)
            sessionId = sid; currentSessionId.value = sid
            controller?.switchSession(sid)
        }.start()
    }

    private fun stopSession(sid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { api.cancel(sid) }
                .onFailure {
                    Log.e("DshStream", "cancel failed", it)
                    runOnUiThread { notify("停止失败：${it.message ?: "引擎未就绪"}") }
                }
            // 稍等引擎状态落定后刷新列表：否则被取消会话的"运行中"角标会一直是旧的
            // （取消的是**其它**会话时，当前会话的 running 不变，不会触发自动刷新）
            kotlinx.coroutines.delay(1200)
            runCatching { refreshSessions() }
        }
    }

    private fun prefsPut(sid: String) {
        getSharedPreferences("dsh_host", MODE_PRIVATE).edit().putString("last_session", sid).apply()
    }

    private fun renameDialog(s: SessionItem) {
        val et = android.widget.EditText(this).apply {
            setText(s.title)
            isSingleLine = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) Thread {
                    runCatching { api.rename(s.id, t) }
                    refreshSessions()
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onSend(text: String) {
        val sid = sessionId ?: run { notify("尚未连接会话，请稍候"); return }
        val mode = sendMode.value
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (pendingImages.value.isNotEmpty()) {
                    api.promptWithImages(sid, text, pendingImages.value, mode)
                    pendingImages.value = emptyList()
                } else {
                    api.prompt(sid, text, mode)
                }
            }.onFailure {
                Log.e("DshStream", "prompt failed", it)
                // 发送失败必须让用户知道（此前只有日志 → 表现为"点了没反应"），
                // 并且**把草稿还回输入框**：ComposerBar 发送时已清空输入，
                // 不返还就等于长消息一键丢失。
                runOnUiThread {
                    injectToComposer.value = text
                    notify("发送失败（内容已放回输入框）：${it.message ?: "引擎未就绪"}")
                }
            }
        }
    }

    /** 图片附件：相册 picker → 压缩（最长边 2048）→ base64 待发。 */
    private fun pickImage() {
        // 先于选择器拦截：避免用户挑完才发现超限
        if (pendingImages.value.size >= MAX_ATTACHMENTS) {
            notify("最多 $MAX_ATTACHMENTS 张图片，请先发送或移除已有附件")
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        imageLauncher.launch(intent)
    }

    private val imageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        // 附件数量上限：base64 图片全量驻留内存并随 POST 发送，无上限会 OOM / 请求超时
        if (pendingImages.value.size >= MAX_ATTACHMENTS) {
            notify("最多 $MAX_ATTACHMENTS 张图片，请先发送或移除已有附件")
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val mediaType = contentResolver.getType(uri) ?: "image/jpeg"
                // 先在解码阶段降采样：避免"先解全尺寸再缩放"造成的 4× 内存峰值
                // （4000×3000 全尺寸 ARGB ≈ 48MB；按 2048 解码后 ≈ 12MB）
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var decodeSample = 1
                while (bounds.outWidth / (decodeSample * 2) >= 2048 ||
                    bounds.outHeight / (decodeSample * 2) >= 2048
                ) decodeSample *= 2
                val bmp = android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = decodeSample },
                ) ?: return@runCatching
                val format = when (mediaType.lowercase()) {
                    "image/png" -> android.graphics.Bitmap.CompressFormat.PNG
                    "image/webp" -> android.graphics.Bitmap.CompressFormat.WEBP
                    else -> android.graphics.Bitmap.CompressFormat.JPEG
                }
                // 逐级降质/降尺寸，直到 ≤ MAX_ATTACHMENT_BYTES（避免超大 base64 撑爆请求体）
                var payload: ByteArray? = null
                for ((dim, quality) in listOf(2048 to 85, 1600 to 80, 1280 to 70, 1024 to 60)) {
                    val scale = minOf(1f, dim.toFloat() / maxOf(bmp.width, bmp.height))
                    val resized = if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(
                            bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1), true,
                        )
                    } else bmp
                    val out = java.io.ByteArrayOutputStream()
                    resized.compress(format, quality, out)
                    if (resized !== bmp) resized.recycle()   // 缩放副本用完即释放
                    payload = out.toByteArray()
                    if (payload.size <= MAX_ATTACHMENT_BYTES) break
                }
                bmp.recycle()      // 显式释放解码位图，不等 GC
                val data = payload ?: return@runCatching
                if (data.size > MAX_ATTACHMENT_BYTES) {
                    throw IllegalStateException("图片过大（压缩后仍 ${data.size / 1024}KB），请换一张")
                }
                Log.d("DshNotif", "attachment ready: ${data.size / 1024}KB @decodeSample=$decodeSample")
                val img = org.json.JSONObject()
                    .put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
                    .put("mediaType", if (format == android.graphics.Bitmap.CompressFormat.JPEG) "image/jpeg" else mediaType)
                // 加入待发附件轨（不立即发送）
                pendingImages.value = pendingImages.value + img
            }.onSuccess {
                runOnUiThread { notify("图片已加入附件（${pendingImages.value.size}/$MAX_ATTACHMENTS）") }
            }.onFailure {
                Log.e("DshStream", "image prepare failed", it)
                runOnUiThread { notify("图片处理失败：${it.message}") }
            }
        }
    }

    private fun stopTurn() {
        val sid = sessionId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { api.cancel(sid) }
                .onFailure {
                    Log.e("DshStream", "cancel failed", it)
                    runOnUiThread { notify("停止失败：${it.message ?: "引擎未就绪"}") }
                }
        }
    }

    private fun waitForEngine() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching {
                val conn = (java.net.URL("http://127.0.0.1:3080/").openConnection() as java.net.HttpURLConnection)
                conn.connectTimeout = 2000; conn.readTimeout = 2000
                conn.connect(); conn.disconnect(); true
            }.getOrDefault(false)
            if (alive) return
            Thread.sleep(1500)
        }
        throw IllegalStateException("引擎未就绪")
    }

    private fun waitForToken(): String {
        val log = File(filesDir, "dsh-node.log")
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            // 只读**文件尾部**：日志是追加型且会持续增长，全量 readText + 全文件正则
            // 在长时间使用后代价明显（token 总是最后一行打印的）。
            val text = runCatching { readTail(log, 16 * 1024) }.getOrDefault("")
            val m = Regex("token=([A-Za-z0-9_-]+)").findAll(text).lastOrNull()
            if (m != null) return m.groupValues[1]
            Thread.sleep(500)
        }
        throw IllegalStateException("未等到引擎 token")
    }

    /** 读文件末尾至多 maxBytes 字节（用于在追加日志里找最新 token）。 */
    private fun readTail(file: File, maxBytes: Int): String {
        if (!file.exists()) return ""
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
    }
}

/**
 * 顶栏的连接状态文案。
 * 此前直接把 ConnectionState.name 显示出来 —— 界面上会出现 IDLE / CONNECTING / CONNECTED /
 * RECONNECTING / AUTH_LOST 这些英文枚举，与全中文界面不一致，且 AUTH_LOST 用户读不懂。
 */
private fun connLabel(state: String): String = when (state) {
    "IDLE" -> "未连接"
    "CONNECTING" -> "连接中…"
    "CONNECTED" -> "已连接"
    "RECONNECTING" -> "重连中…"
    "AUTH_LOST" -> "鉴权失效，重连中…"
    else -> state
}

/** Agent 预设 id → 界面显示名（与新建页/输入栏芯片保持一致）。 */
private fun presetLabelOf(preset: String): String = when (preset) {
    "minimal" -> "极简"
    "ptc" -> "PTC"
    "cordis" -> "创造"
    "standard" -> "标准"
    else -> "标准"
}
