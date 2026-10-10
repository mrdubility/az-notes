package com.az.notes.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.ui.common.resolve
import com.az.notes.ui.components.MoveTargetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对话页（§9）：
 * - TopAppBar：返回 / 标题 + 副标题（供应商 · 模型，点击弹模型菜单）/ 导出 / ⊕ 新会话
 * - 消息列表：反转布局（列表原点即底部、index 0 为最新）——贴底时流式增长由布局
 *   锚定自动保持、无需程序滚动（消除闪动）；离开底部暂停跟随并显示「回到底部」，
 *   程序滚动统一走 animate 平滑过渡
 * - 输入区：待发附件行（文档 + 图片）→ 输入卡片（imePadding 随键盘上浮）→ 功能行
 *   （选择文档 / 压缩上下文 / 添加图片 / 文档取图，§9.1）→ 甄别常驻行
 * - 弹层：文档选择器 / 导出选择（§8.1）/ 文档取图（§7.2）/ 隐私一次性告知（§11.3）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenNote: (String) -> Unit
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
    val pendingImages by viewModel.pendingImages.collectAsStateWithLifecycle()
    val toolStatus by viewModel.toolStatus.collectAsStateWithLifecycle()
    val docPickerItems by viewModel.docPickerItems.collectAsStateWithLifecycle()
    val docPickerQuery by viewModel.docPickerQuery.collectAsStateWithLifecycle()
    val docPickerLoading by viewModel.docPickerLoading.collectAsStateWithLifecycle()
    val docPickerHasMore by viewModel.docPickerHasMore.collectAsStateWithLifecycle()
    val docPickerFavoritesOnly by viewModel.docPickerFavoritesOnly.collectAsStateWithLifecycle()
    val canCompress by viewModel.canCompress.collectAsStateWithLifecycle()
    val compressing by viewModel.compressing.collectAsStateWithLifecycle()
    val visionEnabled by viewModel.visionEnabled.collectAsStateWithLifecycle()
    val docImageItems by viewModel.docImageItems.collectAsStateWithLifecycle()
    val docImageLoading by viewModel.docImageLoading.collectAsStateWithLifecycle()
    val imagePickSource by viewModel.imagePickSource.collectAsStateWithLifecycle()
    val exportVisible by viewModel.exportVisible.collectAsStateWithLifecycle()
    val exportDefaultSelected by viewModel.exportDefaultSelected.collectAsStateWithLifecycle()
    val exportTargetLabel by viewModel.exportTargetLabel.collectAsStateWithLifecycle()
    val exportFolderPicker by viewModel.exportFolderPicker.collectAsStateWithLifecycle()
    val privacyDialog by viewModel.privacyDialog.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.message.collectAsStateWithLifecycle()
    val exportDone by viewModel.exportDone.collectAsStateWithLifecycle()

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
    // 跟随意图只在贴底时为 true——用户任何上滑（离开底部）立即暂停跟随（从头阅读不被拉回），
    // 只有滑回贴底或点击「回到底部」按钮才恢复跟随——
    var following by remember { mutableStateOf(true) }
    // 程序滚动守卫：平滑滚动的动画期间抑制位置观察，防动画中间态互夺跟随状态
    var programmaticScroll by remember { mutableStateOf(false) }

    // 用户交互守卫（修复「上滑无法暂停/回底不恢复」）：程序滚动与用户手势共享滚动互斥锁
    // 且手势优先级更高——手指按住期间发起的 animateScrollToItem 不会抢占手势，而是「排队
    // 等待手势结束」后才执行；等待期间位置观察被 programmaticScroll 抑制，用户整个手势期间
    // 的跟随状态更新（含滑出跟随阈值）全部被吞掉，抬手后排队动画再把画面拉回底部。
    // 因此用户交互（按住 / 拖拽 / 惯性）期间禁止发起新的程序滚动：
    // - listPressed：手指按住列表（在事件 Initial 阶段最早捕获，覆盖手势开始前的窗口）
    // - userScrolling：手势滚动进行中（拖拽与抬手后的惯性都保持 true）
    var listPressed by remember { mutableStateOf(false) }
    var userScrolling by remember { mutableStateOf(false) }

    // 漂移补偿（暂停跟随防画面漂移）：反转布局锚定保持「距列表底端」的距离，
    // 最新消息（index 0）流式增长会推高整列内容（阅读画面向上漂移）；记录最新消息的
    // 实时布局高度与已补偿基线，暂停时按高度增量向下滚动抵消，保持阅读画面稳定
    var latestItemHeight by remember { mutableStateOf(0) }
    var compensatedHeight by remember { mutableStateOf(0) }

    // 唯一滚动出口：平滑滚到最新。
    // - 防重入：追赶滚动进行中时直接返回——高频内容变化不打断进行中的动画
    //   （反复 cancel 重启动画是「顿挫感」的来源）；
    // - 追赶循环：动画完成后若仍跟随且未贴底（期间内容又增长），自动续滚直至贴底。
    var scrollInFlight by remember { mutableStateOf(false) }
    val scrollToLatest: suspend () -> Unit = {
        if (!scrollInFlight && !listPressed && !userScrolling) {
            scrollInFlight = true
            programmaticScroll = true
            try {
                while (following && !listState.isAtLatest()) {
                    listState.animateScrollToItem(0)
                }
            } catch (_: CancellationException) {
                // 用户手势抢占程序滚动：本次作废，跟随状态交还位置观察接管
            } finally {
                programmaticScroll = false
                scrollInFlight = false
            }
        }
    }

    // 暂停跟随时的漂移补偿：内容增长 Δ → 画面向上漂移 Δ → scrollBy(+Δ) 向下滚动抵消
    // （scrollBy 正值 = 向历史方向，即 firstVisibleItemScrollOffset 增大）。
    // - Mutex 串行化：内容流与静止发射两路并发触发时防重复补偿（基线重算收敛）；
    // - 用户交互（按住/拖拽/惯性）期间跳过：手势自身主导画面位置，且共享互斥锁下
    //   scrollBy 会排队等待手势结束；该期间的漂移由位置观察的静止发射兑底补偿。
    val driftMutex = remember { Mutex() }
    val compensateDrift: suspend () -> Unit = {
        driftMutex.withLock {
            if (!following && !listPressed && !userScrolling) {
                val delta = latestItemHeight - compensatedHeight
                if (delta != 0) {
                    if (listState.isAtLatest()) {
                        // 已回贴底：贴底锚定自动保持画面，仅同步基线
                        compensatedHeight = latestItemHeight
                    } else {
                        try {
                            listState.scrollBy(delta.toFloat())
                            compensatedHeight = latestItemHeight
                        } catch (_: CancellationException) {
                            // 滚动被用户手势抢占：本次作废，基线保持待静止发射兑底
                        }
                    }
                }
            }
        }
    }

    // 高度观测：监听布局信息中最新消息（index 0）的实时高度，供漂移补偿使用
    // （item 0 不可见时不更新，漂移补偿跳过；高度写由指纹流吸收后触发补偿）
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.height
        }
            .distinctUntilChanged()
            .collect { height ->
                if (height != null) latestItemHeight = height
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
                last?.status?.ordinal ?: -1,
                latestItemHeight
            )
        }
            .distinctUntilChanged()
            .collect {
                if (following) {
                    scrollToLatest()
                    // 跟随态（贴底锚定自动保持画面）同步漂移补偿基线
                    compensatedHeight = latestItemHeight
                } else {
                    compensateDrift()
                }
            }
    }
    // 位置观察：用户手势（拖拽/惯性）期间实时更新跟随意图——贴底才跟随：上滑一旦离开
    // 底部立即暂停（用户从头阅读时不被拉回），滑回贴底自动恢复；静止发射复位手势滚动
    // 标记并兑底补偿手势期间的漂移（未贴底的收口统一由内容驱动出口在跟随态下处理，
    // 避免「近底拉回」干扰阅读）
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to
                (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        }
            .collect { (inProgress, pos) ->
                if (programmaticScroll) return@collect
                if (inProgress) {
                    // 非程序动画的滚动进行中 = 用户手势（拖拽/惯性）：
                    // 贴底才跟随——上滑离开底部立即暂停，滑回贴底自动恢复
                    userScrolling = true
                    following = pos.first == 0 && pos.second == 0
                } else {
                    userScrolling = false
                    // 手势结束（互斥锁已释放）：兑底补偿手势期间的累计漂移
                    compensateDrift()
                }
            }
    }

    val copyToClipboard: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("az-notes-ai-chat", text))
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ai_chat_copied)) }
    }

    // 系统图片选择器（相册）：选中后交 VM 预处理（压缩 + 临时落盘，§7）
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.attachImage(uri)
    }

    // 一次性 Snackbar 文案（自动压缩 / 图片失败 / 导出失败等；展示后消费）
    val snackbarText = snackbarMessage?.resolve()
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.consumeMessage()
        }
    }

    // 导出完成提示（含导出位置）；「查看」跳预览页（§8.2）
    LaunchedEffect(exportDone) {
        val done = exportDone ?: return@LaunchedEffect
        val location = done.locationLabel ?: context.getString(R.string.ai_chat_export_root)
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.ai_chat_export_done, location),
            actionLabel = context.getString(R.string.ai_chat_export_view),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) onOpenNote(done.absolutePath)
        viewModel.consumeExportDone()
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
                    // 导出入口（§8.1）：有消息时可用，默认不勾选
                    IconButton(
                        onClick = { viewModel.openExport() },
                        enabled = messages.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            stringResource(R.string.ai_chat_export)
                        )
                    }
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
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                // 观测手指按住状态（不消费事件、不影响列表手势）：
                                // 用户交互守卫的最早信号（见上方 scrollToLatest 门控）
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        listPressed = event.changes.any { it.pressed }
                                    }
                                }
                            },
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(items = messages.asReversed(), key = { it.id }) { message ->
                            when (message.role) {
                                ChatRole.USER -> UserBubble(
                                    message = message,
                                    busy = generating,
                                    onCopy = { copyToClipboard(message.text) },
                                    onEditResend = {
                                        // 主动重发路径同样强制跟随
                                        following = true
                                        viewModel.editResend(message.id)
                                    },
                                    onExportSelect = { viewModel.openExport(message.id) }
                                )

                                ChatRole.SYSTEM -> message.systemNote?.let { note ->
                                    SystemNoteRow(note)
                                }

                                else -> AssistantBlock(
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
                                    onOpenSettings = onOpenProviders,
                                    onExportSelect = { viewModel.openExport(message.id) }
                                )
                            }
                        }
                    }
                }
                // 用户上滑离开底部：显示「回到底部」按钮；输出中附加环绕按钮的旋转圆弧动画，
                // 输出结束移除组合即停止动画
                if (messages.isNotEmpty() && !following) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(48.dp)
                    ) {
                        if (generating) {
                            val ringTransition = rememberInfiniteTransition(label = "aiOutputRing")
                            val ringAngle by ringTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "aiOutputRingAngle"
                            )
                            val ringColor = MaterialTheme.colorScheme.primary
                            Canvas(Modifier.fillMaxSize()) {
                                val stroke = 3.dp.toPx()
                                drawArc(
                                    color = ringColor,
                                    startAngle = ringAngle - 90f,
                                    sweepAngle = 110f,
                                    useCenter = false,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                    topLeft = Offset(stroke, stroke),
                                    size = Size(size.width - stroke * 2, size.height - stroke * 2)
                                )
                            }
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                following = true
                                scope.launch { scrollToLatest() }
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                stringResource(R.string.ai_chat_back_to_bottom)
                            )
                        }
                    }
                }
            }
            // 待发附件 / 图片卡片行（非空时；随发送清空）
            if (pendingAttachments.isNotEmpty() || pendingImages.isNotEmpty()) {
                PendingAttachmentRow(
                    documents = pendingAttachments,
                    images = pendingImages,
                    onRemove = viewModel::removePendingAttachment,
                    onRemoveImage = viewModel::removePendingImage
                )
                Spacer(Modifier.height(4.dp))
            }
            ChatInputCard(
                value = input,
                onValueChange = viewModel::setInput,
                generating = generating,
                canSend = selectionLabel != null,
                hasAttachments = pendingAttachments.isNotEmpty() || pendingImages.isNotEmpty(),
                inputFocusRequester = inputFocusRequester,
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
            // 功能行（§9.1）：选择文档 / 添加图片（弹出文档·相册·图库三来源）/ 压缩上下文
            ChatFeatureRow(
                canCompress = canCompress,
                compressing = compressing,
                imageEnabled = visionEnabled,
                onPickDocument = viewModel::openDocumentPicker,
                onCompress = viewModel::compressContext,
                onPickImageFromDoc = viewModel::openDocImagePicker,
                onPickImageFromAlbum = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickImageFromGallery = viewModel::openGalleryPicker,
                modifier = Modifier.fillMaxWidth()
            )
            // 常驻甄别行
            Text(
                text = stringResource(R.string.ai_chat_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 6.dp)
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
        favoritesOnly = docPickerFavoritesOnly,
        onQueryChange = viewModel::setDocPickerQuery,
        onFavoritesChange = viewModel::setDocPickerFavoritesOnly,
        onLoadMore = viewModel::loadMoreDocPicker,
        onConfirm = viewModel::confirmDocumentSelection,
        onDismiss = viewModel::closeDocumentPicker
    )

    ExportDialog(
        visible = exportVisible,
        messages = messages,
        defaultSelectedId = exportDefaultSelected,
        targetFolderLabel = exportTargetLabel ?: stringResource(R.string.ai_chat_export_root),
        onPickFolder = viewModel::openExportFolderPicker,
        onConfirm = viewModel::exportSelected,
        onDismiss = viewModel::closeExport
    )

    // 导出位置选择：复用移动目标对话框（列出仓库根与全部子目录，按层级缩进）
    exportFolderPicker?.let { picker ->
        MoveTargetDialog(
            vaultPath = picker.vaultPath,
            targets = picker.targets,
            title = stringResource(R.string.ai_chat_export_location),
            onDismiss = viewModel::dismissExportFolderPicker,
            onSelect = viewModel::pickExportFolder
        )
    }

    DocImagePickerDialog(
        items = docImageItems,
        loading = docImageLoading,
        // 与 items 同步置位（VM 内先 source 后 items）；null 兜底仅满足类型，不参与实际渲染
        source = imagePickSource ?: ImagePickSource.DOCUMENT,
        onConfirm = viewModel::confirmDocImageSelection,
        onDismiss = viewModel::closeDocImagePicker
    )

    PrivacyDialog(
        visible = privacyDialog,
        onConfirm = viewModel::confirmPrivacy,
        onDismiss = viewModel::dismissPrivacy
    )
}

/**
 * 反转布局（reverseLayout）下滚动原点即列表底部、index 0 为最新消息：
 * 贴底 = firstVisibleItemIndex 0 且滚动偏移 0（内容不足一屏时同样成立）。
 */
private fun LazyListState.isAtLatest(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
