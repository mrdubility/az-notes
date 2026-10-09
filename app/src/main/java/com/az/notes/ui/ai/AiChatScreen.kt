package com.az.notes.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.ai.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 对话页（§9，B2 骨架版）：
 * - TopAppBar：返回 / 标题 + 副标题（供应商 · 模型，点击弹模型菜单）/ ⊕ 新会话
 * - 消息列表：自动滚底（距底 < 100dp 跟随；用户上滑暂停并显示「回到底部」；
 *   滚动由消息指纹驱动、程序化滚动期间抑制跟随判定，防流式抖动与误锁）
 * - 输入卡片：imePadding 随键盘上浮；多行自增；生成中发送变停止；发送后收起输入法
 * - 附件行、压缩 / 图片 / 文档取图、导出、隐私提示与空态三快捷动作随 B3 / B4 引入，本批不预留
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

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
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

    // —— 自动滚底：距底 < 100dp 跟随；用户上滑（位置离开底部）暂停并显示「回到底部」——
    val thresholdPx = with(LocalDensity.current) { FOLLOW_THRESHOLD_DP.dp.toPx() }
    var following by remember { mutableStateOf(true) }
    // 程序化滚动守卫：滚底分两步（对齐末项 + 补滚溢出），中间态「末项顶部对齐」看似
    // 离开底部，必须抑制判定，否则流式高频触发下会把 following 永久锁死
    var programmaticScroll by remember { mutableStateOf(false) }

    // 滚动驱动：单一协程监听消息指纹（条数 / 末条文本与思考长度 / 状态），snapshotFlow
    // 自带背压合并；不再以内容为 LaunchedEffect key 重启滚动——流式每 50ms 取消进行中的
    // 滚动造成抽搐与不跟随（旧实现的根因）
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
            .collect {
                val current = messagesState.value
                if (following && current.isNotEmpty()) {
                    programmaticScroll = true
                    try {
                        listState.scrollToBottom(current.size - 1)
                    } catch (_: CancellationException) {
                        // 用户手势抢占程序滚动：本次跟随作废，following 交还位置判定接管
                    } finally {
                        programmaticScroll = false
                    }
                }
            }
    }
    // 跟随判定：滚动位置一变化即评估（不再监听 isScrollInProgress 停止边沿——程序滚动
    // 两步之间的瞬时「已停止」会被误判为离开底部，导致 following 永久失效）
    LaunchedEffect(listState, thresholdPx) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { if (!programmaticScroll) following = listState.isNearBottom(thresholdPx) }
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
                        onOpenProviders = onOpenProviders
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(items = messages, key = { it.id }) { message ->
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
                            scope.launch { listState.scrollToBottom(messages.size - 1) }
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
            ChatInputCard(
                value = input,
                onValueChange = viewModel::setInput,
                generating = generating,
                canSend = selectionLabel != null,
                onSend = {
                    // 自己发送时强制回到底部跟随；发送后收起输入法
                    following = true
                    keyboard?.hide()
                    viewModel.send()
                },
                onStop = viewModel::stop,
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
}

private const val FOLLOW_THRESHOLD_DP = 100

/** 距底判定：末项可见且其底边距视口底 ≤ 阈值（内容不足一屏时同样视为在底部）。 */
private fun LazyListState.isNearBottom(thresholdPx: Float): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index < info.totalItemsCount - 1) return false
    return lastVisible.offset + lastVisible.size - info.viewportEndOffset <= thresholdPx
}

/**
 * 滚动到真实底部：scrollToItem 只对齐项顶——末项高于视口（长回复）时，
 * 再补滚溢出部分；scrollBy 自带边界钳制（内容不足一屏时为无操作）。
 */
private suspend fun LazyListState.scrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return
    val overshoot = lastVisible.offset + lastVisible.size - info.viewportEndOffset
    if (overshoot > 0) scrollBy(overshoot.toFloat())
}
