package com.az.notes.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 对话页（§9，B3 工具循环版）：
 * - TopAppBar：返回 / 标题 + 副标题（供应商 · 模型，点击弹模型菜单）/ ⊕ 新会话
 * - 消息列表：反转布局（列表原点即底部、index 0 为最新）——贴底时流式增长由布局
 *   锚定自动保持、无需程序滚动（消除闪动）；离开底部暂停跟随并显示「回到底部」，
 *   程序滚动统一走 animate 平滑过渡
 * - 输入卡片：imePadding 随键盘上浮；多行自增；生成中发送变停止；发送后收起输入法；
 *   输入行内置「选择文档」回形针入口；待发附件卡片行 + §9.3 空态三快捷动作
 * - 导出、隐私提示、压缩 / 图片项随 B4 引入，本批不预留
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit
) {
    // 保留 State 而非 by 解构：滚动协程内需同步读取最新消息构建指纹
    val messagesState = viewModel.messages.collectAsStateWithLifecycle()
    val messages = messagesState.value
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val showConfirm by viewModel.showNewSessionConfirm.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val toolStatus by viewModel.toolStatus.collectAsStateWithLifecycle()
    val docPickerItems by viewModel.docPickerItems.collectAsStateWithLifecycle()
    val docPickerQuery by viewModel.docPickerQuery.collectAsStateWithLifecycle()
    val docPickerLoading by viewModel.docPickerLoading.collectAsStateWithLifecycle()
    val docPickerHasMore by viewModel.docPickerHasMore.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 空态「直接提问」/「让 AI 浏览仓库」快捷动作的焦点目标
    val inputFocusRequester = remember { FocusRequester() }
    val browsePrompt = stringResource(R.string.ai_chat_quick_browse_prompt)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // 副标题文案：供应商 · 模型（解析失败 = 无可用选择 → 空态引导）
    val selectionLabel = remember(selection, providers) {
        val ref = selection ?: return@remember null
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return@remember null
        val model = provider.models.firstOrNull { it.id == ref.modelId } ?: return@remember null
        "${provider.name} · ${model.label}"
    }

    // —— 自动跟随（反转布局）：贴底时流式增长由布局锚定自动保持（无需程序滚动）；
    // 离开底部暂停跟随并显示「回到底部」——
    val thresholdPx = with(LocalDensity.current) { FOLLOW_THRESHOLD_DP.dp.toPx() }
    var following by remember { mutableStateOf(true) }
    // 程序滚动守卫：平滑滚动的动画期间抑制位置观察，防动画中间态互夺跟随状态
    var programmaticScroll by remember { mutableStateOf(false) }

    // 唯一滚动出口：平滑滚到最新（已贴底则跳过零成本）
    val scrollToLatest: suspend () -> Unit = {
        if (!listState.isAtLatest()) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(0)
            } catch (_: CancellationException) {
                // 用户手势抢占程序滚动：本次作废，跟随状态交还位置观察接管
            } finally {
                programmaticScroll = false
            }
        }
    }

    // 内容驱动：消息指纹（条数 / 末条文本与思考长度 / 状态）变化时若处于跟随态则收口到底。
    // 贴底流式（最常见）时位置恒为 (0, 0)，本路无操作——零程序滚动即零闪动
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = messagesState.value.lastOrNull()
            listOf(
                messagesState.value.size,
                last?.text?.length ?: 0,
                last?.reasoning?.length ?: 0,
                last?.status?.ordinal ?: -1
            )
        }
            .distinctUntilChanged()
            .collect { if (following) scrollToLatest() }
    }
    // 位置观察：滚动中实时更新跟随意图（距底 ≤ 阈值恢复跟随）；静止时若跟随态下仍
    // 离开底部（数据增删的自动位置修正、被取消动画的残留）→ 平滑收口回底
    LaunchedEffect(listState, thresholdPx) {
        snapshotFlow {
            listState.isScrollInProgress to
                (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        }
            .collect { (inProgress, pos) ->
                if (programmaticScroll) return@collect
                if (inProgress) {
                    following = pos.first == 0 && pos.second <= thresholdPx
                } else if (following && (pos.first != 0 || pos.second != 0)) {
                    scrollToLatest()
                }
            }
    }

    val copyToClipboard: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("az-notes-ai-chat", text))
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ai_chat_copied)) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.ai_chat_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Box {
                            // 副标题：点击弹模型选择菜单（无可用选择时禁用，走空态引导）
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = selectionLabel != null) {
                                        modelMenuExpanded = true
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectionLabel
                                            ?: stringResource(R.string.ai_chat_no_model),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (selectionLabel != null) {
                                        Icon(
                                            imageVector = Icons.Filled.ArrowDropDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            ModelPickerMenu(
                                expanded = modelMenuExpanded,
                                onDismiss = { modelMenuExpanded = false },
                                providers = providers,
                                selection = selection,
                                onSelect = { providerId, modelId ->
                                    viewModel.switchModel(providerId, modelId)
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::requestNewSession) {
                        Icon(
                            Icons.Outlined.Add,
                            stringResource(R.string.ai_chat_new_session)
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(
                        loaded = loaded,
                        hasSelection = selectionLabel != null,
                        onOpenProviders = onOpenProviders,
                        onPickDocument = viewModel::openDocumentPicker,
                        onBrowseRepository = {
                            viewModel.setInput(browsePrompt)
                            inputFocusRequester.requestFocus()
                        },
                        onAskDirectly = { inputFocusRequester.requestFocus() }
                    )
                } else {
                    // 反转布局：列表原点即最新消息——贴底时内容增长由布局锚定自然保持
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(items = messages.asReversed(), key = { it.id }) { message ->
                            if (message.role == ChatRole.USER) {
                                UserBubble(
                                    message = message,
                                    busy = generating,
                                    onCopy = { copyToClipboard(message.text) },
                                    onEditResend = {
                                        // 主动重发路径同样强制跟随
                                        following = true
                                        viewModel.editResend(message.id)
                                    }
                                )
                            } else {
                                AssistantBlock(
                                    message = message,
                                    error = error,
                                    busy = generating,
                                    toolRunning = if (message.status == MessageStatus.STREAMING) {
                                        toolStatus
                                    } else {
                                        null
                                    },
                                    onCopy = { copyToClipboard(message.text) },
                                    onRegenerate = {
                                        // 主动重新生成路径同样强制跟随
                                        following = true
                                        viewModel.regenerate(message.id)
                                    },
                                    onRetry = {
                                        following = true
                                        viewModel.regenerate(message.id)
                                    },
                                    onOpenSettings = onOpenProviders
                                )
                            }
                        }
                    }
                }
                // 用户上滑离开底部：显示「回到底部」气泡
                if (messages.isNotEmpty() && !following) {
                    SmallFloatingActionButton(
                        onClick = {
                            following = true
                            scope.launch { scrollToLatest() }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            stringResource(R.string.ai_chat_back_to_bottom)
                        )
                    }
                }
            }
            // 待发附件卡片行（非空时；随发送清空）
            if (pendingAttachments.isNotEmpty()) {
                PendingAttachmentRow(
                    documents = pendingAttachments,
                    onRemove = viewModel::removePendingAttachment
                )
                Spacer(Modifier.height(4.dp))
            }
            ChatInputCard(
                value = input,
                onValueChange = viewModel::setInput,
                generating = generating,
                canSend = selectionLabel != null,
                hasAttachments = pendingAttachments.isNotEmpty(),
                inputFocusRequester = inputFocusRequester,
                onSend = {
                    // 自己发送时强制回到底部跟随；发送后收起输入法
                    following = true
                    keyboard?.hide()
                    viewModel.send()
                },
                onStop = viewModel::stop,
                onPickDocument = viewModel::openDocumentPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    ClearSessionConfirm(
        visible = showConfirm,
        onConfirm = viewModel::confirmNewSession,
        onDismiss = viewModel::dismissNewSession
    )

    DocumentPickerDialog(
        items = docPickerItems,
        query = docPickerQuery,
        loading = docPickerLoading,
        hasMore = docPickerHasMore,
        pendingRelPaths = pendingAttachments.map { it.vaultRelPath }.toSet(),
        onQueryChange = viewModel::setDocPickerQuery,
        onLoadMore = viewModel::loadMoreDocPicker,
        onConfirm = viewModel::confirmDocumentSelection,
        onDismiss = viewModel::closeDocumentPicker
    )
}

private const val FOLLOW_THRESHOLD_DP = 100

/**
 * 反转布局（reverseLayout）下滚动原点即列表底部、index 0 为最新消息：
 * 贴底 = firstVisibleItemIndex 0 且滚动偏移 0（内容不足一屏时同样成立）。
 */
private fun LazyListState.isAtLatest(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
