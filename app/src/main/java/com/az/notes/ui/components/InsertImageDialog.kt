package com.az.notes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.az.notes.R

/**
 * 插入图片对话框：两个入口——
 * 1. 从设备选择（走系统图片选择器，导入到笔记同目录 `assets/`，受压缩开关控制）；
 * 2. 输入链接或相对路径（直接插入 `![](输入值)`，覆盖网络图片 / 已有本地路径场景）。
 */
@Composable
fun InsertImageDialog(
    onDismiss: () -> Unit,
    onPickDevice: () -> Unit,
    onInsertLink: (String) -> Unit
) {
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_insert_image_title)) },
        text = {
            Column {
                Button(
                    onClick = { onPickDevice() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.editor_insert_image_pick))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.editor_insert_image_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_insert_image_url_label)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = link.isNotBlank(),
                onClick = { onInsertLink(link.trim()) }
            ) {
                Text(stringResource(R.string.editor_insert_image_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
