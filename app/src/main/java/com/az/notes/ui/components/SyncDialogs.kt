package com.az.notes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.window.DialogProperties
import com.az.notes.R
import com.az.notes.domain.model.SyncOp
import com.az.notes.domain.model.SyncOpType
import com.az.notes.domain.model.SyncPlan
import com.az.notes.domain.model.SyncSummary
import com.az.notes.domain.model.displayPath
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.label
import com.az.notes.ui.common.resolve

/** 确认弹窗最多展示的操作条数（更多时仅提示数量）。 */
private const val MAX_CONFIRM_ROWS = 100

/**
 * 同步流程弹窗（主页「立即同步」与同步页共用）：
 * 进度对话框 → 变更确认弹窗 → 结果对话框。
 *
 * 变更清单必须经用户确认才执行（§6.1 预览确认），关闭弹窗即放弃本次计划。
 */

/** 扫描 / 执行进度对话框；进行中不响应返回键与外部点击，避免误触中断同步。 */
@Composable
fun SyncProgressDialog(
    statusText: String,
    done: Int,
    total: Int,
    executing: Boolean
) {
    AlertDialog(
        onDismissRequest = { /* 进行中不允许关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Text(
                stringResource(
                    if (executing) R.string.sync_dialog_executing else R.string.sync_dialog_scanning
                )
            )
        },
        text = {
            Column {
                if (executing && total > 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "$done / $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (statusText.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * 变更确认弹窗（居中对话框）：统计 + 待执行操作清单（按语义着色）+ 取消 / 确认执行。
 * 计划为空时展示「两端已一致」，只保留一个关闭按钮。
 */
@Composable
fun SyncConfirmDialog(
    plan: SyncPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_plan_title)) },
        text = {
            Column {
                if (plan.isEmpty) {
                    Text(
                        text = stringResource(R.string.sync_plan_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@Column
                }
                Text(
                    text = stringResource(
                        R.string.sync_counts_line,
                        plan.uploadCount,
                        plan.downloadCount,
                        plan.deleteRemoteCount,
                        plan.trashLocalCount,
                        plan.conflictCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (plan.moveCount > 0 || plan.skippedLarge > 0) {
                    Text(
                        text = stringResource(R.string.sync_counts_extra, plan.moveCount, plan.skippedLarge),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                plan.warning?.let { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    itemsIndexed(
                        plan.ops.take(MAX_CONFIRM_ROWS),
                        key = { index, _ -> index }
                    ) { _, op ->
                        SyncOpRow(op)
                    }
                    if (plan.ops.size > MAX_CONFIRM_ROWS) {
                        item {
                            Text(
                                text = stringResource(R.string.sync_plan_more, MAX_CONFIRM_ROWS),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (plan.isEmpty) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.sync_result_dismiss))
                }
            } else {
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.sync_confirm_execute))
                }
            }
        },
        dismissButton = {
            if (!plan.isEmpty) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/** 单条计划操作：类型（按语义着色）+ 相对路径（改名显示「旧 → 新」）。 */
@Composable
fun SyncOpRow(op: SyncOp) {
    val labelColor = when (op.type) {
        SyncOpType.DELETE_REMOTE, SyncOpType.TRASH_LOCAL -> MaterialTheme.colorScheme.error
        SyncOpType.CONFLICT_COPY -> MaterialTheme.colorScheme.tertiary
        SyncOpType.UPLOAD, SyncOpType.DOWNLOAD -> MaterialTheme.colorScheme.primary
        SyncOpType.MOVE_LOCAL, SyncOpType.MOVE_REMOTE -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = op.type.label(),
            style = MaterialTheme.typography.labelMedium,
            color = labelColor
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = op.displayPath,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 同步结果对话框：完成汇总或失败原因（[error] 非空时按失败展示）。 */
@Composable
fun SyncResultDialog(
    summary: SyncSummary?,
    error: UiText?,
    onDismiss: () -> Unit
) {
    val failed = error != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (failed) R.string.sync_error_title else R.string.sync_done_title
                ),
                color = if (failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column {
                val failure = error
                if (failure != null) {
                    Text(failure.resolve(), style = MaterialTheme.typography.bodyMedium)
                } else if (summary != null) {
                    Text(
                        text = stringResource(
                            R.string.sync_counts_line,
                            summary.uploaded,
                            summary.downloaded,
                            summary.deletedRemote,
                            summary.trashedLocal,
                            summary.conflictCopies
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (summary.moved > 0 || summary.skippedLarge > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.sync_counts_extra,
                                summary.moved,
                                summary.skippedLarge
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (summary.failed > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.sync_summary_failed, summary.failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sync_result_dismiss))
            }
        }
    )
}
