package com.az.notes.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.az.notes.R
import com.az.notes.ui.common.resolve
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
import kotlinx.coroutines.launch

/**
 * 预览页（§5.2 / §5.4 / §5.5）：Markdown 渲染 + 大纲（真实锚点 + 当前章节高亮）。
 *
 * 渲染走 mikepenz `Markdown` 的 success 插槽 + 自建 LazyColumn：块级虚拟化，
 * 块下标与 AST 顶层块一一对应（[com.az.notes.domain.markdown.HeadingExtractor] 同解析链），
 * 大纲跳转/高亮均为真实索引而非像素估算。独立图片段落（[com.az.notes.domain.markdown.MarkdownImageLifter]
 * 产物）由 [AzImageBlock] 自渲染（首帧即按预读尺寸定盒，避免滚动中占位重排），
 * 其余图片经 [VaultImageTransformer] 从 Vault 本地加载（网络链接走受控护栏），
 * 点击全屏查看（支持捏合缩放）。双击正文快捷进入编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    /** 「加入 AI 对话」：附件已入待发列表后导航到聊天页 */
    onAddToAiChat: () -> Unit,
    /** 刚从“新建待办”进入：退出（顶栏返回 / 系统返回键）时若无条目则清理占位文件 */
    fresh: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showOutline by rememberSaveable { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<File?>(null) }
    // 远程图全屏预览：存规范化 URL（点击回调传入），dialog 内构建同参请求命中内存缓存
    var previewRemote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 新建待办（fresh）退出清理：无条目（正文为空）时删除占位文件（与空笔记 fresh 清理一致）；
    // 顶栏返回按钮与系统返回键走同一路径
    val exit = { viewModel.exitWithTaskCleanup(fresh, onBack) }
    BackHandler(enabled = fresh) { exit() }

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
    // 关闭库默认的段落尺寸动画：animateTextSize 默认 { animateContentSize() }——图片从占位
    // 尺寸切换到真实尺寸时，含图段落做弹簧式过渡（深色主题下呈“黑幕缓缓拉开”观感，每帧
    // 重排还有布局开销）。传恒等修饰符（官方文档给出的关闭方式），图片就位立即定格。
    val staticAnimations = remember { DefaultMarkdownAnimation(animateTextSize = { this }) }
    val markdownState = rememberMarkdownState(
        content = state.content,
        flavour = flavour,
        parser = parser,
        referenceLinkHandler = linkHandler
    )

    // 图片：经 Markdown 的 imageTransformer 注入（段落内图片由渲染库以 inline content 机制
    // 回调该 transformer；components.image 对该路径不生效，勿改用 markdownComponents）
    val vaultRoot = state.vaultPath
    val noteDir = remember(state.path) { File(state.path).parentFile }
    val remoteMaxBytes = state.remoteImageMaxBytes
    // LazyColumn 单侧水平内边距：与下方 contentPadding 共用；同时传给 transformer 以修正
    // 库从 parentLayoutCoordinates 拿到的 containerSize 包含 padding 导致的竖图右侧溢出。
    val contentHPad = 16.dp
    val imageTransformer = remember(vaultRoot, noteDir, remoteMaxBytes, contentHPad) {
        if (vaultRoot.isNullOrBlank() || noteDir == null) {
            NoOpImageTransformerImpl()
        } else {
            VaultImageTransformer(
                vaultRoot = vaultRoot,
                baseDir = noteDir,
                maxBytes = remoteMaxBytes,
                onImageClick = { file -> previewImage = file },
                onRemoteImageClick = { url -> previewRemote = url },
                log = viewModel::imageLog,
                containerHorizontalPaddingDp = contentHPad
            )
        }
    }

    // 从编辑页返回（重新进入组合）时静默重读，避免预览停留在编辑前的旧内容
    LaunchedEffect(Unit) { viewModel.reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_preview)) },
                navigationIcon = {
                    // 返回前先做 fresh 清理（无条目时删除占位文件）
                    IconButton(onClick = exit) {
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
                    // 加入 AI 对话：当前文档作为附件入待发列表并跳转聊天页
                    IconButton(onClick = {
                        viewModel.addToAiChat()
                        onAddToAiChat()
                    }) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            stringResource(R.string.reader_add_to_ai_chat)
                        )
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
                        animations = staticAnimations,
                        success = { success, components, _ ->
                            // 官方 success 插槽为 Column(不虚拟化)；此处换成 LazyColumn：
                            // 块下标与 AST 顶层块一一对应，大文档只渲染可见块；
                            // 文件名作首项（下标 0），正文块整体后移 1 位
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = contentHPad, vertical = 12.dp)
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
                                    // 独立图片段落（lift 产物 `![](link)` 独占一段）自渲染：
                                    // 首帧即按预读尺寸定盒，消除库占位两帧链（兜底 180sp →
                                    // 真实尺寸）在向上回滚时引起的滚动锚点位移；其余块（含
                                    // 图文混排 / 列表 / 引用内图片、远程图）仍走渲染库路径
                                    val imageLink = remember(node, success.content) {
                                        standaloneImageLink(success.content, node)
                                    }
                                    if (imageLink != null &&
                                        !vaultRoot.isNullOrBlank() &&
                                        noteDir != null
                                    ) {
                                        AzImageBlock(
                                            link = imageLink,
                                            vaultRoot = vaultRoot,
                                            baseDir = noteDir,
                                            onImageClick = { file -> previewImage = file }
                                        )
                                    } else {
                                        MarkdownElement(
                                            node = node,
                                            components = components,
                                            content = success.content,
                                            skipLinkDefinition = success.linksLookedUp
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // 全屏图片预览：本地直接用文件；网络图构建与行内相同 URL + 相同解码尺寸的请求——
    // Coil 内存缓存按请求参数做 key，借此命中行内已解码位图，预览零下载零解码
    val platformContext = LocalPlatformContext.current
    previewImage?.let { file ->
        ImagePreviewDialog(file = file, onDismiss = { previewImage = null })
    }
    previewRemote?.let { url ->
        val request = remember(url) {
            val edge = VaultImageTransformer.REMOTE_DECODE_EDGE
            ImageRequest.Builder(platformContext)
                .data(url)
                .size(CoilSize(edge, edge))
                .build()
        }
        ImagePreviewDialog(
            request = request,
            contentDescription = url.substringAfterLast('/'),
            onDismiss = { previewRemote = null }
        )
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

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
