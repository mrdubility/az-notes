package com.az.notes.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataArray
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
import androidx.compose.material.icons.filled.Tag
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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
        EditorTool.WIKILINK -> R.string.editor_tool_wikilink
        EditorTool.TAG -> R.string.editor_tool_tag
        EditorTool.IMAGE -> R.string.editor_tool_image
        EditorTool.PROPERTY -> R.string.editor_tool_property
    }
)

/** 编辑器工具图标（编辑页工具栏与设置页配置行共用）。 */
internal fun EditorTool.icon(): ImageVector = when (this) {
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
    EditorTool.WIKILINK -> Icons.Filled.DataArray
    EditorTool.TAG -> Icons.Filled.Tag
    EditorTool.IMAGE -> Icons.Filled.Image
    EditorTool.PROPERTY -> Icons.Filled.Label
}
