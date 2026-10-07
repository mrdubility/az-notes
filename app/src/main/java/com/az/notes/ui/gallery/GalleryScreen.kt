package com.az.notes.ui.gallery

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.az.notes.R
import com.az.notes.ui.common.resolve
import com.az.notes.ui.reader.ImagePreviewDialog
import java.io.File

/**
 * 图库页（跟随当前仓库）：网格浏览仓库全部图片，点击全屏预览（捏合缩放）；
 * 长按或顶栏「选择」进入多选，可批量「移入回收站」（回收站关闭时物理删除并二次确认）；
 * 「只显示未被引用的图片」按需扫描引用索引（未被任何笔记引用的图片，供清理）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmTrash by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.text.resolve(context))
        viewModel.consumeMessage()
    }

    // 选择模式下系统返回先退出选择（不离开页面）
    BackHandler(enabled = state.selectMode) { viewModel.exitSelectMode() }

    val displayed = state.displayed
    val totalBytes = displayed.sumOf { it.size }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (!state.loading && !state.scanning && state.images.isNotEmpty()) {
                        if (state.selectMode) {
                            if (state.selected.size < displayed.size) {
                                TextButton(onClick = viewModel::selectAll) {
                                    Text(stringResource(R.string.gallery_select_all))
                                }
                            }
                            TextButton(onClick = viewModel::exitSelectMode) {
                                Text(stringResource(R.string.gallery_cancel_select))
                            }
                        } else {
                            TextButton(onClick = viewModel::enterSelectMode) {
                                Text(stringResource(R.string.gallery_select))
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                Button(
                    onClick = { confirmTrash = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (state.trashEnabled) R.string.gallery_move_trash
                            else R.string.gallery_delete_permanently
                        )
                    )
                }
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            GalleryHeader(
                state = state,
                displayedCount = displayed.size,
                totalBytes = totalBytes,
                onToggleFilter = { viewModel.setUnreferencedOnly(it) }
            )
            when {
                state.loading || (state.scanning && displayed.isEmpty()) -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.images.isEmpty() -> EmptyHint(stringResource(R.string.gallery_empty))

                displayed.isEmpty() -> EmptyHint(stringResource(R.string.gallery_empty_unreferenced))

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(displayed, key = { it.file.absolutePath }) { image ->
                        val absolutePath = image.file.absolutePath
                        GalleryCell(
                            image = image,
                            selectMode = state.selectMode,
                            selected = absolutePath in state.selected,
                            onClick = {
                                if (state.selectMode) viewModel.toggleSelect(absolutePath)
                                else previewFile = image.file
                            },
                            onLongClick = {
                                if (!state.selectMode) viewModel.enterSelectMode()
                                if (absolutePath !in state.selected) viewModel.toggleSelect(absolutePath)
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.gallery_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.trashEnabled) R.string.gallery_confirm_trash
                        else R.string.gallery_confirm_delete,
                        state.selected.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    viewModel.trashSelected()
                }) {
                    Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 全屏图片预览（复用阅读器组件：黑底 + 点按关闭 + 捏合缩放）
    previewFile?.let { file ->
        ImagePreviewDialog(file = file, onDismiss = { previewFile = null })
    }
}

/** 顶部区：过滤开关（只显示未被引用的图片）+ 扫描进度 / 统计 + 未扫描笔记提示。 */
@Composable
private fun GalleryHeader(
    state: GalleryUiState,
    displayedCount: Int,
    totalBytes: Long,
    onToggleFilter: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FilterChip(
            selected = state.unreferencedOnly,
            onClick = { onToggleFilter(!state.unreferencedOnly) },
            label = { Text(stringResource(R.string.gallery_filter_orphans)) }
        )
        Spacer(Modifier.height(4.dp))
        when {
            state.scanning -> Text(
                text = stringResource(R.string.gallery_scanning, state.scannedNotes, state.totalNotes),
                style = MaterialTheme.typography.bodyMedium
            )

            !state.loading && state.images.isNotEmpty() -> Text(
                text = if (state.unreferencedOnly) {
                    stringResource(R.string.gallery_summary_unreferenced, displayedCount, formatSize(totalBytes))
                } else {
                    stringResource(R.string.gallery_summary_all, displayedCount, formatSize(totalBytes))
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (!state.scanning && state.unreferencedOnly && state.unscannedNotes > 0) {
            Text(
                text = stringResource(R.string.gallery_unscanned, state.unscannedNotes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 网格单元：方形缩略图；选择模式下叠加蒙层与勾选框（点击 / 长按由外层回调处理）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    image: GalleryImage,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = Uri.fromFile(image.file),
            contentDescription = image.relativePath,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (selectMode) {
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            )
        }
    }
}

/** 空态提示（居中，两侧留白）。 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
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

/** 文件大小：B / KB / MB（各档保留一位小数）。 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
