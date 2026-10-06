package com.az.notes.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.EditorTool
import com.az.notes.ui.common.label

/**
 * 编辑器工具栏设置页：逐项开关 + 顺序调整 + 恢复默认。
 * 从设置页入口进入（内容较长，独立成页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_editor_toolbar)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        // 持久化顺序可能缺少新版本追加的工具：补齐到末尾（默认启用）
        val toolOrder = remember(settings.editorToolOrder) {
            settings.editorToolOrder + EditorTool.defaultOrder.filter { it !in settings.editorToolOrder }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_editor_toolbar_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                )
            }
            toolOrder.forEachIndexed { index, id ->
                val tool = EditorTool.fromId(id) ?: return@forEachIndexed
                item(key = "tool_$id") {
                    ToolConfigRow(
                        label = tool.label(),
                        enabled = id !in settings.editorToolDisabled,
                        canMoveUp = index > 0,
                        canMoveDown = index < toolOrder.lastIndex,
                        onToggle = { enabled ->
                            val disabled = settings.editorToolDisabled.toMutableSet()
                            if (enabled) disabled.remove(id) else disabled.add(id)
                            viewModel.setEditorToolDisabled(disabled)
                        },
                        onMoveUp = {
                            val list = toolOrder.toMutableList()
                            val tmp = list[index - 1]
                            list[index - 1] = list[index]
                            list[index] = tmp
                            viewModel.setEditorToolOrder(list)
                        },
                        onMoveDown = {
                            val list = toolOrder.toMutableList()
                            val tmp = list[index + 1]
                            list[index + 1] = list[index]
                            list[index] = tmp
                            viewModel.setEditorToolOrder(list)
                        }
                    )
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = {
                        viewModel.setEditorToolOrder(EditorTool.defaultOrder)
                        viewModel.setEditorToolDisabled(emptySet())
                    }) {
                        Text(stringResource(R.string.settings_editor_toolbar_reset))
                    }
                }
            }
        }
    }
}

/** 工具配置行：名称 + 上移 / 下移 + 启用开关。 */
@Composable
private fun ToolConfigRow(
    label: String,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                stringResource(R.string.settings_toolbar_move_up),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                stringResource(R.string.settings_toolbar_move_down),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
