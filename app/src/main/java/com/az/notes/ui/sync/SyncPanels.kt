package com.az.notes.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.az.notes.R
import com.az.notes.data.local.ConflictRecordEntity
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.domain.model.SyncOpType
import com.az.notes.domain.model.SyncSummary
import com.az.notes.ui.common.label
import com.az.notes.ui.common.resolve

/**
 * 同步页进度与结果面板：扫描 / 执行进度、结果汇总、错误卡片、同步日志、冲突记录。
 * 自 SyncScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

// ---------------------------------------------------------------- 进度与结果

@Composable
internal fun ProgressRow(statusText: String) {
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
internal fun ExecutingRow(state: SyncUiState) {
    val statusText = state.statusText.resolve()
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
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SummaryCard(summary: SyncSummary, onDismiss: () -> Unit) {
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
internal fun ErrorCard(message: String, onDismiss: () -> Unit) {
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
internal fun LogRow(log: SyncLogEntity) {
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

// ---------------------------------------------------------------- 冲突记录

/** 单条冲突记录：文件、解决方式与败方副本路径（§6.2）。 */
@Composable
internal fun ConflictRow(record: ConflictRecordEntity) {
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
internal fun resolutionLabel(resolvedBy: String): String = when {
    resolvedBy.startsWith("CONFLICT_COPY") ->
        if (resolvedBy.endsWith(":local")) stringResource(R.string.sync_resolve_conflict_local)
        else stringResource(R.string.sync_resolve_conflict_remote)
    resolvedBy.startsWith("LOCAL_FIRST") -> stringResource(R.string.sync_resolve_local_first)
    resolvedBy.startsWith("REMOTE_FIRST") -> stringResource(R.string.sync_resolve_remote_first)
    else -> resolvedBy
}
