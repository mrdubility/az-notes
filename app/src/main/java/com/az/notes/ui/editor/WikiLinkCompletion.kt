package com.az.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.domain.markdown.NoteAlias

/**
 * `[[` 内联补全：编辑器光标处于未闭合 `[[` 上下文时，在键盘上方给出
 * 「文件名 + aliases 别名」候选条，点选即以 `[[目标]]` 替换查询片段并闭合。
 *
 * 与渲染链同套保护口径（见 [com.az.notes.domain.markdown.WikiLinkTransformer]）：
 * 转义 `\[[`、行内代码、围栏代码块内不触发；查询片段跨行 / 已闭合 / 含
 * `#` `|` `[` `]` 时也不触发（避免与显示别名、标题锚点语法歧义）。
 */

/** `[[` 补全的候选条一次最多展示的条数。 */
internal const val WIKI_COMPLETE_LIMIT = 20

/**
 * 补全查询上下文：[start] 为 `[[` 起始偏移，[end] 为替换终点（光标；光标后紧随
 * `]]` 时含其后，使已闭合的链接整体被覆盖），[text] 为查询串。
 */
data class WikiLinkQuery(val start: Int, val end: Int, val text: String)

/**
 * 光标上下文检测：光标前最后一个 `[[` 未闭合时返回查询上下文，否则 null。
 * 查询可为空串（刚敲下 `[[`，展示全部候选）。
 */
fun wikiLinkQueryAt(content: CharSequence, cursor: Int): WikiLinkQuery? {
    if (cursor <= 0 || cursor > content.length) return null
    val head = content.lastIndexOf("[[", cursor - 2)
    if (head < 0) return null
    if (head > 0 && content[head - 1] == '\\') return null
    val inner = content.substring(head + 2, cursor)
    if (inner.contains('\n') || inner.contains("]]") || inner.contains('[') ||
        inner.contains(']') || inner.contains('#') || inner.contains('|')
    ) {
        return null
    }
    if (insideProtectedContext(content, head)) return null
    val end = if (startsWithAt(content, "]]", cursor)) cursor + 2 else cursor
    return WikiLinkQuery(head, end, inner.toString())
}

/** `[[` 补全候选项：`[[insertText]]` 直接可插入（别名条目为 `真名|别名` 形式）。 */
data class WikiLinkCandidate(
    val insertText: String,
    val label: String,
    val detail: String?,
    val isAlias: Boolean = false
)

/**
 * 构建全量候选项（进入编辑器后惰性构建一次并缓存）：
 * - 文件名条目：仅 `.md` 笔记；同名多路径时插入带仓库根相对路径消歧；
 * - 别名条目：插入 `真名|别名`（真名同名歧义时带路径），显示别名、副显真名。
 */
fun buildWikiLinkCandidates(
    names: Map<String, List<String>>,
    aliases: Map<String, List<NoteAlias>>
): List<WikiLinkCandidate> {
    val out = ArrayList<WikiLinkCandidate>()
    names.forEach { (key, paths) ->
        if (!key.endsWith(".md", ignoreCase = true)) return@forEach
        val ambiguous = paths.size > 1
        paths.forEach { relative ->
            val fileName = baseName(relative)
            out += WikiLinkCandidate(
                insertText = if (ambiguous) relative.dropLast(3) else fileName,
                label = fileName,
                detail = relative.substringBeforeLast('/', "").ifEmpty { null }
            )
        }
    }
    aliases.forEach { (_, entries) ->
        entries.forEach { entry ->
            val realName = baseName(entry.relativePath)
            val ambiguous = names[(realName + ".md").lowercase()].orEmpty().size > 1
            val realTarget = if (ambiguous) entry.relativePath.dropLast(3) else realName
            out += WikiLinkCandidate(
                insertText = if (entry.name.equals(realName, ignoreCase = true)) {
                    realTarget
                } else {
                    "$realTarget|${entry.name}"
                },
                label = entry.name,
                detail = realName,
                isAlias = true
            )
        }
    }
    return out
}

/**
 * 过滤 + 排序：[query] 为空返回前 [limit] 条；否则大小写不敏感包含匹配
 * （名称 / 插入串），前缀命中优先、字典序次之。
 */
fun filterWikiLinkCandidates(
    all: List<WikiLinkCandidate>,
    query: String,
    limit: Int
): List<WikiLinkCandidate> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return all.take(limit)
    return all.asSequence()
        .filter { it.label.lowercase().contains(q) || it.insertText.lowercase().contains(q) }
        .sortedWith(
            compareBy(
                {
                    !it.label.lowercase().startsWith(q) &&
                        !it.insertText.lowercase().startsWith(q)
                },
                { it.label.lowercase() }
            )
        )
        .take(limit)
        .toList()
}

/**
 * `[[` 补全候选条（工具栏上方一行，横向滚动）：点选即插入并闭合。
 * 别名条目带标签图标并以独立底色区分（副显对应真名）。
 */
@Composable
internal fun WikiLinkSuggestionBar(
    items: List<WikiLinkCandidate>,
    onPick: (WikiLinkCandidate) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = items,
            key = { "${it.isAlias}\u0000${it.label}\u0000${it.insertText}" }
        ) { item ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (item.isAlias) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clickable { onPick(item) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (item.isAlias) {
                        Icon(
                            Icons.Filled.Label,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Column {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (item.isAlias) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        item.detail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 内部

/** 去扩展名文件名（`.md` / `.MD` 均识别）。 */
private fun baseName(path: String): String {
    val name = path.substringAfterLast('/')
    return if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
}

/** [index] 处是否为 [needle]（不抛越界）。 */
private fun startsWithAt(content: CharSequence, needle: String, index: Int): Boolean {
    if (index + needle.length > content.length) return false
    for (i in needle.indices) {
        if (content[index + i] != needle[i]) return false
    }
    return true
}

/** 判定 [position] 是否处于围栏代码块或行内代码内（处于时不触发补全）。 */
private fun insideProtectedContext(content: CharSequence, position: Int): Boolean {
    // 围栏：起点前不存在 ``` / ~~~ 记号时零成本跳过逐行扫描
    if (content.indexOf("```") in 0 until position || content.indexOf("~~~") in 0 until position) {
        var inFence = false
        var lineStart = 0
        while (lineStart < position) {
            val newline = content.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0 || newline >= position) position else newline + 1
            val line = content.subSequence(lineStart, lineEnd).toString().trimStart()
            if (line.startsWith("```") || line.startsWith("~~~")) inFence = !inFence
            if (newline < 0 || newline >= position) break
            lineStart = lineEnd
        }
        if (inFence) return true
    }
    // 行内代码：本行光标前反引号计数为奇数视为处于代码片段中
    val lineStart = content.lastIndexOf('\n', position - 1).let { if (it < 0) 0 else it + 1 }
    var backticks = 0
    for (i in lineStart until position) {
        if (content[i] == '`') backticks++
    }
    return backticks % 2 == 1
}
