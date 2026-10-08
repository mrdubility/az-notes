package com.az.notes.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import com.az.notes.ui.common.label
import com.az.notes.ui.components.MoveTargetDialog
import com.az.notes.ui.components.SliderDialog
import com.az.notes.util.LocaleHelper
import kotlin.math.roundToInt

/**
 * 设置页（§5.6 重设计）：图标 + 标题 + 副标题的分组列表。
 * 单选类选项（主题 / 字体 / 排序 / 加号行为 / 回收站清理）→ 行旁浮层菜单，
 * 单手即可触达；字号 / 行间距 / 预览字符数 → 滑杆对话框；
 * 动态取色 → 开关；仓库组（仓库管理 / WebDAV 同步 / 接收分享位置）与回收站组各自独立。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenVaults: () -> Unit,
    onOpenToolbarSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenDebugLogs: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var fontSizeDialog by remember { mutableStateOf(false) }
    var lineHeightDialog by remember { mutableStateOf(false) }
    var previewDialog by remember { mutableStateOf(false) }
    var noteNameDialog by remember { mutableStateOf(false) }
    var trashDisableConfirm by remember { mutableStateOf(false) }
    var retentionDialog by remember { mutableStateOf(false) }
    var shareFolderDialog by remember { mutableStateOf(false) }
    var imageMaxMbDialog by remember { mutableStateOf(false) }
    var imageTimeoutDialog by remember { mutableStateOf(false) }
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

            // —— 仓库（切换 / 添加；WebDAV 同步与分享落点跟随仓库） ——
            item { SectionHeader(stringResource(R.string.settings_vault_section)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.vault_manage_title),
                    subtitle = settings.vaults.firstOrNull { it.id == settings.currentVaultId }
                        ?.name
                        ?.let { stringResource(R.string.settings_vault_subtitle_current, it) }
                        ?: stringResource(R.string.settings_vault_unset),
                    onClick = onOpenVaults
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.sync_title),
                    subtitle = stringResource(R.string.settings_sync_subtitle),
                    onClick = onSync
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

            // —— 回收站（独立分组；开关与自动清理） ——
            item { SectionHeader(stringResource(R.string.settings_trash_section)) }
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
                SettingsRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.settings_trash_retention),
                    subtitle = retentionLabel(settings.trashRetentionDays),
                    onClick = { retentionDialog = true }
                )
            }

            // —— 图片（导入压缩 / 附件提示 / 网络图片护栏） ——
            item { SectionHeader(stringResource(R.string.settings_image_section)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Image,
                    title = stringResource(R.string.settings_image_compress),
                    subtitle = stringResource(R.string.settings_image_compress_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.imageCompressEnabled,
                            onCheckedChange = viewModel::setImageCompressEnabled
                        )
                    },
                    onClick = { viewModel.setImageCompressEnabled(!settings.imageCompressEnabled) }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.settings_image_attachment_prompt),
                    subtitle = stringResource(R.string.settings_image_attachment_prompt_subtitle),
                    trailing = {
                        Switch(
                            checked = settings.attachmentPromptEnabled,
                            onCheckedChange = viewModel::setAttachmentPromptEnabled
                        )
                    },
                    onClick = { viewModel.setAttachmentPromptEnabled(!settings.attachmentPromptEnabled) }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    title = stringResource(R.string.settings_image_remote_max),
                    subtitle = stringResource(
                        if (settings.remoteImageMaxMb <= 0) R.string.settings_image_remote_max_zero
                        else R.string.settings_image_remote_max_value,
                        settings.remoteImageMaxMb
                    ),
                    onClick = { imageMaxMbDialog = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(R.string.settings_image_timeout),
                    subtitle = stringResource(R.string.settings_image_timeout_value, settings.remoteImageTimeoutSeconds),
                    onClick = { imageTimeoutDialog = true }
                )
            }

            // —— 备份（导出 / 导入全部配置；置于「关于」上方） ——
            item { SectionHeader(stringResource(R.string.settings_backup_section)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    title = stringResource(R.string.backup_title),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    onClick = onOpenBackup
                )
            }

            // —— 关于 ——
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.settings_about_version, versionName),
                    onClick = onOpenDebugLogs
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

    // 回收站自动清理天数：连续滑块可选任意天数（0 = 永不清理）
    if (retentionDialog) {
        SliderDialog(
            title = stringResource(R.string.settings_trash_retention),
            value = settings.trashRetentionDays.toFloat(),
            valueRange = 0f..180f,
            valueText = { days -> retentionLabel(days.roundToInt()) },
            onConfirm = {
                viewModel.setTrashRetentionDays(it.roundToInt())
                retentionDialog = false
            },
            onDismiss = { retentionDialog = false }
        )
    }

    // 网络图片体积上限（MB）：0 = 不加载网络图片（与 Repository 收敛区间 0..1024 一致）
    if (imageMaxMbDialog) {
        SliderDialog(
            title = stringResource(R.string.settings_image_remote_max),
            value = settings.remoteImageMaxMb.toFloat(),
            valueRange = 0f..AppSettings.REMOTE_IMAGE_MAX_MB_LIMIT.toFloat(),
            steps = AppSettings.REMOTE_IMAGE_MAX_MB_LIMIT - 1,
            valueText = { mb ->
                val v = mb.roundToInt()
                if (v <= 0) stringResource(R.string.settings_image_remote_max_zero)
                else stringResource(R.string.settings_image_remote_max_value, v)
            },
            hint = stringResource(R.string.settings_image_remote_max_hint),
            onConfirm = {
                viewModel.setRemoteImageMaxMb(it.roundToInt())
                imageMaxMbDialog = false
            },
            onDismiss = { imageMaxMbDialog = false }
        )
    }

    // 网络图片读取超时（秒）：限定 1..30
    if (imageTimeoutDialog) {
        SliderDialog(
            title = stringResource(R.string.settings_image_timeout),
            value = settings.remoteImageTimeoutSeconds.toFloat(),
            valueRange = AppSettings.REMOTE_IMAGE_TIMEOUT_RANGE.first.toFloat()..
                AppSettings.REMOTE_IMAGE_TIMEOUT_RANGE.last.toFloat(),
            steps = AppSettings.REMOTE_IMAGE_TIMEOUT_RANGE.last - AppSettings.REMOTE_IMAGE_TIMEOUT_RANGE.first - 1,
            valueText = { s -> stringResource(R.string.settings_image_timeout_value, s.roundToInt()) },
            onConfirm = {
                viewModel.setRemoteImageTimeoutSeconds(it.roundToInt())
                imageTimeoutDialog = false
            },
            onDismiss = { imageTimeoutDialog = false }
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

/** 项目仓库地址（关于区展示，点击打开浏览器）。 */
private const val GITHUB_URL = "https://github.com/mrdubility/az-notes"
