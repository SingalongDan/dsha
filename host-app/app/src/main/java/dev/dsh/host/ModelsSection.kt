package dev.dsh.host

import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型设置页（对齐 web settings→Models）：
 *   说明 → 当前模型 → 提供方列表（状态圆点 + 编辑器）→ 添加提供方 / 添加自定义提供方。
 * 数据：settings/describe + llm/listProviders + llm/listConfigurableProviders + credentials/describe。
 * 保存：settings/mutate（set/unset 路径差量）+ credentials/set（密钥只写）。
 */
@Composable
fun ModelsSection(provider: SettingsProvider) {
    var describe by remember { mutableStateOf<JSONObject?>(null) }
    var configurable by remember { mutableStateOf<JSONArray?>(null) }
    var creds by remember { mutableStateOf<JSONObject?>(null) }
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun nsValue(ns: String): JSONObject? {
        val arr = describe?.optJSONArray("namespaces") ?: return null
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            if (n.optString("ns") == ns) return n.optJSONObject("value") ?: JSONObject()
        }
        return null
    }
    fun nsRevision(ns: String): Long {
        val arr = describe?.optJSONArray("namespaces") ?: return 0
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            if (n.optString("ns") == ns) return n.optLong("revision", 0)
        }
        return 0
    }

    /** deepseek-official → llm-deepseek（无路径）；其他 → llm-pi-ai.providers.<id> */
    fun nsOf(id: String): String = if (id == "deepseek-official") "llm-deepseek" else "llm-pi-ai"
    fun pathOf(id: String): JSONArray =
        if (id == "deepseek-official") JSONArray() else JSONArray().put("providers").put(id)

    /** 读取某提供方的配置对象（deepseek 用 ns 根；pi-ai 用 providers.<id>） */
    fun providerValue(id: String): JSONObject? {
        val ns = nsOf(id)
        val v = nsValue(ns) ?: return null
        return if (id == "deepseek-official") v else v.optJSONObject("providers")?.optJSONObject(id)
    }

    /** 密钥引用名：优先 ns 的 apiKeyEnv，否则派生 <PROVIDER>_API_KEY */
    fun keyRefOf(id: String): String {
        val env = providerValue(id)?.optString("apiKeyEnv").orEmpty()
        if (env.isNotEmpty()) return env
        if (id == "deepseek-official") return "DEEPSEEK_API_KEY"
        return id.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"
    }

    fun credOf(ref: String): JSONObject? = creds?.optJSONObject(ref)

    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            describe = runCatching { provider.settingsDescribe() }.getOrNull()
            configurable = runCatching { provider.llmConfigurableProviders() }.getOrNull()
            catalog = runCatching { provider.modelCatalog() }.getOrNull()
            val refs = mutableListOf<String>()
            configurable?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val id = p.optString("provider")
                    val env = runCatching {
                        val ns = if (id == "deepseek-official") "llm-deepseek" else "llm-pi-ai"
                        val v = describe?.optJSONArray("namespaces")?.let { nsa ->
                            (0 until nsa.length()).mapNotNull { nsa.optJSONObject(it) }
                                .firstOrNull { it.optString("ns") == ns }?.optJSONObject("value")
                        }
                        if (id == "deepseek-official") v?.optString("apiKeyEnv").orEmpty()
                        else v?.optJSONObject("providers")?.optJSONObject(id)?.optString("apiKeyEnv").orEmpty()
                    }.getOrDefault("")
                    refs.add(if (env.isNotEmpty()) env else if (id == "deepseek-official") "DEEPSEEK_API_KEY"
                    else id.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY")
                }
            }
            creds = runCatching { provider.credentialsDescribe(refs.distinct().take(60)) }.getOrNull()
        }
    }

    val allProviders = configurable ?: JSONArray()
    val routable = catalog?.optJSONArray("groups") ?: JSONArray()
    val currentModel = catalog?.optJSONObject("default")?.optString("model").orEmpty()

    /** 已配置的提供方（web：settingsPath 为空或路径存在） */
    val configuredIds = mutableListOf<String>()
    for (i in 0 until allProviders.length()) {
        val p = allProviders.optJSONObject(i) ?: continue
        val id = p.optString("provider")
        if (id == "deepseek-official") { configuredIds.add(id); continue }
        if (providerValue(id) != null) configuredIds.add(id)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("模型", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "填入各提供方的 API 密钥即可使用其模型。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            savedMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text("已保存 $it。", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        // 当前模型（来自 modelCatalog.default）
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("当前模型", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    currentModel.ifEmpty { "未选择" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 提供方列表
        for (id in configuredIds) {
            val p = (0 until allProviders.length()).mapNotNull { allProviders.optJSONObject(it) }
                .firstOrNull { it.optString("provider") == id }
            val display = p?.optString("displayName")?.takeIf { it.isNotEmpty() } ?: id
            val declared = p?.optBoolean("declared", false) ?: false
            val ref = keyRefOf(id)
            val cred = credOf(ref)
            val configuredKey = cred?.optBoolean("configured", false) ?: false
            val keyWritable = cred?.optBoolean("writable", true) ?: true
            val keySource = cred?.optString("source").orEmpty()

            item {
                ProviderRow(
                    title = display,
                    subtitle = id,
                    tag = if (declared) "自定义" else null,
                    keyConfigured = configuredKey,
                    expanded = openId == id,
                    onToggle = { openId = if (openId == id) null else id },
                )
            }
            if (openId == id) {
                item {
                    ProviderEditor(
                        provider = provider,
                        id = id,
                        settingsNs = nsOf(id),
                        path = pathOf(id),
                        keyRef = ref,
                        keyConfigured = configuredKey,
                        keyWritable = keyWritable,
                        keySource = keySource,
                        value = providerValue(id) ?: JSONObject(),
                        revision = nsRevision(nsOf(id)),
                        onSaved = { msg ->
                            savedMsg = msg
                            reload++
                        },
                        onDeleted = {
                            savedMsg = "已删除 $display"
                            openId = null
                            reload++
                        },
                    )
                }
            }
        }

        // 添加区
        item {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                "＋ 添加提供方",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { adding = !adding }
                    .padding(vertical = 10.dp),
            )
            if (adding) {
                val addable = mutableListOf<Pair<String, String>>()
                for (i in 0 until allProviders.length()) {
                    val p = allProviders.optJSONObject(i) ?: continue
                    val pid = p.optString("provider")
                    if (pid == "deepseek-official") continue
                    if (providerValue(pid) != null) continue
                    val label = p.optString("displayName").takeIf { it.isNotEmpty() } ?: pid
                    addable.add(pid to label)
                }
                Text(
                    if (addable.isEmpty()) "没有可添加的提供方（内置目录已全部列出）" else "选择要添加的提供方：",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((pid, label) in addable.take(40)) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            provider.settingsMutateOps(
                                                "llm-pi-ai",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("op", "set")
                                                        .put("path", JSONArray().put("providers").put(pid))
                                                        .put("value", JSONObject()),
                                                ),
                                            )
                                        }
                                    }
                                    adding = false
                                    openId = pid
                                    savedMsg = label
                                    reload++
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }
            Text(
                "＋ 添加自定义提供方",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { customOpen = true }
                    .padding(vertical = 10.dp),
            )
        }
    }

    // 自定义提供方表单
    if (customOpen) {
        CustomProviderDialog(
            provider = provider,
            onClose = { customOpen = false },
            onCreated = { id ->
                customOpen = false
                openId = id
                savedMsg = id
                reload++
            },
        )
    }
}

