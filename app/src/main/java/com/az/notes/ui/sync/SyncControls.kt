package com.az.notes.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.model.SyncConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步页通用控件与服务器配置区：分组标题 / 可点行 / 单选行 / 开关行 /
 * 服务器配置 / 过滤规则对话框 / 选项与时间文案。
 * 自 SyncScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
internal fun ClickableRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单选行：点击在行旁弹出浮层菜单（替代底部弹窗，单手更好操作），
 * 选中项显示对勾；点选后立即生效并收起。
 */
@Composable
internal fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ClickableRow(
            title = title,
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 菜单向右偏移，避免紧贴屏幕左缘
            offset = DpOffset(x = 56.dp, y = 0.dp)
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        if (value == selected) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

/** 带副标题的开关行（自动同步选项）。 */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------- 服务器配置

/**
 * 服务器区：地址 / 账号 / 应用密码 / 远端目录。
 * 文本字段以本地草稿为准（避免 DataStore 回流延迟导致快速输入丢字符），
 * 每次变更同步写入 ViewModel；进程重建后草稿回落到 DataStore 中的最新值。
 * 密码框使用 [PasswordVisualTransformation] 掩码。
 */
@Composable
internal fun ServerSection(
    state: SyncUiState,
    viewModel: SyncViewModel
) {
    var serverDraft by remember { mutableStateOf<String?>(null) }
    var usernameDraft by remember { mutableStateOf<String?>(null) }
    var remoteDirDraft by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = serverDraft ?: state.config.serverUrl,
            onValueChange = {
                serverDraft = it
                viewModel.updateServerUrl(it)
            },
            label = { Text(stringResource(R.string.sync_server_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = usernameDraft ?: state.config.username,
            onValueChange = {
                usernameDraft = it
                viewModel.updateUsername(it)
            },
            label = { Text(stringResource(R.string.sync_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text(stringResource(R.string.sync_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = remoteDirDraft ?: state.config.remoteDir,
            onValueChange = {
                remoteDirDraft = it
                viewModel.updateRemoteDir(it)
            },
            label = { Text(stringResource(R.string.sync_remote_dir)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                serverDraft = SyncConfig.DEFAULT_SERVER_URL
                viewModel.applyNutstorePreset()
            }) {
                Text(stringResource(R.string.sync_preset_nutstore))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = viewModel::testConnection,
                enabled = !state.busy
            ) {
                Text(stringResource(R.string.sync_test))
            }
        }
    }
}

/** 过滤规则编辑对话框（§6.3）：多行编辑 + 恢复默认。 */
@Composable
internal fun FilterEditDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_filter_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_filter_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { draft = SyncConfig.DEFAULT_IGNORE_RULES }) {
                    Text(stringResource(R.string.sync_filter_reset))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** 大文件上限展示文案：0 = 不限制。 */
@Composable
internal fun fileSizeLabel(mb: Int): String =
    if (mb <= 0) stringResource(R.string.sync_max_file_size_unlimited)
    else stringResource(R.string.sync_max_file_size_value, mb)

/**
 * 大文件上限输入对话框（§6.3）：整数 MB 自由输入，0 = 不限制。
 * 仅接受数字字符；超出范围时确认按钮禁用并给出提示。
 */
@Composable
internal fun MaxSizeInputDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial.toString()) }
    val parsed = draft.trim().toIntOrNull()
    val limit = SyncConfig.MAX_FILE_SIZE_MB_LIMIT
    val valid = parsed != null && parsed in 0..limit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_max_file_size_row)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { input -> draft = input.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.sync_max_file_size_input_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (valid) stringResource(R.string.sync_max_file_size_input_hint, limit)
                    else stringResource(R.string.sync_max_file_size_input_error, limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let(onConfirm) }) {
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

internal fun formatLogTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
