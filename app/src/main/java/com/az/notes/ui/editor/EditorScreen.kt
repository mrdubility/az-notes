package com.az.notes.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.ui.theme.LocalReadingStyle

/**
 * 编辑页（§5.3 重设计）：工具条移到底部（键盘上方，横向可滚动）；
 * 顶栏 = 返回 + 标题 + 查找 + 预览（眼睛）；停止输入 1.5s 自动保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onPreview: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readingStyle = LocalReadingStyle.current

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val matchCount = remember(state.text, query) {
        if (query.isBlank()) 0
        else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(state.text).count()
    }
    val highlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayTitle(state.path),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.save(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (state.dirty) "未保存" else "已保存",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.dirty) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    IconButton(onClick = {
                        if (searchActive) {
                            searchActive = false
                            query = ""
                        } else {
                            searchActive = true
                        }
                    }) {
                        Icon(
                            if (searchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(R.string.editor_search_hint)
                        )
                    }
                    IconButton(onClick = {
                        viewModel.save()
                        onPreview(state.path)
                    }) {
                        Icon(Icons.Outlined.Visibility, stringResource(R.string.action_preview))
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
            if (searchActive) {
                FindBar(
                    query = query,
                    matchCount = matchCount,
                    onQueryChange = { query = it }
                )
            }

            Box(Modifier.weight(1f)) {
                if (state.loading) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    BasicTextField(
                        value = state.text,
                        onValueChange = viewModel::onTextChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        textStyle = readingStyle.textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = if (searchActive && query.isNotBlank()) {
                            remember(query, highlightColor) { HighlightTransformation(query, highlightColor) }
                        } else {
                            VisualTransformation.None
                        }
                    )
                }
            }

            HorizontalDivider()
            // 底部工具条：位于键盘上方（由 imePadding 抬升），横向可滚动
            EditorToolbar(viewModel)
        }
    }
}

/** 查找栏：实时高亮全部匹配并显示数量。 */
@Composable
private fun FindBar(
    query: String,
    matchCount: Int,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.editor_search_hint), style = MaterialTheme.typography.bodyMedium)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = when {
                query.isBlank() -> ""
                matchCount == 0 -> stringResource(R.string.editor_search_empty)
                else -> stringResource(R.string.editor_search_matches, matchCount)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 底部工具条：常用 Markdown 标记插入。 */
@Composable
private fun EditorToolbar(viewModel: EditorViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Filled.FormatBold, "粗体") { viewModel.applyDecoration("**") }
        ToolButton(Icons.Filled.FormatItalic, "斜体") { viewModel.applyDecoration("*") }
        ToolButton(Icons.Filled.FormatSize, "一级标题") { viewModel.insertLinePrefix("# ") }
        ToolButton(Icons.Filled.FormatListBulleted, "无序列表") { viewModel.insertLinePrefix("- ") }
        ToolButton(Icons.Filled.FormatListNumbered, "有序列表") { viewModel.insertLinePrefix("1. ") }
        ToolButton(Icons.Filled.FormatQuote, "引用") { viewModel.insertLinePrefix("> ") }
        ToolButton(Icons.Filled.Code, "代码块") { viewModel.insertLinePrefix("```\n") }
        ToolButton(Icons.Filled.InsertLink, "链接") { viewModel.applyDecoration("[", "](https://)") }
        ToolButton(Icons.Filled.Image, "图片") { viewModel.insertLinePrefix("![]()") }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 查找高亮：不改动文本长度，直接为匹配区间叠加背景色。 */
private class HighlightTransformation(
    private val query: String,
    private val color: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (query.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text.text)
        var index = text.text.indexOf(query, 0, ignoreCase = true)
        while (index >= 0) {
            builder.addStyle(SpanStyle(background = color), index, index + query.length)
            index = text.text.indexOf(query, index + query.length, ignoreCase = true)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** 标题：文件名去掉 .md 扩展名。 */
private fun displayTitle(path: String): String =
    path.substringAfterLast('/').let { if (it.endsWith(".md")) it.dropLast(3) else it }
