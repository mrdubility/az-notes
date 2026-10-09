package com.az.notes.ui.ai

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.az.notes.R
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.SystemNote
import com.az.notes.domain.ai.SystemNoteMode
import com.az.notes.domain.ai.ToolCallRecord
import com.az.notes.domain.ai.ToolSpecs
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.resolve
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import java.io.File
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 对话页组件（§9.2）：
 * - [UserBubble] 右对齐气泡（无头像）+ 已发送文档 / 图片卡片 + 操作行（复制 / 编辑重发 / 选择导出）
 * - [AssistantBlock] 思考折叠行（思考中直播滚动 / 结束自动折叠）+ 工具活动行 +
 *   Markdown 正文 + 操作行 + 错误条
 * - [ToolActivityLine] 工具状态行（运行中逐条实时文案 / 完成后已读取摘要）
 * - [SystemNoteRow] 压缩系统条目（灰色小行：「已压缩 / 已丢弃 N 条早期消息」，§6.5）
 * - [PendingAttachmentRow] 待发附件卡片行（文档 + 图片缩略图 + 移除）
 * - [ChatInputCard] 多行自增输入卡片（生成中发送变停止）
 * - [ChatFeatureRow] 输入卡下方功能行（选择文档 / 压缩上下文 / 添加图片 / 文档取图，§9.1）
 * - [EmptyState] 欢迎语 + §9.3 三快捷动作；无供应商 / 无模型时替换为配置引导
 * - [ErrorBar] 红条 + 重试（401/404 附「去设置」）
 */

/**
 * 用户消息气泡（右对齐）：已发送文档 / 图片卡片在上方（便于回看本轮发送了什么），
 * 文本气泡在下；纯附件消息不渲染空气泡。[busy]（生成中）时隐藏编辑重发。
 */
@Composable
internal fun UserBubble(
    message: ChatMessage,
    busy: Boolean,
    onCopy: () -> Unit,
    onEditResend: () -> Unit,
    onExportSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End
    ) {
        message.parts.filterIsInstance<ChatPart.Document>().forEach { document ->
            SentAttachmentCard(document)
            Spacer(Modifier.height(4.dp))
        }
        message.parts.filterIsInstance<ChatPart.Image>().forEach { image ->
            ImageAttachmentCard(image)
            Spacer(Modifier.height(4.dp))
        }
        if (message.text.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        ChatActionRow(
            actions = buildList {
                if (message.text.isNotEmpty()) {
                    add(
                        ChatAction(
                            Icons.Outlined.ContentCopy,
                            stringResource(R.string.ai_chat_copy),
                            onCopy
                        )
                    )
                }
                if (!busy) {
                    add(
                        ChatAction(
                            Icons.Outlined.Edit,
                            stringResource(R.string.ai_chat_edit_resend),
                            onEditResend
                        )
                    )
                }
                add(
                    ChatAction(
                        Icons.Outlined.Share,
                        stringResource(R.string.ai_chat_export_select),
                        onExportSelect
                    )
                )
            },
            // 图标自带触控内边距：右移补齐，使视觉右缘与气泡对齐
            modifier = Modifier.offset(x = 4.dp)
        )
    }
}

/** 已发送文档卡片（用户气泡上方；与待发卡片同风格、不可移除）。 */
@Composable
private fun SentAttachmentCard(document: ChatPart.Document) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = document.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * AI 消息块（无头像）：思考折叠行 + Markdown 正文 + 操作行 + 错误条。
 * 思考中 =「reasoning 非空且正文为空且流式中」：默认展开固定高度滚动直播框；
 * 思考结束（首个正文 delta 到达）自动折叠；用户手动展开时显示全部内容。
 */
