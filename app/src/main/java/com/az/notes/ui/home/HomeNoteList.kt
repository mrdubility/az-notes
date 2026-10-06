package com.az.notes.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.model.FileNode
import com.az.notes.ui.common.resolve
import com.az.notes.ui.notes.NoteListItem
import com.az.notes.ui.notes.NotesUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页笔记列表内容：下拉刷新 + 卡片列表（文件夹在前，笔记随后）；
 * 滚动接近末尾自动加载下一批。自 HomeScreen 拆分独立文件（纯 UI，逻辑不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesListContent(
    state: NotesUiState,
    searchActive: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (NoteListItem) -> Unit,
    onRename: (NoteListItem) -> Unit,
    onDelete: (NoteListItem) -> Unit,
    onRefresh: () -> Unit,
    onLongPress: (NoteListItem) -> Unit,
    onToggleSelect: (NoteListItem) -> Unit,
    onToggleFavorite: (NoteListItem) -> Unit,
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
                                isFavorite = item.node.relativePath in state.favoritePaths,
                                onClick = {
                                    if (state.selectMode) onToggleSelect(item) else onOpen(item)
                                },
                                onLongPress = {
                                    if (state.selectMode) onToggleSelect(item) else onLongPress(item)
                                },
                                onRename = { onRename(item) },
                                onDelete = { onDelete(item) },
                                onToggleFavorite = { onToggleFavorite(item) }
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

/** 单条：文件夹 → 图标 + 名称；笔记 → 日期 / 标题 + ⋮ / 正文预览；多选模式：选中高亮 + 勾选指示（仅选中显示）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    item: NoteListItem,
    selectMode: Boolean,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
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
                    NoteMoreButton(
                        isFavorite = false,
                        onToggleFavorite = null,
                        onRename = onRename,
                        onDelete = onDelete
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
                if (isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.action_unfavorite),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                item.progress?.let { percent ->
                    Spacer(Modifier.width(6.dp))
                    ReadingProgressDot(progress = percent)
                }
                if (selectMode) {
                    SelectIndicator(selected)
                } else {
                    NoteMoreButton(
                        isFavorite = isFavorite,
                        onToggleFavorite = onToggleFavorite,
                        onRename = onRename,
                        onDelete = onDelete
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

/** 多选模式选中指示：仅选中时显示实心勾，未选中透明占位（保持布局稳定）。 */
@Composable
private fun SelectIndicator(selected: Boolean) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.padding(12.dp)
    )
}

/**
 * ⋮ 按钮：点击在按钮旁弹出浮层菜单（收藏 / 重命名 / 删除），替代底部弹窗以便单手操作；
 * 文件夹不显示收藏项（[onToggleFavorite] 为 null）。
 */
@Composable
private fun NoteMoreButton(
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
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
            onToggleFavorite?.let { toggle ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFavorite) R.string.action_unfavorite else R.string.action_favorite
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            null
                        )
                    },
                    onClick = {
                        menuOpen = false
                        toggle()
                    }
                )
            }
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

/** 列表展示标题：笔记去掉 .md 扩展名。 */
private fun displayTitle(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name

/** 列表日期格式化器：UI 线程使用；缓存实例，避免滚动时为每个条目新建 SimpleDateFormat。 */
private val LIST_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun formatDateTime(millis: Long): String = LIST_DATE_FORMAT.format(Date(millis))
