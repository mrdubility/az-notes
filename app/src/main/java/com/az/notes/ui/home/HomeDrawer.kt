package com.az.notes.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
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
 * 主页抽屉内容：仓库信息（名称 + 路径） + 收藏夹 / 最近查看 / 图库 / 回收站（关闭时隐藏入口）
 * / 设置入口。自 HomeScreen 拆分独立文件（纯 UI，逻辑不变）。
 */
@Composable
internal fun DrawerContent(
    vaultName: String?,
    vaultPath: String?,
    /** 回收站开关：关闭时隐藏入口（删除即物理删除） */
    trashEnabled: Boolean,
    onOpenAiChat: () -> Unit,
    onFavorites: () -> Unit,
    onRecent: () -> Unit,
    onGallery: () -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            if (vaultName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = vaultName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (vaultPath != null) {
                Spacer(Modifier.height(2.dp))
                // 路径完整展示（超长自动换行，不截断）
                Text(
                    text = vaultPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.ai_chat_title)) },
            icon = { Icon(Icons.Outlined.SmartToy, null) },
            selected = false,
            onClick = onOpenAiChat,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.fav_title)) },
            icon = { Icon(Icons.Outlined.Star, null) },
            selected = false,
            onClick = onFavorites,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.recents_title)) },
            icon = { Icon(Icons.Outlined.History, null) },
            selected = false,
            onClick = onRecent,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.gallery_title)) },
            icon = { Icon(Icons.Outlined.Image, null) },
            selected = false,
            onClick = onGallery,
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
