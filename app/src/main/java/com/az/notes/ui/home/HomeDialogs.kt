package com.az.notes.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.az.notes.R
import com.az.notes.domain.model.FileNode

/**
 * 主页对话框与小工具：删除确认 / 新建文件夹 / 重命名输入框初始值。
 * 自 HomeScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

@Composable
internal fun DeleteDialog(
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
