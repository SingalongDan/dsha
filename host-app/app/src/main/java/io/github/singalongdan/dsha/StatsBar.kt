package io.github.singalongdan.dsha

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 元数据行（单行紧凑，对齐 web chat stats）：
 *   [上下文 n%] N 轮 · M 步 ｜ LLM x · 工具 y · 首 token z · k tok/s · 缓存 n% ｜ 输入 a · 输出 b
 * 字号 10sp（8sp 低于可读下限）；单行截断，**点击查看完整统计**以免截断丢信息。
 * 上下文占用胶囊并入本行（此前单独一行，底部会堆两行元数据）。
 */
@Composable
fun StatsBar(
    stats: SessionStreamController.SessionStats,
    onExpand: (() -> Unit)? = null,
    contextUsed: Long = 0,
    contextWindow: Long = 0,
    onContextClick: (() -> Unit)? = null,
) {
    fun fmtDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
    val totalInput = stats.inputTokens + stats.cacheRead
    val cacheHit = if (totalInput > 0) (stats.cacheRead.toDouble() / totalInput * 100).roundToInt() else 0
    val tps = if (stats.decodeMs > 0) (stats.decodeTokens * 1000.0 / stats.decodeMs).roundToInt() else 0
    val ttftAvg = if (stats.ttftSteps > 0) stats.ttftMs / stats.ttftSteps else 0L

    val line = buildString {
        append("${stats.turns} 轮 · ${stats.steps} 步")
        append(" ｜ LLM ${fmtDuration(stats.llmMs)} · 工具 ${fmtDuration(stats.toolMs)}")
        if (stats.ttftSteps > 0) append(" · 首 token ${fmtDuration(ttftAvg)}")
        if (tps > 0) append(" · ${tps} tok/s")
        if (stats.cacheRead > 0) append(" · 缓存 ${cacheHit}%")
        if (stats.inputTokens > 0 || stats.outputTokens > 0) {
            append(" ｜ 输入 ${fmtTok(stats.inputTokens + stats.cacheRead + stats.cacheWrite)} · 输出 ${fmtTok(stats.outputTokens)}")
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(
                if (onExpand != null) Modifier.clickable { onExpand() } else Modifier
            ),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (onContextClick != null && (contextWindow > 0 || contextUsed > 0)) {
            ContextMeterPill(contextUsed, contextWindow, onContextClick)
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        }
        Text(
            line,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onExpand != null) {
            Text(" ⓘ", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 1000.0 / 1000.0)}M"
    n >= 1000 -> "${(n / 1000.0)}K"
    else -> n.toString()
}

/** 完整统计面板（统计行被截断时的兜底：所有指标逐行可读）。 */
@Composable
fun StatsDialog(stats: SessionStreamController.SessionStats, onClose: () -> Unit) {
    fun dur(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
    val totalInput = stats.inputTokens + stats.cacheRead
    val cacheHit = if (totalInput > 0) (stats.cacheRead.toDouble() / totalInput * 100) else 0.0
    val tps = if (stats.decodeMs > 0) stats.decodeTokens * 1000.0 / stats.decodeMs else 0.0

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("本次会话统计", fontSize = 16.sp) },
        text = {
            androidx.compose.foundation.layout.Column {
                StatsRow("轮次 / 步骤", "${stats.turns} / ${stats.steps}")
                StatsRow("LLM 用时", dur(stats.llmMs))
                StatsRow("工具用时", dur(stats.toolMs))
                if (stats.ttftSteps > 0) {
                    StatsRow("首 token（均值）", dur(stats.ttftMs / stats.ttftSteps))
                }
                if (tps > 0) StatsRow("输出速度", "%.1f tok/s".format(tps))
                StatsRow("缓存命中", "%.1f%%".format(cacheHit))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                StatsRow("未缓存输入", stats.inputTokens.toString())
                StatsRow("缓存读取", stats.cacheRead.toString())
                StatsRow("缓存写入", stats.cacheWrite.toString())
                StatsRow("输出", stats.outputTokens.toString())
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("关闭") } },
    )
}

@Composable
private fun StatsRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp)
    }
}