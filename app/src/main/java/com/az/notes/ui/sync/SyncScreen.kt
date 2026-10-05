package com.az.notes.ui.sync

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.domain.model.SyncConfig
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
    var modeSheet by remember { mutableStateOf(false) }

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
                ClickableRow(
                    title = stringResource(R.string.sync_mode_row),
                    value = state.config.mode.label(),
                    onClick = { modeSheet = true }
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
                items(logs, key = { it.id }) { log -> LogRow(log = log) }
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

    if (modeSheet) {
        ChoiceSheet(
            title = stringResource(R.string.sync_mode_row),
            options = SyncMode.entries.map { it to it.label() },
            selected = state.config.mode,
            onSelect = {
                viewModel.updateMode(it)
                modeSheet = false
            },
            onDismiss = { modeSheet = false }
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

/** 底部弹窗单选（同步策略）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            options.forEach { (value, optionLabel) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = optionLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (value == selected) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun formatLogTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
