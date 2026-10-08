package com.az.notes.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.data.ai.AiTestResult
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.ui.common.resolve

/**
 * 供应商管理页组件（对齐 ui/settings/SettingsControls 的拆分与交互惯例）：
 * 列表行 / 底部添加行（模板菜单）/ 协议选择行 / 模型编辑行 / 测试连接行。
 * 选项类弹窗一律用锚点 DropdownMenu（禁用 ModalBottomSheet），偏移 56dp 避免紧贴屏幕左缘。
 */

/** 单条供应商：点击进入编辑；⋮ 菜单（测试连接 / 编辑 / 删除）。 */
@Composable
internal fun ProviderRow(
    provider: AiProvider,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                ProviderBadge(text = provider.protocol.label())
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = provider.baseUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (provider.models.isEmpty()) {
                    stringResource(R.string.ai_provider_no_models)
                } else {
                    stringResource(R.string.ai_provider_model_count, provider.models.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_provider_test)) },
                    onClick = {
                        menuOpen = false
                        onTest()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** 供应商徽标（协议名）：小号标签，跟随主题色。 */
@Composable
private fun ProviderBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** 底部入口：添加供应商（弹出模板菜单：平台模板 + 空白配置；模板不预填模型 ID）。 */
@Composable
internal fun AddProviderRow(onPick: (AiProviderPreset) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.ai_provider_add),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ai_provider_add_template),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 56.dp, y = 0.dp)
        ) {
            aiProviderPresets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(stringResource(preset.nameResId)) },
                    onClick = {
                        menuOpen = false
                        onPick(preset)
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(blankProviderPreset.nameResId)) },
                onClick = {
                    menuOpen = false
                    onPick(blankProviderPreset)
                }
            )
        }
    }
}

/** 空状态：未配置任何供应商时引导添加。 */
@Composable
internal fun EmptyProviders() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.ai_provider_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 协议选择行：点击在行旁弹出浮层菜单（三选一），选中项显示对勾。 */
@Composable
internal fun ProtocolSelector(
    selected: AiProtocol,
    onSelect: (AiProtocol) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ai_provider_protocol),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = selected.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 56.dp, y = 0.dp)
        ) {
            AiProtocol.entries.forEach { protocol ->
                DropdownMenuItem(
                    text = { Text(protocol.label()) },
                    trailingIcon = {
                        if (protocol == selected) {
                            Icon(
                                Icons.Filled.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(protocol)
                    }
                )
            }
        }
    }
}

/** 模型编辑行：模型 ID + 支持图片开关 + 删除。 */
@Composable
internal fun ModelEditRow(
    model: AiModelDraft,
    onIdChange: (String) -> Unit,
    onVisionChange: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = model.id,
            onValueChange = onIdChange,
            label = { Text(stringResource(R.string.ai_provider_model_id)) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.ai_provider_vision),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(checked = model.vision, onCheckedChange = onVisionChange)
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.ai_provider_remove_model),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 测试连接行：按钮（测试中转圈）+ 内联结果（成功主题色 / 失败错误色）。 */
@Composable
internal fun ConnectionTestRow(
    testing: Boolean,
    result: AiTestResult?,
    onTest: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onTest, enabled = !testing) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_provider_testing))
            } else {
                Text(stringResource(R.string.ai_provider_test))
            }
        }
        result?.let {
            Spacer(Modifier.height(6.dp))
            val success = it is AiTestResult.Success
            Text(
                text = AiProviderViewModel.resultText(it).resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = if (success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 「添加模型」行。 */
@Composable
internal fun AddModelRow(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.ai_provider_add_model))
    }
}

/** 协议显示名（表单选择与列表徽标共用）。 */
@Composable
internal fun AiProtocol.label(): String = stringResource(
    when (this) {
        AiProtocol.OPENAI_CHAT -> R.string.ai_protocol_openai_chat
        AiProtocol.OPENAI_RESPONSES -> R.string.ai_protocol_openai_responses
        AiProtocol.ANTHROPIC_MESSAGES -> R.string.ai_protocol_anthropic
    }
)
