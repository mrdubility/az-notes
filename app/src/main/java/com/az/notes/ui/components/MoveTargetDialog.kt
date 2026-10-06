package com.az.notes.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.model.FileNode

/**
 * 「移动到文件夹」对话框（主页多选移动 / 编辑页移动 / 设置页分享文件夹共用）：
 * 列出 Vault 根目录与全部子目录（按层级缩进）；[targets] 为 null 时显示加载中；
 * 点击目标即回调 [onSelect]（对话框由调用方关闭）。
 */
@Composable
fun MoveTargetDialog(
    vaultPath: String?,
    targets: List<FileNode>?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    title: String = stringResource(R.string.home_move_title)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
