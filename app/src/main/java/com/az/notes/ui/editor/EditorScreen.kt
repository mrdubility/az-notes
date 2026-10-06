package com.az.notes.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.ui.common.resolve
import com.az.notes.ui.components.MoveTargetDialog
import com.az.notes.ui.components.RenameDialog
import com.az.notes.ui.theme.LocalReadingStyle
import kotlinx.coroutines.launch

/**
 * 编辑页（§5.3 重设计）：工具条移到底部（键盘上方，横向可滚动）；
 * 顶栏 = 返回 + 标题 + 查找 + 预览（眼睛）；停止输入 1.5s 自动保存。
 *
 * 文本编辑采用 state 版 [BasicTextField]：内建撤销 / 重做与光标自滚动（光标跟随
 * 屏幕）；工具栏直接编辑 [TextFieldState]，插入标记后光标 / 选区自动跟随；
 * 回车自动续行列表 / 任务 / 引用（见 [MarkdownListContinuation]）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onPreview: (String) -> Unit,
    /** 分享独立页模式：返回即退出应用（回到分享来源），顶栏另提供「回列表」 */
    fromShare: Boolean = false,
    onExitApp: () -> Unit = {},
    onBackToHome: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readingStyle = LocalReadingStyle.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var renameDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }

    // —— 编辑器文本状态：内建 undo/redo 栈与自滚动 ——
    val textState = rememberTextFieldState()
    val scrollState = rememberScrollState()
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 笔记文本是否已灌入编辑器；配置重建时经 rememberSaveable 恢复，避免重复灌入
    var initialized by rememberSaveable { mutableStateOf(false) }

    // 加载完成后一次性灌入文本（进程重建时若已恢复编辑器内容则跳过；
    // 此时恢复内容优先于刚读出的磁盘内容，回写一次避免被加载结果覆盖）
    LaunchedEffect(state.loading, state.error) {
        if (state.loading || state.error != null) return@LaunchedEffect
        if (!initialized) {
            textState.setTextAndPlaceCursorAtEnd(state.text)
            initialized = true
        } else if (textState.text.toString() != state.text) {
            viewModel.onTextChange(textState.text.toString())
        }
    }

    val editorVisible = initialized || state.error != null
    // 编辑内容实时回写 ViewModel（驱动自动保存；内容相同时不置脏）
    LaunchedEffect(editorVisible) {
        if (!editorVisible) return@LaunchedEffect
        snapshotFlow { textState.text.toString() }.collect { viewModel.onTextChange(it) }
    }

    // 系统返回 / 返回按钮共用：先同步编辑器最新文本给 ViewModel，
    // 再走原有收尾（空笔记清理 / 未落盘内容写入），完成后才返回，避免丢字；
    // 分享独立页模式下返回即退出应用，回列表走 [exitToList]
    val exit: () -> Unit = {
        scope.launch {
            viewModel.onTextChange(textState.text.toString())
            viewModel.flushOnExit()
            if (fromShare) onExitApp() else onBack()
        }
    }
    val exitToList: () -> Unit = {
        scope.launch {
            viewModel.onTextChange(textState.text.toString())
            viewModel.flushOnExit()
            onBackToHome()
        }
    }
    BackHandler { exit() }

    // 一次性提示（重命名结果等）
    val stateMessage = state.message?.resolve()
    LaunchedEffect(state.message) {
        stateMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // —— 保存状态反馈：顶栏标题旁文字常驻显示；分享页首次保存弹「回列表」横幅 ——
    var shareBannerShown by rememberSaveable { mutableStateOf(false) }
    val savedLabel = stringResource(R.string.editor_saved)
    val backToHomeLabel = stringResource(R.string.editor_back_to_home)
    LaunchedEffect(state.savedAt) {
        if (state.savedAt == null) return@LaunchedEffect
        if (fromShare && !shareBannerShown) {
            shareBannerShown = true
            val result = snackbarHostState.showSnackbar(
                message = savedLabel,
                actionLabel = backToHomeLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) exitToList()
        }
    }

    // 匹配区间（查找栏计数与编辑器内高亮共用；纯文本查询不会跨换行）
    val matchRanges = remember(state.text, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
                .findAll(state.text)
                .map { it.range }
                .toList()
        }
    }
    val highlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // 文件名标题已移入正文区（见 EditorTitleHeader），顶栏 = 页面名 + 保存状态
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.action_edit))
                        // 保存状态紧贴“编辑”右侧常驻显示（保存中 / 未保存 / 已保存）
                        if (!searchActive) {
                            Spacer(Modifier.width(10.dp))
                            SaveStatusText(saving = state.saving, dirty = state.dirty)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = exit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // 移动到其他文件夹
                    IconButton(onClick = {
                        moveDialog = true
                        viewModel.loadMoveTargets()
                    }) {
                        Icon(Icons.Outlined.FolderOpen, stringResource(R.string.editor_move))
                    }
                    IconButton(onClick = {
                        if (searchActive) {
                            searchActive = false
                            query = ""
                        } else {
                            searchActive = true
                        }
                    }) {
                        Icon(
                            if (searchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(R.string.editor_search_hint)
                        )
                    }
                    IconButton(onClick = {
                        viewModel.save()
                        onPreview(state.path)
                    }) {
                        Icon(Icons.Outlined.Visibility, stringResource(R.string.action_preview))
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // 标题（文件名）：随内容排版，与正文用分隔线区分；点击可重命名
                EditorTitleHeader(path = state.path, onRenameClick = { renameDialog = true })

                if (searchActive) {
                    FindBar(
                        query = query,
                        matchCount = matchRanges.size,
                        onQueryChange = { query = it }
                    )
                }

                Box(Modifier.weight(1f)) {
                    if (!editorVisible) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        BasicTextField(
                            state = textState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .drawBehind {
                                    // 查找高亮：绘制在文本下层（drawBehind 先于内容绘制）
                                    if (searchActive) {
                                        drawSearchHighlights(layoutResult, scrollState, matchRanges, highlightColor)
                                    }
                                },
                            textStyle = readingStyle.textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            inputTransformation = MarkdownListContinuation,
                            scrollState = scrollState,
                            onTextLayout = { getResult -> layoutResult = getResult() }
                        )
                    }
                }

                HorizontalDivider()
                // 底部工具条：位于键盘上方（由 imePadding 抬升）；
                // 显示哪些工具、顺序如何均由设置决定（可开关 / 排序）
                EditorToolbar(textState, state.toolbarTools)
            }
        }
    }

    if (renameDialog) {
        RenameDialog(
            initialName = editableName(state.path),
            onDismiss = { renameDialog = false },
            onConfirm = { newName ->
                viewModel.rename(newName)
                renameDialog = false
            }
        )
    }

    if (moveDialog) {
        MoveTargetDialog(
            vaultPath = state.vaultPath,
            targets = state.moveTargets,
            onDismiss = { moveDialog = false },
            onSelect = { target ->
                moveDialog = false
                viewModel.moveTo(target)
            }
        )
    }
}
