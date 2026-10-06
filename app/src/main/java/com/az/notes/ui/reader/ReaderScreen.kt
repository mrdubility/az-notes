package com.az.notes.ui.reader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.az.notes.R
import com.az.notes.domain.markdown.FrontmatterAttribute
import com.az.notes.domain.markdown.Heading
import com.az.notes.domain.markdown.TaskItem
import com.az.notes.ui.common.resolve
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 预览页（§5.2 / §5.4 / §5.5）：Markdown 渲染 + 大纲（真实锚点 + 当前章节高亮）+ 进度恢复。
 *
 * 渲染走 mikepenz `Markdown` 的 success 插槽 + 自建 LazyColumn：块级虚拟化，
 * 块下标与 AST 顶层块一一对应（[com.az.notes.domain.markdown.HeadingExtractor] 同解析链），
 * 大纲跳转/高亮与滚动恢复均为真实索引而非像素估算。图片经 [VaultImageTransformer]
 * 从 Vault 本地加载，点击全屏查看（支持捏合缩放）。双击正文快捷进入编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showOutline by rememberSaveable { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<File?>(null) }
    var restoredOnce by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 笔记属性面板：frontmatter 中除 note_type 外的属性（默认展开，可折叠）
    var attrsExpanded by rememberSaveable { mutableStateOf(true) }
    val otherAttributes = remember(state.attributes) {
        state.attributes.filter { it.key != "note_type" }
    }
    // 正文块下标偏移：文件名 header（与属性面板）占据列表前几项
    val blockOffset = if (otherAttributes.isNotEmpty()) 2 else 1

    // 待办保存失败等一次性提示：展示后清除，避免重组重复弹出
    val toastMessage = state.message?.let { it.resolve() }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            snackbarHostState.showSnackbar(toastMessage)
            viewModel.consumeMessage()
        }
    }

    // 解析链显式传入稳定实例：若用默认参数，recomposition 时 flavour/parser/linkHandler
    // 会新建实例导致 remember 失效、反复重新解析
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val linkHandler = remember { ReferenceLinkHandlerImpl() }

    // 修复库默认渲染的两个问题：
    // 1) 软换行（行内 EOL）默认渲染为空格——截获后改为换行输出；
    // 2) 字面强调符（如 snake_case 的下划线）默认硬编码渲染为 '*'——截获后原样输出。
    val annotator = remember {
        markdownAnnotator { content, child ->
            when {
                child.type == MarkdownTokenTypes.EOL -> {
                    val siblings = child.parent?.children
                    if (siblings == null || siblings.last() === child) {
                        // 块内最后一个 EOL（含 setext 标题/表格行等）交给默认处理
                        false
                    } else {
                        val next = siblings[siblings.indexOf(child) + 1]
                        if (next.type == MarkdownTokenTypes.SETEXT_1 ||
                            next.type == MarkdownTokenTypes.SETEXT_2
                        ) {
                            // "Title\n====" 的下划线行：不参与换行
                            false
                        } else {
                            append('\n')
                            true
                        }
                    }
                }

                child.type == MarkdownTokenTypes.EMPH &&
                    child.parent?.type != MarkdownElementTypes.EMPH &&
                    child.parent?.type != MarkdownElementTypes.STRONG -> {
                    // 配对标记（parent 为 EMPH/STRONG）保持默认（吞掉标记）
                    append(content.substring(child.startOffset, child.endOffset))
                    true
                }

                else -> false
            }
        }
    }
    val markdownState = rememberMarkdownState(
        content = state.content,
        flavour = flavour,
        parser = parser,
        referenceLinkHandler = linkHandler
    )

    // 图片：Vault 内相对路径 → 本地文件（点击全屏查看）
    val vaultRoot = state.vaultPath
    val noteDir = remember(state.path) { File(state.path).parentFile }
    val imageTransformer = remember(vaultRoot, noteDir) {
        if (vaultRoot.isNullOrBlank() || noteDir == null) {
            NoOpImageTransformerImpl()
        } else {
            VaultImageTransformer(
                vaultRoot = vaultRoot,
                baseDir = noteDir,
                onImageClick = { file -> previewImage = file }
            )
        }
    }

    // 从编辑页返回（重新进入组合）时静默重读，避免预览停留在编辑前的旧内容
    LaunchedEffect(Unit) { viewModel.reload() }

    // 恢复上次滚动位置：仅首次加载恢复一次（从编辑页返回时 listState 自身已恢复位置）
    LaunchedEffect(state.loading, state.initialProgress, markdownState, state.noteType) {
        val prog = state.initialProgress
        if (restoredOnce || state.loading || prog == null || state.noteType == "task") {
            return@LaunchedEffect
        }
        markdownState.state.first { it is State.Success }
        // 块偏移：文件名（与属性面板）占位，正文块整体后移
        listState.scrollToItem(prog.scrollIndex + blockOffset, prog.scrollOffset)
        restoredOnce = true
    }

    // 监听滚动，转成块序号交给 ViewModel（内部 500ms 去抖落库）
    LaunchedEffect(listState, state.content, blockOffset) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            // 去掉文件名（与属性面板）占位，还原为真实块序号
            .collect { (index, offset) ->
                viewModel.onScrollPosition((index - blockOffset).coerceAtLeast(0), offset)
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_preview)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showOutline = true }) {
                        Icon(Icons.Outlined.List, stringResource(R.string.action_outline))
                    }
                    // 文档信息：文件属性与正文统计
                    IconButton(onClick = { viewModel.loadDocInfo() }) {
                        Icon(Icons.Outlined.Info, stringResource(R.string.reader_info))
                    }
                    IconButton(onClick = { onEdit(state.path) }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.action_edit))
                    }
                }
            )
        }
    ) { inner ->
        when {
            state.loading -> CenterBox { CircularProgressIndicator() }
            state.error != null -> CenterBox {
                Text(state.error!!.resolve(), color = MaterialTheme.colorScheme.error)
            }
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    // 双击正文进入编辑（顶栏编辑按钮之外的快捷入口）；不影响滚动
                    .pointerInput(state.path) {
                        detectTapGestures(onDoubleTap = { onEdit(state.path) })
                    }
            ) {
                if (state.noteType == "task") {
                    // frontmatter `note_type: task`：待办清单页
                    TaskListPane(
                        fileName = state.path.substringAfterLast('/'),
                        tasks = state.tasks,
                        attributes = state.attributes,
                        onToggle = viewModel::toggleTask,
                        onAdd = viewModel::addTask,
                        onReorder = viewModel::reorderTasks
                    )
                } else {
                    Markdown(
                        markdownState = markdownState,
                        modifier = Modifier.fillMaxSize(),
                        imageTransformer = imageTransformer,
                        annotator = annotator,
                        success = { success, components, _ ->
                            // 官方 success 插槽为 Column(不虚拟化)；此处换成 LazyColumn：
                            // 块下标与 AST 顶层块一一对应，大文档只渲染可见块；
                            // 文件名作首项（下标 0），正文块整体后移 1 位
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                item(key = "file_name_header") {
                                    FileNameHeader(state.path.substringAfterLast('/'))
                                }
                                if (otherAttributes.isNotEmpty()) {
                                    item(key = "note_attrs") {
                                        NoteAttributesPanel(
                                            attributes = otherAttributes,
                                            expanded = attrsExpanded,
                                            onToggleExpanded = { attrsExpanded = !attrsExpanded },
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )
                                    }
                                }
                                items(
                                    items = success.node.children,
                                    key = { node -> node.startOffset }
                                ) { node ->
                                    MarkdownElement(
                                        node = node,
                                        components = components,
                                        content = success.content,
                                        skipLinkDefinition = success.linksLookedUp
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // 全屏图片预览
    previewImage?.let { file ->
        ImagePreviewDialog(file = file, onDismiss = { previewImage = null })
    }

    // 大纲浮层：从右侧滑出（全屏 Dialog 承载：右侧面板 + 半透明遮罩）
    if (showOutline) {
        OutlineDialog(
            headings = state.headings,
            listState = listState,
            blockOffset = blockOffset,
            onSelect = { h ->
                showOutline = false
                // 块偏移：文件名（与属性面板）占位
                scope.launch { listState.animateScrollToItem(h.blockIndex + blockOffset) }
            },
            onDismiss = { showOutline = false }
        )
    }

    // 文档信息（点击顶栏 Info 后从 ViewModel 读取）
    state.docInfo?.let { info ->
        DocInfoDialog(info = info, onDismiss = viewModel::clearDocInfo)
    }
}

/**
 * 文件名标题（预览页 / 待办页共用）：与正文之间用分隔线区分，避免整片连在一起。
 */
@Composable
private fun FileNameHeader(fileName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 笔记属性面板：frontmatter 中除 `note_type` 外的属性，圆角卡片显示、可折叠；
 * 多值属性（YAML 列表写法）以流式标签展示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteAttributesPanel(
    attributes: List<FrontmatterAttribute>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_attrs_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.reader_attrs_collapse else R.string.reader_attrs_expand
                    ),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    attributes.forEach { attr ->
                        val values = attr.values.filter { it.isNotBlank() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = attr.key,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(80.dp)
                                    .padding(top = 2.dp)
                            )
                            if (values.isEmpty()) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    values.forEach { value ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = value,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 待办清单页（frontmatter `note_type: task`）：仅渲染待办条目，
 * 点击勾选完成（写回 `- [x]`），底部输入栏追加新条目（写回 `- [ ]`）。
 * 支持「仅未完成」过滤；长按条目拖动排序（过滤态下只在可见子集内重排）；
 * 添加待办后列表自动滚动到新条目。
 */
@Composable
private fun TaskListPane(
    fileName: String,
    tasks: List<TaskItem>,
    attributes: List<FrontmatterAttribute>,
    onToggle: (Int, Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onReorder: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable { mutableStateOf("") }
    var incompleteOnly by rememberSaveable { mutableStateOf(false) }
    var attrsExpanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // 非定制属性（note_type 之外的普通属性）
    val attrs = remember(attributes) { attributes.filter { it.key != "note_type" } }

    // 显示条目：勾选「仅未完成」时过滤已完成项；拖动排序仅作用于可见子集
    val visibleTasks = remember(tasks, incompleteOnly) {
        if (incompleteOnly) tasks.filter { !it.checked } else tasks
    }

    // 拖动排序：长按拖起后本地顺序实时交换，松手写回；数据刷新后收敛回 tasks
    var dragOrder by remember { mutableStateOf<List<TaskItem>?>(null) }
    var draggedLine by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<Int, Float>() }
    val displayTasks = dragOrder ?: visibleTasks
    val latestTasks by rememberUpdatedState(visibleTasks)

    // 列表前导项数：文件名 + （属性面板）+ 过滤行
    val leadingCount = 2 + if (attrs.isNotEmpty()) 1 else 0

    // 添加后跟随滚动：等待列表数据刷新后再滚到末尾
    var pendingScrollToEnd by remember { mutableStateOf(false) }

    // 写回完成后本地顺序收敛（内容对齐即视为已落入磁盘）
    LaunchedEffect(visibleTasks, dragOrder) {
        val order = dragOrder ?: return@LaunchedEffect
        if (draggedLine != null) return@LaunchedEffect
        val converged = order.size == visibleTasks.size &&
            order.indices.all { i ->
                visibleTasks[i].text == order[i].text && visibleTasks[i].checked == order[i].checked
            }
        if (converged) dragOrder = null
    }

    LaunchedEffect(tasks.size) {
        if (!pendingScrollToEnd) return@LaunchedEffect
        pendingScrollToEnd = false
        if (visibleTasks.isNotEmpty()) {
            listState.animateScrollToItem(leadingCount + visibleTasks.size - 1, scrollOffset = 10_000)
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        onAdd(text)
        input = ""
        pendingScrollToEnd = true
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            item(key = "file_name_header") {
                FileNameHeader(fileName)
            }
            if (attrs.isNotEmpty()) {
                item(key = "note_attrs") {
                    NoteAttributesPanel(
                        attributes = attrs,
                        expanded = attrsExpanded,
                        onToggleExpanded = { attrsExpanded = !attrsExpanded },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
            item(key = "task_filter") {
                FilterChip(
                    selected = incompleteOnly,
                    onClick = { incompleteOnly = !incompleteOnly },
                    label = { Text(stringResource(R.string.task_only_incomplete)) },
                    leadingIcon = if (incompleteOnly) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (displayTasks.isEmpty()) {
                item(key = "task_empty") {
                    Text(
                        text = stringResource(
                            if (tasks.isEmpty()) R.string.task_empty else R.string.task_incomplete_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                itemsIndexed(items = displayTasks, key = { _, task -> task.lineIndex }) { index, task ->
                    val isDragging = draggedLine != null && task.lineIndex == draggedLine
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .then(
                                when {
                                    isDragging -> Modifier
                                        .shadow(6.dp, RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    // 拖动期间项位交换带动画；写回重载时 key 置换，关掉动画避免无意义滑动
                                    dragOrder != null -> Modifier.animateItem()
                                    else -> Modifier
                                }
                            )
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                            .onGloballyPositioned { itemHeights[index] = it.size.height.toFloat() }
                            .pointerInput(task.lineIndex) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggedLine = task.lineIndex
                                        dragOrder = latestTasks
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggedLine = null
                                        dragOffsetY = 0f
                                        dragOrder = null
                                    },
                                    onDragEnd = {
                                        val order = dragOrder
                                        draggedLine = null
                                        dragOffsetY = 0f
                                        if (order != null) {
                                            val newOrder = order.map { it.lineIndex }
                                            if (newOrder != latestTasks.map { it.lineIndex }) {
                                                onReorder(newOrder)
                                                // 兜底：写回失败时避免本地顺序悬挂（正常由数据收敛清空）
                                                scope.launch {
                                                    delay(800)
                                                    if (dragOrder === order) dragOrder = null
                                                }
                                            } else {
                                                dragOrder = null
                                            }
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        dragOffsetY += drag.y
                                        val line = draggedLine ?: return@detectDragGesturesAfterLongPress
                                        var order = dragOrder ?: return@detectDragGesturesAfterLongPress
                                        // 越过相邻项半高即交换；一次事件可连续交换多格
                                        while (true) {
                                            val from = order.indexOfFirst { it.lineIndex == line }
                                            if (from < 0) break
                                            val hDrag = itemHeights[from] ?: break
                                            if (dragOffsetY > 0f && from + 1 < order.size) {
                                                val hNext = itemHeights[from + 1] ?: break
                                                if (dragOffsetY < (hDrag + hNext) / 2f) break
                                                order = order.toMutableList().apply { add(from + 1, removeAt(from)) }
                                                dragOffsetY -= hNext
                                            } else if (dragOffsetY < 0f && from > 0) {
                                                val hPrev = itemHeights[from - 1] ?: break
                                                if (-dragOffsetY < (hPrev + hDrag) / 2f) break
                                                order = order.toMutableList().apply { add(from - 1, removeAt(from)) }
                                                dragOffsetY += hPrev
                                            } else {
                                                break
                                            }
                                            dragOrder = order
                                        }
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = task.checked,
                            onCheckedChange = { checked -> onToggle(task.lineIndex, checked) }
                        )
                        Text(
                            text = task.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (task.checked) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (task.checked) TextDecoration.LineThrough else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggle(task.lineIndex, !task.checked) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.task_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { submit() }, enabled = input.isNotBlank()) {
                Icon(Icons.Filled.Add, stringResource(R.string.task_add_action))
            }
        }
    }
}

/**
 * 大纲浮层（全屏 Dialog）：正文右侧滑出面板 + 半透明遮罩；
 * 点击遮罩 / 系统返回收起；点击标题回传由调用方滚动到对应块。
 */
@Composable
private fun OutlineDialog(
    headings: List<Heading>,
    listState: LazyListState,
    blockOffset: Int,
    onSelect: (Heading) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 当前章节：第一个可见块所属的最近标题
        val activeIndex by remember(headings, blockOffset) {
            derivedStateOf {
                // 去掉文件名（与属性面板）占位，还原为真实块序号
                headings.indexOfLast { it.blockIndex <= listState.firstVisibleItemIndex - blockOffset }
            }
        }
        // 打开后触发一次性进入动画（面板自右滑入、遮罩淡入）
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        )
                )
            }
            AnimatedVisibility(
                visible = entered,
                enter = slideInHorizontally(initialOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.78f)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.fillMaxHeight()) {
                        Text(
                            stringResource(R.string.action_outline),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                        if (headings.isEmpty()) {
                            Text(
                                stringResource(R.string.reader_outline_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(headings) { index, h ->
                                    OutlineItem(
                                        h = h,
                                        active = index == activeIndex,
                                        onClick = { onSelect(h) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 大纲条目：按级别缩进；[active] 高亮当前所在章节（真实块锚点匹配）。 */
@Composable
private fun OutlineItem(h: Heading, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .padding(start = (h.level * 16).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "H${h.level}",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = h.text.ifBlank { stringResource(R.string.reader_outline_untitled) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 全屏图片预览：黑底 + 点按关闭 + 捏合缩放（1–5 倍，放大后可拖动）。 */
@Composable
private fun ImagePreviewDialog(file: File, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .transformable(transformableState)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(Uri.fromFile(file))
                    .crossfade(true)
                    .build(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }
    }
}

/** 文档信息对话框：文件名 / 位置 / 大小 / 修改时间 / 字数统计。 */
@Composable
private fun DocInfoDialog(info: DocInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocInfoRow(stringResource(R.string.reader_info_name), info.name)
                DocInfoRow(stringResource(R.string.reader_info_path), info.path)
                DocInfoRow(stringResource(R.string.reader_info_size), formatSize(info.sizeBytes))
                DocInfoRow(
                    stringResource(R.string.reader_info_modified),
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(info.lastModified))
                )
                DocInfoRow(stringResource(R.string.reader_info_chars), info.charCount.toString())
                DocInfoRow(stringResource(R.string.reader_info_lines), info.lineCount.toString())
                DocInfoRow(stringResource(R.string.reader_info_headings), info.headingCount.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/** 信息行：左侧固定宽度标签 + 自适应值。 */
@Composable
private fun DocInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 文件大小：B / KB / MB（各档保留一位小数）。 */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
