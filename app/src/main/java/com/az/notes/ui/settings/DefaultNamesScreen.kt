package com.az.notes.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.AppSettings

/** 默认名编辑对象：三种新建场景（新建笔记 / 接收分享 / AI 对话导出）。 */
private enum class DefaultNameKind { NEW_NOTE, SHARE, AI_EXPORT }

/**
 * 默认文件名设置页：分别设置新建笔记 / 接收分享笔记 / AI 对话导出的默认名模板。
 * 模板中 `$...$` 包裹片段按日期变量解析（如 `$yyyyMMdd-HHmmss$` → 当前时间戳），
 * `$` 之外字符原样保留；生成时重名自动追加序号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultNamesScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DefaultNameKind?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_default_note_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())) {
            SettingsRow(
                icon = Icons.Outlined.Edit,
                title = stringResource(R.string.default_names_new_note),
                subtitle = settings.defaultNoteName,
                onClick = { editing = DefaultNameKind.NEW_NOTE }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.default_names_share),
                subtitle = settings.shareNoteName,
                onClick = { editing = DefaultNameKind.SHARE }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                icon = Icons.Outlined.SmartToy,
                title = stringResource(R.string.default_names_ai_export),
                subtitle = settings.aiExportNoteName,
                onClick = { editing = DefaultNameKind.AI_EXPORT }
            )
            // 日期变量语法说明（与编辑对话框 hint 一致）
            Text(
                text = stringResource(R.string.settings_default_note_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }

    // 编辑所选模板；确认后写回设置（空值回退默认模板，由仓库层兜底）
    editing?.let { kind ->
        TextInputDialog(
            title = stringResource(kind.titleRes()),
            initial = kind.current(settings),
            label = stringResource(R.string.dialog_note_name_label),
            hint = stringResource(R.string.settings_default_note_name_hint),
            onConfirm = { value ->
                kind.applyTo(viewModel, value)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

private fun DefaultNameKind.titleRes(): Int = when (this) {
    DefaultNameKind.NEW_NOTE -> R.string.default_names_new_note
    DefaultNameKind.SHARE -> R.string.default_names_share
    DefaultNameKind.AI_EXPORT -> R.string.default_names_ai_export
}

private fun DefaultNameKind.current(settings: AppSettings): String = when (this) {
    DefaultNameKind.NEW_NOTE -> settings.defaultNoteName
    DefaultNameKind.SHARE -> settings.shareNoteName
    DefaultNameKind.AI_EXPORT -> settings.aiExportNoteName
}

private fun DefaultNameKind.applyTo(viewModel: SettingsViewModel, value: String) {
    when (this) {
        DefaultNameKind.NEW_NOTE -> viewModel.setDefaultNoteName(value)
        DefaultNameKind.SHARE -> viewModel.setShareNoteName(value)
        DefaultNameKind.AI_EXPORT -> viewModel.setAiExportNoteName(value)
    }
}
