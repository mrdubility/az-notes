package com.az.notes.ui.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.az.notes.R
import com.az.notes.domain.markdown.Heading
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 解析链显式传入稳定实例：若用默认参数，recomposition 时 flavour/parser/linkHandler
    // 会新建实例导致 remember 失效、反复重新解析
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val linkHandler = remember { ReferenceLinkHandlerImpl() }
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
    LaunchedEffect(state.loading, state.initialProgress, markdownState) {
        val prog = state.initialProgress
        if (restoredOnce || state.loading || prog == null) return@LaunchedEffect
        markdownState.state.first { it is State.Success }
        listState.scrollToItem(prog.scrollIndex, prog.scrollOffset)
        restoredOnce = true
    }

    // 监听滚动，转成块序号交给 ViewModel（内部 500ms 去抖落库）
    LaunchedEffect(listState, state.content) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> viewModel.onScrollPosition(index, offset) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showOutline = true }) {
                        Icon(Icons.Outlined.List, stringResource(R.string.action_outline))
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
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
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
                Markdown(
                    markdownState = markdownState,
                    modifier = Modifier.fillMaxSize(),
                    imageTransformer = imageTransformer,
                    success = { success, components, _ ->
                        // 官方 success 插槽为 Column(不虚拟化)；此处换成 LazyColumn：
                        // 块下标与 AST 顶层块一一对应，大文档只渲染可见块
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
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

    // 全屏图片预览
    previewImage?.let { file ->
        ImagePreviewDialog(file = file, onDismiss = { previewImage = null })
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }, sheetState = sheetState) {
            val headings = state.headings
            // 当前章节：第一个可见块所属的最近标题
            val activeIndex by remember(headings) {
                derivedStateOf {
                    headings.indexOfLast { it.blockIndex <= listState.firstVisibleItemIndex }
                }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.action_outline),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                if (headings.isEmpty()) {
                    Text(
                        "本文无标题层级",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                } else {
                    headings.forEachIndexed { index, h ->
                        OutlineItem(
                            h = h,
                            active = index == activeIndex,
                            onClick = {
                                showOutline = false
                                scope.launch {
                                    listState.animateScrollToItem(h.blockIndex)
                                }
                            }
                        )
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
            text = h.text,
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

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
