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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.az.notes.R
import com.az.notes.domain.ai.AiProvider
import com.az.notes.ui.common.formatDateTimeSeconds

/**
 * 对话页弹层（§9.2 / §9.1，B3 版）：
 * - [ModelPickerMenu] 锚点 DropdownMenu（项目约定禁用 ModalBottomSheet）：
 *   按供应商分组列出全部模型，当前项打勾；未配置模型的供应商显示引导行
 * - [ClearSessionConfirm] 新会话（清空会话）二次确认
 * - [DocumentPickerDialog] 文档选择器（全屏；搜索 + 笔记多选 + 列表页同款正文预览）
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
 * 文档选择器（§9.1，B3）：全屏 Dialog——搜索（VM 过滤：文件名 / 相对路径）+ 笔记多选
 * + 底部「添加」；条目采用列表页同款结构（日期 / 标题 / 正文预览 / 所在目录），
 * 预览由 VM 分批并行读取（首批就绪即展示，滚动到底加载后续）；
 * 已在待发附件中的条目打勾提示（不可重复选择）。
 * [items] = null 表示未打开；打开后先置空列表（[loading] 转圈）再 IO 填充。
 */
@Composable
internal fun DocumentPickerDialog(
    items: List<DocPickerItem>?,
    query: String,
    loading: Boolean,
    hasMore: Boolean,
    pendingRelPaths: Set<String>,
    onQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    if (items == null) return
    // 已选中项（absolutePath；保持选择顺序）。不设 key：关闭（items = null）时组件离开
    // 组合槽位、状态自动重置；分页追加 items 不会清空当前选择
    var selected by remember { mutableStateOf(emptyList<String>()) }
    val listState = rememberLazyListState()
    // 滚动接近末尾（距底 3 项）且还有未装载批次时自动加载；快照闭包经
    // rememberUpdatedState 读取最新值，避免捕获旧状态
    val currentHasMore by rememberUpdatedState(hasMore)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, total) ->
            if (currentHasMore && total > 0 && last >= total - 3) currentOnLoadMore()
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
                // 搜索：VM 过滤（文件名 / 相对路径），过滤后首批预览重新装载
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.ai_chat_doc_picker_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    items.isEmpty() -> Box(
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

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = items, key = { it.node.absolutePath }) { item ->
                            val added = item.node.relativePath in pendingRelPaths
                            val checked = item.node.absolutePath in selected
                            DocPickerRow(
                                item = item,
                                added = added,
                                checked = checked,
                                onClick = {
                                    selected = if (checked) {
                                        selected - item.node.absolutePath
                                    } else {
                                        selected + item.node.absolutePath
                                    }
                                }
                            )
                        }
                        // 后续批次装载指示（滚动到底自动触发）
                        if (hasMore) {
                            item(key = "doc_picker_load_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
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

/**
 * 选择器条目：列表页同款结构（日期 / 标题 / 正文预览 / 所在目录）+ 勾选 / 已添加标记。
 * [added]（已在待发附件）不可点选，[checked]（本次已选）显示主题色勾。
 */
@Composable
private fun DocPickerRow(
    item: DocPickerItem,
    added: Boolean,
    checked: Boolean,
    onClick: () -> Unit
) {
    val node = item.node
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !added, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDateTimeSeconds(node.lastModified),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = node.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.titleMedium,
                color = if (added) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.preview.isNotEmpty()) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (added) {
                    stringResource(R.string.ai_chat_doc_picker_selected)
                } else {
                    node.relativePath
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (added || checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
                tint = if (added) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}