/** 提供方行（对齐 web rowCard：displayName + 自定义标签 + 密钥状态圆点 + 展开箭头）。 */
@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    tag: String?,
    keyConfigured: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (keyConfigured) Color(0xFF22C55E) else Color(0xFFF25A5A),
                    CircleShape,
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tag,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                if (keyConfigured) "$subtitle · API 密钥已配置" else "$subtitle · API 密钥缺失",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (expanded) "▴" else "▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 提供方编辑器（对齐 web ProviderEditor：API 密钥 + API 地址 + 模型目录 + 保存/删除）。 */
@Composable
private fun ProviderEditor(
    provider: SettingsProvider,
    id: String,
    settingsNs: String,
    path: JSONArray,
    keyRef: String,
    keyConfigured: Boolean,
    keyWritable: Boolean,
    keySource: String,
    value: JSONObject,
    revision: Long,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = provider.activityContext()
    val scope = rememberCoroutineScope()
    var baseURL by remember(value) { mutableStateOf(value.optString("baseURL")) }
    var displayName by remember(value) { mutableStateOf(value.optString("displayName")) }
    var api by remember(value) { mutableStateOf(value.optString("api")) }
    var models by remember(value) {
        mutableStateOf<List<JSONObject>>(
            value.optJSONArray("models")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } ?: emptyList()
        )
    }
    var keyDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val isDeepSeek = id == "deepseek-official"
    val defaultBase = if (isDeepSeek) "https://api.deepseek.com" else ""

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 6.dp, bottom = 10.dp)
    ) {
        // API 密钥
        Text("API 密钥", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        val keyHint = when {
            !keyWritable -> "由启动环境提供（只读）"
            keyConfigured -> "已配置——输入新值可替换"
            else -> "输入 API 密钥"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                if (keyConfigured) "●●●●●●●●" else "（未配置）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (keyWritable) "设置" else keyHint,
                fontSize = 12.sp,
                color = if (keyWritable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = keyWritable) {
                        val et = EditText(ctx).apply {
                            hint = keyHint
                            isSingleLine = true
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("$keyRef")
                            .setView(et)
                            .setPositiveButton("保存") { _, _ ->
                                val v = et.text.toString().trim()
                                if (v.isNotEmpty()) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { runCatching { provider.credentialsSet(keyRef, v) } }
                                        onSaved("$keyRef")
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .padding(4.dp),
            )
        }
        if (keySource.isNotEmpty()) {
            Text("来源：$keySource", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!isDeepSeek) {
            Spacer(Modifier.height(8.dp))
            EditorRow("显示名称", displayName.ifEmpty { "（默认 $id）" }) {
                textInput(ctx, "显示名称", displayName) { displayName = it }
            }
            EditorRow("API 协议", api.ifEmpty { "openai-completions（默认）" }) {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("API 协议")
                    .setItems(arrayOf("openai-completions", "openai-responses", "anthropic-messages")) { _, w ->
                        api = arrayOf("openai-completions", "openai-responses", "anthropic-messages")[w]
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        EditorRow("API 地址", baseURL.ifEmpty { "提供方默认 ${defaultBase.ifEmpty { "" }}".trim() }) {
            textInput(ctx, "API 地址", baseURL) { baseURL = it }
        }

        // 模型目录
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型目录（${models.size}）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "＋ 添加模型",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    textInput(ctx, "模型 ID", "") { mid ->
                        if (mid.isNotEmpty()) models = models + JSONObject().put("id", mid).put("name", mid)
                    }
                }.padding(4.dp),
            )
        }
        for ((idx, m) in models.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    m.optString("id"),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    capLabel(m.optLong("contextWindow", 0)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "  删除",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { models = models.filterIndexed { i, _ -> i != idx } }.padding(4.dp),
                )
            }
        }

        // 操作行
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (busy) "保存中…" else "保存",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val ops = JSONArray()
                                    val base = JSONArray().apply { for (i in 0 until path.length()) put(path.opt(i)) }
                                    // baseURL
                                    if (baseURL.isNotEmpty()) {
                                        ops.put(JSONObject().put("op", "set").put("path", basePut(base, "baseURL")).put("value", baseURL))
                                    } else {
                                        ops.put(JSONObject().put("op", "unset").put("path", basePut(base, "baseURL")))
                                    }
                                    // displayName / api（非 deepseek）
                                    if (!isDeepSeek) {
                                        if (displayName.isNotEmpty()) ops.put(
                                            JSONObject().put("op", "set").put("path", basePut(base, "displayName")).put("value", displayName)
                                        )
                                        if (api.isNotEmpty()) ops.put(
                                            JSONObject().put("op", "set").put("path", basePut(base, "api")).put("value", api)
                                        )
                                    }
                                    // models
                                    if (models.isNotEmpty()) {
                                        ops.put(
                                            JSONObject().put("op", "set").put("path", basePut(base, "models"))
                                                .put("value", JSONArray().apply { models.forEach { put(it) } })
                                        )
                                    }
                                    provider.settingsMutateOps(settingsNs, ops)
                                }.getOrDefault(false)
                            }
                            busy = false
                            if (ok) onSaved(id) else onSaved("$id（保存失败）")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(10.dp))
            if (!isDeepSeek) {
                Text(
                    "删除提供方",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        provider.settingsMutateOps(
                                            settingsNs,
                                            JSONArray().put(
                                                JSONObject().put("op", "unset").put("path", path)
                                            ),
                                        )
                                        if (keyWritable) runCatching { provider.credentialsUnset(keyRef) }
                                    }
                                }
                                onDeleted()
                            }
                        }
                        .padding(6.dp),
                )
            } else {
                Text(
                    "恢复默认模型",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        provider.settingsMutateOps(
                                            settingsNs,
                                            JSONArray().put(
                                                JSONObject().put("op", "unset").put("path", JSONArray().put("models"))
                                            ),
                                        )
                                    }
                                }
                                onSaved("$id（已恢复默认模型）")
                            }
                        }
                        .padding(6.dp),
                )
            }
        }
    }
}

