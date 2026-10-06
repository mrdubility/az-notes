package com.az.notes.ui.sync

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import com.az.notes.ui.common.label
import com.az.notes.ui.common.resolve
import com.az.notes.ui.components.SyncConfirmDialog

/**
 * 同步页（§6）：WebDAV / 坚果云配置（服务器 / 账号 / 应用密码 / 远端目录）、
 * 同步策略、手动同步（Scan → 弹窗预览确认 → Execute）、结果汇总与同步日志。
 *
 * 从设置 → 同步进入；主页右上角的“立即同步”不跳本页，而是在主页用同一套
 * 弹窗组件（[SyncConfirmDialog] 等）完成扫描 → 确认 → 执行。
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
        val message = state.testMessage?.resolve(context) ?: return@LaunchedEffect
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
                    ProgressRow(statusText = state.statusText.resolve())
                }
                SyncPhase.EXECUTING -> item {
                    ExecutingRow(state = state)
                }
                // 变更清单改由确认弹窗展示（见 Scaffold 之后的 SyncConfirmDialog）
                SyncPhase.AWAIT_CONFIRM -> Unit
                SyncPhase.DONE -> item {
                    state.summary?.let { summary ->
                        SummaryCard(summary = summary, onDismiss = viewModel::dismissResult)
                    }
                }
                SyncPhase.ERROR -> item {
                    ErrorCard(
                        message = state.error?.resolve().orEmpty(),
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
            SyncConfirmDialog(
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
