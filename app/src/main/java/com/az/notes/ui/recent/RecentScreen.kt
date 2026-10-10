package com.az.notes.ui.recent

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.FileNode
import com.az.notes.ui.common.formatDateTime
import com.az.notes.ui.common.resolve
import com.az.notes.ui.common.showTimedSnackbar

/**
 * 最近查看页：跨文件夹展示最近打开（查看 / 编辑）过的笔记（相对路径解析，
 * 移动 / 改名后仍对应原始文档）；点击按默认模式进入预览页 / 编辑页，
 * 右侧 × 移除单条记录（横幅可撤销），右上角「清空」清空全部（横幅可撤销）。
 * 记录仅本地有效、按仓库隔离，上限 50 条滚动存储（超出淘汰最旧）。
 * 条目含正文预览（列表页同款头部小字节读取）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    viewModel: RecentViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 一次性提示（移除 / 清空记录）；携带撤销动作时显示横幅与「撤销」按钮，
    // 统一限时 5 秒后自动消失（超时视为放弃撤销）
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        val result = snackbarHostState.showTimedSnackbar(
            message = msg.text.resolve(context),
            actionLabel = if (msg.undo != null) undoLabel else null
        )
        if (result == SnackbarResult.ActionPerformed) msg.undo?.invoke()
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 标题 + 当前仓库标注（记录仅本地，且按仓库隔离）
                    Column {
                        Text(stringResource(R.string.recents_title))
                        state.vaultName?.let { name ->
                            Text(
                                text = stringResource(R.string.vault_label, name),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // 右上角清空：清空全部记录（空列表时禁用）
                    IconButton(onClick = viewModel::clearAll, enabled = state.items.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.recents_clear))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center
            ) {
                EmptyRecents()
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { it.node.relativePath }) { item ->
                    RecentRow(
                        item = item,
                        onOpen = { onOpen(item.node.absolutePath) },
                        onRemove = { viewModel.remove(item) }
                    )
                }
            }
        }
    }
}

/** 单条：标题 + 正文预览 + 所在文件夹（相对 Vault） + 修改时间；点击进入，× 移除记录。 */
@Composable
private fun RecentRow(
    item: RecentItem,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val node = item.node
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle(node),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = folderLabel(node.relativePath),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatDateTime(node.lastModified),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.recents_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyRecents() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recents_empty),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.recents_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/** 所在文件夹文案：取相对路径的父目录；位于 Vault 根时显示占位。 */
@Composable
private fun folderLabel(relativePath: String): String {
    val folder = relativePath.substringBeforeLast('/', "")
    return if (folder.isEmpty()) stringResource(R.string.fav_in_root) else folder
}

/** 列表展示标题：笔记去掉 .md 扩展名。 */
private fun displayTitle(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name
