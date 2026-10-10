package com.az.notes.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.ui.common.resolve
import com.az.notes.ui.common.showTimedSnackbar
import kotlinx.coroutines.delay

/**
 * 备份与恢复页（设置 → 备份）：导出当前全部配置为 JSON 文件 / 从备份恢复。
 * 导出与导入均走系统文件选择器（SAF），无需存储权限；
 * 导入完成后语言若发生变化，短暂展示统计提示后重建界面生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 导出：系统「另存为」文件选择器（默认 JSON 文件名）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> viewModel.writeExport(uri) }
    // 导入：系统文件选择器（宽松过滤，由解析器校验内容有效性）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.import(uri) }

    // 导出内容组装完成 → 拉起「另存为」
    val exportName = state.exportFileName
    LaunchedEffect(exportName) {
        if (exportName != null) exportLauncher.launch(exportName)
    }

    // 一次性提示（导出结果 / 导入统计）
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showTimedSnackbar(msg.resolve(context))
        viewModel.consumeMessage()
    }

    // 导入后语言变更：提示展示片刻后重建界面（recreate 随 Snackbar 一起销毁，故先延时）
    LaunchedEffect(state.languageChanged) {
        if (!state.languageChanged) return@LaunchedEffect
        viewModel.consumeLanguageChanged()
        delay(1800)
        viewModel.consumeMessage()
        (context as? Activity)?.recreate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (state.busy) {
                item(key = "busy") {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    )
                }
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Upload,
                    title = stringResource(R.string.backup_export),
                    subtitle = stringResource(R.string.backup_export_subtitle),
                    onClick = { if (!state.busy) viewModel.prepareExport() }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.backup_import),
                    subtitle = stringResource(R.string.backup_import_subtitle),
                    // 按 mime 过滤：文件选择器仅列出 json（“最近”列表同样只显示 json）
                    onClick = { if (!state.busy) importLauncher.launch(arrayOf("application/json")) }
                )
            }
            item(key = "hint") {
                Text(
                    text = stringResource(R.string.backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}
