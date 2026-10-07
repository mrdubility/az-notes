package com.az.notes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.az.notes.domain.model.EditorTool
import com.az.notes.ui.common.label

/**
 * 编辑页工具条与编辑操作：工具按钮 / 图标映射 / 文本插入操作 / 查找高亮 / 回车续行。
 * 自 EditorScreen 拆分独立文件（纯 UI 与文本操作，行为不变）。
 */

/** 底部工具条：工具集与顺序由设置决定（可开关 / 排序），横向可滚动。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorToolbar(
    textState: TextFieldState,
    tools: List<EditorTool>,
    /** 图片工具：不再插字面量，改为触发选图 / 链接录入流程 */
    onImageRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tools.forEach { tool ->
            ToolButton(tool.icon(), tool.label()) {
                if (tool == EditorTool.IMAGE) onImageRequest() else tool.perform(textState)
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** 工具图标（与 [EditorTool] 一一对应）。 */
private fun EditorTool.icon(): ImageVector = when (this) {
    EditorTool.UNDO -> Icons.AutoMirrored.Filled.Undo
    EditorTool.REDO -> Icons.AutoMirrored.Filled.Redo
    EditorTool.BOLD -> Icons.Filled.FormatBold
    EditorTool.ITALIC -> Icons.Filled.FormatItalic
    EditorTool.STRIKETHROUGH -> Icons.Filled.StrikethroughS
    EditorTool.INLINE_CODE -> Icons.Filled.Code
    EditorTool.HEADING -> Icons.Filled.FormatSize
    EditorTool.QUOTE -> Icons.Filled.FormatQuote
    EditorTool.LIST_BULLET -> Icons.Filled.FormatListBulleted
    EditorTool.LIST_NUMBER -> Icons.Filled.FormatListNumbered
    EditorTool.TASK -> Icons.Filled.Checklist
    EditorTool.CODE_BLOCK -> Icons.Filled.DataObject
    EditorTool.HORIZONTAL_RULE -> Icons.Filled.HorizontalRule
    EditorTool.INDENT -> Icons.AutoMirrored.Filled.FormatIndentIncrease
    EditorTool.UNINDENT -> Icons.AutoMirrored.Filled.FormatIndentDecrease
    EditorTool.LINK -> Icons.Filled.InsertLink
    EditorTool.IMAGE -> Icons.Filled.Image
    EditorTool.PROPERTY -> Icons.Filled.Label
}

/** 执行工具动作：标记插入后光标 / 选区自动跟随。 */
@OptIn(ExperimentalFoundationApi::class)
private fun EditorTool.perform(textState: TextFieldState) {
    when (this) {
        EditorTool.UNDO -> textState.undoState.undo()
        EditorTool.REDO -> textState.undoState.redo()
        // 行内标记：包裹选区；无选区时插入标记对并把光标停在中缝
        EditorTool.BOLD -> textState.wrapSelection("**")
        EditorTool.ITALIC -> textState.wrapSelection("*")
        EditorTool.STRIKETHROUGH -> textState.wrapSelection("~~")
        EditorTool.INLINE_CODE -> textState.wrapSelection("`")
        // 块级标记：插入当前行行首，光标随之右移
        EditorTool.HEADING -> textState.prefixCurrentLine("# ")
        EditorTool.QUOTE -> textState.prefixCurrentLine("> ")
        EditorTool.LIST_BULLET -> textState.prefixCurrentLine("- ")
        EditorTool.LIST_NUMBER -> textState.prefixCurrentLine("1. ")
        EditorTool.TASK -> textState.prefixCurrentLine("- [ ] ")
        EditorTool.CODE_BLOCK -> textState.prefixCurrentLine("```\n")
        EditorTool.HORIZONTAL_RULE -> textState.prefixCurrentLine("---\n")
        EditorTool.INDENT -> textState.indentLines()
        EditorTool.UNINDENT -> textState.unindentLines()
        EditorTool.LINK -> textState.wrapSelection("[", "](https://)")
        EditorTool.IMAGE -> textState.insertAtCursor("![]()", 2)
        EditorTool.PROPERTY -> textState.insertProperty()
    }
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
internal fun DrawScope.drawSearchHighlights(
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

/**
 * 快捷添加属性记录：文档已有 frontmatter 时在结束分隔行前插入一行占位，
 * 否则在文首生成 frontmatter 骨架；插入后选中占位的 key，直接输入即可替换。
 */
private fun TextFieldState.insertProperty() {
    edit {
        val lines = asCharSequence().toString().split('\n')
        var closingIndex = -1
        if (lines.firstOrNull()?.trimEnd() == FRONTMATTER_DELIMITER) {
            for (i in 1 until minOf(lines.size, 61)) {
                if (lines[i].trimEnd() == FRONTMATTER_DELIMITER) {
                    closingIndex = i
                    break
                }
            }
        }
        if (closingIndex > 0) {
            // 已有 frontmatter：在结束分隔行之前插入新属性
            var offset = 0
            for (i in 0 until closingIndex) offset += lines[i].length + 1
            replace(offset, offset, "$PROPERTY_PLACEHOLDER\n")
            selection = TextRange(offset, offset + PLACEHOLDER_KEY_LENGTH)
        } else {
            // 无 frontmatter：文首生成骨架并选中 key
            replace(0, 0, "$FRONTMATTER_DELIMITER\n$PROPERTY_PLACEHOLDER\n$FRONTMATTER_DELIMITER\n")
            selection = TextRange(4, 4 + PLACEHOLDER_KEY_LENGTH)
        }
    }
}

/** 属性占位模版（插入后 key 部分被选中，直接输入即可替换）。 */
private const val PROPERTY_PLACEHOLDER = "key: value"
private const val PLACEHOLDER_KEY_LENGTH = 3
private const val FRONTMATTER_DELIMITER = "---"

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
internal object MarkdownListContinuation : InputTransformation {
    private val prefixRegex = Regex("^(\\s*)([-+*]|>|\\d+[.)])(\\s+)(\\[[ xX]\\]\\s+)?")

    override fun TextFieldBuffer.transformInput() {
        val new = asCharSequence().toString()
        val selAfter = selection
        // 快速排除：只有“光标紧随换行”的输入才与回车续行相关，其余按键零开销放行
        if (!selAfter.collapsed || selAfter.min == 0 || new[selAfter.min - 1] != '\n') return
        // 取编辑前文本：先整体回退缓冲区（originalValue 为 internal 不可用），
        // 随后在回退状态上重建本次编辑（需要时合并续行内容），撤销仍为一步到位
        revertAllChanges()
        val old = asCharSequence().toString()

        // 定位首个差异点，识别“恰好插入单个 '\n'、其余内容不变”
        var p = 0
        val limit = minOf(old.length, new.length - 1)
        while (p < limit && old[p] == new[p]) p++
        val tailMatches = new.substring(p + 1) == old.substring(p)
        val singleEnter = new.length == old.length + 1 && tailMatches && new[p] == '\n'

        if (singleEnter && selAfter.min == p + 1) {
            // 回车前光标所在行的内容
            var lineStart = p
            while (lineStart > 0 && old[lineStart - 1] != '\n') lineStart--
            val lineText = old.substring(lineStart, p)
            val match = prefixRegex.find(lineText)
            when {
                match == null -> {
                    // 非列表行：等价于用户原本的回车
                    replace(p, p, "\n")
                    selection = selAfter
                }
                match.value.length == lineText.length -> {
                    // 空列表项回车：移除本行前缀退出列表，仅保留换行
                    replace(lineStart, p, "\n")
                    selection = TextRange(lineStart + 1)
                }
                else -> {
                    val indent = match.groupValues[1]
                    val marker = match.groupValues[2]
                    val nextMarker = if (marker.first().isDigit()) {
                        val number = marker.dropLast(1).toIntOrNull()
                        if (number == null) marker else "${number + 1}${marker.last()}"
                    } else {
                        marker
                    }
                    val task = if (match.groupValues[4].isNotEmpty()) "[ ] " else ""
                    val continuation = indent + nextMarker + " " + task
                    replace(p, p, "\n" + continuation)
                    selection = TextRange(p + 1 + continuation.length)
                }
            }
            return
        }

        // 其它涉及换行的编辑（多字符粘贴 / 删除 / 替换）：按首尾公共段差异恢复原文
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) suffix++
        replace(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
        selection = selAfter
    }
}
