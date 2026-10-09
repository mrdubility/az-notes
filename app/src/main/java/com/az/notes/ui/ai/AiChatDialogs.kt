package com.az.notes.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.az.notes.R
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.model.FileNode

/**
 * 对话页弹层（§9.2 / §9.1，B3 版）：
 * - [ModelPickerMenu] 锚点 DropdownMenu（项目约定禁用 ModalBottomSheet）：
 *   按供应商分组列出全部模型，当前项打勾；未配置模型的供应商显示引导行
 * - [ClearSessionConfirm] 新会话（清空会话）二次确认
 * - [DocumentPickerDialog] 文档选择器（全屏；搜索过滤 + 笔记多选）
 */

/** 模型选择菜单（锚定顶栏副标题处）。 */
@Composable
internal fun ModelPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    providers: List<AiProvider>,
    selection: ChatSelection?,
    onSelect: (providerId: String, modelId: String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (providers.isEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_chat_no_provider)) },
                onClick = {},
                enabled = false
            )
            return@DropdownMenu
        }
        providers.forEachIndexed { index, provider ->
            if (index > 0) HorizontalDivider()
            // 供应商分组标题
            Text(
                text = provider.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (provider.models.isEmpty()) {
                // 该供应商未配置模型：引导回供应商管理页补充（菜单项禁用仅作提示）
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.ai_provider_no_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {},
                    enabled = false
                )
            } else {
                provider.models.forEach { model ->
                    val checked = selection?.providerId == provider.id &&
                        selection.modelId == model.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model.label,
                                color = if (checked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        trailingIcon = {
                            if (checked) Icon(Icons.Filled.Check, null)
                        },
                        onClick = { onSelect(provider.id, model.id) }
                    )
                }
            }
        }
    }
}

/** 新会话二次确认（仅在有消息时由 VM 触发显示）。 */
@Composable
internal fun ClearSessionConfirm(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_chat_new_session)) },
        text = { Text(stringResource(R.string.ai_chat_new_session_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ai_chat_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 文档选择器（§9.1，B3）：全屏 Dialog——搜索本地过滤 + 笔记多选 + 底部「添加」；
 * 已在待发附件中的条目打勾提示（不可重复选择）。
 * [items] = null 表示未打开；打开期间先置空列表（加载中）再 IO 填充。
 */
@Composable
internal fun DocumentPickerDialog(
    items: List<FileNode>?,
    pendingRelPaths: Set<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    if (items == null) return
    var query by remember { mutableStateOf("") }
    // 已选中项（absolutePath；保持选择顺序，列表数据更新时重置）
    var selected by remember(items) { mutableStateOf(emptyList<String>()) }
    val filtered = remember(items, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            items
        } else {
            items.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                    it.relativePath.contains(keyword, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：标题 + 关闭
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_chat_doc_picker_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }
                // 搜索：本地过滤（文件名 / 相对路径）
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.ai_chat_doc_picker_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.ai_chat_doc_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(items = filtered, key = { it.absolutePath }) { node ->
                            val added = node.relativePath in pendingRelPaths
                            val checked = node.absolutePath in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !added) {
                                        selected = if (checked) {
                                            selected - node.absolutePath
                                        } else {
                                            selected + node.absolutePath
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = node.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (added) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (added) {
                                            stringResource(R.string.ai_chat_doc_picker_selected)
                                        } else {
                                            node.relativePath
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (added || checked) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (added) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                // 底部：「添加」（带选中数）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selected) },
                        enabled = selected.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.ai_chat_doc_picker_add, selected.size))
                    }
                }
            }
        }
    }
}