@Composable
internal fun AssistantBlock(
    message: ChatMessage,
    error: ChatErrorState?,
    busy: Boolean,
    toolRunning: List<ToolCallRecord>?,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportSelect: () -> Unit
) {
    val reasoning = message.reasoning
    val streaming = message.status == MessageStatus.STREAMING
    val hasText = message.text.isNotEmpty()
    val thinking = streaming && !hasText && !reasoning.isNullOrEmpty()

    var userChoice by remember(message.id) { mutableStateOf<Boolean?>(null) }
    val reasoningExpanded = userChoice ?: thinking

    // 思考结束自动折叠（覆盖展开的直播框；之后以用户手动选择为准）
    var wasThinking by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(thinking) {
        if (wasThinking && !thinking) userChoice = false
        wasThinking = thinking
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (!reasoning.isNullOrEmpty()) {
            ReasoningSection(
                reasoning = reasoning,
                thinking = thinking,
                expanded = reasoningExpanded,
                onToggle = { userChoice = !reasoningExpanded }
            )
            Spacer(Modifier.height(8.dp))
        }

        // 工具活动行：运行中（toolRunning）或完成后（toolTrail 摘要），正文之前展示
        if (toolRunning != null && toolRunning.isNotEmpty()) {
            ToolActivityLine(records = toolRunning, running = true)
            Spacer(Modifier.height(8.dp))
        } else if (message.toolTrail.isNotEmpty()) {
            ToolActivityLine(records = message.toolTrail, running = false)
            Spacer(Modifier.height(8.dp))
        }

        when {
            hasText -> ChatMarkdown(text = message.text, streaming = streaming)

            streaming && reasoning.isNullOrEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ai_chat_generating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 错误条：仅错误消息本人展示（关联 id 校验，防截断后残留）
        if (message.status == MessageStatus.ERROR && error != null && error.messageId == message.id) {
            Spacer(Modifier.height(4.dp))
            ErrorBar(
                text = error.text,
                showSettings = error.showSettings,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings
            )
        }

        // 操作行：流结束后展示（复制 / 重新生成；错误条另含重试）——
        // 与正文留出呼吸间距；图标视觉左缘与正文对齐（左移抵消图标触控内边距）
        if (hasText && !streaming) {
            Spacer(Modifier.height(6.dp))
            ChatActionRow(
                actions = buildList {
                    add(
                        ChatAction(
                            Icons.Outlined.ContentCopy,
                            stringResource(R.string.ai_chat_copy),
                            onCopy
                        )
                    )
                    if (!busy) {
                        add(
                            ChatAction(
                                Icons.Outlined.Refresh,
                                stringResource(R.string.ai_chat_regenerate),
                                onRegenerate
                            )
                        )
                    }
                    add(
                        ChatAction(
                            Icons.Outlined.Share,
                            stringResource(R.string.ai_chat_export_select),
                            onExportSelect
                        )
                    )
                },
                modifier = Modifier.offset(x = (-4).dp)
            )
        }
    }
}

/**
 * 工具活动行：运行中逐条展示实时操作（读取 / 搜索 / 浏览，argsSummary 填充）；
 * 完成后从轨迹聚合「已读取」摘要（无读取则回退为执行次数）。
 */
@Composable
internal fun ToolActivityLine(records: List<ToolCallRecord>, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (running) {
            records.forEach { record ->
                ToolStatusRow(
                    running = true,
                    text = when (record.tool) {
                        ToolSpecs.READ_NOTE ->
                            stringResource(R.string.ai_chat_tool_reading, record.argsSummary)

                        ToolSpecs.SEARCH_NOTES ->
                            stringResource(R.string.ai_chat_tool_searching, record.argsSummary)

                        ToolSpecs.LIST_NOTES -> if (record.argsSummary.isBlank() || record.argsSummary == ".") {
                            stringResource(R.string.ai_chat_tool_listing_root)
                        } else {
                            stringResource(R.string.ai_chat_tool_listing, record.argsSummary)
                        }

                        else -> record.tool
                    }
                )
            }
        } else {
            ToolStatusRow(running = false, text = completedLine(records))
        }
    }
}

/** 工具状态行单行（前缀：运行中 = 转圈；完成 = 对勾）。 */
@Composable
private fun ToolStatusRow(running: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 完成态摘要：优先列出读取过的文件名（路径归一后去重，超 [READ_SUMMARY_MAX] 篇附「等 N 篇」）。 */
@Composable
private fun completedLine(records: List<ToolCallRecord>): String {
    val readTargets = records
        .filter { it.tool == ToolSpecs.READ_NOTE && it.argsSummary.isNotBlank() }
        .map { normalizeToolPath(it.argsSummary) }
        .distinct()
    if (readTargets.isEmpty()) {
        return stringResource(R.string.ai_chat_tool_executed, records.size)
    }
    return if (readTargets.size > READ_SUMMARY_MAX) {
        stringResource(
            R.string.ai_chat_tool_read_summary_more,
            readTargets.take(READ_SUMMARY_MAX).joinToString("、"),
            readTargets.size
        )
    } else {
        stringResource(R.string.ai_chat_tool_read_summary, readTargets.joinToString("、"))
    }
}

/**
 * 压缩系统条目（§6.5）：灰色小行展示压缩结果概要（「已压缩 / 已丢弃 N 条早期消息」）；
 * 摘要正文不再直接展示（已进入后续对话上下文，完整摘要经选中导出回看）。
 */
@Composable
internal fun SystemNoteRow(note: SystemNote) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                when (note.mode) {
                    SystemNoteMode.SUMMARIZED -> R.string.ai_chat_compressed
                    SystemNoteMode.TRIMMED -> R.string.ai_chat_trimmed
                },
                note.count
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 待发附件卡片行：文档卡 + 图片卡（缩略图 + 文件名 + 移除，横向滚动）。 */
@Composable
internal fun PendingAttachmentRow(
    documents: List<ChatPart.Document>,
    images: List<ChatPart.Image>,
    onRemove: (String) -> Unit,
    onRemoveImage: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items = documents, key = { it.vaultRelPath }) { document ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(
                            R.string.ai_chat_attachment_remove,
                            document.name
                        ),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onRemove(document.vaultRelPath) }
                            .padding(4.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(items = images, key = { it.localPath }) { image ->
            ImageAttachmentCard(image = image, onRemove = { onRemoveImage(image.localPath) })
        }
    }
}

