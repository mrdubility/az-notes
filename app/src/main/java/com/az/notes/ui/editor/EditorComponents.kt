package com.az.notes.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R

/**
 * 编辑页局部组件：标题头 / 保存状态文字 / 查找栏。
 * 自 EditorScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

/** 编辑页标题（文件名）：随内容排版，与正文用分隔线区分；点击可重命名。 */
@Composable
internal fun EditorTitleHeader(path: String, onRenameClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRenameClick)
        ) {
            Text(
                text = displayTitle(path),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.action_rename),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 保存状态文字：紧贴顶栏“编辑”右侧常驻显示（保存中 / 未保存 / 已保存）。 */
@Composable
internal fun SaveStatusText(saving: Boolean, dirty: Boolean) {
    Text(
        text = stringResource(
            when {
                saving -> R.string.editor_saving
                dirty -> R.string.editor_unsaved
                else -> R.string.editor_saved
            }
        ),
        style = MaterialTheme.typography.labelMedium,
        color = when {
            saving -> MaterialTheme.colorScheme.primary
            dirty -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/** 查找栏：实时高亮全部匹配并显示数量。 */
@Composable
internal fun FindBar(
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

/** 标题：文件名去掉 .md 扩展名。 */
private fun displayTitle(path: String): String =
    path.substringAfterLast('/').let { if (it.endsWith(".md")) it.dropLast(3) else it }

/** 重命名输入框初始值：文件名去掉 .md 扩展名。 */
internal fun editableName(path: String): String =
    path.substringAfterLast('/').let { if (it.endsWith(".md")) it.dropLast(3) else it }
