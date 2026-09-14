package io.github.singalongdan.dsha

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** @ 文件补全候选。 */
data class FileCandidate(val path: String, val display: String)

/**
 * 输入栏（对齐 dsh web composer）：
 *   [权限名▾] [模型 · 强度▾]  (仅运行时) [排队|插话]      [附件] [发送/停止]
 *   [  输入框（/ 命令、@ 文件名补全）  ]
 *
 * 依据 dsh web（见 `dsh-web-vs-android.md`）：
 * - **权限胶囊直接显示权限名**（web `input.accessMode`="访问模式，当前：{name}"），不再写"权限"二字
 * - **模型胶囊带该模型的思考强度档位**（web：effort 是 per-MODEL 能力，放在模型选择器内）
 * - **排队/插话仅在 agent 运行时出现**（web：`settings.enter.title`="繁忙时 Enter 键行为"、
 *   "仅在智能体运行时生效"；steer 在无运行时返回 `session/steer-unavailable`）
 * - **Agent 预设不在此处**（web 只在 hero 渲染 `conversation.hero.agentPreset`）
 */
@Composable
fun ComposerBar(
    running: Boolean,
    permissionLabel: String,
    modelLabel: String,
    /** 思考强度胶囊（与模型分开，各自独立入口）。 */
    effortLabel: String,
    commands: List<String>,
    fileCandidates: List<FileCandidate>,
    sendMode: String,
    onPermission: () -> Unit,
    onModel: () -> Unit,
    onEffort: () -> Unit,
    onAttach: () -> Unit,
    onCommand: (String) -> Unit,
    onAtQuery: (String) -> Unit,
    onAtPick: (String) -> Unit,
    onSendMode: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    /** 回车键行为："send"（默认）或 "newline"（多行编辑，发送键提交）。 */
    enterBehavior: String = "send",
    /** 外部注入到输入框的文本（如"引用消息"）；消费后回调置空。 */
    injectText: String? = null,
    onInjectConsumed: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    // 引用/注入：拼到现有草稿前面（保留用户已输入内容）
    LaunchedEffect(injectText) {
        if (!injectText.isNullOrEmpty()) {
            text = if (text.isBlank()) injectText else "$injectText\n$text"
            onInjectConsumed()
        }
    }
    var showCommands by remember { mutableStateOf(false) }
    var showAt by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 聚焦动效：聚焦时输入框展开（单行→多行，高度随之增长，无固定 height 避免截断）
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fieldHeight by androidx.compose.animation.core.animateDpAsState(if (focused) 88.dp else 52.dp)

    // 发送/Enter 共用（清空输入 + 收起键盘）
    // 发送：**运行中也允许**（引擎侧按"排队/插话"语义入队）。
    // 此前 `if (!running)` 直接把输入丢掉且没有任何提示，而输入栏又恰好显示着「排队/插话」两个芯片，
    // 语义自相矛盾 —— 用户以为发出去了，实际什么都没发生。
    val doSend: () -> Unit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t)
            text = ""
            keyboard?.hide()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        // 与 web 一致：输入区是 bg-base 上的**分层卡片**（layer-1）+ 顶边细分隔线，不用重阴影
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
    ) {
        Column {
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            // 单行元数据：[权限名] [模型 · 强度] ｜ (仅运行时) 排队/插话
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SelectChip(label = permissionLabel, onClick = onPermission, compact = true)
                SelectChip(label = modelLabel, onClick = onModel, compact = true, modifier = Modifier.weight(1f))
                SelectChip(label = effortLabel, onClick = onEffort, compact = true)
                if (running) {
                    // 仅运行时出现：空闲时这两个选项没有意义（web 同此语义）
                    ModeChip("排队", sendMode == "queue") { onSendMode("queue") }
                    ModeChip("插话", sendMode == "steer") { onSendMode("steer") }
                }
            }
            Spacer(Modifier.height(6.dp))
            // / 命令菜单
            if (showCommands && commands.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        commands.forEach { cmd ->
                            Text(
                                cmd,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showCommands = false
                                        onCommand(cmd)
                                        text = ""
                                        keyboard?.hide()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            // @ 文件补全候选
            if (showAt && fileCandidates.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        fileCandidates.take(6).forEach { c ->
                            Text(
                                c.display,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showAt = false
                                        onAtPick(c.path)
                                        text = "@${c.path}"
                                        keyboard?.hide()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            // 输入 + 附件 + 发送
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        val trimmed = it.trimStart()
                        // / 命令触发
                        showCommands = trimmed.length <= 12 && trimmed.startsWith("/") && !trimmed.contains(" ")
                        // @ 补全触发（@ 开头无空格；@ 单独输入也触发空查询）
                        val atQuery = if (trimmed.startsWith("@")) trimmed.drop(1).substringBefore(' ') else null
                        showAt = atQuery != null
                        if (atQuery != null) {
                            onAtQuery(atQuery)
                        }
                    },
                    modifier = Modifier.weight(1f).height(fieldHeight),
                    placeholder = { Text("输入消息…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = RoundedCornerShape(24.dp),
                    interactionSource = interaction,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = if (enterBehavior == "newline") androidx.compose.ui.text.input.ImeAction.Default
                        else androidx.compose.ui.text.input.ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (enterBehavior != "newline") doSend() },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = if (focused) 4 else 1,
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "附加", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { if (running) onStop() else doSend() },
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Stop else Icons.Filled.Send,
                        contentDescription = if (running) "停止" else "发送",
                        tint = Color.White,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (compact) 11.sp else 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(3.dp))
        Text("▾", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .height(28.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}
