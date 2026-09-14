package dev.dsh.host

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown 渲染器（原生 Compose）：行内粗体/斜体/行内代码/链接，块级标题/列表/代码块/表格。
 * 覆盖 dsh 助手回复中实际出现的语法（不追求完整 GFM）。
 */
/** 分块结果 LRU 缓存：LazyColumn 滚动会销毁/重建离屏项，无缓存则每次滑回都重新解析。 */
private val blockCache = object : android.util.LruCache<String, List<Block>>(48) {}

/**
 * 预热解析缓存（供后台线程调用）。
 * `MarkdownText` 在**组合期**同步解析，长文本（实测 10.7 KB ≈ 18.7 ms）会掉一帧；
 * 快照应用后先在后台把这些长文本解析好，组合时即可直接命中缓存、不占帧时间。
 */
internal fun warmMarkdownCache(texts: Collection<String>, minLength: Int = 2000) {
    for (t in texts) {
        if (t.length < minLength) continue
        if (blockCache.get(t) != null) continue
        runCatching {
            val t0 = System.nanoTime()
            val blocks = splitBlocks(t)
            blockCache.put(t, blocks)
            android.util.Log.d(
                "DshPerf",
                "markdown warm: ${t.length} chars, ${blocks.size} blocks, " +
                    "${"%.1f".format((System.nanoTime() - t0) / 1_000_000.0)}ms (off-main)",
            )
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    fontSize: Int = 15,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val blocks = runCatching {
        val cached = blockCache.get(text)
        if (cached != null) {
            if (text.length > 4000) android.util.Log.d("DshPerf", "markdown cache HIT: ${text.length} chars")
            cached
        } else {
            val t0 = System.nanoTime()
            val b = splitBlocks(text)
            val parseMs = (System.nanoTime() - t0) / 1_000_000.0
            if (text.length > 4000) {
                android.util.Log.d(
                    "DshPerf",
                    "markdown parse: ${text.length} chars, ${b.size} blocks, ${"%.1f".format(parseMs)}ms",
                )
            }
            blockCache.put(text, b)
            b
        }
    }.getOrElse { listOf(Block(BlockKind.PARAGRAPH, text = text)) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block.kind) {
                BlockKind.PARAGRAPH -> {
                    Text(
                        buildInline(block.text, color = color, fontSize = fontSize, accent = MaterialTheme.colorScheme.primary),
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.5).sp,
                        color = color,
                    )
                }
                BlockKind.HEADING -> {
                    val level = block.level
                    val sizes = listOf(21, 19, 17, 15, 14, 13)
                    val size = sizes.getOrElse(level - 1) { 15 }
                    Text(
                        buildInline(block.text, color = color, fontSize = size, accent = MaterialTheme.colorScheme.primary),
                        fontSize = size.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                BlockKind.LIST_ITEM -> {
                    val indent = block.indent * 16
                    Row(Modifier.padding(start = indent.dp, top = 2.dp, bottom = 2.dp)) {
                        if (block.ordered) {
                            Text("${block.number}.", fontSize = fontSize.sp, color = color, modifier = Modifier.padding(end = 6.dp))
                        } else {
                            Text("•", fontSize = fontSize.sp, color = color, modifier = Modifier.padding(end = 6.dp))
                        }
                        Text(
                            buildInline(block.text, color = color, fontSize = fontSize, accent = MaterialTheme.colorScheme.primary),
                            fontSize = fontSize.sp,
                            color = color,
                        )
                    }
                }
                BlockKind.CODE -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        if (block.lang.isNotEmpty()) {
                            Text(block.lang, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                        }
                        // 代码块：横向滚动 + 不自动换行（换行会破坏命令/代码结构，
                        // 而编码场景里长命令行是常态）
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            Text(
                                highlightCode(block.text.trimEnd('\n'), block.lang),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                softWrap = false,
                            )
                        }
                    }
                }
                BlockKind.QUOTE -> {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.width(3.dp).heightIn(min = 20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            buildInline(block.text, color = color, fontSize = fontSize, accent = MaterialTheme.colorScheme.primary),
                            fontSize = fontSize.sp,
                            color = color,
                        )
                    }
                }
                BlockKind.TABLE -> {
                    // 列对齐：按全表最大列数补齐后再按权重分配（否则各行列数不同 → 列错位）。
                    // 首行视为表头（加粗 + 下方分隔线）。
                    val maxCols = block.rows.maxOfOrNull { it.size } ?: 0
                    if (maxCols > 0) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            block.rows.forEachIndexed { rowIndex, row ->
                                val isHeader = rowIndex == 0 && block.rows.size > 1
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    for (c in 0 until maxCols) {
                                        val cell = row.getOrElse(c) { "" }
                                        Text(
                                            buildInline(
                                                cell,
                                                color = color,
                                                fontSize = fontSize,
                                                accent = MaterialTheme.colorScheme.primary,
                                                bold = isHeader,
                                            ),
                                            fontSize = fontSize.sp,
                                            color = color,
                                            fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 6.dp),
                                        )
                                    }
                                }
                                if (isHeader) {
                                    androidx.compose.material3.HorizontalDivider(
                                        Modifier.padding(bottom = 2.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class BlockKind { PARAGRAPH, HEADING, LIST_ITEM, CODE, QUOTE, TABLE }
private data class Block(
    val kind: BlockKind,
    val text: String = "",
    val level: Int = 0,
    val indent: Int = 0,
    val ordered: Boolean = false,
    val number: Int = 0,
    val lang: String = "",
    val rows: List<List<String>> = emptyList(),
)

/** 按行分块（不处理嵌套列表的复杂语义，够用）。 */
private fun splitBlocks(text: String): List<Block> {
    val lines = text.split('\n')
    val out = mutableListOf<Block>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val lang = line.removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    code.add(lines[i]); i++
                }
                i++ // skip closing ```
                out.add(Block(BlockKind.CODE, text = code.joinToString("\n"), lang = lang))
                continue
            }
            line.startsWith("> ") -> {
                out.add(Block(BlockKind.QUOTE, text = line.removePrefix("> ").trim()))
                i++
                continue
            }
            line.startsWith("|") && i + 1 < lines.size && lines[i + 1].matches(Regex("\\|?\\s*:?-{2,}.*")) -> {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].startsWith("|")) {
                    val cells = lines[i].trim('|').split('|').map { it.trim() }
                    if (lines[i].matches(Regex("\\|?\\s*:?-{2,}.*\\|?")) && rows.isNotEmpty() && cells.all { it.isBlank() || it.startsWith(":") || it.matches(Regex(":?-+:?")) }) {
                        // 分隔行跳过
                    } else {
                        rows.add(cells)
                    }
                    i++
                }
                out.add(Block(BlockKind.TABLE, rows = rows))
                continue
            }
            else -> {
                val heading = Regex("^(#{1,6})\\s+(.*)").find(line)
                if (heading != null) {
                    out.add(Block(BlockKind.HEADING, text = heading.groupValues[2].trim(), level = heading.groupValues[1].length))
                    i++
                    continue
                }
                val list = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.*)").find(line)
                if (list != null) {
                    // 缩进级别：Tab 视为 2 空格（否则 Tab 缩进的嵌套项会被压成 0 级）
                    val indent = list.groupValues[1].replace("\t", "  ").length / 2
                    val marker = list.groupValues[2]
                    val ordered = marker.last() == '.'
                    val number = marker.trimEnd('.').toIntOrNull() ?: 0
                    out.add(Block(BlockKind.LIST_ITEM, text = list.groupValues[3].trim(),
                        indent = indent, ordered = ordered, number = number))
                    i++
                    continue
                }
                if (line.isBlank()) { i++; continue }
                // 连续非空行合并为段落
                val para = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() &&
                    !lines[i].startsWith("```") && !lines[i].startsWith("> ") &&
                    !Regex("^#{1,6}\\s").matches(lines[i]) &&
                    !Regex("^\\s*([-*+]|\\d+\\.)\\s").matches(lines[i])) {
                    para.add(lines[i].trim()); i++
                }
                out.add(Block(BlockKind.PARAGRAPH, text = para.joinToString("\n")))
                continue
            }
        }
    }
    return out
}

