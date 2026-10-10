package com.az.notes.ui.home

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.data.media.AttachmentRepository
import com.az.notes.domain.model.FabAction
import com.az.notes.ui.common.resolve
import com.az.notes.ui.components.MoveTargetDialog
import com.az.notes.ui.components.RenameDialog
import com.az.notes.ui.components.SyncConfirmDialog
import com.az.notes.ui.components.SyncProgressDialog
import com.az.notes.ui.components.SyncResultDialog
import com.az.notes.ui.notes.NoteListItem
import com.az.notes.ui.notes.NotesViewModel
import com.az.notes.ui.sync.SyncPhase
import com.az.notes.ui.sync.SyncViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主界面（§5.1 重设计）：抽屉菜单 + 笔记卡片列表。
 * 左上角抽屉 → 设置入口；右上角 → 立即同步 / 搜索 / 排序；右下角 FAB → 新建笔记；
 * 下拉刷新；条目右侧 ⋮ → 收藏 / 重命名 / 移动 / 删除（文件夹除收藏外同样支持）；文件夹可点击进入；隐藏 '.' 开头项。
 *
 * 立即同步不离开本页：扫描进度、变更清单确认与结果均以弹窗展示
 * （与同步页共用 [SyncConfirmDialog] 等组件）；同步配置仍从设置 → 同步进入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenFile: (String) -> Unit,
    /** 新建待办后直接进入待办预览页（fresh=true：退出时无条目则清理占位文件） */
    onOpenTask: (String) -> Unit,
    onOpenEditor: (path: String, fresh: Boolean, fromShare: Boolean) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenGallery: () -> Unit,
    /** AI 对话页（B2）：抽屉入口 */
    onOpenAiChat: () -> Unit,
    onSettings: () -> Unit,
    /** 顶栏切换当前仓库（记忆为下次启动 / 分享的目标仓库） */
    onSwitchVault: (String) -> Unit,
    /** 顶栏仓库菜单 → 仓库管理页 */
    onOpenVaults: () -> Unit,
    /** 系统分享 / 内容传送门传入的待写入文本（null = 无） */
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    /** 系统分享（image 类型）传入的待导入图片 URI（null = 无）；与文本互斥 */
    sharedImageUri: Uri? = null,
    onSharedImageConsumed: () -> Unit = {},
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
    // 删除确认时的附件引用统计（仅笔记、开关开启时非 null）
    var deleteReferenced by remember { mutableStateOf<AttachmentRepository.ReferencedAttachments?>(null) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var moveSingle by remember { mutableStateOf<String?>(null) }
    var lastBackAt by remember { mutableStateOf(0L) }
    var dragActive by remember { mutableStateOf(false) }

    // 首次进入 / 从阅读、编辑页返回时静默重读当前目录，保证修改时间与预览最新
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // 选定删除目标时异步统计其引用附件（开关关闭 / 目录时为 null，对话框走原路径）
    LaunchedEffect(deleteTarget) {
        val target = deleteTarget
        deleteReferenced = if (target != null) viewModel.referencedAttachmentsFor(target.node) else null
    }

    // 一次性提示（重命名/删除/收藏等结果）；携带撤销动作时显示横幅与「撤销」按钮，
    // 限时 [UNDO_BANNER_MS] 后自动消失（超时视为放弃撤销）
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        val result = withTimeoutOrNull(UNDO_BANNER_MS) {
            snackbarHostState.showSnackbar(
                message = msg.text.resolve(context),
                actionLabel = if (msg.undo != null) undoLabel else null,
                duration = if (msg.undo != null) SnackbarDuration.Indefinite else SnackbarDuration.Short
            )
        }
        if (result == SnackbarResult.ActionPerformed) msg.undo?.invoke()
        viewModel.consumeMessage()
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

    // 系统分享图片（ACTION_SEND image/*）：导入图片新建图文笔记并进入编辑页（fromShare=true）
    LaunchedEffect(sharedImageUri) {
        val uri = sharedImageUri ?: return@LaunchedEffect
        onSharedImageConsumed()
        viewModel.createNoteFromSharedImage(uri) { path -> onOpenEditor(path, false, true) }
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
                    vaultName = state.currentVaultName,
                    vaultPath = state.vaultPath,
                    trashEnabled = state.trashEnabled,
                    onFavorites = {
                        scope.launch { drawerState.close() }
                        onOpenFavorites()
                    },
                    onOpenAiChat = {
                        scope.launch { drawerState.close() }
                        onOpenAiChat()
                    },
                    onGallery = {
                        scope.launch { drawerState.close() }
                        onOpenGallery()
                    },
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
                        ?: state.currentVaultName
                        ?: stringResource(R.string.app_name),
                    vaultName = state.currentVaultName ?: stringResource(R.string.app_name),
                    // 隐藏的仓库不出现在切换列表（仓库管理页仍可见并可就地恢复显示）
                    vaults = state.vaults.filterNot { it.hidden },
                    currentVaultId = state.currentVaultId,
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
                    // 同步入口仅配置了同步账号后出现（loaded 前不显示，避免「出现又消失」闪烁）
                    syncConfigured = syncState.loaded && syncState.config.configured,
                    onSync = { syncViewModel.startSync() },
                    conflictCount = conflicts.size,
                    syncActive = syncRunning,
                    onExitSelect = { viewModel.exitSelectMode() },
                    onSelectAll = { viewModel.toggleSelectAll() },
                    onFavoriteSelected = { viewModel.favoriteSelected() },
                    onMoveSelected = {
                        viewModel.loadMoveTargets()
                        moveDialog = true
                    },
                    onDeleteSelected = { viewModel.deleteSelected() },
                    onSwitchVault = onSwitchVault,
                    onManageVaults = onOpenVaults
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
                                        FabAction.NEW_TASK -> viewModel.createTaskNote { path ->
                                            // 新建待办直接进入待办预览页（而非文本编辑页）
                                            onOpenTask(path)
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
                    // FAB 菜单：新建笔记 / 新建待办 / 新建文件夹（底部空间不足时自动向上展开）
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
                            text = { Text(stringResource(R.string.home_new_task)) },
                            leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                            onClick = {
                                fabMenuOpen = false
                                // 新建待办直接进入待办预览页（而非文本编辑页）
                                viewModel.createTaskNote { path -> onOpenTask(path) }
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
                    onToggleFavorite = { item -> viewModel.toggleFavorite(item.node) },
                    onMove = { item ->
                        viewModel.loadMoveTargets()
                        moveSingle = item.node.absolutePath
                    },
                    onDuplicate = { item -> viewModel.copyNote(item.node) },
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
            referenced = deleteReferenced,
            onDismiss = { deleteTarget = null },
            onConfirm = { alsoTrashAttachments ->
                // 复用打开对话框时已查的引用索引，避免确认后再全库重扫
                viewModel.delete(target.node, alsoTrashAttachments, deleteReferenced?.exclusiveFiles)
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

    // 移动：目标文件夹选择（批量 / 单条共用；加载中先展示进度；与编辑页共用组件）
    // 顶部队列可切换目标仓库（不显示隐藏仓库）；根目录行以目标仓库为准
    if (moveDialog || moveSingle != null) {
        MoveTargetDialog(
            vaultPath = state.moveTargetVaultPath,
            targets = state.moveTargets,
            onDismiss = {
                moveDialog = false
                moveSingle = null
            },
            onSelect = { dir ->
                val single = moveSingle
                if (single != null) viewModel.moveTo(single, dir) else viewModel.moveSelectedTo(dir)
                moveDialog = false
                moveSingle = null
            },
            vaults = state.vaults.filterNot { it.hidden },
            selectedVaultId = state.moveTargetVaultId,
            onSelectVault = { viewModel.loadMoveTargetsFor(it) }
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

/** 双击返回退出的时间窗口（毫秒）。 */
private const val DOUBLE_BACK_EXIT_MS = 2000L

/** 可撤销横幅（删除/收藏等）的显示时长（毫秒）：超时视为放弃撤销。 */
private const val UNDO_BANNER_MS = 5_000L
