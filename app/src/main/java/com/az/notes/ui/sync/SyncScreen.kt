package com.az.notes.ui.sync

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.data.local.ConflictRecordEntity
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import com.az.notes.domain.model.SyncOpType
import com.az.notes.domain.model.SyncSummary
import com.az.notes.domain.model.label
import com.az.notes.ui.components.SyncConfirmSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步页（§6）：WebDAV / 坚果云配置（服务器 / 账号 / 应用密码 / 远端目录）、
 * 同步策略、手动同步（Scan → 弹窗预览确认 → Execute）、结果汇总与同步日志。
 *
 * 从设置 → 同步进入；主页右上角的“立即同步”不跳本页，而是在主页用同一套
 * 弹窗组件（[SyncConfirmSheet] 等）完成扫描 → 确认 → 执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    var filterEditOpen by remember { mutableStateOf(false) }

    // 连接测试结果用 Toast 弹出；展示后立即消费，避免重组 / 重建时重复提示
    val context = LocalContext.current
    LaunchedEffect(state.testMessage) {
        val message = state.testMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearTestMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // —— 服务器配置 ——
            item { SectionHeader(stringResource(R.string.sync_section_server)) }
            item { ServerSection(state = state, viewModel = viewModel) }

            // —— 同步策略 ——
            item { SectionHeader(stringResource(R.string.sync_section_policy)) }
            item {
                ChoiceRow(
                    title = stringResource(R.string.sync_mode_row),
                    options = SyncMode.entries.map { it to it.label() },
                    selected = state.config.mode,
                    onSelect = viewModel::updateMode
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.sync_conflict_strategy_row),
                    options = ConflictStrategy.entries.map { it to it.label() },
                    selected = state.config.conflictStrategy,
                    onSelect = viewModel::updateConflictStrategy
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.sync_max_file_size_row),
                    options = MAX_FILE_SIZE_OPTIONS.map { it to fileSizeLabel(it) },
                    selected = state.config.maxFileSizeMb,
                    onSelect = viewModel::updateMaxFileSizeMb
                )
            }

            // —— 过滤规则（§6.3） ——
            item { SectionHeader(stringResource(R.string.sync_section_filter)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sync_filter_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { filterEditOpen = true }) {
                        Text(stringResource(R.string.sync_filter_edit))
                    }
                }
            }

            // —— 自动同步（§6.4） ——
            item { SectionHeader(stringResource(R.string.sync_section_auto)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.sync_auto_on_start),
                    subtitle = stringResource(R.string.sync_auto_on_start_desc),
                    checked = state.config.autoSyncOnStart,
                    onCheckedChange = viewModel::updateAutoSyncOnStart
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.sync_auto_periodic),
                    options = SyncInterval.entries.map { it to it.label() },
                    selected = state.config.periodicInterval,
                    onSelect = viewModel::updatePeriodicInterval
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.sync_auto_after_save),
                    subtitle = stringResource(R.string.sync_auto_after_save_desc),
                    checked = state.config.syncAfterSave,
                    onCheckedChange = viewModel::updateSyncAfterSave
                )
            }

            // —— 手动同步 ——
            item { SectionHeader(stringResource(R.string.sync_section_run)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    if (state.vaultPath.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.sync_no_vault),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = viewModel::startSync,
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sync_start))
                    }
                }
            }

            // —— 阶段反馈：扫描 / 执行进度、计划预览、结果汇总 ——
            when (state.phase) {
                SyncPhase.TESTING, SyncPhase.SCANNING -> item {
                    ProgressRow(statusText = state.statusText)
                }
                SyncPhase.EXECUTING -> item {
                    ExecutingRow(state = state)
                }
                // 变更清单改由底部弹窗展示（见 Scaffold 之后的 SyncConfirmSheet）
                SyncPhase.AWAIT_CONFIRM -> Unit
                SyncPhase.DONE -> item {
                    state.summary?.let { summary ->
                        SummaryCard(summary = summary, onDismiss = viewModel::dismissResult)
                    }
                }
                SyncPhase.ERROR -> item {
                    ErrorCard(
                        message = state.error ?: "",
                        onDismiss = viewModel::dismissResult
                    )
                }
                SyncPhase.IDLE -> Unit
            }

            // —— 冲突记录（§6.2）：有记录时展示，人工合并后手动清除 ——
            item { SectionHeader(stringResource(R.string.sync_conflict_title)) }
            if (conflicts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.sync_conflict_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.sync_conflict_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                // key 加前缀：两张表自增 id 均从 1 开始，裸 id 会在同一 LazyColumn 内重复导致崩溃
                items(conflicts, key = { "conflict-${it.id}" }) { record -> ConflictRow(record) }
                item {
                    TextButton(
                        onClick = viewModel::clearConflicts,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(stringResource(R.string.sync_conflict_clear))
                    }
                }
            }

            // —— 同步日志 ——
            item { SectionHeader(stringResource(R.string.sync_log_title)) }
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.sync_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(logs, key = { "log-${it.id}" }) { log -> LogRow(log = log) }
            }
        }
    }

    // 变更清单确认弹窗：与主页“立即同步”入口共用同一组件与交互
    if (state.phase == SyncPhase.AWAIT_CONFIRM) {
        state.plan?.let { plan ->
            SyncConfirmSheet(
                plan = plan,
                onConfirm = viewModel::confirmExecute,
                onDismiss = viewModel::cancelPlan
            )
        }
    }

    if (filterEditOpen) {
        FilterEditDialog(
            initial = state.config.ignoreRules,
            onSave = {
                viewModel.updateIgnoreRules(it)
                filterEditOpen = false
            },
            onDismiss = { filterEditOpen = false }
        )
    }
}

