package com.az.notes.ui.home

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FileNode
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.ui.common.resolve
import com.az.notes.ui.components.RenameDialog
import com.az.notes.ui.components.SyncConfirmDialog
import com.az.notes.ui.components.SyncProgressDialog
import com.az.notes.ui.components.SyncResultDialog
import com.az.notes.ui.notes.NoteListItem
import com.az.notes.ui.notes.NotesUiState
import com.az.notes.ui.notes.NotesViewModel
import com.az.notes.ui.sync.SyncPhase
import com.az.notes.ui.sync.SyncViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面（§5.1 重设计）：抽屉菜单 + 笔记卡片列表。
 * 左上角抽屉 → 设置入口；右上角 → 立即同步 / 搜索 / 排序；右下角 FAB → 新建笔记；
 * 下拉刷新；条目右侧 ⋮ → 重命名 / 删除（文件夹同样支持）；文件夹可点击进入；隐藏 '.' 开头项。
 *
 * 立即同步不离开本页：扫描进度、变更清单确认与结果均以弹窗展示
 * （与同步页共用 [SyncConfirmDialog] 等组件）；同步配置仍从设置 → 同步进入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenFile: (String) -> Unit,
    onOpenEditor: (path: String, fresh: Boolean, fromShare: Boolean) -> Unit,
    onOpenTrash: () -> Unit,
    onSettings: () -> Unit,
    /** 系统分享 / 内容传送门传入的待写入文本（null = 无） */
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by syncViewModel.state.collectAsStateWithLifecycle()
    val conflicts by syncViewModel.conflicts.collectAsStateWithLifecycle()
    val syncRunning by viewModel.syncRunning.collectAsStateWithLifecycle()
    val syncCompletedAt by viewModel.syncCompletedAt.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NoteListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteListItem?>(null) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableStateOf(0L) }
    var dragActive by remember { mutableStateOf(false) }

    // 首次进入 / 从阅读、编辑页返回时静默重读当前目录，保证修改时间与预览最新
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // 一次性提示（重命名/删除/新建结果）
    val stateMessage = state.message?.resolve()
    LaunchedEffect(state.message) {
        stateMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 立即同步的一次性提示（尚未配置 / 正在同步中 / 未选 Vault 等）
    LaunchedEffect(syncState.testMessage) {
        val message = syncState.testMessage?.resolve(context) ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        syncViewModel.clearTestMessage()
    }

    // 同步（手动或自动）完成后静默重读当前目录（可能从云端拉下了新文件）
    LaunchedEffect(syncCompletedAt) {
        if (syncCompletedAt > 0L) viewModel.onScreenEntered()
    }

    // 系统分享 / 文本处理（ACTION_SEND / ACTION_PROCESS_TEXT）传入的内容：新建笔记并进入编辑页
    // （fromShare = true：编辑页返回即退出应用，顶栏提供“回列表”入口）
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        onSharedTextConsumed()
        viewModel.createNoteFromShare(text) { path -> onOpenEditor(path, false, true) }
    }

    // 接收跨应用拖入的纯文本（ColorOS 内容传送门等）；拖拽悬停期间显示“松开新建”提示
    val onDropText by rememberUpdatedState<(String) -> Unit> { text ->
        viewModel.createNoteFromShare(text) { path -> onOpenEditor(path, false, false) }
    }
    val dragTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                dragActive = true
            }

            override fun onEntered(event: DragAndDropEvent) {
                dragActive = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dragActive = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragActive = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragActive = false
                val clip = event.toAndroidDragEvent().clipData ?: return false
                val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return false
                if (text.isBlank()) return false
                onDropText(text)
                return true
            }
        }
    }

    // 系统返回：优先退出搜索 / 多选，其次返回上一级目录，根目录下双击返回才退出应用
    BackHandler(enabled = searchActive) {
        searchActive = false
        viewModel.clearSearch()
    }
    BackHandler(enabled = !searchActive && !state.selectMode && !state.atRoot) { viewModel.navigateUp() }
    BackHandler(enabled = !searchActive && !state.selectMode && state.atRoot) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt <= DOUBLE_BACK_EXIT_MS) {
            (context as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(
                context,
                context.getString(R.string.home_double_back_exit),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    // 多选模式最后注册：系统返回优先退出选择（与搜索互斥）
    BackHandler(enabled = state.selectMode) { viewModel.exitSelectMode() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // 抽屉按“大半屏”宽度拉出（约 72%），不覆盖全屏
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.72f)) {
                DrawerContent(
                    vaultPath = state.vaultPath,
                    trashEnabled = state.trashEnabled,
                    onTrash = {
                        scope.launch { drawerState.close() }
                        onOpenTrash()
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onSettings()
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                HomeTopBar(
                    title = state.currentDirName
                        ?: state.vaultPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.app_name),
                    atRoot = state.atRoot,
                    searchActive = searchActive,
                    searchQuery = state.searchQuery,
                    sortOrder = state.sortOrder,
                    selectMode = state.selectMode,
                    selectedCount = state.selectedPaths.size,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNavigateUp = { viewModel.navigateUp() },
                    onSearchOpen = { searchActive = true },
                    onSearchClose = {
                        searchActive = false
                        viewModel.clearSearch()
                    },
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onSortSelected = viewModel::setSortOrder,
                    onSync = { syncViewModel.startSync() },
                    conflictCount = conflicts.size,
                    syncActive = syncRunning,
                    onExitSelect = { viewModel.exitSelectMode() },
                    onSelectAll = { viewModel.toggleSelectAll() },
                    onMoveSelected = {
                        viewModel.loadMoveTargets()
                        moveDialog = true
                    }
                )
            },
            floatingActionButton = {
                Box {
                    // 点击执行设置中的默认行为（新建笔记 / 新建文件夹 / 弹出菜单），长按始终弹出菜单；
                    // 由 combinedClickable 全权处理两者，无内部 onClick 以防重复触发
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .combinedClickable(
                                onClick = {
                                    when (state.fabAction) {
                                        FabAction.NEW_NOTE -> viewModel.createNote { path ->
                                            onOpenEditor(path, true, false)
                                        }
                                        FabAction.NEW_FOLDER -> newFolderDialog = true
                                        FabAction.SHOW_MENU -> fabMenuOpen = true
                                    }
                                },
                                onLongClick = { fabMenuOpen = true }
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 6.dp
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, stringResource(R.string.home_new_note))
                        }
                    }
                    // FAB 菜单：新建笔记 / 新建文件夹（底部空间不足时自动向上展开）
                    DropdownMenu(
                        expanded = fabMenuOpen,
                        onDismissRequest = { fabMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_new_note)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                fabMenuOpen = false
                                viewModel.createNote { path -> onOpenEditor(path, true, false) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_new_folder)) },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                            onClick = {
                                fabMenuOpen = false
                                newFolderDialog = true
                            }
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { inner ->
            Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                NotesListContent(
                    state = state,
                    searchActive = searchActive,
                    modifier = Modifier
                        .fillMaxSize()
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                event.mimeTypes().any { it.startsWith("text/") }
                            },
                            target = dragTarget
                        ),
                    onOpen = { item ->
                        if (item.node.isDirectory) {
                            viewModel.enterDir(item.node)
                        } else {
                            onOpenFile(item.node.absolutePath)
                        }
                    },
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                    onRefresh = viewModel::refresh,
                    onLongPress = { item -> viewModel.enterSelectMode(item.node.absolutePath) },
                    onToggleSelect = { item -> viewModel.toggleSelected(item.node.absolutePath) },
                    onLoadMore = { viewModel.loadMore() }
                )
                // 拖拽悬停提示：松开即把内容保存为新笔记
                if (dragActive) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 6.dp
                    ) {
                        Text(
                            text = stringResource(R.string.home_drop_hint),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    // 条目右侧 ⋮ 弹窗：重命名 / 删除
    renameTarget?.let { target ->
        RenameDialog(
            initialName = editableName(target.node),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(target.node, newName)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        DeleteDialog(
            node = target.node,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.delete(target.node)
                deleteTarget = null
            }
        )
    }

    if (newFolderDialog) {
        NewFolderDialog(
            onDismiss = { newFolderDialog = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                newFolderDialog = false
            }
        )
    }

    // 批量移动：目标文件夹选择（加载中先展示进度）
    if (moveDialog) {
        MoveDialog(
            vaultPath = state.vaultPath,
            targets = state.moveTargets,
            onDismiss = { moveDialog = false },
            onSelect = { dir ->
                viewModel.moveSelectedTo(dir)
                moveDialog = false
            }
        )
    }

    // —— 立即同步：进度 → 变更清单确认 → 结果，三段弹窗均不离开本页 ——
    when (syncState.phase) {
        SyncPhase.SCANNING -> SyncProgressDialog(
            statusText = syncState.statusText.resolve(),
            done = 0,
            total = 0,
            executing = false
        )
        SyncPhase.EXECUTING -> SyncProgressDialog(
            statusText = syncState.statusText.resolve(),
            done = syncState.progressDone,
            total = syncState.progressTotal,
            executing = true
        )
        SyncPhase.AWAIT_CONFIRM -> syncState.plan?.let { plan ->
            SyncConfirmDialog(
                plan = plan,
                onConfirm = syncViewModel::confirmExecute,
                onDismiss = syncViewModel::cancelPlan
            )
        }
        SyncPhase.DONE -> SyncResultDialog(
            summary = syncState.summary,
            error = null,
            onDismiss = syncViewModel::dismissResult
        )
        SyncPhase.ERROR -> SyncResultDialog(
            summary = null,
            error = syncState.error,
            onDismiss = syncViewModel::dismissResult
        )
        SyncPhase.TESTING, SyncPhase.IDLE -> Unit
    }
}

/** 顶栏：抽屉/返回 + 标题（或搜索输入、多选计数）+ 同步/搜索/排序（或全选/移动）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    title: String,
    atRoot: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    sortOrder: NoteSortOrder,
    /** 多选模式：标题显示已选计数，操作区换为全选 / 移动 */
    selectMode: Boolean,
    selectedCount: Int,
    onOpenDrawer: () -> Unit,
    onNavigateUp: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortSelected: (NoteSortOrder) -> Unit,
    onSync: () -> Unit,
    /** 未处理的冲突记录数：> 0 时同步图标显示角标（§5.7）。 */
    conflictCount: Int,
    /** 是否有同步正在执行：执行中把同步图标替换为环形进度指示。 */
    syncActive: Boolean,
    onExitSelect: () -> Unit,
    onSelectAll: () -> Unit,
    onMoveSelected: () -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 进入搜索时自动聚焦输入框（弹软键盘）
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // 等输入框完成组合挂载后再请求焦点
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
        }
    }

    TopAppBar(
        title = {
            when {
                selectMode -> Text(stringResource(R.string.home_selected_count, selectedCount))
                searchActive -> BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.home_search_hint),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                else -> Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            when {
                selectMode -> IconButton(onClick = onExitSelect) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                }
                searchActive -> IconButton(onClick = onSearchClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close))
                }
                atRoot -> IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Outlined.Menu, stringResource(R.string.action_menu))
                }
                else -> IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            when {
                selectMode -> {
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.home_select_all))
                    }
                    TextButton(onClick = onMoveSelected, enabled = selectedCount > 0) {
                        Text(stringResource(R.string.home_select_move))
                    }
                }
                searchActive -> {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                        }
                    }
                }
                else -> {
                    IconButton(onClick = onSync, enabled = !syncActive) {
                        BadgedBox(
                            badge = {
                                if (conflictCount > 0) {
                                    Badge { Text(conflictCount.toString()) }
                                }
                            }
                        ) {
                            if (syncActive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Outlined.Sync, stringResource(R.string.action_sync_now))
                            }
                        }
                    }
                    IconButton(onClick = onSearchOpen) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.home_search_hint))
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort_by))
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            NoteSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label()) },
                                    leadingIcon = {
                                        if (order == sortOrder) {
                                            Icon(Icons.Filled.Check, null)
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }
                                    },
                                    onClick = {
                                        sortMenuOpen = false
                                        onSortSelected(order)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/** 抽屉内容：Vault 信息 + 回收站（关闭时隐藏入口）/ 设置入口。 */
@Composable
private fun DrawerContent(
    vaultPath: String?,
    /** 回收站开关：关闭时隐藏入口（删除即物理删除） */
    trashEnabled: Boolean,
    onTrash: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            val vaultName = vaultPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            if (vaultName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vaultName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (trashEnabled) {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.trash_title)) },
                icon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                selected = false,
                onClick = onTrash,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.action_settings)) },
            icon = { Icon(Icons.Outlined.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

/** 列表内容：下拉刷新 + 卡片列表（文件夹在前，笔记随后）；滚动接近末尾自动加载下一批。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesListContent(
    state: NotesUiState,
    searchActive: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (NoteListItem) -> Unit,
    onRename: (NoteListItem) -> Unit,
    onDelete: (NoteListItem) -> Unit,
    onRefresh: () -> Unit,
    onLongPress: (NoteListItem) -> Unit,
    onToggleSelect: (NoteListItem) -> Unit,
    onLoadMore: () -> Unit
) {
    val visible = if (searchActive) state.searchResults else state.items
    val listState = rememberLazyListState()
    // 滚动接近末尾（距底 5 项）且有未装载批次时自动加载下一批；快照闭包经
    // rememberUpdatedState 读取最新值，避免捕获旧状态；搜索结果一次性返回、不分页
    val currentHasMore by rememberUpdatedState(state.hasMore)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, searchActive) {
        if (searchActive) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, total) ->
            if (currentHasMore && total > 0 && last >= total - 5) currentOnLoadMore()
        }
    }

    Box(modifier) {
        when {
            state.loading && visible.isEmpty() -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error != null && visible.isEmpty() && !searchActive -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.error?.resolve().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tree_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when {
                            searchActive && state.searching && visible.isEmpty() -> {
                                item {
                                    Box(Modifier.fillParentMaxWidth().padding(top = 48.dp)) {
                                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                                    }
                                }
                            }
                            searchActive && state.searchQuery.isBlank() -> {
                                item { ListHint(stringResource(R.string.home_search_tip)) }
                            }
                            searchActive && visible.isEmpty() -> {
                                item { ListHint(stringResource(R.string.home_search_empty)) }
                            }
                            !searchActive && visible.isEmpty() -> {
                                item { ListHint(stringResource(R.string.tree_empty)) }
                            }
                        }
                        items(visible, key = { it.node.absolutePath }) { item ->
                            NoteRow(
                                item = item,
                                selectMode = state.selectMode,
                                selected = item.node.absolutePath in state.selectedPaths,
                                onClick = {
                                    if (state.selectMode) onToggleSelect(item) else onOpen(item)
                                },
                                onLongPress = {
                                    if (state.selectMode) onToggleSelect(item) else onLongPress(item)
                                },
                                onRename = { onRename(item) },
                                onDelete = { onDelete(item) }
                            )
                        }
                        // 后续批次装载指示（滚动到底自动触发）
                        if (!searchActive && state.hasMore) {
                            item(key = "load_more_indicator") {
                                Box(
                                    modifier = Modifier.fillParentMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
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

@Composable
private fun ListHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 单条：文件夹 → 图标 + 名称；笔记 → 日期 / 标题 + ⋮ / 正文预览；多选模式：选中高亮 + 勾选指示。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    item: NoteListItem,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val node = item.node
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (node.isDirectory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = stringResource(R.string.home_folder),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selectMode) {
                    SelectIndicator(selected)
                } else {
                    // 文件夹同样提供重命名 / 删除入口（重命名后云端由 MOVE 同步）
                    NoteMoreButton(onRename = onRename, onDelete = onDelete)
                }
            }
        } else {
            Text(
                text = formatDateTime(node.lastModified),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayTitle(node),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                item.progress?.let { percent ->
                    ReadingProgressDot(progress = percent)
                    Spacer(Modifier.width(6.dp))
                }
                if (selectMode) {
                    SelectIndicator(selected)
                } else {
                    NoteMoreButton(onRename = onRename, onDelete = onDelete)
                }
            }
            if (item.preview.isNotEmpty()) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.subtitle?.let { dir ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = dir,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 多选模式选中指示：选中实心主色，未选中弱化显示（点击条目切换）；尺寸对齐 [NoteMoreButton]。 */
@Composable
private fun SelectIndicator(selected: Boolean) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(12.dp)
    )
}

