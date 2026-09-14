package dev.dsh.host

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置六栏目（P4，Compose 版，**全屏**）：通用 / 模型 / 插件 / Agent 预设 / Android 宿主 / 关于。
 * 全部走真实 RPC（settings/describe|mutate、credentials、agentPresets、modelCatalog）。
 */
@Composable
fun SettingsDialog(
    ctx: Context,
    onClose: () -> Unit,
    provider: SettingsProvider,
) {
    var selectedSection by remember { mutableStateOf(0) }
    val sections = listOf("通用", "模型", "插件", "Agent预设", "Android宿主", "关于")

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                // 顶部栏：标题 + 关闭
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("设置", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("关闭", fontSize = 14.sp) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                // 左导航 + 右内容（全屏生效）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = 8.dp)
                ) {
                    Column(
                        Modifier
                            .width(132.dp)
                            .padding(end = 8.dp)
                            .fillMaxHeight()
                    ) {
                        sections.forEachIndexed { idx, name ->
                            val isSel = selectedSection == idx
                            Text(
                                name,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSection = idx }
                                    .padding(vertical = 12.dp)
                                    .background(
                                        if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        RoundedCornerShape(8.dp),
                                    ),
                            )
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.height(200.dp))
                    // 右侧内容
                    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp)) {
                        when (selectedSection) {
                            0 -> GeneralSettingsSection(provider)
                            1 -> ModelsSection(provider)
                            2 -> PluginsSettingsSection(provider)
                            3 -> PresetsSettingsSection(provider)
                            4 -> AndroidHostSection(provider)
                            else -> AboutSection(provider)
                        }
                    }
                }
            }
        }
    }
}

/** 设置数据提供者：桥接 RPC 调用（由 ComposeChatActivity 实现）。 */
interface SettingsProvider {
    fun settingsDescribe(): JSONObject
    fun agentPresetsList(): JSONObject
    fun agentPresetsSelect(sessionId: String, preset: String): String
    /** 返回 (DSH 版本, 运行时版本, 应用版本)，全部运行时读取。 */
    fun engineVersions(): Triple<String, String, String>
    fun modelCatalog(): JSONObject
    fun selectModel(sessionId: String, provider: String, model: String): JSONObject
    fun currentSessionId(): String?
    fun currentModel(): String
    fun isRootMode(): Boolean
    fun setRootMode(on: Boolean)
    fun restartEngine()
    fun apiKey(): String
    fun setApiKey(key: String)
    fun showApiKeyDialog(onSet: (Boolean) -> Unit)
    fun showThemePicker(onSet: (String) -> Unit)
    fun setDefaultPermission(preset: String): String
    fun showPermissionPicker(onSet: (String) -> Unit)
    fun pluginInventory(): JSONObject
    fun setGeneral(ns: String, path: String, value: String)
    fun activityContext(): Context
    // 模型页（对齐 web settings→Models）
    fun llmConfigurableProviders(): JSONArray
    fun credentialsDescribe(refs: List<String>): JSONObject
    fun credentialsSet(ref: String, value: String)
    fun credentialsUnset(ref: String)
    fun settingsMutateOps(ns: String, ops: JSONArray): Boolean
    // Android 宿主
    fun isAutostart(): Boolean
    fun setAutostart(on: Boolean)
    /** 设置消息正文字号（同时写入引擎设置与本地偏好，立即生效）。 */
    fun setFontSize(px: String)
    fun areNotificationsEnabled(): Boolean
    fun openNotificationSettings()
    fun hasNotificationPermission(): Boolean
    fun requestNotificationPermission()
    fun enterBehavior(): String
    fun setEnterBehavior(v: String)
}

// ---------------- 通用 ----------------