// ---------------------------------------------------------------- 服务器配置

/**
 * 服务器区：地址 / 账号 / 应用密码 / 远端目录。
 * 文本字段以本地草稿为准（避免 DataStore 回流延迟导致快速输入丢字符），
 * 每次变更同步写入 ViewModel；进程重建后草稿回落到 DataStore 中的最新值。
 * 密码框使用 [PasswordVisualTransformation] 掩码。
 */
@Composable
private fun ServerSection(
    state: SyncUiState,
    viewModel: SyncViewModel
) {
    var serverDraft by remember { mutableStateOf<String?>(null) }
    var usernameDraft by remember { mutableStateOf<String?>(null) }
    var remoteDirDraft by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = serverDraft ?: state.config.serverUrl,
            onValueChange = {
                serverDraft = it
                viewModel.updateServerUrl(it)
            },
            label = { Text(stringResource(R.string.sync_server_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = usernameDraft ?: state.config.username,
            onValueChange = {
                usernameDraft = it
                viewModel.updateUsername(it)
            },
            label = { Text(stringResource(R.string.sync_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text(stringResource(R.string.sync_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = remoteDirDraft ?: state.config.remoteDir,
            onValueChange = {
                remoteDirDraft = it
                viewModel.updateRemoteDir(it)
            },
            label = { Text(stringResource(R.string.sync_remote_dir)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                serverDraft = SyncConfig.DEFAULT_SERVER_URL
                viewModel.applyNutstorePreset()
            }) {
                Text(stringResource(R.string.sync_preset_nutstore))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = viewModel::testConnection,
                enabled = !state.busy
            ) {
                Text(stringResource(R.string.sync_test))
            }
        }
    }
}

// ---------------------------------------------------------------- 进度与结果

@Composable
private fun ProgressRow(statusText: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExecutingRow(state: SyncUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        LinearProgressIndicator(
            progress = {
                state.progressDone.toFloat() / state.progressTotal.coerceAtLeast(1)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${state.progressDone} / ${state.progressTotal}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.statusText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: SyncSummary, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.sync_done_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(
                    R.string.sync_counts_line,
                    summary.uploaded,
                    summary.downloaded,
                    summary.deletedRemote,
                    summary.trashedLocal,
                    summary.conflictCopies
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (summary.moved > 0 || summary.skippedLarge > 0) {
                Text(
                    text = stringResource(
                        R.string.sync_counts_extra,
                        summary.moved,
                        summary.skippedLarge
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (summary.failed > 0) {
                Text(
                    text = stringResource(R.string.sync_summary_failed, summary.failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.sync_result_dismiss))
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.sync_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.sync_result_dismiss))
            }
        }
    }
}

// ---------------------------------------------------------------- 日志

@Composable
private fun LogRow(log: SyncLogEntity) {
    val opLabel = runCatching { SyncOpType.valueOf(log.op).label() }
        .getOrDefault(
            when (log.op) {
                "BASELINE" -> "同步基线"
                "REMOTE_MOVE" -> "云端重命名"
                "AUTO_SYNC" -> "自动同步"
                else -> log.op
            }
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatLogTime(log.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = opLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = log.result,
                style = MaterialTheme.typography.labelMedium,
                color = if (log.result == "OK") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        if (log.path.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = log.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        log.detail?.takeIf { it.isNotEmpty() }?.let { detail ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------- 冲突记录

/** 单条冲突记录：文件、解决方式与败方副本路径（§6.2）。 */
@Composable
private fun ConflictRow(record: ConflictRecordEntity) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.path,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatLogTime(record.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = resolutionLabel(record.resolvedBy),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        if (record.backupPath.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = record.backupPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 冲突解决方式的可读文案（resolved_by 形如「策略:胜方」）。 */
private fun resolutionLabel(resolvedBy: String): String = when {
    resolvedBy.startsWith("CONFLICT_COPY") ->
        if (resolvedBy.endsWith(":local")) "冲突副本 · 保留本地版本" else "冲突副本 · 保留云端版本"
    resolvedBy.startsWith("LOCAL_FIRST") -> "本地优先"
    resolvedBy.startsWith("REMOTE_FIRST") -> "云端优先"
    else -> resolvedBy
}

// ---------------------------------------------------------------- 通用小组件

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
private fun ClickableRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单选行：点击在行旁弹出浮层菜单（替代底部弹窗，单手更好操作），
 * 选中项显示对勾；点选后立即生效并收起。
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ClickableRow(
            title = title,
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
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

/** 带副标题的开关行（自动同步选项）。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 过滤规则编辑对话框（§6.3）：多行编辑 + 恢复默认。 */
@Composable
private fun FilterEditDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_filter_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_filter_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { draft = SyncConfig.DEFAULT_IGNORE_RULES }) {
                    Text(stringResource(R.string.sync_filter_reset))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** 大文件上限选项（MB）与展示文案。 */
private val MAX_FILE_SIZE_OPTIONS = listOf(10, 20, 50, 100, 200, 500, 1024)

private fun fileSizeLabel(mb: Int): String =
    if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

private fun formatLogTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
