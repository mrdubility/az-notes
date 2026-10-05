package com.az.notes.ui.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.FileNode

/**
 * Drawer 内目录树内容（§5.1）。LazyColumn 扁平化渲染，缩进表达层级，
 * 目录在上、按名称排序；md 与附件均可点开。
 */
@Composable
fun TreeContent(
    viewModel: TreeViewModel,
    onOpenFile: (FileNode) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.onSearch(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("搜索文件名") }
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }
            state.searchQuery.isNotEmpty() -> SearchResults(state.searchResults, onOpenFile)
            else -> TreeList(state.rows, onToggle = viewModel::toggle, onOpenFile = onOpenFile)
        }
    }
}

@Composable
private fun TreeList(
    rows: List<FileNode>,
    onToggle: (String) -> Unit,
    onOpenFile: (FileNode) -> Unit
) {
    if (rows.isEmpty()) {
        EmptyHint()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(rows, key = { it.relativePath }) { node ->
            TreeRow(
                node = node,
                onToggle = { onToggle(node.relativePath) },
                onOpen = { onOpenFile(node) }
            )
        }
    }
}

@Composable
private fun SearchResults(results: List<FileNode>, onOpenFile: (FileNode) -> Unit) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("无匹配文件")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(results, key = { it.relativePath }) { node ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(node) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileIcon(node)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        node.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        node.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeRow(node: FileNode, onToggle: () -> Unit, onOpen: () -> Unit) {
    val indent = (node.depth * 16).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .clickable { if (node.isDirectory) onToggle() else onOpen() }
            .padding(end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                Icons.Outlined.ChevronRight, contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(24.dp))
        }
        FileIcon(node)
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FileIcon(node: FileNode) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        node.isDirectory -> Icon(
            Icons.Outlined.Folder, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        node.isMarkdown -> Icon(
            Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = tint
        )
        node.isAttachment -> Icon(Icons.Outlined.Image, contentDescription = null, tint = tint)
        else -> Icon(Icons.Outlined.Checklist, contentDescription = null, tint = tint)
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.tree_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
