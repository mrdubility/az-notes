package com.az.notes.ui.sync

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.local.ConflictRecordDao
import com.az.notes.data.local.ConflictRecordEntity
import com.az.notes.data.local.SyncLogDao
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.sync.ConflictBackupStore
import com.az.notes.domain.model.ConflictMergeState
import com.az.notes.domain.model.SyncOpType
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.label
import com.az.notes.ui.common.resolve
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 同步记录 ViewModel：冲突记录与同步日志（自 SyncViewModel 拆出，供两个独立页面共用）。
 * 多仓库：均按当前仓库过滤展示。
 */
@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    syncLogDao: SyncLogDao,
    private val conflictRecordDao: ConflictRecordDao,
    private val settingsRepository: SettingsRepository,
    private val conflictBackupStore: ConflictBackupStore,
    private val syncScheduler: com.az.notes.work.SyncScheduler
) : ViewModel() {

    /** 同步日志（最近 100 条，仅当前仓库）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val logs: StateFlow<List<SyncLogEntity>> = settingsRepository.settings
        .map { it.currentVaultId }
        .distinctUntilChanged()
        .flatMapLatest { vaultId ->
            if (vaultId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                syncLogDao.recent(vaultId, 100)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 冲突记录（最近 50 条，仅当前仓库）：供人工合并后清除（§6.2）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val conflicts: StateFlow<List<ConflictRecordEntity>> = settingsRepository.settings
        .map { it.currentVaultId }
        .distinctUntilChanged()
        .flatMapLatest { vaultId ->
            if (vaultId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                conflictRecordDao.recent(vaultId, 50)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 人工合并完成后清除当前仓库全部冲突记录（顶栏角标随之消失）。 */
    fun clearConflicts() = viewModelScope.launch {
        val vaultId = settingsRepository.requireCurrentVaultId() ?: return@launch
        conflictRecordDao.clear(vaultId)
    }

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<UiText?>(null)

    /** 一次性提示（恢复结果）。 */
    val message: StateFlow<UiText?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    /** 从应用私有备份里恢复旧版本到原笔记路径（人工回退用）。 */
    fun restoreBackup(record: ConflictRecordEntity) = viewModelScope.launch {
        val vault = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
        if (vault.isNullOrBlank() || record.backupPath.isBlank()) {
            _message.value = UiText.of(R.string.sync_restore_failed)
            return@launch
        }
        val ok = withContext(Dispatchers.IO) {
            conflictBackupStore.restore(
                backup = File(record.backupPath),
                targetPath = File(vault, record.path).absolutePath
            )
        }
        _message.value = UiText.of(if (ok) R.string.sync_restore_done else R.string.sync_restore_failed)
        if (ok) runCatching { syncScheduler.scheduleSaveSync() }
    }
}

/** 冲突记录页：说明 + 列表 + 清除全部（自同步页独立成页）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncConflictScreen(
    onBack: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel()
) {
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageText = message?.resolve()
    LaunchedEffect(messageText) {
        val text = messageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_conflict_title)) },
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
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                items(conflicts, key = { it.id }) { record ->
                    ConflictRow(record) { viewModel.restoreBackup(record) }
                }
                item {
                    TextButton(
                        onClick = viewModel::clearConflicts,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(stringResource(R.string.sync_conflict_clear))
                    }
                }
            }
        }
    }
}

/** 同步日志页：最近 100 条同步记录（自同步页独立成页）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(
    onBack: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_log_title)) },
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
}

// ---------------------------------------------------------------- 行组件（自 SyncPanels 迁入）

@Composable
private fun LogRow(log: SyncLogEntity) {
    val opType = runCatching { SyncOpType.valueOf(log.op) }.getOrNull()
    val opLabel = opType?.label() ?: when (log.op) {
        "BASELINE" -> stringResource(R.string.sync_op_baseline)
        "REMOTE_MOVE" -> stringResource(R.string.sync_op_remote_move)
        "AUTO_SYNC" -> stringResource(R.string.sync_op_auto_sync)
        else -> log.op
    }
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

/** 单条冲突记录：文件、解决方式与败方副本路径（§6.2）。 */
@Composable
private fun ConflictRow(record: ConflictRecordEntity, onRestore: () -> Unit) {
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
        if (record.markerCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.sync_conflict_markers, record.markerCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (record.backupPath.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = backupSummary(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else if (record.mergeState == ConflictMergeState.BINARY_KEEP.name) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.sync_conflict_no_backup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (record.backupPath.isNotEmpty()) {
            TextButton(
                onClick = onRestore,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.sync_restore_backup))
            }
        }
    }
}

/** 败方备份的可读文案：只展示文件名（完整路径在应用私有目录，对用户无意义）。 */
@Composable
private fun backupSummary(record: ConflictRecordEntity): String {
    val name = record.backupPath.substringAfterLast('/').ifEmpty { record.backupPath }
    return stringResource(R.string.sync_conflict_backed_up, name)
}

/** 冲突解决方式的可读文案（resolved_by 形如「策略:胜方」）。 */
@Composable
private fun resolutionLabel(resolvedBy: String): String = when {
    resolvedBy.startsWith("CONFLICT_COPY") ->
        if (resolvedBy.endsWith(":local")) stringResource(R.string.sync_resolve_conflict_local)
        else stringResource(R.string.sync_resolve_conflict_remote)
    resolvedBy.startsWith("LOCAL_FIRST") -> stringResource(R.string.sync_resolve_local_first)
    resolvedBy.startsWith("REMOTE_FIRST") -> stringResource(R.string.sync_resolve_remote_first)
    else -> resolvedBy
}