/** 图片附件卡片（待发 / 已发送复用）：缩略图（本地文件）+ 文件名 + 可选移除。 */
@Composable
private fun ImageAttachmentCard(image: ChatPart.Image, onRemove: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
        ) {
            AsyncImage(
                model = Uri.fromFile(File(image.localPath)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = image.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            if (onRemove != null) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(
                        R.string.ai_chat_attachment_remove,
                        image.name
                    ),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onRemove)
                        .padding(4.dp)
                        .size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 正文渲染：流式中用纯文本（未闭合的 Markdown 标记令解析结构反复突变，底部锚定下
 * 表现为屏幕持续跳动），流结束后一次性切换完整 Markdown 渲染（行业成熟做法）。
 */
@Composable
private fun ChatMarkdown(text: String, streaming: Boolean) {
    if (streaming) {
        // 流式期间纯文本：高度单调增长，反转布局底部锚定下画面稳定不跳
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    // 解析链实例显式稳定化：默认参数在重组时会新建实例导致反复重新解析（对齐预览页先例）
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val linkHandler = remember { ReferenceLinkHandlerImpl() }
    val staticAnimations = remember { DefaultMarkdownAnimation(animateTextSize = { this }) }
    val markdownState = rememberMarkdownState(
        content = text,
        flavour = flavour,
        parser = parser,
        referenceLinkHandler = linkHandler
    )
    Markdown(
        markdownState = markdownState,
        modifier = Modifier.fillMaxWidth(),
        animations = staticAnimations
    )
}

/**
 * 思考折叠行（设计稿同款无背景行：灯泡图标 + 文案 + 展开箭头；不计时，仅本地展示）。
 * 左缘与正文对齐；展开的思考内容与正文同宽（不再内缩）。
 */
@Composable
private fun ReasoningSection(
    reasoning: String,
    thinking: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(
                    if (thinking) R.string.ai_chat_reasoning_thinking
                    else R.string.ai_chat_reasoning_done
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            if (thinking) {
                // 思考中：固定高度滚动直播（随输出自动滚到底）
                val scrollState = rememberScrollState()
                LaunchedEffect(Unit) {
                    snapshotFlow { scrollState.maxValue }
                        .collect { scrollState.scrollTo(it) }
                }
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(REASONING_LIVE_HEIGHT)
                        .verticalScroll(scrollState)
                        .padding(bottom = 4.dp)
                )
            } else {
                // 思考结束：展开显示全部内容（不限制高度）
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

/** 错误条：红底 + 文案 + 重试；配置类错误（401/404）附「去设置」。 */
@Composable
internal fun ErrorBar(
    text: UiText,
    showSettings: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = text.resolve(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(stringResource(R.string.ai_chat_retry), style = MaterialTheme.typography.labelMedium)
            }
            if (showSettings) {
                TextButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.ai_chat_go_settings),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

/** 消息操作行单项（图标 + 无障碍文案）。 */
private data class ChatAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

/** 紧凑操作行：小号图标按钮（复制 / 编辑重发 / 重新生成）。 */
@Composable
private fun ChatActionRow(actions: List<ChatAction>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = action.onClick)
                    .padding(4.dp)
                    .size(16.dp)
            )
        }
    }
}

/**
 * 输入卡片：多行自增；生成中发送变停止。功能入口（选择文档 / 压缩 / 图片）在
 * 卡片下方 [ChatFeatureRow] 一行四按钮（§9.1）。
 * 边距：文本左距 = 行首 4dp + TextField 内建 16dp = 20dp；按钮距卡缘 8dp，
 * 单行时与 56dp 文本行垂直居中（40dp 圆钮 + 底部 8dp，中心对齐）。
 */
@Composable
internal fun ChatInputCard(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    canSend: Boolean,
    hasAttachments: Boolean,
    inputFocusRequester: FocusRequester,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocusRequester),
                placeholder = { Text(stringResource(R.string.ai_chat_input_hint)) },
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            FilledIconButton(
                onClick = if (generating) onStop else onSend,
                enabled = generating || (canSend && (value.isNotBlank() || hasAttachments)),
                // bottom padding：单行时与输入框文本行垂直居中；多行增长时保持贴底
                modifier = Modifier
                    .padding(start = 2.dp, bottom = 8.dp)
                    .size(40.dp)
            ) {
                if (generating) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.ai_chat_stop))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ai_chat_send))
                }
            }
        }
    }
}

