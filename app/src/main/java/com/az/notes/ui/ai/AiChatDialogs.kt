package com.az.notes.ui.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.ai.AiProvider

/**
 * 对话页弹层（§9.2，B2 版）：
 * - [ModelPickerMenu] 锚点 DropdownMenu（项目约定禁用 ModalBottomSheet）：
 *   按供应商分组列出全部模型，当前项打勾；未配置模型的供应商显示引导行
 * - [ClearSessionConfirm] 新会话（清空会话）二次确认
 */

/** 模型选择菜单（锚定顶栏副标题处）。 */
@Composable
internal fun ModelPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    providers: List<AiProvider>,
    selection: ChatSelection?,
    onSelect: (providerId: String, modelId: String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (providers.isEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_chat_no_provider)) },
                onClick = {},
                enabled = false
            )
            return@DropdownMenu
        }
        providers.forEachIndexed { index, provider ->
            if (index > 0) HorizontalDivider()
            // 供应商分组标题
            Text(
                text = provider.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (provider.models.isEmpty()) {
                // 该供应商未配置模型：引导回供应商管理页补充（菜单项禁用仅作提示）
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.ai_provider_no_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {},
                    enabled = false
                )
            } else {
                provider.models.forEach { model ->
                    val checked = selection?.providerId == provider.id &&
                        selection.modelId == model.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model.label,
                                color = if (checked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        trailingIcon = {
                            if (checked) Icon(Icons.Filled.Check, null)
                        },
                        onClick = { onSelect(provider.id, model.id) }
                    )
                }
            }
        }
    }
}

/** 新会话二次确认（仅在有消息时由 VM 触发显示）。 */
@Composable
internal fun ClearSessionConfirm(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_chat_new_session)) },
        text = { Text(stringResource(R.string.ai_chat_new_session_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ai_chat_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
