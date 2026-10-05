package com.az.notes.ui.gate

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.util.SafPathUtils
import com.az.notes.util.StoragePermission

/**
 * 门禁页：串联两步——(1) 所有文件访问授权引导，(2) Vault 目录选择。
 * 授权返回后 `ON_RESUME` 重新检测权限；两者就绪即回调 [onReady]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GateScreen(
    viewModel: com.az.notes.ui.MainViewModel,
    onReady: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(StoragePermission.hasAllFilesAccess()) }
    var invalidPath by rememberSaveable { mutableStateOf<String?>(null) }

    // 返回应用时重新检测授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = StoragePermission.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 权限就绪且已选定有效 Vault —— 进入主界面
    LaunchedEffect(granted, settings.vaultPath) {
        if (granted && !settings.vaultPath.isNullOrBlank()) onReady()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Top)
        ) {
            Header()

            if (!granted) {
                PermissionStep(
                    onGrant = {
                        runCatching {
                            context.startActivity(StoragePermission.buildIntent(context))
                        }
                    }
                )
            } else {
                VaultStep(
                    currentPath = settings.vaultPath,
                    onPick = { path ->
                        if (!viewModel.openVault(path)) {
                            invalidPath = path
                        } else {
                            invalidPath = null
                        }
                    }
                )
                invalidPath?.let {
                    Text(
                        text = "目录不存在或不可读：$it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// 非法路径提示状态由 GateScreen 内部 rememberSaveable 持有

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "读取笔记库 · 标准 Markdown 显示/编辑 · 笔记列表与大纲",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionStep(onGrant: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = stringResource(R.string.permission_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permission_grant))
            }
        }
    }
}

/**
 * Vault 目录选择：使用系统自带文件夹选择器（OpenDocumentTree），
 * 不让用户手填路径；选中后将 SAF 树 Uri 换算为文件系统绝对路径再回调。
 */
@Composable
private fun VaultStep(currentPath: String?, onPick: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SafPathUtils.treeUriToPath(uri)?.let(onPick)
        }
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vault_title),
                    style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = stringResource(R.string.vault_pick_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = currentPath ?: stringResource(R.string.vault_pick_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = if (currentPath != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = { launcher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.vault_pick_button))
            }
        }
    }
}