/**
 * 功能行（§9.1，输入卡下方一行四按钮）：[选择文档][压缩上下文][添加图片][文档取图]。
 * - 「压缩上下文」：空闲且有可压缩区（[canCompress]）才可点；[compressing] 时文案变
 *   「正在压缩…」并禁用（手动压缩防重入，§6.4）
 * - 「添加图片 / 文档取图」：当前模型不支持图片（vision=false）时置灰，行下小字提示（§7.3）
 */
@Composable
internal fun ChatFeatureRow(
    canCompress: Boolean,
    compressing: Boolean,
    imageEnabled: Boolean,
    onPickDocument: () -> Unit,
    onCompress: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            FeatureButton(
                icon = Icons.Outlined.Description,
                label = stringResource(R.string.ai_chat_attach_document),
                enabled = true,
                onClick = onPickDocument,
                modifier = Modifier.weight(1f)
            )
            FeatureButton(
                icon = Icons.Outlined.Compress,
                label = if (compressing) {
                    stringResource(R.string.ai_chat_compressing)
                } else {
                    stringResource(R.string.ai_chat_compress)
                },
                enabled = canCompress,
                onClick = onCompress,
                modifier = Modifier.weight(1f)
            )
            FeatureButton(
                icon = Icons.Outlined.AddPhotoAlternate,
                label = stringResource(R.string.ai_chat_add_image),
                enabled = imageEnabled,
                onClick = onPickImage,
                modifier = Modifier.weight(1f)
            )
            FeatureButton(
                icon = Icons.Outlined.PhotoLibrary,
                label = stringResource(R.string.ai_chat_doc_image),
                enabled = imageEnabled,
                onClick = onPickDocImage,
                modifier = Modifier.weight(1f)
            )
        }
        if (!imageEnabled) {
            Text(
                text = stringResource(R.string.ai_chat_image_unsupported),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp)
            )
        }
    }
}

/** 功能行按钮：小图标 16dp + labelMedium 文案（weight 均分，窄屏省略号兜底）。 */
@Composable
private fun FeatureButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 空态：欢迎语 + §9.3 三快捷动作（选择文档提问 / 让 AI 浏览仓库 / 直接提问）；
 * 无供应商 / 无模型（[hasSelection] = false 且已回流完成）时替换为配置引导（直达供应商管理页）。
 */
@Composable
internal fun EmptyState(
    loaded: Boolean,
    hasSelection: Boolean,
    onOpenProviders: () -> Unit,
    onPickDocument: () -> Unit,
    onBrowseRepository: () -> Unit,
    onAskDirectly: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loaded && !hasSelection) {
            Text(
                text = stringResource(R.string.ai_chat_no_provider),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenProviders) {
                Text(stringResource(R.string.ai_chat_go_provider))
            }
        } else {
            Text(
                text = stringResource(R.string.ai_chat_welcome_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ai_chat_welcome_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            // §9.3 三快捷动作：选择文档提问 / 让 AI 浏览仓库 / 直接提问
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickDocument) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_chat_quick_attach))
                }
                OutlinedButton(onClick = onBrowseRepository) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_chat_quick_browse))
                }
                OutlinedButton(onClick = onAskDirectly) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_chat_quick_ask))
                }
            }
        }
    }
}

/** 思考中直播框固定高度（一小块滚动区域，约 5 行 bodySmall）。 */
private val REASONING_LIVE_HEIGHT = 96.dp

/** 完成态「已读取」摘要最多列出的文件名数（超出附「等 N 篇」）。 */
private const val READ_SUMMARY_MAX = 5

/**
 * 工具路径归一（read_note argsSummary 去重与展示用）：统一分隔符、剥前导斜杠与
 * `.` 段（`./a.md` 与 `a.md`、`/folder/a.md` 与 `folder/a.md` 视为同一文档）。
 */
internal fun normalizeToolPath(path: String): String =
    path.trim()
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")
