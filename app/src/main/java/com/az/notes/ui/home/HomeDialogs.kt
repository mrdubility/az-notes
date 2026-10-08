package com.az.notes.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.data.media.AttachmentRepository
import com.az.notes.domain.model.FileNode

/**
 * 主页对话框与小工具：删除确认 / 新建文件夹 / 重命名输入框初始值。
 * 自 HomeScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

@Composable
internal fun DeleteDialog(
    node: FileNode,
    referenced: AttachmentRepository.ReferencedAttachments?,
    onDismiss: () -> Unit,
    onConfirm: (alsoTrashAttachments: Boolean) -> Unit
) {
    // 仅当存在「只被本文引用」的图片时才允许勾选联动清理；默认勾选，删除笔记时一并清理独占图片
    var trashAttachments by remember { mutableStateOf(true) }
    val hasExclusive = referenced?.hasExclusive == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_delete)) },
        text = {
            Column {
                Text(
                    if (node.isDirectory) {
                        stringResource(R.string.delete_folder_message, node.name)
                    } else {
                        stringResource(R.string.delete_note_message, node.name)
                    }
                )
                if (referenced != null && referenced.total > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.delete_note_attachments,
                            referenced.total,
                            referenced.exclusiveCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (hasExclusive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { trashAttachments = !trashAttachments }
                                .height(44.dp)
                        ) {
                            Checkbox(
                                checked = trashAttachments,
                                onCheckedChange = { trashAttachments = it }
                            )
                            Text(stringResource(R.string.delete_note_trash_attachments))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hasExclusive && trashAttachments) }) {
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
internal fun NewFolderDialog(
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

/** 重命名输入框初始值：笔记显示不含扩展名的名称。 */
internal fun editableName(node: FileNode): String =
    if (node.isMarkdown) node.name.substringBeforeLast('.') else node.name
