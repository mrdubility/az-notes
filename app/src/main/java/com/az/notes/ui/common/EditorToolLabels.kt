package com.az.notes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.az.notes.R
import com.az.notes.domain.model.EditorTool

/** 编辑器工具名称（设置页配置行与编辑页工具栏共用）。 */
@Composable
internal fun EditorTool.label(): String = stringResource(
    when (this) {
        EditorTool.UNDO -> R.string.editor_tool_undo
        EditorTool.REDO -> R.string.editor_tool_redo
        EditorTool.BOLD -> R.string.editor_tool_bold
        EditorTool.ITALIC -> R.string.editor_tool_italic
        EditorTool.STRIKETHROUGH -> R.string.editor_tool_strikethrough
        EditorTool.INLINE_CODE -> R.string.editor_tool_inline_code
        EditorTool.HEADING -> R.string.editor_tool_heading
        EditorTool.QUOTE -> R.string.editor_tool_quote
        EditorTool.LIST_BULLET -> R.string.editor_tool_list_bullet
        EditorTool.LIST_NUMBER -> R.string.editor_tool_list_number
        EditorTool.TASK -> R.string.editor_tool_task
        EditorTool.CODE_BLOCK -> R.string.editor_tool_code_block
        EditorTool.HORIZONTAL_RULE -> R.string.editor_tool_hr
        EditorTool.INDENT -> R.string.editor_tool_indent
        EditorTool.UNINDENT -> R.string.editor_tool_unindent
        EditorTool.LINK -> R.string.editor_tool_link
        EditorTool.IMAGE -> R.string.editor_tool_image
        EditorTool.PROPERTY -> R.string.editor_tool_property
    }
)
