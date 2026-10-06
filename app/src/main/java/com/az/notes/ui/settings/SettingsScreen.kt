package com.az.notes.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import com.az.notes.ui.components.MoveTargetDialog
import com.az.notes.util.LocaleHelper

/**
 * 设置页（§5.6 重设计）：图标 + 标题 + 副标题的分组列表。
 * 单选类选项（主题 / 字体 / 排序 / 加号行为 / 回收站清理）→ 行旁浮层菜单，
 * 单手即可触达；字号 / 行间距 / 预览字符数 → 滑杆对话框；
 * 动态取色 → 开关；同步、Vault 更换目录 → 对应入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onChangeVault: () -> Unit,
    onOpenToolbarSettings: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var fontSizeDialog by remember { mutableStateOf(false) }
    var lineHeightDialog by remember { mutableStateOf(false) }
    var previewDialog by remember { mutableStateOf(false) }
    var noteNameDialog by remember { mutableStateOf(false) }
    var trashDisableConfirm by remember { mutableStateOf(false) }
    var shareFolderDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val moveTargets by viewModel.moveTargets.collectAsStateWithLifecycle()
    // 当前版本号（关于区展示）：读取失败时留空
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

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
                SettingsChoiceRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.settings_theme),
                    options = ThemeMode.entries.map { it to it.label() },
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode
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
            item {
                SettingsChoiceRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.settings_language),
                    options = AppLanguage.entries.map { it to it.label() },
                    selected = settings.language,
                    onSelect = { language ->
                        // 镜像先同步写入（attachBaseContext 阶段同步读取），再落偏好并重建 Activity
                        LocaleHelper.persist(context, language.tag)
                        viewModel.setLanguage(language)
                        (context as? Activity)?.recreate()
                    }
                )
            }

            // —— 编辑器和查看器 ——
            item { SectionHeader(stringResource(R.string.settings_editor_viewer)) }
            item {
                SettingsChoiceRow(
                    icon = Icons.Outlined.TextFormat,
                    title = stringResource(R.string.settings_font_family),
                    options = FontFamilyPreference.entries.map { it to it.label() },
                    selected = settings.fontFamily,
                    onSelect = viewModel::setFontFamily
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
            item {
                SettingsRow(
                    icon = Icons.Outlined.Build,
                    title = stringResource(R.string.settings_editor_toolbar),
                    subtitle = stringResource(R.string.settings_editor_toolbar_subtitle),
                    onClick = onOpenToolbarSettings
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
                    icon = Icons.Outlined.Edit,
                    title = stringResource(R.string.settings_default_note_name),
                    subtitle = stringResource(
                        R.string.settings_default_note_name_subtitle,
                        settings.defaultNoteName
                    ),
                    onClick = { noteNameDialog = true }
                )
            }
            item {
                SettingsChoiceRow(
                    icon = Icons.AutoMirrored.Outlined.Sort,
                    title = stringResource(R.string.settings_sort_order),
                    options = NoteSortOrder.entries.map { it to it.label() },
                    selected = settings.sortOrder,
                    onSelect = viewModel::setSortOrder
                )
            }
            item {
                SettingsChoiceRow(
                    icon = Icons.Outlined.Add,
                    title = stringResource(R.string.settings_fab_action),
                    options = FabAction.entries.map { it to it.label() },
                    selected = settings.fabAction,
                    onSelect = viewModel::setFabAction
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Share,
                    title = stringResource(R.string.settings_share_folder),
                    subtitle = settings.shareFolder?.let {
                        stringResource(R.string.settings_share_folder_current, it)
                    } ?: stringResource(R.string.settings_share_folder_root),
                    onClick = {
                        viewModel.loadMoveTargets()
                        shareFolderDialog = true
                    }
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
            item {
                SettingsRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.settings_trash_enabled),
                    subtitle = stringResource(
                        if (settings.trashEnabled) R.string.settings_trash_enabled_subtitle
                        else R.string.settings_trash_disabled_subtitle
                    ),
                    trailing = {
                        Switch(
                            checked = settings.trashEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) viewModel.setTrashEnabled(true)
                                else trashDisableConfirm = true // 关闭前二次确认（删除将不可恢复）
                            }
                        )
                    },
                    onClick = {
                        if (settings.trashEnabled) trashDisableConfirm = true
                        else viewModel.setTrashEnabled(true)
                    }
                )
            }
            item {
                SettingsChoiceRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.settings_trash_retention),
                    options = TRASH_RETENTION_OPTIONS.map { days ->
                        days to if (days <= 0) stringResource(R.string.settings_trash_retention_never)
                        else stringResource(R.string.settings_trash_retention_value, days)
                    },
                    selected = settings.trashRetentionDays,
                    onSelect = viewModel::setTrashRetentionDays
                )
            }

            // —— 关于 ——
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.settings_about_version, versionName)
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.OpenInNew,
                    title = stringResource(R.string.settings_about_github),
                    subtitle = GITHUB_URL,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                        }
                    }
                )
            }
        }
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

    if (noteNameDialog) {
        TextInputDialog(
            title = stringResource(R.string.settings_default_note_name),
            initial = settings.defaultNoteName,
            label = stringResource(R.string.dialog_note_name_label),
            hint = stringResource(R.string.settings_default_note_name_hint),
            onConfirm = {
                viewModel.setDefaultNoteName(it)
                noteNameDialog = false
            },
            onDismiss = { noteNameDialog = false }
        )
    }

    // 分享文件夹选择：列出 Vault 根与全部子目录（选择根目录即恢复默认）
    if (shareFolderDialog) {
        MoveTargetDialog(
            vaultPath = settings.vaultPath,
            targets = moveTargets,
            onDismiss = { shareFolderDialog = false },
            onSelect = { dir ->
                viewModel.setShareFolderAbsolute(dir)
                shareFolderDialog = false
            },
            title = stringResource(R.string.settings_share_folder)
        )
    }

    // 关闭回收站前二次确认：关闭后删除不可恢复
    if (trashDisableConfirm) {
        AlertDialog(
            onDismissRequest = { trashDisableConfirm = false },
            title = { Text(stringResource(R.string.settings_trash_disable_title)) },
            text = { Text(stringResource(R.string.settings_trash_disable_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTrashEnabled(false)
                    trashDisableConfirm = false
                }) {
                    Text(
                        stringResource(R.string.action_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trashDisableConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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

/**
 * 单选设置行：点击在行旁弹出浮层菜单（替代底部弹窗，单手更好操作），
 * 选中项显示对勾；点选后立即生效并收起。
 */
@Composable
private fun <T> SettingsChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = icon,
            title = title,
            subtitle = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 菜单向右偏移至图标右侧起始，避免紧贴屏幕左缘
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

/** 文本输入对话框（默认新建笔记名等；[hint] 显示格式说明）。 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    hint: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(label) }
                )
                hint?.let {
                    Spacer(Modifier.height(8.dp))
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

@Composable
private fun FabAction.label(): String = stringResource(
    when (this) {
        FabAction.NEW_NOTE -> R.string.settings_fab_action_new_note
        FabAction.NEW_TASK -> R.string.settings_fab_action_new_task
        FabAction.NEW_FOLDER -> R.string.settings_fab_action_new_folder
        FabAction.SHOW_MENU -> R.string.settings_fab_action_menu
    }
)

/** 项目仓库地址（关于区展示，点击打开浏览器）。 */
private const val GITHUB_URL = "https://github.com/mrdubility/az-notes"

/** 回收站自动清理天数候选（0 = 永不清理）。 */
private val TRASH_RETENTION_OPTIONS = listOf(7, 30, 90, 365, 0)

@Composable
private fun AppLanguage.label(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.ZH -> R.string.settings_language_zh
        AppLanguage.EN -> R.string.settings_language_en
    }
)