/** 行内解析：**粗体** *斜体* `代码`。 */
private fun buildInline(text: String, color: Color, fontSize: Int, accent: Color, bold: Boolean = false): AnnotatedString {
    // 表头等场景：整段加粗（内联 ** 仍可覆盖）
    return buildAnnotatedString {
        if (bold && text.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text); return@buildAnnotatedString }
        var i = 0
        var plainStart = 0
        fun flushTo(j: Int) { if (j > plainStart) append(text.substring(plainStart, j)) }
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        flushTo(i)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2; plainStart = i
                    } else i++
                }
                text.startsWith("*", i) && !text.startsWith("**") -> {
                    val end = text.indexOf("*", i + 1)
                    if (end > i && !text.startsWith("**", i)) {
                        flushTo(i)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1; plainStart = i
                    } else i++
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end > i) {
                        flushTo(i)
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = accent,)) { append(text.substring(i + 1, end)) }
                        i = end + 1; plainStart = i
                    } else i++
                }
                else -> i++
            }
        }
        flushTo(text.length)
    }
}

/**
 * 代码块轻量高亮（按语言简化着色：关键字/字符串/注释/数字，按行扫描）。
 * 不做完整语法树；任何异常都回退为纯文本（渲染路径绝不能崩）。
 */
private fun highlightCode(code: String, lang: String): AnnotatedString {
    return runCatching { highlightCodeUnsafe(code, lang) }
        .getOrElse { buildAnnotatedString { append(code) } }
}

