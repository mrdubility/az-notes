package com.az.notes.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode

/**
 * 设置页通用控件：分组标题 / 设置行 / 单选行（浮层菜单）/ 文本输入对话框 / 选项文案映射。
 * 自 SettingsScreen 拆分独立文件（纯 UI，逻辑不变）；滑杆对话框已迁至 ui/components 共用。
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
internal fun SettingsRow(
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
internal fun <T> SettingsChoiceRow(
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
internal fun TextInputDialog(
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

@Composable
internal fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)

@Composable
internal fun FontFamilyPreference.label(): String = stringResource(
    when (this) {
        FontFamilyPreference.SANS -> R.string.settings_font_sans
        FontFamilyPreference.SERIF -> R.string.settings_font_serif
        FontFamilyPreference.MONO -> R.string.settings_font_mono
    }
)

@Composable
internal fun NoteSortOrder.label(): String = stringResource(
    when (this) {
        NoteSortOrder.MODIFIED_DESC -> R.string.sort_modified_desc
        NoteSortOrder.MODIFIED_ASC -> R.string.sort_modified_asc
        NoteSortOrder.NAME_ASC -> R.string.sort_name_asc
        NoteSortOrder.NAME_DESC -> R.string.sort_name_desc
    }
)

@Composable
internal fun FabAction.label(): String = stringResource(
    when (this) {
        FabAction.NEW_NOTE -> R.string.settings_fab_action_new_note
        FabAction.NEW_TASK -> R.string.settings_fab_action_new_task
        FabAction.NEW_FOLDER -> R.string.settings_fab_action_new_folder
        FabAction.SHOW_MENU -> R.string.settings_fab_action_menu
    }
)

@Composable
internal fun AppLanguage.label(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.ZH -> R.string.settings_language_zh
        AppLanguage.EN -> R.string.settings_language_en
    }
)

/** 回收站自动清理说明：0 = 永不清理，其余显示天数。 */
@Composable
internal fun retentionLabel(days: Int): String =
    if (days <= 0) stringResource(R.string.settings_trash_retention_never)
    else stringResource(R.string.settings_trash_retention_value, days)
