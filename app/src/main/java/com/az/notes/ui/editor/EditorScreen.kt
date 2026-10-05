package com.az.notes.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.ui.components.RenameDialog
import com.az.notes.ui.theme.LocalReadingStyle
import kotlinx.coroutines.launch

/**
 * 编辑页（§5.3 重设计）：工具条移到底部（键盘上方，横向可滚动）；
 * 顶栏 = 返回 + 标题 + 查找 + 预览（眼睛）；停止输入 1.5s 自动保存。
 *
 * 文本编辑采用 state 版 [BasicTextField]：内建撤销 / 重做与光标自滚动（光标跟随
 * 屏幕）；工具栏直接编辑 [TextFieldState]，插入标记后光标 / 选区自动跟随；
 * 回车自动续行列表 / 任务 / 引用（见 [MarkdownListContinuation]）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onPreview: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readingStyle = LocalReadingStyle.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var renameDialog by remember { mutableStateOf(false) }

    // —— 编辑器文本状态：内建 undo/redo 栈与自滚动 ——
    val textState = rememberTextFieldState()
    val scrollState = rememberScrollState()
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 笔记文本是否已灌入编辑器；配置重建时经 rememberSaveable 恢复，避免重复灌入
    var initialized by rememberSaveable { mutableStateOf(false) }

    // 加载完成后一次性灌入文本（进程重建时若已恢复编辑器内容则跳过；
    // 此时恢复内容优先于刚读出的磁盘内容，回写一次避免被加载结果覆盖）
    LaunchedEffect(state.loading, state.error) {
        if (state.loading || state.error != null) return@LaunchedEffect
        if (!initialized) {
            textState.setTextAndPlaceCursorAtEnd(state.text)
            initialized = true
        } else if (textState.text.toString() != state.text) {
            viewModel.onTextChange(textState.text.toString())
        }
    }

    val editorVisible = initialized || state.error != null
    // 编辑内容实时回写 ViewModel（驱动自动保存；内容相同时不置脏）
    LaunchedEffect(editorVisible) {
        if (!editorVisible) return@LaunchedEffect
        snapshotFlow { textState.text.toString() }.collect { viewModel.onTextChange(it) }
    }

    // 系统返回 / 返回按钮共用：先同步编辑器最新文本给 ViewModel，
    // 再走原有收尾（空笔记清理 / 未落盘内容写入），完成后才返回，避免丢字
    val exit: () -> Unit = {
        scope.launch {
            viewModel.onTextChange(textState.text.toString())
            viewModel.flushOnExit()
            onBack()
        }
    }
    BackHandler { exit() }

    // 一次性提示（重命名结果等）
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 匹配区间（查找栏计数与编辑器内高亮共用；纯文本查询不会跨换行）
    val matchRanges = remember(state.text, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
                .findAll(state.text)
                .map { it.range }
                .toList()
        }
    }
    val highlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // 点击标题可重命名文件（铅笔图标为可点击提示）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { renameDialog = true }
                    ) {
                        Text(
                            text = displayTitle(state.path),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.action_rename),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = exit) {
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
                    matchCount = matchRanges.size,
                    onQueryChange = { query = it }
                )
            }

            Box(Modifier.weight(1f)) {
                if (!editorVisible) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    BasicTextField(
                        state = textState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .drawBehind {
                                // 查找高亮：绘制在文本下层（drawBehind 先于内容绘制）
                                if (searchActive) {
                                    drawSearchHighlights(layoutResult, scrollState, matchRanges, highlightColor)
                                }
                            },
                        textStyle = readingStyle.textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        inputTransformation = MarkdownListContinuation,
                        scrollState = scrollState,
                        onTextLayout = { getResult -> layoutResult = getResult() }
                    )
                }
            }

            HorizontalDivider()
            // 底部工具条：位于键盘上方（由 imePadding 抬升），横向可滚动
            EditorToolbar(textState)
        }
    }

    if (renameDialog) {
        RenameDialog(
            initialName = editableName(state.path),
            onDismiss = { renameDialog = false },
            onConfirm = { newName ->
                viewModel.rename(newName)
                renameDialog = false
            }
        )
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

/** 底部工具条：撤销 / 重做、标记插入（光标 / 选区跟随）、缩进与插入类快捷操作。 */
@Composable
private fun EditorToolbar(textState: TextFieldState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // —— 撤销 / 重做（内建 undo 栈；无历史时点击为无操作）——
        ToolButton(Icons.AutoMirrored.Filled.Undo, "撤销") { textState.undoState.undo() }
        ToolButton(Icons.AutoMirrored.Filled.Redo, "重做") { textState.undoState.redo() }
        ToolGroupGap()

        // —— 行内标记：包裹选区；无选区时插入标记对并把光标停在中缝 ——
        ToolButton(Icons.Filled.FormatBold, "粗体") { textState.wrapSelection("**") }
        ToolButton(Icons.Filled.FormatItalic, "斜体") { textState.wrapSelection("*") }
        ToolButton(Icons.Filled.StrikethroughS, "删除线") { textState.wrapSelection("~~") }
        ToolButton(Icons.Filled.Code, "行内代码") { textState.wrapSelection("`") }
        ToolGroupGap()

        // —— 块级标记：插入当前行行首，光标随之右移 ——
        ToolButton(Icons.Filled.FormatSize, "标题") { textState.prefixCurrentLine("# ") }
        ToolButton(Icons.Filled.FormatQuote, "引用") { textState.prefixCurrentLine("> ") }
        ToolButton(Icons.Filled.FormatListBulleted, "无序列表") { textState.prefixCurrentLine("- ") }
        ToolButton(Icons.Filled.FormatListNumbered, "有序列表") { textState.prefixCurrentLine("1. ") }
        ToolButton(Icons.Filled.Checklist, "任务列表") { textState.prefixCurrentLine("- [ ] ") }
        ToolButton(Icons.Filled.DataObject, "代码块") { textState.prefixCurrentLine("```\n") }
        ToolButton(Icons.Filled.HorizontalRule, "分割线") { textState.prefixCurrentLine("---\n") }
        ToolGroupGap()

        // —— 缩进 ——
        ToolButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "缩进") { textState.indentLines() }
        ToolButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "反缩进") { textState.unindentLines() }
        ToolGroupGap()

        // —— 插入 ——
        ToolButton(Icons.Filled.InsertLink, "链接") { textState.wrapSelection("[", "](https://)") }
        ToolButton(Icons.Filled.Image, "图片") { textState.insertAtCursor("![]()", 2) }
        Spacer(Modifier.width(8.dp))
    }
}