/** ⋮ 按钮：点击在按钮旁弹出浮层菜单（重命名 / 删除），替代底部弹窗以便单手操作。 */
@Composable
private fun NoteMoreButton(
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = {
                    menuOpen = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

// 重命名对话框由 ui/components/RenameDialog 提供（主页与编辑页共用）

@Composable
private fun DeleteDialog(
    node: FileNode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_delete)) },
        text = {
            Text(
                if (node.isDirectory) {
                    stringResource(R.string.delete_folder_message, node.name)
                } else {
                    stringResource(R.string.delete_note_message, node.name)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** 新建文件夹对话框（在当前目录创建；名称清洗由 ViewModel 负责）。 */
@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_new_folder)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.rename_hint)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.action_confirm))
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
 * 批量移动对话框：列出 Vault 根目录与全部子目录（按层级缩进）；
 * 点击目标即执行移动（对话框由调用方关闭）。
 */
@Composable
private fun MoveDialog(
    vaultPath: String?,
    targets: List<FileNode>?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_move_title)) },
        text = {
            if (targets == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    vaultPath?.let { root ->
                        item(key = "__root__") {
                            MoveTargetRow(
                                depth = 0,
                                label = stringResource(R.string.home_move_root),
                                onClick = { onSelect(root) }
                            )
                        }
                    }
                    items(targets, key = { it.absolutePath }) { dir ->
                        MoveTargetRow(
                            depth = dir.depth + 1,
                            label = dir.name,
                            onClick = { onSelect(dir.absolutePath) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** 目标文件夹行：按 depth 缩进展示层级，点击该项即移动。 */
@Composable
private fun MoveTargetRow(
    depth: Int,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = (8 + depth * 16).dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 阅读进度小圆点：环形弧长表示 1–99% 的阅读进度。 */
@Composable
private fun ReadingProgressDot(progress: Int) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val strokeWidth = 2.dp
    Canvas(modifier = Modifier.size(16.dp)) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(strokePx)
        )
        drawArc(
            color = active,
            startAngle = -90f,
            sweepAngle = progress / 100f * 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(strokePx, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun NoteSortOrder.label(): String = stringResource(
    when (this) {
        NoteSortOrder.MODIFIED_DESC -> R.string.sort_modified_desc
        NoteSortOrder.MODIFIED_ASC -> R.string.sort_modified_asc
        NoteSortOrder.NAME_ASC -> R.string.sort_name_asc
        NoteSortOrder.NAME_DESC -> R.string.sort_name_desc
    }
)

/** 列表展示标题：笔记去掉 .md 扩展名。 */
private fun displayTitle(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name

/** 重命名输入框初始值：笔记显示不含扩展名的名称。 */
private fun editableName(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name

/** 列表日期格式化器：UI 线程使用；缓存实例，避免滚动时为每个条目新建 SimpleDateFormat。 */
private val LIST_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun formatDateTime(millis: Long): String = LIST_DATE_FORMAT.format(Date(millis))

/** 双击返回退出的时间窗口（毫秒）。 */
private const val DOUBLE_BACK_EXIT_MS = 2000L
