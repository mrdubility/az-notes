package com.az.notes.ui.orphan

import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.az.notes.R
import com.az.notes.ui.common.resolve
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 孤儿图片页（附件依托笔记理念的反向清理）：扫描未被任何笔记引用的图片，
 * 支持多选后「移入回收站」（回收站关闭时物理删除并二次确认），清理后自动重扫。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrphanImageScreen(
    viewModel: OrphanImageViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmTrash by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.text.resolve(context))
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.orphan_images_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.orphans.isNotEmpty() && !state.scanning) {
                        if (state.selected.isEmpty()) {
                            TextButton(onClick = viewModel::selectAll) {
                                Text(stringResource(R.string.orphan_images_select_all))
                            }
                        } else {
                            TextButton(onClick = viewModel::clearSelection) {
                                Text(stringResource(R.string.orphan_images_clear))
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
                            if (state.trashEnabled) R.string.orphan_images_move_trash
                            else R.string.orphan_images_delete_permanently
                        )
                    )
                }
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            StatsBar(state)
            when {
                state.scanning -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.orphans.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.orphan_images_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.orphans, key = { it.file.absolutePath }) { orphan ->
                        OrphanRow(
                            orphan = orphan,
                            checked = orphan.file.absolutePath in state.selected,
                            onToggle = { viewModel.toggleSelect(orphan.file.absolutePath) }
                        )
                    }
                }
            }
        }
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.orphan_images_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.trashEnabled) R.string.orphan_images_confirm_trash
                        else R.string.orphan_images_confirm_delete,
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
}

/** 顶部统计条：扫描进度 / 汇总数量与体积 + 未扫描笔记提示。 */
@Composable
private fun StatsBar(state: OrphanUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.scanning) {
            Text(
                text = stringResource(
                    R.string.orphan_images_scanning,
                    state.scannedNotes,
                    state.totalNotes
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = stringResource(R.string.orphan_images_summary, state.orphans.size, formatSize(state.totalBytes)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (!state.scanning && state.unscannedNotes > 0) {
            Text(
                text = stringResource(R.string.orphan_images_unscanned, state.unscannedNotes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 单行：缩略图 + 相对路径 + 体积/时间 + 多选勾选框。 */
@Composable
private fun OrphanRow(orphan: OrphanFile, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AsyncImage(
            model = Uri.fromFile(orphan.file),
            contentDescription = orphan.relativePath,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = orphan.relativePath,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatSize(orphan.size)} · ${formatDate(orphan.lastModified)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
