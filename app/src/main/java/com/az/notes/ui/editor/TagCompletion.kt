package com.az.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.az.notes.R
import com.az.notes.data.storage.TagRow

/**
 * `#标签` 内联补全：编辑器光标处于未完成的 `#标签名` 上下文时弹出浮层
 * （搜索框 + 已有标签候选列表），点选即以 `#名称` 替换查询片段。
 *
 * 与渲染链同套保护口径（见 [com.az.notes.domain.markdown.TagMatcher]）：
 * 转义 `\#`、行内代码、围栏代码块内不触发；`#` 前必须不是字母数字与 URL
 * 前导字符（中文标点紧贴仍可触发）；行首刚敲 `#`（标题待定）不触发。
 */

/** 标签补全浮层的候选列表一次最多展示的条数。 */
private const val TAG_PICKER_LIMIT = 200

/**
 * 补全查询上下文：[start] 为 `#` 起始偏移，[end] 为替换终点（光标），
 * [text] 为 `#` 与光标之间的输入串。
 */
data class TagQuery(val start: Int, val end: Int, val text: String)

/**
 * 光标上下文检测：光标前存在未完成的 `#标签名` 时返回查询上下文，否则 null。
 * 刚敲下 `#`（后无字符）返回 null（行首场景留给标题输入；工具栏按钮走手动打开）。
 */
fun tagQueryAt(content: CharSequence, cursor: Int): TagQuery? {
    if (cursor <= 1 || cursor > content.length) return null
    var hash = -1
    for (i in cursor - 1 downTo 0) {
        val c = content[i]
        if (c == '\n' || c.isWhitespace()) break
        if (c == '#') {
            hash = i
            break
        }
    }
    if (hash < 0 || hash == cursor - 1) return null
    if (hash > 0) {
        val before = content[hash - 1]
        if (before == '\\') return null
        if (before.isLetterOrDigit() || before in URL_PREFIX_CHARS) return null
    }
    val inner = content.subSequence(hash + 1, cursor)
    if (inner.contains('\n')) return null
    if (insideProtectedContext(content, hash)) return null
    return TagQuery(hash, cursor, inner.toString())
}

/** `#` 前导禁用字符：URL 常见前导（挡链接锚点误伤），与渲染链口径一致。 */
private val URL_PREFIX_CHARS = setOf('/', ':', '?', '=', '&')

/**
 * 过滤 + 排序：[query] 为空返回前 [limit] 条；否则大小写不敏感包含匹配，
 * 前缀命中优先、使用次数降序、字典序次之。
 */
fun filterTagCandidates(
    all: List<TagRow>,
    query: String,
    limit: Int
): List<TagRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return all.take(limit)
    return all.asSequence()
        .filter { it.name.lowercase().contains(q) }
        .sortedWith(
            compareBy(
                { !it.name.lowercase().startsWith(q) },
                { -it.count },
                { it.name.lowercase() }
            )
        )
        .take(limit)
        .toList()
}

/**
 * 标签补全浮层：搜索框（自动聚焦）+ 竖向候选列表（`#名称` + 使用计数）。
 * 点选回传 [onPick]（由调用方完成文本替换）；点遮罩 / 系统返回关闭（继续手打）。
 * [candidates] 为 null 表示索引构建中（显示加载指示）。
 */
@Composable
internal fun TagPickerDialog(
    initialQuery: String,
    candidates: List<TagRow>?,
    onPick: (TagRow) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf(initialQuery) }
    val searchFocus = remember { FocusRequester() }
    // 等一帧布局完成后再请求焦点，避免输入框未就绪焦点被吞（同搜索页做法）
    LaunchedEffect(Unit) {
        withFrameNanos { }
        searchFocus.requestFocus()
    }
    val filtered = remember(search, candidates) {
        candidates?.let { filterTagCandidates(it, search, TAG_PICKER_LIMIT) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.66f)
                    // 卡片内点击不穿透到遮罩（空动作仅消费事件）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Column {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.tags_search_hint)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .focusRequester(searchFocus)
                    )
                    HorizontalDivider()
                    when {
                        filtered == null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }

                        filtered.isEmpty() -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.editor_tag_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered, key = { it.name.lowercase() }) { item ->
                                TagPickerRow(item = item, onClick = { onPick(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 补全浮层行：`#名称` + 使用计数。 */
@Composable
private fun TagPickerRow(item: TagRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = "#${item.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