/** 自定义提供方表单（对齐 web CustomProviderCard）。 */
@Composable
private fun CustomProviderDialog(
    provider: SettingsProvider,
    onClose: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val ctx = provider.activityContext()
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var baseURL by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("openai-completions") }
    var apiKey by remember { mutableStateOf("") }

    android.app.AlertDialog.Builder(ctx)
        .setTitle("添加自定义提供方")
        .setMessage("Provider ID（小写字母开头，可含数字与短横线）\n当前：$id\n显示名：${displayName.ifEmpty { "（同 ID）" }}\nAPI 协议：$api\n地址：${baseURL.ifEmpty { "（必填）" }}")
        .setPositiveButton("下一步") { _, _ ->
            // 简化表单：用连续输入对话框收集字段
            textInput(ctx, "Provider ID（如 acme-gateway）", id) { v1 ->
                id = v1
                textInput(ctx, "显示名称（可留空）", displayName) { v2 ->
                    displayName = v2
                    textInput(ctx, "API 地址（必填，如 https://gateway.example/v1）", baseURL) { v3 ->
                        baseURL = v3
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("API 协议")
                            .setItems(arrayOf("openai-completions", "openai-responses", "anthropic-messages")) { _, w ->
                                api = arrayOf("openai-completions", "openai-responses", "anthropic-messages")[w]
                                textInput(ctx, "API 密钥（可留空）", "") { k ->
                                    apiKey = k
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                val obj = JSONObject().put("api", api).put("baseURL", baseURL)
                                                    .put("models", JSONArray())
                                                if (displayName.isNotEmpty()) obj.put("displayName", displayName)
                                                val ref = id.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"
                                                if (apiKey.isNotEmpty()) obj.put("apiKeyEnv", ref)
                                                provider.settingsMutateOps(
                                                    "llm-pi-ai",
                                                    JSONArray().put(
                                                        JSONObject().put("op", "set")
                                                            .put("path", JSONArray().put("providers").put(id))
                                                            .put("value", obj),
                                                    ),
                                                )
                                                if (apiKey.isNotEmpty()) provider.credentialsSet(ref, apiKey)
                                            }
                                        }
                                        onCreated(id)
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }
        .setNegativeButton("取消") { _, _ -> onClose() }
        .show()
}

@Composable
private fun EditorRow(label: String, valueText: String, onEdit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Text(
            valueText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("›", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun basePut(base: JSONArray, key: String): JSONArray =
    JSONArray().apply { for (i in 0 until base.length()) put(base.opt(i)); put(key) }

private fun capLabel(n: Long): String = when {
    n <= 0 -> ""
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1000 -> "${n / 1000}K"
    else -> n.toString()
}

/** 通用文本输入对话框。 */
private fun textInput(
    ctx: android.content.Context,
    title: String,
    initial: String,
    onSet: (String) -> Unit,
) {
    val et = EditText(ctx).apply {
        setText(initial)
        isSingleLine = true
    }
    android.app.AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(et)
        .setPositiveButton("确定") { _, _ -> onSet(et.text.toString().trim()) }
        .setNegativeButton("取消", null)
        .show()
}
