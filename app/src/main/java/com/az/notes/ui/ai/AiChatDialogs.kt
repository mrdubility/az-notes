package com.az.notes.ui.ai

import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.az.notes.R
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.SystemNoteMode
import com.az.notes.ui.common.formatDateTimeSeconds
import com.az.notes.ui.common.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话页弹层（§9.2 / §9.1）：
 * - [ModelPickerMenu] 锚点 DropdownMenu（项目约定禁用 ModalBottomSheet）：
 *   按供应商分组列出全部模型，当前项打勾；未配置模型的供应商显示引导行
 * - [ClearSessionConfirm] 新会话（清空会话）二次确认
 * - [DocumentPickerDialog] 文档选择器（全屏；搜索 + 笔记多选 + 列表页同款正文预览）
 * - [ExportDialog] 导出选择（全屏；勾选消息 + 包含工具轨迹开关，§8.1）
 * - [DocImagePickerDialog] 文档取图（全屏；会话文档中的本地图片，§7.2）
 * - [PrivacyDialog] 隐私一次性告知（首次发送前，§11.3）
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

/**
 * 导出选择对话框（§8.1，全屏）：勾选要导出的消息（导出按会话顺序）+「包含工具轨迹」
 * 开关（默认开），底部「导出为 Markdown 文档（N）」（空选禁用）。初始选中由入口决定
 * （顶栏 = 不选、消息操作行 = 该条）；进行中的流式消息不列入。
 */
@Composable
internal fun ExportDialog(
    visible: Boolean,
    messages: List<ChatMessage>,
    defaultSelectedId: String?,
    onConfirm: (Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    // 不设 key：关闭（visible=false，提前 return）时状态离开组合槽位自动重置；
    // 每次打开读取当时的 defaultSelectedId
    val shown = messages.filterNot { it.status == MessageStatus.STREAMING }
    var selected by remember {
        mutableStateOf(defaultSelectedId?.let { setOf(it) } ?: emptySet<String>())
    }
    var includeTrail by remember { mutableStateOf(true) }

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
                        text = stringResource(R.string.ai_chat_export_title),
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
                // 快捷行：全选 / 反选 + 「包含工具轨迹」
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    TextButton(onClick = { selected = shown.map { it.id }.toSet() }) {
                        Text(
                            text = stringResource(R.string.ai_chat_export_select_all),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(onClick = { selected = shown.map { it.id }.toSet() - selected }) {
                        Text(
                            text = stringResource(R.string.ai_chat_export_invert),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { includeTrail = !includeTrail }
                    ) {
                        Text(
                            text = stringResource(R.string.ai_chat_export_include_trail),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Checkbox(
                            checked = includeTrail,
                            onCheckedChange = { includeTrail = it }
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items = shown, key = { it.id }) { message ->
                        val checked = message.id in selected
                        ExportRow(
                            message = message,
                            checked = checked,
                            onToggle = {
                                selected = if (checked) {
                                    selected - message.id
                                } else {
                                    selected + message.id
                                }
                            }
                        )
                    }
                }
                // 底部：取消 + 导出（空选禁用）
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
                        onClick = { onConfirm(selected, includeTrail) },
                        enabled = selected.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.ai_chat_export_confirm, selected.size))
                    }
                }
            }
        }
    }
}