private fun highlightCodeUnsafe(code: String, lang: String): AnnotatedString {
    val keywordColor = Color(0xFFCE9178)
    val stringColor = Color(0xFF6A9955)
    val commentColor = Color(0xFF6A9955)
    val numberColor = Color(0xFFB5CEA8)

    val keywords = setOf(
        "function", "return", "if", "else", "for", "while", "const", "let", "var",
        "new", "class", "async", "await", "import", "export", "from", "def", "with",
        "try", "catch", "finally", "throw", "switch", "case", "break", "continue",
        "true", "false", "null", "undefined", "None", "True", "False", "echo",
        "cd", "ls", "cat", "grep", "mv", "cp", "rm", "mkdir", "touch", "su",
        "print", "input", "run",
    )
    val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
    var lineStart = 0
    for (line in code.split('\n')) {
        var idx = 0
        while (idx < line.length) {
            val c = line[idx]
            if (c == '#' || (c == '/' && idx + 1 < line.length && line[idx + 1] == '/')) {
                spans.add((lineStart + idx) until (lineStart + line.length) to SpanStyle(color = commentColor))
                break
            }
            if (c == '"' || c == '\'') {
                val q = c
                var j = idx + 1
                while (j < line.length && line[j] != q) j++
                spans.add((lineStart + idx) until (lineStart + j + 1) to SpanStyle(color = stringColor))
                idx = j + 1
                continue
            }
            if (c.isDigit()) {
                var j = idx
                while (j < line.length && line[j].isDigit()) j++
                spans.add((lineStart + idx) until (lineStart + j) to SpanStyle(color = numberColor))
                idx = j
                continue
            }
            if (c.isLetter() || c == '_') {
                var j = idx
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(idx, j)
                if (word in keywords) {
                    spans.add((lineStart + idx) until (lineStart + j) to SpanStyle(color = keywordColor))
                }
                idx = j
                continue
            }
            idx++
        }
        lineStart += line.length + 1
    }
    // 规范化：裁剪到正文长度、丢弃空/越界 span、按起点排序并去重叠
    // （此前未闭合引号/注释会产生越界区间 → substring 抛 StringIndexOutOfBounds 导致闪退）
    val safe = spans
        .mapNotNull { (range, style) ->
            val start = range.first.coerceIn(0, code.length)
            val endExclusive = (range.last + 1).coerceIn(0, code.length)
            if (endExclusive <= start) null else (start until endExclusive) to style
        }
        .sortedBy { it.first.first }
    return buildAnnotatedString {
        if (safe.isEmpty()) { append(code); return@buildAnnotatedString }
        var pos = 0
        for ((range, style) in safe) {
            if (range.first < pos) continue          // 与前一个 span 重叠：跳过
            if (range.first > pos) append(code.substring(pos, range.first))
            withStyle(style) { append(code.substring(range.first, range.last + 1)) }
            pos = range.last + 1
        }
        if (pos < code.length) append(code.substring(pos))
    }
}
