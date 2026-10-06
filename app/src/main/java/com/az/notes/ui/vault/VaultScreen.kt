package com.az.notes.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.VaultInfo
import com.az.notes.util.SafPathUtils
import com.az.notes.util.StoragePermission
import kotlinx.coroutines.launch

/**
 * 仓库管理页：列出全部仓库（单选切换当前仓库），支持添加 / 重命名 / 移除。
 * WebDAV 同步配置与「接收分享的笔记位置」跟随仓库切换（设置 → 仓库组同页可见）。
 * 移除仅清理应用内数据，磁盘上的笔记文件不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<VaultInfo?>(null) }
    var removeTarget by remember { mutableStateOf<VaultInfo?>(null) }
    var permissionDialog by remember { mutableStateOf(false) }
    var pendingPick by remember { mutableStateOf(false) }

    // 系统文件夹选择器：选中后换算为文件系统绝对路径并加入注册表
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafPathUtils.treeUriToPath(uri)
        if (path == null || !viewModel.addVault(path)) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.vault_dir_invalid, path ?: ""))
            }
        }
    }

    // 外部目录需要「所有文件访问权限」：授权返回后自动接续打开文件夹选择器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingPick && StoragePermission.hasAllFilesAccess()) {
            pendingPick = false
            picker.launch(null)
        }
    }
    // 部分 ROM 从系统授权页返回时不带 result：ON_RESUME 兜底重检
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                pendingPick && StoragePermission.hasAllFilesAccess()
            ) {
                pendingPick = false
                picker.launch(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        when {
            !loaded -> Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)
            ) {
                if (settings.vaults.isEmpty()) {
                    item { EmptyVaults() }
                } else {
                    val multiVault = settings.vaults.size > 1
                    items(settings.vaults, key = { it.id }) { vault ->
                        VaultRow(
                            vault = vault,
                            current = vault.id == settings.currentVaultId,
                            hideEnabled = multiVault,
                            onSelect = { viewModel.switchTo(vault.id) },
                            onRename = { renameTarget = vault },
                            onRemove = { removeTarget = vault },
                            onToggleHidden = {
                                when {
                                    vault.hidden -> viewModel.setVaultHidden(vault.id, false)
                                    vault.id == settings.currentVaultId -> scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.vault_hide_current)
                                        )
                                    }
                                    else -> viewModel.setVaultHidden(vault.id, true)
                                }
                            }
                        )
                    }
                }
                item(key = "add") {
                    AddVaultRow(onClick = {
                        if (StoragePermission.hasAllFilesAccess()) {
                            picker.launch(null)
                        } else {
                            permissionDialog = true
                        }
                    })
                }
            }
        }
    }

    // 重命名仓库（仅展示名）
    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.vault_rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_hint)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameVault(target.id, text)
                    renameTarget = null
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 移除仓库（二次确认：仅清除应用内数据，磁盘文件不受影响；默认仓库不提供此入口）
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.vault_remove)) },
            text = { Text(stringResource(R.string.vault_remove_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeVault(target.id)
                    removeTarget = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 添加外部仓库前的权限引导：授权返回后自动接续打开文件夹选择器（见 pendingPick）
    if (permissionDialog) {
        AlertDialog(
            onDismissRequest = { permissionDialog = false },
            title = { Text(stringResource(R.string.permission_title)) },
            text = { Text(stringResource(R.string.permission_subtitle)) },
            confirmButton = {
                TextButton(onClick = {
                    permissionDialog = false
                    pendingPick = true
                    runCatching { permissionLauncher.launch(StoragePermission.buildIntent(context)) }
                }) {
                    Text(stringResource(R.string.permission_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** 单条仓库：单选（点击切换当前仓库）+ 名称 / 徽标 + 路径 + ⋮（重命名 / 隐藏 / 移除）。 */
@Composable
private fun VaultRow(
    vault: VaultInfo,
    current: Boolean,
    /** 多仓库时才允许隐藏默认仓库（只有一个仓库时强行显示）。 */
    hideEnabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onToggleHidden: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = vault.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (current) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (vault.builtin) {
                    Spacer(Modifier.width(6.dp))
                    VaultBadge(
                        text = stringResource(R.string.vault_builtin_badge),
                        tint = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primaryContainer
                    )
                }
                if (vault.hidden) {
                    Spacer(Modifier.width(6.dp))
                    VaultBadge(
                        text = stringResource(R.string.vault_hidden_badge),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = vault.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vault_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                if (vault.builtin) {
                    // 默认仓库：多仓库时可隐藏 / 恢复显示；只有一个仓库时强行显示（置灰）
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (vault.hidden) R.string.vault_unhide else R.string.vault_hide
                                )
                            )
                        },
                        enabled = hideEnabled || vault.hidden,
                        onClick = {
                            menuOpen = false
                            onToggleHidden()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.vault_remove),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

/** 仓库徽标（默认 / 已隐藏）：小号标签，跟随主题色。 */
@Composable
private fun VaultBadge(text: String, tint: Color, container: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** 底部入口：添加仓库（打开系统文件夹选择器）。 */
@Composable
private fun AddVaultRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
                text = stringResource(R.string.vault_add),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.vault_add_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 空状态：一个仓库都没有时引导添加。 */
@Composable
private fun EmptyVaults() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.vault_empty),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.vault_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
