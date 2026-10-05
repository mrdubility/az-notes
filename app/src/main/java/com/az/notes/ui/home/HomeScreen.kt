package com.az.notes.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.FileNode
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.ui.components.RenameDialog
import com.az.notes.ui.components.SyncConfirmSheet
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
 * （与同步页共用 [SyncConfirmSheet] 等组件）；同步配置仍从设置 → 同步进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFile: (String) -> Unit,
    onOpenEditor: (path: String, fresh: Boolean) -> Unit,
    onSettings: () -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by syncViewModel.state.collectAsStateWithLifecycle()
    val conflicts by syncViewModel.conflicts.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<NoteListItem?>(null) }
    var renameTarget by remember { mutableStateOf<NoteListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteListItem?>(null) }

    // 首次进入 / 从阅读、编辑页返回时静默重读当前目录，保证修改时间与预览最新
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // 一次性提示（重命名/删除/新建结果）
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 立即同步的一次性提示（尚未配置 / 正在同步中 / 未选 Vault 等）
    LaunchedEffect(syncState.testMessage) {
        val message = syncState.testMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        syncViewModel.clearTestMessage()
    }

    // 同步完成后静默重读当前目录（可能从云端拉下了新文件）
    LaunchedEffect(syncState.phase) {
        if (syncState.phase == SyncPhase.DONE) viewModel.onScreenEntered()
    }

    // 系统返回：优先退出搜索，其次返回上一级目录
    BackHandler(enabled = searchActive) {
        searchActive = false
        viewModel.clearSearch()
    }
    BackHandler(enabled = !searchActive && !state.atRoot) { viewModel.navigateUp() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // 抽屉按“大半屏”宽度拉出（约 72%），不覆盖全屏
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.72f)) {
                DrawerContent(
                    vaultPath = state.vaultPath,
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
                    conflictCount = conflicts.size
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { viewModel.createNote { path -> onOpenEditor(path, true) } },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, stringResource(R.string.home_new_note))
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { inner ->
            NotesListContent(
                state = state,
                searchActive = searchActive,
                modifier = Modifier.fillMaxSize().padding(inner),
                onOpen = { item ->
                    if (item.node.isDirectory) {
                        viewModel.enterDir(item.node)
                    } else {
                        onOpenFile(item.node.absolutePath)
                    }
                },
                onMore = { actionTarget = it },
                onRefresh = viewModel::refresh
            )
        }
    }

    // 条目右侧 ⋮ 弹窗：重命名 / 删除
    actionTarget?.let { target ->
        NoteActionSheet(
            target = target,
            onDismiss = { actionTarget = null },
            onRename = {
                actionTarget = null
                renameTarget = target
            },
            onDelete = {
                actionTarget = null
                deleteTarget = target
            }
        )
    }

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

    // —— 立即同步：进度 → 变更清单确认 → 结果，三段弹窗均不离开本页 ——
    when (syncState.phase) {
        SyncPhase.SCANNING -> SyncProgressDialog(
            statusText = syncState.statusText,
            done = 0,
            total = 0,
            executing = false
        )
        SyncPhase.EXECUTING -> SyncProgressDialog(
            statusText = syncState.statusText,
            done = syncState.progressDone,
            total = syncState.progressTotal,
            executing = true
        )
        SyncPhase.AWAIT_CONFIRM -> syncState.plan?.let { plan ->
            SyncConfirmSheet(
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

/** 顶栏：抽屉/返回 + 标题（或搜索输入）+ 同步/搜索/排序。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    title: String,
    atRoot: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    sortOrder: NoteSortOrder,
    onOpenDrawer: () -> Unit,
    onNavigateUp: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortSelected: (NoteSortOrder) -> Unit,
    onSync: () -> Unit,
    /** 未处理的冲突记录数：> 0 时同步图标显示角标（§5.7）。 */
    conflictCount: Int
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            if (searchActive) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            when {
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
            if (searchActive) {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                    }
                }
            } else {
                IconButton(onClick = onSync) {
                    BadgedBox(
                        badge = {
                            if (conflictCount > 0) {
                                Badge { Text(conflictCount.toString()) }
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.Sync, stringResource(R.string.action_sync_now))
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
    )
}

/** 抽屉内容：Vault 信息 + 设置入口（后续可扩展更多条目）。 */
@Composable
private fun DrawerContent(
    vaultPath: String?,
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
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.action_settings)) },
            icon = { Icon(Icons.Outlined.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

/** 列表内容：下拉刷新 + 卡片列表（文件夹在前，笔记随后）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesListContent(
    state: NotesUiState,
    searchActive: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (NoteListItem) -> Unit,
    onMore: (NoteListItem) -> Unit,
    onRefresh: () -> Unit
) {
    val visible = if (searchActive) state.searchResults else state.items

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
                        text = state.error,
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
                                onClick = { onOpen(item) },
                                onMore = { onMore(item) }
                            )
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

/** 单条：文件夹 → 图标 + 名称；笔记 → 日期 / 标题 + ⋮ / 正文预览。 */
@Composable
private fun NoteRow(
    item: NoteListItem,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val node = item.node
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
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
                // 文件夹同样提供重命名 / 删除入口（重命名后云端由 MOVE 同步）
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

/** ⋮ 弹窗（ModalBottomSheet）：重命名 / 删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteActionSheet(
    target: NoteListItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = displayTitle(target.node),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            SheetAction(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.action_rename),
                onClick = onRename
            )
            SheetAction(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.action_delete),
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
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

private fun NoteSortOrder.label(): String = when (this) {
    NoteSortOrder.MODIFIED_DESC -> "修改时间（新 → 旧）"
    NoteSortOrder.MODIFIED_ASC -> "修改时间（旧 → 新）"
    NoteSortOrder.NAME_ASC -> "名称（A → Z）"
    NoteSortOrder.NAME_DESC -> "名称（Z → A）"
}

/** 列表展示标题：笔记去掉 .md 扩展名。 */
private fun displayTitle(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name

/** 重命名输入框初始值：笔记显示不含扩展名的名称。 */
private fun editableName(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
