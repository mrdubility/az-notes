package com.az.notes.ui.debug

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.debug.DebugLogConfig
import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogStatus
import com.az.notes.data.debug.DebugLogType
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.resolve
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 调试日志设置页（设置 → 关于 → 版本条目进入）。
 * 开关 / 最低等级 / 类型多选、存储占用、查看 / 导出 / 清空；默认关闭。
 */
@HiltViewModel
class DebugLogViewModel @Inject constructor(
    private val debugLogRepository: DebugLogRepository
) : ViewModel() {

    val config = debugLogRepository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, DebugLogConfig())

    var status by mutableStateOf(DebugLogStatus())
        private set

    /** 查看对话框内容；null = 未打开。 */
    var logs by mutableStateOf<List<String>?>(null)
        private set

    var message by mutableStateOf<UiText?>(null)
        private set

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        status = debugLogRepository.status()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        debugLogRepository.setEnabled(enabled)
    }

    fun setMinLevel(level: DebugLogLevel) = viewModelScope.launch {
        debugLogRepository.setMinLevel(level)
    }

    fun toggleType(type: DebugLogType) {
        val current = config.value.types
        val next = if (type in current) current - type else current + type
        viewModelScope.launch { debugLogRepository.setTypes(next) }
    }

    fun openLogs() {
        logs = debugLogRepository.readAllLines()
        refreshStatus()
    }

    fun closeLogs() {
        logs = null
    }

    fun clearLogs() {
        debugLogRepository.clearAll()
        refreshStatus()
        message = UiText.of(R.string.debug_logs_clear_done)
    }

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { debugLogRepository.exportTo(uri) }
            .onSuccess { lines -> message = UiText.of(R.string.debug_logs_export_done, lines) }
            .onFailure { message = UiText.of(R.string.debug_logs_export_failed) }
        refreshStatus()
    }

    fun consumeMessage() {
        message = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit,
    viewModel: DebugLogViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var clearConfirm by remember { mutableStateOf(false) }

    val message = viewModel.message
    LaunchedEffect(message) {
        val text = message?.resolve() ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    // 导出走系统“保存文件”：用户可选任意目录，文件名自动带时间戳
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // —— 提醒：日常请关闭 ——
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.debug_logs_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // —— 收集开关与过滤 ——
            item { SectionHeader(stringResource(R.string.debug_logs_collect)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.debug_logs_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(
                                if (config.enabled) R.string.debug_logs_enabled_on
                                else R.string.debug_logs_enabled_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = viewModel::setEnabled
                    )
                }
            }
            item {
                LevelRow(
                    current = config.minLevel,
                    onSelect = viewModel::setMinLevel
                )
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.debug_logs_types),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.debug_logs_types_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TypeChips(
                        selected = config.types,
                        onToggle = viewModel::toggleType
                    )
                }
            }

            // —— 存储占用 ——
            item { SectionHeader(stringResource(R.string.debug_logs_storage)) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.debug_logs_storage_value,
                            viewModel.status.segmentCount,
                            formatBytes(viewModel.status.totalBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.debug_logs_storage_path, viewModel.status.dirPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // —— 操作 ——
            item { SectionHeader(stringResource(R.string.debug_logs_actions)) }
            item {
                DebugRow(
                    title = stringResource(R.string.debug_logs_view),
                    subtitle = stringResource(R.string.debug_logs_view_desc),
                    onClick = viewModel::openLogs
                )
            }
            item {
                DebugRow(
                    title = stringResource(R.string.debug_logs_export),
                    subtitle = stringResource(R.string.debug_logs_export_desc),
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("az-notes-debug-$stamp.jsonl")
                    }
                )
            }
            item {
                DebugRow(
                    title = stringResource(R.string.debug_logs_clear),
                    subtitle = stringResource(R.string.debug_logs_clear_desc),
                    onClick = { clearConfirm = true }
                )
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(stringResource(R.string.debug_logs_clear)) },
            text = { Text(stringResource(R.string.debug_logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogs()
                    clearConfirm = false
                }) {
                    Text(
                        stringResource(R.string.action_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    viewModel.logs?.let { lines ->
        LogViewerDialog(lines = lines, onDismiss = viewModel::closeLogs)
    }
}

// ---------------------------------------------------------------- 子组件

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
private fun DebugRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 最低等级选择行：行旁浮层菜单。 */
@Composable
private fun LevelRow(current: DebugLogLevel, onSelect: (DebugLogLevel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.debug_logs_min_level),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.debug_logs_min_level_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                current.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DebugLogLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text(level.name) },
                    trailingIcon = {
                        if (level == current) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(level)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(selected: Set<DebugLogType>, onToggle: (DebugLogType) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DebugLogType.entries.forEach { type ->
            FilterChip(
                selected = type in selected,
                onClick = { onToggle(type) },
                label = { Text(type.label()) }
            )
        }
    }
}

/** 日志查看对话框：全屏、按时间倒序（最新在前）、等宽字体。 */
@Composable
private fun LogViewerDialog(lines: List<String>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back)
                        )
                    }
                    Text(
                        text = stringResource(R.string.debug_logs_view),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (lines.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.debug_logs_line_count, lines.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
                HorizontalDivider()
                if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.debug_logs_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val reversed = remember(lines) { lines.asReversed() }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        items(reversed) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 文案 / 格式化

@Composable
private fun DebugLogType.label(): String = stringResource(
    when (this) {
        DebugLogType.SYNC -> R.string.debug_logs_type_sync
        DebugLogType.NET -> R.string.debug_logs_type_net
        DebugLogType.WORK -> R.string.debug_logs_type_work
        DebugLogType.FILE -> R.string.debug_logs_type_file
        DebugLogType.APP -> R.string.debug_logs_type_app
    }
)

/** 字节数 → 人类可读（B / KB / MB）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
