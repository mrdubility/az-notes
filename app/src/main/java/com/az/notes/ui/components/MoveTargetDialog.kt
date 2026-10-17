package com.az.notes.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.domain.model.FileNode
import com.az.notes.domain.model.VaultInfo
import com.az.notes.ui.common.CreateFolderResult
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.resolve
import kotlinx.coroutines.launch

/**
 * 「移动到文件夹」对话框（主页多选移动 / 编辑页移动 / AI 对话导出位置 / 设置页分享文件夹共用）：
 * 列出 Vault 根目录与全部子目录（按层级缩进）；[targets] 为 null 时显示加载中；
 * 点击目标即回调 [onSelect]（对话框由调用方关闭）。
 * 传入 [vaults] / [onSelectVault] 且可见仓库多于一个时，顶部出现仓库切换行（跨仓库移动）；
 * 隐藏仓库由调用方过滤，不在此列表出现。
 *
 * 传入 [onCreateFolder] 时每个目录行（含根目录）右侧出现「新建子文件夹」入口：
 * 输入名称 → 调用方创建并刷新目录树 / 调度同步 → 成功后组件直接把新目录回调 [onSelect]
 * （与「点击目标」同一出口，新建即可用作移动 / 导出 / 分享目标）；
 * 失败原因（名称不合法 / 同名已存在）就地展示在输入框下方，对话框不关闭。
 */
@Composable
fun MoveTargetDialog(
    vaultPath: String?,
    targets: List<FileNode>?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    title: String = stringResource(R.string.home_move_title),
    /** 可切换的目标仓库（含当前仓库；空 = 不显示切换行，如编辑页 / 设置页） */
    vaults: List<VaultInfo> = emptyList(),
    selectedVaultId: String? = null,
    onSelectVault: ((String) -> Unit)? = null,
    /** 新建文件夹（null = 不提供该能力）：父目录绝对路径 + 原始输入 → 创建结果 */
    onCreateFolder: (suspend (parentPath: String, input: String) -> CreateFolderResult)? = null
) {
    val scope = rememberCoroutineScope()
    // 新建子文件夹输入态：createParent == null 时不展示输入对话框
    var createParent by remember { mutableStateOf<String?>(null) }
    var createParentLabel by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<UiText?>(null) }
    var creating by remember { mutableStateOf(false) }
    val createFocus = remember { FocusRequester() }
    val createErrorText = createError?.resolve()
    val rootDirLabel = stringResource(R.string.home_move_root)

    // 打开输入对话框（父目录展示名在组合期解析好传入，事件回调内不可调 stringResource）
    fun openCreate(parent: String, label: String) {
        createParent = parent
        createParentLabel = label
        createName = ""
        createError = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 仓库切换行：可见仓库多于一个时出现（切换后由调用方重新装载目录树）
                if (onSelectVault != null && vaults.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        items(vaults, key = { it.id }) { vault ->
                            FilterChip(
                                selected = vault.id == selectedVaultId,
                                onClick = { if (vault.id != selectedVaultId) onSelectVault(vault.id) },
                                label = {
                                    Text(vault.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            )
                        }
                    }
                }
                val createRoot = vaultPath
                if (onCreateFolder != null && createRoot != null) {
                    // 根目录新建入口（置于列表上方更易发现；子目录新建用行尾图标）
                    TextButton(
                        onClick = { openCreate(createRoot, rootDirLabel) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_new_folder))
                    }
                }
                if (targets == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        vaultPath?.let { root ->
                            item(key = "__root__") {
                                MoveTargetRow(
                                    depth = 0,
                                    label = rootDirLabel,
                                    onClick = { onSelect(root) }
                                )
                            }
                        }
                        items(targets, key = { it.absolutePath }) { dir ->
                            MoveTargetRow(
                                depth = dir.depth + 1,
                                label = dir.name,
                                onClick = { onSelect(dir.absolutePath) },
                                allowNewFolder = onCreateFolder != null,
                                onNewFolder = { openCreate(dir.absolutePath, dir.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )

    // —— 新建子文件夹输入对话框（叠在选择器之上） ——
    if (createParent != null) {
        // 等一帧布局完成后再请求焦点，避免输入框未就绪焦点被吞（同搜索页做法）
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { createFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { if (!creating) createParent = null },
            title = { Text(stringResource(R.string.home_new_folder)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.folder_create_in, createParentLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = createName,
                        onValueChange = {
                            createName = it
                            createError = null
                        },
                        singleLine = true,
                        isError = createErrorText != null,
                        label = { Text(stringResource(R.string.folder_name)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(createFocus)
                    )
                    if (createErrorText != null) {
                        Text(
                            text = createErrorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !creating && createName.isNotBlank(),
                    onClick = {
                        val parent = createParent ?: return@TextButton
                        val callback = onCreateFolder ?: return@TextButton
                        scope.launch {
                            creating = true
                            createError = null
                            when (val result = callback(parent, createName)) {
                                is CreateFolderResult.Created -> {
                                    creating = false
                                    createParent = null
                                    createName = ""
                                    // 新建即选中：与点击目标同一出口（移动 / 导出 / 分享目录各自收口）
                                    onSelect(result.absolutePath)
                                }

                                is CreateFolderResult.Failed -> {
                                    creating = false
                                    createError = result.message
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !creating,
                    onClick = { createParent = null }
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** 目标文件夹行：按 depth 缩进展示层级，点击该项即移动；[allowNewFolder] 时右侧显示新建子文件夹入口。 */
@Composable
private fun MoveTargetRow(
    depth: Int,
    label: String,
    onClick: () -> Unit,
    allowNewFolder: Boolean = false,
    onNewFolder: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = (8 + depth * 16).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (allowNewFolder) {
            // 独立点击区：点击图标新建子文件夹，点击行其余区域仍是“选中该目录”
            IconButton(onClick = onNewFolder, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.CreateNewFolder,
                    contentDescription = stringResource(R.string.folder_new_subfolder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