/** 导出条目：角色徽标 + HH:mm + 摘要（正文前 60 字 / 系统条目概要 / 纯附件消息附件数）+ 勾选。 */
@Composable
private fun ExportRow(
    message: ChatMessage,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
    ) {
        Text(
            text = stringResource(
                when (message.role) {
                    ChatRole.USER -> R.string.ai_chat_role_user
                    ChatRole.ASSISTANT -> R.string.ai_chat_role_ai
                    else -> R.string.ai_chat_role_system
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = when (message.role) {
                ChatRole.USER -> MaterialTheme.colorScheme.primary
                ChatRole.ASSISTANT -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.width(EXPORT_BADGE_WIDTH)
        )
        Text(
            text = TIME_FORMAT.format(Date(message.timestamp)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text(
            text = exportSummary(message),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 导出条目摘要：正文前 60 字（空白归一）；系统条目用概要文案；纯附件消息列附件数。 */
@Composable
private fun exportSummary(message: ChatMessage): String {
    val note = message.systemNote
    if (note != null) {
        return stringResource(
            if (note.mode == SystemNoteMode.SUMMARIZED) {
                R.string.ai_chat_compressed
            } else {
                R.string.ai_chat_trimmed
            },
            note.count
        )
    }
    val text = message.text.replace(WHITESPACE_REGEX, " ").trim()
    if (text.isNotEmpty()) {
        return if (text.length > EXPORT_SUMMARY_MAX) {
            text.take(EXPORT_SUMMARY_MAX) + "…"
        } else {
            text
        }
    }
    val images = message.parts.count { it is ChatPart.Image }
    val docs = message.parts.count { it is ChatPart.Document }
    val summary = mutableListOf<String>()
    if (docs > 0) summary += stringResource(R.string.ai_chat_export_summary_docs, docs)
    if (images > 0) summary += stringResource(R.string.ai_chat_export_summary_images, images)
    return summary.joinToString(" · ")
}

/**
 * 图片选择对话框（§7.2，全屏；文档取图 / 软件图库双来源共用）：列出候选本地图片（缩略图 +
 * 名称 + 体积 + 来源标签），勾选后「添加」（逐张复用 [AiImagePreparer] 压缩管线；3 张上限
 * 由 VM 守卫）。[items] = null 表示未打开；打开后先置空列表（[loading] 转圈）再 IO 填充；
 * [source] 决定标题 / 空态文案与条目来源行含义。
 */
@Composable
internal fun DocImagePickerDialog(
    items: List<DocImageItem>?,
    loading: Boolean,
    source: ImagePickSource,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    if (items == null) return
    // 已选中项（absolutePath；保持选择顺序）；关闭（items = null）时状态自动重置
    var selected by remember { mutableStateOf(emptyList<String>()) }
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
                        text = stringResource(
                            if (source == ImagePickSource.GALLERY) {
                                R.string.ai_chat_gallery_image_title
                            } else {
                                R.string.ai_chat_doc_image_title
                            }
                        ),
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
                            text = stringResource(
                                if (source == ImagePickSource.GALLERY) {
                                    R.string.ai_chat_gallery_image_empty
                                } else {
                                    R.string.ai_chat_doc_image_empty
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.weight(1f)) {
                        items(items = items, key = { it.file.absolutePath }) { item ->
                            val checked = item.file.absolutePath in selected
                            DocImageRow(
                                item = item,
                                checked = checked,
                                onToggle = {
                                    selected = if (checked) {
                                        selected - item.file.absolutePath
                                    } else {
                                        selected + item.file.absolutePath
                                    }
                                }
                            )
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
                        Text(stringResource(R.string.ai_chat_doc_image_add, selected.size))
                    }
                }
            }
        }
    }
}

/** 图片条目：缩略图 + 图片名 + 体积 · 来源（文档名 / 仓库相对路径）+ 勾选。 */
@Composable
private fun DocImageRow(
    item: DocImageItem,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
    ) {
        AsyncImage(
            model = Uri.fromFile(item.file),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSize(item.sizeBytes) + " · " +
                    stringResource(R.string.ai_chat_doc_image_source, item.sourceLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 隐私一次性告知（§11.3）：首次发送前弹出；「我知道了」确认后持久化不再弹。 */
@Composable
internal fun PrivacyDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_chat_privacy_title)) },
        text = { Text(stringResource(R.string.ai_chat_privacy_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ai_chat_privacy_confirm))
            }
        }
    )
}

/** 导出条目角色徽标宽度。 */
private val EXPORT_BADGE_WIDTH = 32.dp

/** 导出条目摘要最长字符数（§8.1：摘要前 60 字）。 */
private const val EXPORT_SUMMARY_MAX = 60

/** 摘要空白归一（换行 / 连续空白压为单空格）。 */
private val WHITESPACE_REGEX = Regex("\\s+")

/** 导出条目时间（仅时:分；UI 单线程调用）。 */
private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
