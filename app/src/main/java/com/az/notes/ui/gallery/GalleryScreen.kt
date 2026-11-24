package com.az.notes.ui.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.az.notes.R
import com.az.notes.data.media.GallerySaver
import com.az.notes.ui.common.formatDateTimeSeconds
import com.az.notes.ui.common.formatSize
import com.az.notes.ui.common.localFile
import com.az.notes.ui.common.localFileVersion
import com.az.notes.ui.common.resolve
import com.az.notes.ui.common.showTimedSnackbar
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 待删除的图片集合：预览单张与多选批量共用同一确认框。 */
private data class DeleteRequest(val paths: List<String>)

/**
 * 图库页（跟随当前仓库）：网格 / 列表两种样式，操作完全一致——
 * 点按图片全屏预览（底部提供保存到相册 / 删除 / 查看引用 / 图片信息）；
 * 长按进入多选，底栏提供批量保存与删除（回收站关闭时物理删除并二次确认）；
 * 「未被引用」按需扫描引用索引（未被任何笔记引用的图片，供清理）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var previewImage by remember { mutableStateOf<GalleryImage?>(null) }
    // 预览页内的即时反馈（全屏 Dialog 会盖住页面 Snackbar）
    var previewHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(previewHint) {
        if (previewHint != null) {
            delay(2000)
            previewHint = null
        }
    }
    // 视图模式：false = 网格（默认），true = 列表（缩略图 + 文件名 + 大小）
    var listView by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        try {
            snackbarHostState.showTimedSnackbar(msg.text.resolve(context))
        } finally {
            // 正常结束或离开页面（协程取消）都消费本条：返回不再重放
            viewModel.consumeMessage(msg)
        }
    }

    // 选择模式下系统返回先退出选择（不离开页面）
    BackHandler(enabled = state.selectMode) { viewModel.exitSelectMode() }

    val displayed = state.displayed
    // 仅当展示列表变化时重算（选择 / 预览等状态重组不再触发 O(n) 求和）
    val totalBytes = remember(displayed) { displayed.sumOf { it.size } }
    val selectedImages = remember(state.images, state.selected) {
        state.images.filter { it.file.absolutePath in state.selected }
    }
    // 网格 / 列表双视图共用的点击（预览或选择）与长按（进入选择）行为
    val onItemClick: (GalleryImage) -> Unit = { image ->
        if (state.selectMode) {
            viewModel.toggleSelect(image.file.absolutePath)
        } else {
            previewImage = image
        }
    }
    val onItemLongClick: (GalleryImage) -> Unit = { image ->
        val absolutePath = image.file.absolutePath
        if (!state.selectMode) viewModel.enterSelectMode()
        if (absolutePath !in state.selected) viewModel.toggleSelect(absolutePath)
    }

    // —— 保存到相册（预览单张 / 多选批量共用；Android 8–9 需运行时 WRITE 权限）——
    var pendingSave by remember { mutableStateOf<List<File>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saveToGallery: (List<File>) -> Unit = { files ->
        if (files.isNotEmpty() && !saving) {
            saving = true
            scope.launch {
                var saved = 0
                for (file in files) {
                    if (GallerySaver.save(context, file) != null) saved++
                }
                saving = false
                val text = when {
                    saved == files.size -> context.getString(R.string.gallery_saved_count, saved)
                    saved > 0 -> context.getString(R.string.gallery_saved_partial, saved, files.size - saved)
                    else -> context.getString(R.string.image_save_failed)
                }
                if (previewImage != null) previewHint = text else snackbarHostState.showTimedSnackbar(text)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val files = pendingSave
        pendingSave = null
        if (granted && files != null) {
            saveToGallery(files)
        } else if (!granted) {
            val text = context.getString(R.string.image_save_denied)
            if (previewImage != null) {
                previewHint = text
            } else {
                scope.launch { snackbarHostState.showTimedSnackbar(text) }
            }
        }
    }
    val requestSave: (List<File>) -> Unit = { files ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSave = files
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveToGallery(files)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectMode) {
                            stringResource(R.string.gallery_selected_count, state.selected.size)
                        } else {
                            stringResource(R.string.gallery_title)
                        }
                    )
                },
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
                            // 网格 / 列表视图切换（选择模式下隐藏，避免操作区拥挤）
                            IconButton(onClick = { listView = !listView }) {
                                Icon(
                                    imageVector = if (listView) Icons.Outlined.GridView
                                    else Icons.AutoMirrored.Outlined.ViewList,
                                    contentDescription = stringResource(
                                        if (listView) R.string.gallery_view_grid
                                        else R.string.gallery_view_list
                                    )
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.selectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { requestSave(selectedImages.map { it.file }) },
                        enabled = selectedImages.isNotEmpty() && !saving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.gallery_action_save))
                    }
                    Button(
                        onClick = { deleteRequest = DeleteRequest(state.selected.toList()) },
                        enabled = selectedImages.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (state.trashEnabled) R.string.gallery_move_trash
                                else R.string.gallery_delete_permanently
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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

                listView -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(count = displayed.size, key = { displayed[it].file.absolutePath }) { index ->
                        val image = displayed[index]
                        GalleryListRow(
                            image = image,
                            selectMode = state.selectMode,
                            selected = image.file.absolutePath in state.selected,
                            onClick = { onItemClick(image) },
                            onLongClick = { onItemLongClick(image) }
                        )
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(displayed, key = { it.file.absolutePath }) { image ->
                        GalleryCell(
                            image = image,
                            selectMode = state.selectMode,
                            selected = image.file.absolutePath in state.selected,
                            onClick = { onItemClick(image) },
                            onLongClick = { onItemLongClick(image) }
                        )
                    }
                }
            }
        }
    }

    deleteRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text(stringResource(R.string.gallery_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.trashEnabled) R.string.gallery_confirm_trash
                        else R.string.gallery_confirm_delete,
                        request.paths.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteRequest = null
                    previewImage = null
                    previewHint = null
                    viewModel.deleteImages(request.paths)
                }) {
                    Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequest = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 全屏预览：底部提供保存 / 删除 / 查看引用 / 图片信息
    previewImage?.let { image ->
        GalleryPreviewDialog(
            image = image,
            deleteLabel = stringResource(
                if (state.trashEnabled) R.string.gallery_move_trash
                else R.string.gallery_delete_permanently
            ),
            hint = previewHint,
            onDismiss = { previewImage = null; previewHint = null },
            onSave = { requestSave(listOf(image.file)) },
            onDelete = { deleteRequest = DeleteRequest(listOf(image.file.absolutePath)) },
            onShowReferences = { viewModel.openReferences(image) },
            onShowInfo = { viewModel.openInfo(image) }
        )
    }

    // 引用抽屉：列出引用该图片的笔记，点击进入笔记
    val references = state.references
    if (references != null) {
        ModalBottomSheet(onDismissRequest = viewModel::closeReferences) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.gallery_references_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                when {
                    state.referencesLoading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    references.isEmpty() -> Text(
                        text = stringResource(R.string.gallery_references_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(references, key = { it.notePath }) { hit ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.closeReferences()
                                        previewImage = null
                                        onOpenNote(hit.notePath)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = hit.relativePath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.gallery_reference_line,
                                        hit.line + 1,
                                        hit.snippet
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 图片信息浮窗：完整文件名 / 路径 / 大小 / 修改时间 / 分辨率
    state.info?.let { info ->
        AlertDialog(
            onDismissRequest = viewModel::closeInfo,
            title = { Text(stringResource(R.string.gallery_info_title)) },
            text = {
                Column {
                    InfoRow(stringResource(R.string.gallery_info_name), info.name)
                    InfoRow(stringResource(R.string.gallery_info_path), info.relativePath)
                    InfoRow(stringResource(R.string.gallery_info_size), formatSize(info.size))
                    InfoRow(
                        stringResource(R.string.gallery_info_time),
                        formatDateTimeSeconds(info.lastModified)
                    )
                    InfoRow(
                        stringResource(R.string.gallery_info_resolution),
                        if (info.width > 0 && info.height > 0) {
                            "${info.width} × ${info.height}"
                        } else {
                            stringResource(R.string.gallery_info_unknown)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeInfo) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

/** 顶部区：过滤开关（未被引用）+ 扫描进度 / 统计 + 未扫描笔记提示。 */
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

/** 信息行：定宽标签 + 可换行取值。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
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
        val platformContext = LocalPlatformContext.current
        // 文件版本进键：同步覆盖同名图片后缩略图重新解码
        val request = remember(image.file, localFileVersion(image.file)) {
            ImageRequest.Builder(platformContext).localFile(image.file).build()
        }
        AsyncImage(
            model = request,
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

/** 列表模式行：缩略图 + 文件名 + 体积（选择模式下显示勾选，行为与网格一致）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryListRow(
    image: GalleryImage,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val platformContext = LocalPlatformContext.current
        val request = remember(image.file, localFileVersion(image.file)) {
            ImageRequest.Builder(platformContext).localFile(image.file).build()
        }
        AsyncImage(
            model = request,
            contentDescription = image.relativePath,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = image.file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatSize(image.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selectMode) {
            Checkbox(checked = selected, onCheckedChange = null)
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
