package com.az.notes.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.VaultInfo
import com.az.notes.ui.common.label

/**
 * 主页顶栏：抽屉/返回 + 标题（或搜索输入、多选计数）+ 同步/搜索/排序（或全选 + 批量操作菜单）。
 * 自 HomeScreen 拆分独立文件（纯 UI，逻辑不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTopBar(
    title: String,
    /** 当前仓库展示名（根目录标题，点按弹出仓库切换菜单） */
    vaultName: String,
    vaults: List<VaultInfo>,
    currentVaultId: String?,
    atRoot: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    sortOrder: NoteSortOrder,
    /** 多选模式：标题显示已选计数，操作区换为全选 / 移动 */
    selectMode: Boolean,
    selectedCount: Int,
    onOpenDrawer: () -> Unit,
    onNavigateUp: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortSelected: (NoteSortOrder) -> Unit,
    onSync: () -> Unit,
    /** 未处理的冲突记录数：> 0 时同步图标显示角标（§5.7）。 */
    conflictCount: Int,
    /** 是否有同步正在执行：执行中把同步图标替换为环形进度指示。 */
    syncActive: Boolean,
    onExitSelect: () -> Unit,
    onSelectAll: () -> Unit,
    onFavoriteSelected: () -> Unit,
    onMoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    /** 顶栏切换仓库（记忆为下次启动 / 分享的目标仓库） */
    onSwitchVault: (String) -> Unit,
    /** 仓库菜单 → 管理仓库页 */
    onManageVaults: () -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var selectMenuOpen by remember { mutableStateOf(false) }
    // 进入搜索时自动聚焦输入框（弹软键盘）
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // 等输入框完成组合挂载后再请求焦点
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
        }
    }

    TopAppBar(
        title = {
            when {
                selectMode -> Text(stringResource(R.string.home_selected_count, selectedCount))
                searchActive -> BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.home_search_hint),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                else -> {
                    if (atRoot) {
                        VaultTitle(
                            vaultName = vaultName,
                            vaults = vaults,
                            currentVaultId = currentVaultId,
                            onSwitchVault = onSwitchVault,
                            onManageVaults = onManageVaults
                        )
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        navigationIcon = {
            when {
                selectMode -> IconButton(onClick = onExitSelect) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                }
                searchActive -> IconButton(onClick = onSearchClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close))
                }
                atRoot -> IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Outlined.Menu, stringResource(R.string.action_menu))
                }
                else -> IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            when {
                selectMode -> {
                    // 全选留作独立快捷操作；收藏 / 移动 / 删除收进 ⋮ 菜单，便于后续扩展更多批量操作
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.home_select_all))
                    }
                    Box {
                        IconButton(onClick = { selectMenuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                        }
                        DropdownMenu(
                            expanded = selectMenuOpen,
                            onDismissRequest = { selectMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_favorite)) },
                                enabled = selectedCount > 0,
                                onClick = {
                                    selectMenuOpen = false
                                    onFavoriteSelected()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_select_move)) },
                                enabled = selectedCount > 0,
                                onClick = {
                                    selectMenuOpen = false
                                    onMoveSelected()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                enabled = selectedCount > 0,
                                onClick = {
                                    selectMenuOpen = false
                                    onDeleteSelected()
                                }
                            )
                        }
                    }
                }
                searchActive -> {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                        }
                    }
                }
                else -> {
                    IconButton(onClick = onSync, enabled = !syncActive) {
                        BadgedBox(
                            badge = {
                                if (conflictCount > 0) {
                                    Badge { Text(conflictCount.toString()) }
                                }
                            }
                        ) {
                            if (syncActive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Outlined.Sync, stringResource(R.string.action_sync_now))
                            }
                        }
                    }
                    IconButton(onClick = onSearchOpen) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.home_search_hint))
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort_by))
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            NoteSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label()) },
                                    leadingIcon = {
                                        if (order == sortOrder) {
                                            Icon(Icons.Filled.Check, null)
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }
                                    },
                                    onClick = {
                                        sortMenuOpen = false
                                        onSortSelected(order)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/**
 * 根目录标题：仓库名 + 下拉箭头；点按弹出仓库切换菜单
 * （勾选当前项；底部「管理仓库…」进入仓库管理页）。
 */
@Composable
private fun VaultTitle(
    vaultName: String,
    vaults: List<VaultInfo>,
    currentVaultId: String?,
    onSwitchVault: (String) -> Unit,
    onManageVaults: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { menuOpen = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = vaultName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.vault_switch),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            vaults.forEach { vault ->
                DropdownMenuItem(
                    text = { Text(vault.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (vault.id == currentVaultId) {
                            Icon(Icons.Filled.Check, null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        menuOpen = false
                        if (vault.id != currentVaultId) onSwitchVault(vault.id)
                    }
                )
            }
            if (vaults.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_manage)) },
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                onClick = {
                    menuOpen = false
                    onManageVaults()
                }
            )
        }
    }
}