@Composable
private fun GeneralSettingsSection(provider: SettingsProvider) {
    var describe by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        describe = withContext(Dispatchers.IO) { runCatching { provider.settingsDescribe() }.getOrNull() }
    }
    // 从 namespaces 取值（通用助手）
    fun nsVal(ns: String, field: String, def: String): String {
        val arr = describe?.optJSONArray("namespaces") ?: return def
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            if (n.optString("ns") == ns) return n.optJSONObject("value")?.optString(field, def) ?: def
        }
        return def
    }
    var currentPerm by remember { mutableStateOf("") }
    var currentTheme by remember { mutableStateOf("system") }
    var currentLang by remember { mutableStateOf("zh") }
    var currentFont by remember { mutableStateOf("14") }
    var currentView by remember { mutableStateOf("compact") }
    var currentEnter by remember { mutableStateOf("queue") }

    LaunchedEffect(describe) {
        if (describe != null) {
            currentPerm = nsVal("permission", "defaultPreset", "workspace-write")
            currentTheme = nsVal("ui-theme", "preference", "system")
            currentLang = nsVal("locale", "preference", "zh")
            currentFont = nsVal("ui-theme", "fontSize", "14")
            currentView = nsVal("ui-chat", "transcriptView", "compact")
            currentEnter = nsVal("ui-conversation", "busyEnter", "queue")
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingRow(
                "权限默认",
                when (currentPerm) {
                    "read-only" -> "只读"
                    "danger-full-access" -> "完全访问"
                    else -> currentPerm
                },
                onClick = {
                    provider.showPermissionPicker(onSet = { currentPerm = it })
                },
            )
        }
        item {
            SettingRow(
                "语言（引擎/Web）",
                if (currentLang == "en") "English" else "中文",
                onClick = {
                    android.app.AlertDialog.Builder(provider.activityContext())
                        .setTitle("语言")
                        .setItems(arrayOf("中文", "English")) { _, w ->
                            val v = if (w == 0) "zh" else "en"
                            currentLang = v
                            provider.setGeneral("locale", "preference", v)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            )
        }
        item {
            SettingRow(
                "外观",
                if (currentTheme == "light") "浅色" else if (currentTheme == "dark") "深色" else "跟随系统",
                onClick = { provider.showThemePicker(onSet = { currentTheme = it }) },
            )
        }
        item {
            SettingRow(
                "字号",
                "${currentFont}px（消息正文）",
                onClick = {
                    val options = arrayOf("12px", "13px", "14px", "15px", "16px", "17px")
                    android.app.AlertDialog.Builder(provider.activityContext())
                        .setTitle("字号")
                        .setItems(options) { _, w ->
                            val v = options[w].removeSuffix("px")
                            currentFont = v
                            // 既写入引擎设置（与 Web 端一致），也**立即作用于 App 消息正文**
                            provider.setFontSize(v)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            )
        }
        item {
            SettingRow(
                "对话显示",
                if (currentView == "normal") "标准" else "精简",
                onClick = {
                    android.app.AlertDialog.Builder(provider.activityContext())
                        .setTitle("对话显示")
                        .setItems(arrayOf("精简 Compact", "标准 Normal")) { _, w ->
                            val v = if (w == 0) "compact" else "normal"
                            currentView = v
                            provider.setGeneral("ui-chat", "transcriptView", v)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            )
        }
        item {
            SettingRow(
                "繁忙时 Enter",
                if (currentEnter == "steer") "插话发送" else "排队发送",
                onClick = {
                    android.app.AlertDialog.Builder(provider.activityContext())
                        .setTitle("繁忙时 Enter")
                        .setItems(arrayOf("排队发送 queue", "插话发送 steer")) { _, w ->
                            val v = if (w == 0) "queue" else "steer"
                            currentEnter = v
                            provider.setGeneral("ui-conversation", "busyEnter", v)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            )
        }
        item {
            SettingRow(
                "回车键行为",
                if (provider.enterBehavior() == "newline") "换行（发送键提交）" else "直接发送",
                onClick = {
                    val next = if (provider.enterBehavior() == "newline") "send" else "newline"
                    provider.setEnterBehavior(next)
                },
            )
        }
    }
}

// ---------------- 模型 ----------------

@Composable
private fun ModelsSettingsSection(provider: SettingsProvider) {
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var current by remember { mutableStateOf(provider.currentModel()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) { runCatching { provider.modelCatalog() }.getOrNull() }
    }
    val groups = catalog?.optJSONArray("groups") ?: JSONArray()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingRow("当前模型", current.ifEmpty { "默认" }) }
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val models = group.optJSONArray("models") ?: continue
            item {
                Text(
                    group.optString("name", group.optString("id", "")),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            for (m in 0 until models.length()) {
                val model = models.optJSONObject(m) ?: continue
                val id = model.optString("id")
                item {
                    SettingRow(
                        title = model.optString("name", id),
                        value = if (current == id) "当前" else "点击选择",
                        onClick = {
                            val sid = provider.currentSessionId() ?: return@SettingRow
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { provider.selectModel(sid, group.optString("id"), id) }
                                }.onSuccess { current = id }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------- 插件 ----------------

@Composable
private fun PluginsSettingsSection(provider: SettingsProvider) {
    var inventory by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        inventory = withContext(Dispatchers.IO) { runCatching { provider.pluginInventory() }.getOrNull() }
    }
    val entries = inventory?.optJSONArray("entries") ?: JSONArray()
    val enabled = entries.optJSONObject(0)?.let {
        entries.length().let { n ->
            (0 until n).count { i -> entries.optJSONObject(i)?.optBoolean("enabled", false) == true }
        }
    } ?: 0
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingRow("插件总数", "${entries.length()} 个已注册")
        }
        item {
            SettingRow("已启用", "$enabled 个（含核心与可选）")
        }
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val name = e.optString("moduleName", e.optString("entryId", ""))
            if (name.isEmpty() || e.optString("entryId").startsWith("include:")) continue
            item {
                SettingRow(
                    title = name,
                    value = if (e.optBoolean("enabled", false)) "启用" else "禁用",
                )
            }
        }
    }
}

// ---------------- Agent 预设 ----------------

@Composable
private fun PresetsSettingsSection(provider: SettingsProvider) {
    var roster by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        roster = withContext(Dispatchers.IO) { runCatching { provider.agentPresetsList() }.getOrNull() }
    }
    val presets = roster?.optJSONArray("presets") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize()) {
        for (i in 0 until presets.length()) {
            val p = presets.optJSONObject(i) ?: continue
            val id = p.optString("id")
            item {
                SettingRow(
                    title = p.optString("name", id) + if (p.optBoolean("isDefault", false)) "（默认）" else "",
                    value = id,
                    onClick = {
                        val sid = provider.currentSessionId() ?: return@SettingRow
                        runCatching { provider.agentPresetsSelect(sid, id) }
                    },
                )
            }
        }
        if (presets.length() == 0) {
            item { Text("无可用预设", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---------------- Android 宿主 ----------------

@Composable
private fun AndroidHostSection(provider: SettingsProvider) {
    var rootMode by remember { mutableStateOf(provider.isRootMode()) }
    var keyMasked by remember { mutableStateOf(provider.apiKey().isNotEmpty()) }
    var autostart by remember { mutableStateOf(provider.isAutostart()) }
    var notifyGranted by remember { mutableStateOf(provider.hasNotificationPermission()) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SettingRow(
                title = "Root 模式",
                value = if (rootMode) "开启" else "关闭",
                onClick = {
                    if (!rootMode) {
                        // 开启前必须明确告知：Agent 的全部 shell 命令将以 root 执行，
                        // 且此后不再逐次弹授权（授权完全交给 KernelSU 等管理器）。
                        android.app.AlertDialog.Builder(provider.activityContext())
                            .setTitle("开启 Root 模式？")
                            .setMessage(
                                "开启后，Agent 的 bash 等 shell 工具将一律以 root 身份执行，" +
                                    "不再逐次征求授权。\n\n" +
                                    "仅在你信任当前会话要执行的内容时开启；" +
                                    "不需要时请随时关闭。"
                            )
                            .setPositiveButton("仍要开启") { _, _ ->
                                provider.setRootMode(true)
                                rootMode = true
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        provider.setRootMode(false)
                        rootMode = false
                    }
                    // 注意：状态**只在上面两个分支里改**（开启走确认回调、关闭走 else）。
                    // 这里若再写 `rootMode = !rootMode`（曾经如此）会在弹确认框的同时立刻翻转 UI：
                    // 点"取消"后行仍显示"开启"，且此后每次点击都走 else 再翻回 true
                    // —— 确认框再也不出现，Root 模式无法从界面开启。
                },
            )
        }
        item {
            SettingRow(
                title = "自启引擎",
                value = if (autostart) "开机后自动启动" else "关闭",
                onClick = {
                    provider.setAutostart(!autostart)
                    autostart = !autostart
                },
            )
        }
        item {
            // 通知状态取**有效值**：仅有运行时权限还不够，系统/用户可能已把本应用通知整体关闭
            // （大量重装或用户手动关闭都会如此），此时任何通知都不会出现。
            var enabled by remember { mutableStateOf(provider.areNotificationsEnabled()) }
            // 授权对话框是**异步**的：点击后立刻回读必然还是旧值（此前 UI 会一直显示"未授权"，
            // 用户以为没生效）。改为在回到前台时重新采样。
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        notifyGranted = provider.hasNotificationPermission()
                        enabled = provider.areNotificationsEnabled()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            SettingRow(
                title = "通知",
                value = when {
                    !notifyGranted -> "未授权（点击授权）"
                    !enabled -> "已被系统关闭（点击去开启）"
                    else -> "已开启"
                },
                onClick = {
                    if (!notifyGranted) {
                        provider.requestNotificationPermission()
                        // 结果由上面的 ON_RESUME 观察者回写，这里不立即回读（会是旧值）
                    } else if (!enabled) {
                        provider.openNotificationSettings()
                    }
                },
            )
        }
        item { SettingRow("引擎地址", "127.0.0.1:3080") }
        item {
            SettingRow(
                title = "API Key",
                value = if (keyMasked) "已设置（sk-…）" else "未设置（点击填写）",
                onClick = {
                    provider.showApiKeyDialog(onSet = { set -> keyMasked = set })
                },
            )
        }
        item { SettingRow("引擎日志", "files/dsh-node.log") }
        item { SettingRow("重启引擎", "崩溃/卡死/改设置后用", onClick = { provider.restartEngine() }) }
    }
}

// ---------------- 关于 ----------------

@Composable
private fun AboutSection(provider: SettingsProvider) {
    // 版本**运行时读取**，不写死：DSH 取自随包引擎的 package.json，node 实测运行，
    // 应用版本取自包信息 —— 这样升级引擎后关于页自动跟随，声明永远与实际一致。
    val versions = remember { mutableStateOf<Triple<String, String, String>?>(null) }
    LaunchedEffect(Unit) {
        versions.value = withContext(Dispatchers.IO) { provider.engineVersions() }
    }
    val v = versions.value
    LazyColumn(Modifier.fillMaxSize()) {
        item { SettingRow("应用", v?.third ?: "读取中…") }
        item { SettingRow("引擎（DSH）", v?.first ?: "读取中…") }
        item { SettingRow("运行时", v?.second ?: "读取中…") }
        item { SettingRow("渲染", "Jetpack Compose 原生（非 WebView）") }
        item { SettingRow("许可", "AGPL-3.0-only") }
        item { SettingRow("数据", "API Key 仅存本机，不会上传；本应用不含任何内置密钥") }
        item {
            Text(
                "DSHA 是 DeepSeek Harness 的 Android 原生客户端：引擎（bionic 前缀 + node）" +
                    "直接跑在手机本机，界面用 Jetpack Compose 渲染。\n\n" +
                    "本项目自有代码以 AGPL-3.0-only 发布（最严格的自由软件许可，SPDX: AGPL-3.0-only）；" +
                    "上游引擎 @deepseek-ai/dsh 为 MIT，运行时组件（bionic / openssl / Termux 前缀）与各 npm 依赖遵循各自许可，清单见仓库 NOTICE.md。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

// ---------------- 通用行 ----------------

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) {
            Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
