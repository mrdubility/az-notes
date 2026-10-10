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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.data.ai.AiDiag
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.ui.common.resolve
import com.az.notes.ui.common.showTimedSnackbar
import com.az.notes.ui.components.MoveTargetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 对话页（§9）：
 * - TopAppBar：返回 / 标题 + 副标题（供应商 · 模型，点击弹模型菜单）/ 导出 / ⊕ 新会话
 * - 消息列表：普通布局（最早在上、最新在下）——贴底跟随时内容增长由同帧快照滚动
 *   保持（零可见闪动）；翻看上文时底部增长由顶部锚定自然吸收（画面静止零漂移），
 *   离开底部暂停跟随并显示「回到底部」，仅「回到底部」走 animate 平滑过渡
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
    val exportDefaultName by viewModel.exportDefaultName.collectAsStateWithLifecycle()
    val exportTargetLabel by viewModel.exportTargetLabel.collectAsStateWithLifecycle()
    val exportFolderPicker by viewModel.exportFolderPicker.collectAsStateWithLifecycle()
    val privacyDialog by viewModel.privacyDialog.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.message.collectAsStateWithLifecycle()
    val exportDone by viewModel.exportDone.collectAsStateWithLifecycle()

    // 初始即贴底：index 落到末项 + 超大偏移（测量时 scroll-back 机制会钳到列表尽头，
    // 首帧直接渲染在底部——scrollToItem(末项) 语义是「顶部对齐」，末项高于一屏时
    // 会停在正文第一行在顶部而非贴底）
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (messages.isNotEmpty()) messages.lastIndex else 0,
        initialFirstVisibleItemScrollOffset = BOTTOM_OVERSHOOT
    )
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 输入卡焦点目标（错误条 / 外部入口聚焦复用）
    val inputFocusRequester = remember { FocusRequester() }
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

    // —— 自动跟随（普通布局 + 贴底快照跟随）——
    // 布局锚定在列表顶部（首可见项）：用户翻看上文时，最新消息（列表底部）流式增长
    // 不会推动画面——阅读画面天然静止，无需任何补偿滚动（反转布局下「正文被顶出屏幕
    // 顶端」的漂移根源整体移除）。贴底跟随时内容增长由「同帧快照滚动」保持：内容指纹
    // 变化 → scrollToItem(末项, 超大偏移)（forceRemeasure 同步重测量，新内容与贴底
    // 位置同帧渲染，零可见闪动）。
    // 跟随意图：手势（拖拽/惯性）期间实时判定「贴底才跟随」（末项完全可见 = 滚到尽头）；
    // 上滑离开尽头立即暂停（画面停在哪就在哪，绝不拉回），滑回尽头自动恢复。
    var following by remember { mutableStateOf(true) }
    // 程序动画守卫：「回到底部」平滑动画期间抑制位置观察，防动画中间态互夺跟随状态
    var programmaticScroll by remember { mutableStateOf(false) }

    // 滚动行为埋点（AiDiag → 调试日志 type=AI / DEBUG 级）：仅在设置开启收集且最低等级
    // 调至 DEBUG 时落盘；高频事件节流防刷屏（普通数组作节流槽，避免写 State 触发重组）。
    // 用于复现「跳动 / 漂移」时取证帧级行为
    val scrollLogSlot = remember { longArrayOf(0L) }
    fun scrollLog(msg: String, extra: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        if (now - scrollLogSlot[0] < 500) return
        scrollLogSlot[0] = now
        AiDiag.emitDebug(msg, extra)
    }
    // 强制恢复跟随（发送 / 重发 / 重新生成 / 回到底部按钮）：原因入埋点便于追溯
    fun resumeFollow(cause: String) {
        following = true
        AiDiag.emitDebug("follow_resume", mapOf("cause" to cause))
    }

    // 「回到底部」平滑滚动（唯一动画出口）：目标 = 末项 + 超大偏移（等价滚到列表尽头，
    // 末项高于一屏时也不能停在「顶部对齐」）；动画被用户手势抢占时作废，结束后重新
    // 评估贴底判定，把跟随意图交还位置观察（用户取消动画翻看时不被拉回）
    val scrollToBottom: suspend () -> Unit = {
        if (!programmaticScroll) {
            programmaticScroll = true
            AiDiag.emitDebug("scroll_animate_start", emptyMap())
            try {
                if (messagesState.value.isNotEmpty()) {
                    listState.animateScrollToItem(messagesState.value.lastIndex, BOTTOM_OVERSHOOT)
                }
            } catch (_: CancellationException) {
                // 用户手势抢占动画：本次作废，贴底判定在 finally 重新评估
            } finally {
                programmaticScroll = false
                // 动画结束/取消后重新评估（动画期间位置观察被抑制）：正常完成必然贴底
                // 继续跟随；被取消则按用户当前实际位置决定跟随意图；仍跟随且未到尽头
                // 时补一步快照兑底（forceRemeasure 同步到位，已在尽头则不动）
                val info = listState.layoutInfo
                val lastItem = info.visibleItemsInfo.lastOrNull()
                val atBottom = lastItem != null &&
                    lastItem.index == messagesState.value.lastIndex &&
                    lastItem.offset + lastItem.size <= info.viewportEndOffset
                following = atBottom
                if (following && messagesState.value.isNotEmpty()) {
                    listState.scrollToItem(messagesState.value.lastIndex, BOTTOM_OVERSHOOT)
                }
                AiDiag.emitDebug("scroll_animate_done", mapOf("atBottom" to atBottom))
            }
        }
    }

    // 近底收口窗口（仅 settle 负侧生效）：末项底边差视口底不超过该距离时精确收口到底——
    // 覆盖「输出中划回贴底惯性停在差几行处」（内容增长使边界下移、惯性停在旧边界）的
    // 场景；判定仍严格「完全可见」（正侧不吸附），窗口远小于翻看距离，不会重蹈弹回覆辙
    val bottomSnapThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }

    // 内容驱动贴底（同帧关键）：以消息指纹为 LaunchedEffect key——指纹变化 → 重组 →
    // 本 effect 在「组合完成后、布局之前」重启，此时新内容已完成组合，scrollToItem 的
    // forceRemeasure 测得新高度并滚到尽头，同一帧渲染贴底、零可见闪动。不可用
    // snapshotFlow collect 替代：collect 在状态写入后立即执行（组合之前），forceRemeasure
    // 只能测得旧组合的内容高度，滚到旧尽头后重组又把 item 变高——画面永久落后最新
    // 增长量（最后一行被截半、操作行不露出，正是本缺陷）。也不可用 scrollToItem(末项)
    // 默认偏移（顶部对齐语义，末项高于一屏时停在正文第一行在顶部）。手势进行中不抢
    // 滚动锁（贴底收口由位置观察的手势结束复核补齐）。翻看上文（非跟随态）时不滚动——
    // 底部增长由顶部锚定自然吸收，画面静止
    val contentFingerprint = listOf(
        messages.size,
        messages.lastOrNull()?.text?.length ?: 0,
        messages.lastOrNull()?.reasoning?.length ?: 0,
        messages.lastOrNull()?.toolTrail?.size ?: 0,
        messages.lastOrNull()?.status?.ordinal ?: -1
    )
    LaunchedEffect(contentFingerprint) {
        if (following && !programmaticScroll && !listState.isScrollInProgress &&
            messagesState.value.isNotEmpty()
        ) {
            listState.scrollToItem(messagesState.value.lastIndex, BOTTOM_OVERSHOOT)
            scrollLog(
                "scroll_snap",
                mapOf(
                    "index" to messagesState.value.lastIndex,
                    "size" to messagesState.value.size
                )
            )
        }
    }
    // 位置观察：用户手势（拖拽/惯性）期间实时判定「贴底才跟随」——贴底 = 末项完全可见
    // （视口坐标系下末项底边在视口底之内，等价「滚到列表尽头」；末项高于一屏时滚到
    // 尽头同样成立）。上滑离开尽头立即暂停（画面停在哪就在哪，绝不拉回），滑回尽头
    // 自动恢复；手势结束仅在真贴底（或停在贴底附近负侧小窗口——惯性停在差几行处）
    // 时恢复跟随并快照收口。程序动画自身引起的位置变化不属于用户手势，抑制
    LaunchedEffect(listState) {
        var wasInProgress = false
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            val lastIndex = messagesState.value.lastIndex
            // 贴底 = 末项完全可见（末项底边在视口底之内；末项高于一屏时滚到尽头
            // offset 为负、可见部分底边仍在视口内，判定同样成立）
            val atBottom = lastItem != null && lastItem.index == lastIndex &&
                lastItem.offset + lastItem.size <= info.viewportEndOffset
            // 末项底边到视口底边的剩余距离（负 = 内容被截断；非末项 = MAX 哨兵）
            val gap = if (lastItem != null && lastItem.index == lastIndex) {
                info.viewportEndOffset - (lastItem.offset + lastItem.size)
            } else Int.MAX_VALUE
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                atBottom to gap
            )
        }
            .distinctUntilChanged()
            .collect { (inProgress, pos, bottom) ->
                if (programmaticScroll) {
                    wasInProgress = false
                    return@collect
                }
                val (firstIndex, firstOffset) = pos
                val (atBottom, gap) = bottom
                if (inProgress) {
                    if (following != atBottom) {
                        following = atBottom
                        AiDiag.emitDebug(
                            "follow_gesture",
                            mapOf(
                                "following" to atBottom,
                                "firstIndex" to firstIndex,
                                "firstOffset" to firstOffset
                            )
                        )
                    }
                } else if (wasInProgress) {
                    // 手势结束：真贴底，或停在贴底附近负侧小窗口内（惯性停在差几行处
                    // ——输出中内容增长使滚动边界下移，惯性停在旧边界）→ 恢复跟随并
                    // 快照收口；否则暂停跟随——画面停在用户松手位置，绝不吸附拉回
                    if (atBottom || gap in -(bottomSnapThresholdPx.toInt())..0) {
                        following = true
                        if (messagesState.value.isNotEmpty()) {
                            listState.scrollToItem(messagesState.value.lastIndex, BOTTOM_OVERSHOOT)
                        }
                        AiDiag.emitDebug(
                            "follow_settle_snap",
                            mapOf("gap" to gap)
                        )
                    } else if (following) {
                        following = false
                        AiDiag.emitDebug(
                            "follow_settle_pause",
                            mapOf("gap" to gap)
                        )
                    }
                }
                wasInProgress = inProgress
            }
    }

    val copyToClipboard: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("az-notes-ai-chat", text))
        scope.launch { snackbarHostState.showTimedSnackbar(context.getString(R.string.ai_chat_copied)) }
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
            snackbarHostState.showTimedSnackbar(snackbarText)
            viewModel.consumeMessage()
        }
    }

    // 导出完成提示（含导出位置）；「查看」跳预览页（§8.2）
    LaunchedEffect(exportDone) {
        val done = exportDone ?: return@LaunchedEffect
        val location = done.locationLabel ?: context.getString(R.string.ai_chat_export_root)
        val result = snackbarHostState.showTimedSnackbar(
            message = context.getString(R.string.ai_chat_export_done, location),
            actionLabel = context.getString(R.string.ai_chat_export_view),
            withDismissAction = true
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
                        onOpenProviders = onOpenProviders
                    )
                } else {
                    // 普通布局：最早在上、最新在下。底部最新消息流式增长由顶部锚定自然
                    // 吸收（翻看上文画面静止），贴底跟随由内容驱动的快照滚动保持
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(items = messages, key = { it.id }) { message ->
                            when (message.role) {
                                ChatRole.USER -> UserBubble(
                                    message = message,
                                    busy = generating,
                                    onCopy = { copyToClipboard(message.text) },
                                    onEditResend = {
                                        // 主动重发路径同样强制跟随
                                        resumeFollow("edit_resend")
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
                                        resumeFollow("regenerate")
                                        viewModel.regenerate(message.id)
                                    },
                                    onRetry = {
                                        resumeFollow("retry")
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
                                resumeFollow("button")
                                scope.launch { scrollToBottom() }
                            },
                            // 圆形按钮：旋转环（正圆）贴合按钮圆边环绕，不再与圆角方形相切出错位观感
                            shape = CircleShape,
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
                    resumeFollow("send")
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
        initialFileName = exportDefaultName,
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
 * 贴底滚动的超大偏移量（1e8 像素，远超任何列表长度且无 Int 溢出风险）：
 * - 快照：`scrollToItem(末项, 该值)` 经 forceRemeasure 同步重测量 + 测量 scroll-back
 *   钳到尽头（新内容与贴底位置同帧渲染）；
 * - 初始/动画：`scrollToItem/rememberLazyListState(末项, 该值)` 首测量 scroll-back
 *   钳到尽头。
 * 目的：绕开 scrollToItem(末项) 「顶部对齐」语义——末项高于一屏时会停在正文第一行
 * 在顶部而非贴底；且不用 scroll { scrollBy }（基于旧布局的边界钳位会让最新一次增长
 * 永远滚不掉，画面落后几行字）。
 */
private const val BOTTOM_OVERSHOOT = 100_000_000
