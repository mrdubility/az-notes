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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.az.notes.domain.model.SyncOpType
import com.az.notes.ui.common.label
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 同步记录 ViewModel：冲突记录与同步日志（自 SyncViewModel 拆出，供两个独立页面共用）。
 */
@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    syncLogDao: SyncLogDao,
    private val conflictRecordDao: ConflictRecordDao
) : ViewModel() {

    /** 同步日志（最近 100 条）。 */
    val logs: StateFlow<List<SyncLogEntity>> = syncLogDao.recent(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 冲突记录（最近 50 条）：供人工合并后清除（§6.2）。 */
    val conflicts: StateFlow<List<ConflictRecordEntity>> = conflictRecordDao.recent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 人工合并完成后清除全部冲突记录（顶栏角标随之消失）。 */
    fun clearConflicts() = viewModelScope.launch { conflictRecordDao.clear() }
}

/** 冲突记录页：说明 + 列表 + 清除全部（自同步页独立成页）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncConflictScreen(
    onBack: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel()
) {
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    Scaffold(
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
                items(conflicts, key = { it.id }) { record -> ConflictRow(record) }
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
@Composable
private fun resolutionLabel(resolvedBy: String): String = when {
    resolvedBy.startsWith("CONFLICT_COPY") ->
        if (resolvedBy.endsWith(":local")) stringResource(R.string.sync_resolve_conflict_local)
        else stringResource(R.string.sync_resolve_conflict_remote)
    resolvedBy.startsWith("LOCAL_FIRST") -> stringResource(R.string.sync_resolve_local_first)
    resolvedBy.startsWith("REMOTE_FIRST") -> stringResource(R.string.sync_resolve_remote_first)
    else -> resolvedBy
}
