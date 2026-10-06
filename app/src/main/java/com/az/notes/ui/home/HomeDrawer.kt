package com.az.notes.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R

/**
 * 主页抽屉内容：Vault 信息 + 收藏夹 / 回收站（关闭时隐藏入口） / 设置入口。
 * 自 HomeScreen 拆分独立文件（纯 UI，逻辑不变）。
 */
@Composable
internal fun DrawerContent(
    vaultPath: String?,
    /** 回收站开关：关闭时隐藏入口（删除即物理删除） */
    trashEnabled: Boolean,
    onFavorites: () -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            val vaultName = vaultPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            if (vaultName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vaultName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.fav_title)) },
            icon = { Icon(Icons.Outlined.Star, null) },
            selected = false,
            onClick = onFavorites,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (trashEnabled) {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.trash_title)) },
                icon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                selected = false,
                onClick = onTrash,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.action_settings)) },
            icon = { Icon(Icons.Outlined.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}
