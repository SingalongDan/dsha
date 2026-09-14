package dev.dsh.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 新建会话向导（对齐 web Workspace create：先选权限/模型/预设再创建）。
 * 数据来自 catalog 探测（modelCatalog/list + settingsDescribe）。
 */
@Composable
fun NewSessionDialog(
    onClose: () -> Unit,
    catalog: JSONObject?,
    onCreate: (permission: String, provider: String, model: String) -> Unit,
) {
    var permission by remember { mutableStateOf("workspace-write") }
    var providerId by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf<String?>(null) }

    val groups = catalog?.optJSONArray("groups") ?: org.json.JSONArray()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("新建会话", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("取消") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Text("权限预设", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OptionChip("只读", permission == "read-only", onClick = { permission = "read-only" })
                            OptionChip("工作区写", permission == "workspace-write", onClick = { permission = "workspace-write" })
                            OptionChip("完全访问", permission == "danger-full-access", onClick = { permission = "danger-full-access" })
                        }
                    }
                    item {
                        Text("模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                    // 提供方分组 + 模型列表
                    for (g in 0 until groups.length()) {
                        val group = groups.optJSONObject(g) ?: continue
                        val gId = group.optString("id")
                        val models = group.optJSONArray("models") ?: continue
                        item {
                            Text(
                                group.optString("name", gId),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        for (m in 0 until models.length()) {
                            val model = models.optJSONObject(m) ?: continue
                            val mid = model.optString("id")
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            providerId = gId
                                            modelId = mid
                                        }
                                        .padding(vertical = 8.dp)
                                        .background(
                                            if (modelId == mid) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        model.optString("name", mid),
                                        fontSize = 13.sp,
                                        color = if (modelId == mid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (modelId == mid) {
                                        Text("已选", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Button(
                    onClick = {
                        onClose()
                        onCreate(
                            permission,
                            providerId ?: return@Button,
                            modelId ?: return@Button,
                        )
                    },
                    enabled = providerId != null && modelId != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) {
                    Text("创建会话")
                }
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