/** 工具条分组间隔。 */
@Composable
private fun ToolGroupGap() {
    Spacer(Modifier.width(10.dp))
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 查找高亮：绘制在文本下层（drawBehind 先于内容绘制）。匹配区间按视觉行分段，
 * 逐段取 [TextLayoutResult] 边界框绘制；滚动偏移与文本同步（scrollState 与
 * BasicTextField 共享，自滚动时光标跟随屏幕）。
 */
private fun DrawScope.drawSearchHighlights(
    layout: TextLayoutResult?,
    scrollState: ScrollState,
    ranges: List<IntRange>,
    color: Color
) {
    if (layout == null || ranges.isEmpty()) return
    val textLength = layout.layoutInput.text.length
    translate(top = -scrollState.value.toFloat()) {
        ranges.forEach { range ->
            var index = range.first
            while (index <= range.last && index < textLength) {
                val line = layout.getLineForOffset(index)
                val lineEnd = layout.getLineEnd(line, visibleEnd = true)
                if (lineEnd <= index) break
                val segmentEnd = minOf(range.last, lineEnd - 1)
                val startBox = layout.getBoundingBox(index)
                val endBox = layout.getBoundingBox(segmentEnd)
                val width = endBox.right - startBox.left
                if (width > 0f) {
                    drawRect(
                        color = color,
                        topLeft = Offset(startBox.left, startBox.top),
                        size = Size(width, startBox.height)
                    )
                }
                index = segmentEnd + 1
            }
        }
    }
}

// ——————————————— 工具栏编辑操作（直接作用于编辑器状态，插入后光标 / 选区跟随） ———————————————

/** 以成对标记包裹选区；无选区时插入标记对并把光标停在中缝。 */
private fun TextFieldState.wrapSelection(open: String, close: String = open) {
    edit {
        val start = selection.min
        val end = selection.max
        val selected = asCharSequence().subSequence(start, end).toString()
        replace(start, end, open + selected + close)
        selection = TextRange(start + open.length, start + open.length + selected.length)
    }
}

/** 在当前行行首插入标记（标题 / 列表 / 引用 / 代码块等），光标随之右移。 */
private fun TextFieldState.prefixCurrentLine(prefix: String) {
    edit {
        val cursor = selection.min
        val text = asCharSequence()
        var lineStart = cursor
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        replace(lineStart, lineStart, prefix)
        selection = TextRange(cursor + prefix.length)
    }
}

/** 光标处插入文本，并把光标放到插入内容的 [cursorOffset] 处（如 ![]() 停在中括号内）。 */
private fun TextFieldState.insertAtCursor(text: String, cursorOffset: Int) {
    edit {
        val cursor = selection.min
        replace(cursor, cursor, text)
        selection = TextRange(cursor + cursorOffset.coerceIn(0, text.length))
    }
}

/** 选区覆盖的每一行整体缩进两格（无选区时缩进当前行）。 */
private fun TextFieldState.indentLines() {
    edit {
        val selStart = selection.min
        val selEnd = selection.max
        val source = asCharSequence().toString()
        val lineStarts = selectedLineStarts(source, selStart, selEnd)
        // 从后往前插入，避免行首偏移漂移
        for (lineStart in lineStarts.asReversed()) replace(lineStart, lineStart, INDENT)
        selection = TextRange(
            (selStart + INDENT.length).coerceAtMost(length),
            (selEnd + INDENT.length * lineStarts.size).coerceAtMost(length)
        )
    }
}

/** 选区覆盖的每一行整体反缩进（行首最多移除两个空格或一个制表符）。 */
private fun TextFieldState.unindentLines() {
    edit {
        val selStart = selection.min
        val selEnd = selection.max
        val source = asCharSequence().toString()
        val lineStarts = selectedLineStarts(source, selStart, selEnd)
        var removedFirst = 0
        var removedTotal = 0
        // 从后往前删除，避免行首偏移漂移
        for (lineStart in lineStarts.asReversed()) {
            val removable = when {
                source.startsWith("\t", lineStart) -> 1
                source.startsWith("  ", lineStart) -> 2
                source.startsWith(" ", lineStart) -> 1
                else -> 0
            }
            if (removable > 0) {
                replace(lineStart, lineStart + removable, "")
                removedTotal += removable
                if (lineStart == lineStarts.first()) removedFirst = removable
            }
        }
        selection = TextRange(
            (selStart - removedFirst).coerceAtLeast(0),
            (selEnd - removedTotal).coerceAtLeast(0)
        )
    }
}

/** [selStart, selEnd] 覆盖到的所有行首偏移（升序，含选区触及的每一行）。 */
private fun selectedLineStarts(source: String, selStart: Int, selEnd: Int): List<Int> {
    var first = selStart
    while (first > 0 && source[first - 1] != '\n') first--
    val starts = mutableListOf(first)
    var cursor = first
    while (true) {
        val newline = source.indexOf('\n', cursor)
        if (newline < 0 || newline + 1 > selEnd || newline + 1 >= source.length) break
        starts.add(newline + 1)
        cursor = newline + 1
    }
    return starts
}

/** 缩进宽度：两个空格（手机窄屏下比 Tab 更可控，渲染器同样按 Markdown 规则处理）。 */
private const val INDENT = "  "

/**
 * 回车续行：[TextFieldBuffer] 输入变换，与用户的回车合并为同一次编辑，撤销一步到位。
 * 列表 / 任务 / 引用行回车后自动补前缀：有序列表数字递增、任务重置为未勾选、
 * 保留缩进；空列表项回车移除前缀退出列表；其余行不处理。
 */
private object MarkdownListContinuation : InputTransformation {
    private val prefixRegex = Regex("^(\\s*)([-+*]|>|\\d+[.)])(\\s+)(\\[[ xX]\\]\\s+)?")

    override fun TextFieldBuffer.transformInput() {
        // 只处理“恰好插入一个换行”的输入（跳过删除 / 替换 / 多字符粘贴）
        if (length != originalValue.length + 1) return
        val new = asCharSequence().toString()
        val old = originalValue.toString()
        var insertAt = 0
        val limit = minOf(old.length, new.length - 1)
        while (insertAt < limit && old[insertAt] == new[insertAt]) insertAt++
        if (new[insertAt] != '\n') return
        for (i in insertAt until old.length) if (old[i] != new[i + 1]) return
        // 光标必须紧随换行（排除单字符粘贴等非回车场景）
        if (!selection.collapsed || selection.min != insertAt + 1) return
        // 回车前光标所在行的内容
        var lineStart = insertAt
        while (lineStart > 0 && old[lineStart - 1] != '\n') lineStart--
        val lineText = old.substring(lineStart, insertAt)
        val match = prefixRegex.find(lineText) ?: return
        val indent = match.groupValues[1]
        val marker = match.groupValues[2]
        val taskMark = match.groupValues[4]
        if (match.value.length == lineText.length) {
            // 空列表项回车：移除本行前缀退出列表，仅保留用户输入的换行
            val removed = insertAt - lineStart
            replace(lineStart, insertAt, "")
            selection = TextRange((insertAt + 1 - removed).coerceAtLeast(0))
        } else {
            val nextMarker = if (marker.first().isDigit()) {
                val number = marker.dropLast(1).toIntOrNull()
                if (number == null) marker else "${number + 1}${marker.last()}"
            } else {
                marker
            }
            val task = if (taskMark.isNotEmpty()) "[ ] " else ""
            val continuation = indent + nextMarker + " " + task
            replace(insertAt + 1, insertAt + 1, continuation)
            selection = TextRange(insertAt + 1 + continuation.length)
        }
    }
}

/** 标题：文件名去掉 .md 扩展名。 */
private fun displayTitle(path: String): String =
    path.substringAfterLast('/').let { if (it.endsWith(".md")) it.dropLast(3) else it }

/** 重命名输入框初始值：文件名去掉 .md 扩展名。 */
private fun editableName(path: String): String =
    path.substringAfterLast('/').let { if (it.endsWith(".md")) it.dropLast(3) else it }
