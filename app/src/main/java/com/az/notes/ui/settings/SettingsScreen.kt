package com.az.notes.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode

/**
 * 设置页（§5.6 重设计）：图标 + 标题 + 副标题的分组列表。
 * 主题 / 字体 / 排序 → 底部弹窗单选；字号 / 行间距 / 预览字符数 → 滑杆对话框；
 * 动态取色 → 开关；同步、Vault 更换目录 → 对应入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onChangeVault: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var themeSheet by remember { mutableStateOf(false) }
    var fontSheet by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }
    var fontSizeDialog by remember { mutableStateOf(false) }
    var lineHeightDialog by remember { mutableStateOf(false) }
    var previewDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // —— 外观 ——
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.settings_theme),
                    subtitle = settings.themeMode.label(),
                    onClick = { themeSheet = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor
                        )
                    },
                    onClick = { viewModel.setDynamicColor(!settings.dynamicColor) }
                )
            }

            // —— 编辑器和查看器 ——
            item { SectionHeader(stringResource(R.string.settings_editor_viewer)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.TextFormat,
                    title = stringResource(R.string.settings_font_family),
                    subtitle = settings.fontFamily.label(),
                    onClick = { fontSheet = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.FormatSize,
                    title = stringResource(R.string.settings_font_size),
                    subtitle = stringResource(R.string.settings_font_size_value, settings.fontSizeSp.toInt()),
                    onClick = { fontSizeDialog = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.FormatLineSpacing,
                    title = stringResource(R.string.settings_line_height),
                    subtitle = stringResource(R.string.settings_line_height_value, settings.lineHeightRatio),
                    onClick = { lineHeightDialog = true }
                )
            }

            // —— 笔记列表 ——
            item { SectionHeader(stringResource(R.string.settings_notes_list)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Notes,
                    title = stringResource(R.string.settings_preview_chars),
                    subtitle = stringResource(R.string.settings_preview_chars_value, settings.previewChars),
                    onClick = { previewDialog = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.Sort,
                    title = stringResource(R.string.settings_sort_order),
                    subtitle = settings.sortOrder.label(),
                    onClick = { sortSheet = true }
                )
            }

            // —— 同步 ——
            item { SectionHeader(stringResource(R.string.settings_sync)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.sync_title),
                    subtitle = stringResource(R.string.settings_sync_subtitle),
                    onClick = onSync
                )
            }

            // —— Vault ——
            item { SectionHeader(stringResource(R.string.settings_vault)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.settings_vault),
                    subtitle = settings.vaultPath?.let {
                        stringResource(R.string.settings_vault_subtitle_current, it)
                    } ?: stringResource(R.string.settings_vault_unset),
                    trailing = {
                        TextButton(onClick = {
                            viewModel.resetVault()
                            onChangeVault()
                        }) {
                            Text(stringResource(R.string.settings_change_vault))
                        }
                    }
                )
            }

            // —— 关于 ——
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.settings_about_subtitle)
                )
            }
        }
    }

    if (themeSheet) {
        ChoiceSheet(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to it.label() },
            selected = settings.themeMode,
            onSelect = {
                viewModel.setThemeMode(it)
                themeSheet = false
            },
            onDismiss = { themeSheet = false }
        )
    }

    if (fontSheet) {
        ChoiceSheet(
            title = stringResource(R.string.settings_font_family),
            options = FontFamilyPreference.entries.map { it to it.label() },
            selected = settings.fontFamily,
            onSelect = {
                viewModel.setFontFamily(it)
                fontSheet = false
            },
            onDismiss = { fontSheet = false }
        )
    }

    if (sortSheet) {
        ChoiceSheet(
            title = stringResource(R.string.settings_sort_order),
            options = NoteSortOrder.entries.map { it to it.label() },
            selected = settings.sortOrder,
            onSelect = {
                viewModel.setSortOrder(it)
                sortSheet = false
            },
            onDismiss = { sortSheet = false }
        )
    }

    if (fontSizeDialog) {
        SliderDialog(
            title = stringResource(R.string.settings_font_size),
            value = settings.fontSizeSp,
            valueRange = 12f..24f,
            steps = 11,
            valueText = { stringResource(R.string.settings_font_size_value, it.toInt()) },
            onConfirm = {
                viewModel.setFontSize(it)
                fontSizeDialog = false
            },
            onDismiss = { fontSizeDialog = false }
        )
    }

    if (lineHeightDialog) {
        SliderDialog(
            title = stringResource(R.string.settings_line_height),
            value = settings.lineHeightRatio,
            valueRange = 1.2f..2.0f,
            valueText = { stringResource(R.string.settings_line_height_value, it) },
            onConfirm = {
                viewModel.setLineHeight(it)
                lineHeightDialog = false
            },
            onDismiss = { lineHeightDialog = false }
        )
    }

    if (previewDialog) {
        SliderDialog(
            title = stringResource(R.string.settings_preview_chars),
            value = settings.previewChars.toFloat(),
            valueRange = 20f..300f,
            steps = 13,
            valueText = { stringResource(R.string.settings_preview_chars_value, it.toInt()) },
            hint = stringResource(R.string.settings_preview_range_hint),
            onConfirm = {
                viewModel.setPreviewChars(it.toInt())
                previewDialog = false
            },
            onDismiss = { previewDialog = false }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

/** 底部弹窗单选（主题 / 字体族 / 排序方式）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (value == selected) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 滑杆对话框（字号 / 行间距 / 预览字符数）。 */
@Composable
private fun SliderDialog(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueText: @Composable (Float) -> String,
    hint: String? = null,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = valueText(draft),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    valueRange = valueRange,
                    steps = steps
                )
                hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
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

@Composable
private fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)

@Composable
private fun FontFamilyPreference.label(): String = stringResource(
    when (this) {
        FontFamilyPreference.SANS -> R.string.settings_font_sans
        FontFamilyPreference.SERIF -> R.string.settings_font_serif
        FontFamilyPreference.MONO -> R.string.settings_font_mono
    }
)

@Composable
private fun NoteSortOrder.label(): String = stringResource(
    when (this) {
        NoteSortOrder.MODIFIED_DESC -> R.string.sort_modified_desc
        NoteSortOrder.MODIFIED_ASC -> R.string.sort_modified_asc
        NoteSortOrder.NAME_ASC -> R.string.sort_name_asc
        NoteSortOrder.NAME_DESC -> R.string.sort_name_desc
    }
)
