package com.az.notes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.az.notes.R
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import com.az.notes.domain.model.SyncOpType

/**
 * 同步相关枚举的可本地化名称（预览列表 / 进度文案 / 日志 / 下拉共用）。
 * 与 domain 里旧的硬编码中文 label 对应；UI 层统一使用这里的资源化版本。
 */

@Composable
internal fun SyncOpType.label(): String = stringResource(
    when (this) {
        SyncOpType.UPLOAD -> R.string.sync_op_upload
        SyncOpType.DOWNLOAD -> R.string.sync_op_download
        SyncOpType.DELETE_REMOTE -> R.string.sync_op_delete_remote
        SyncOpType.TRASH_LOCAL -> R.string.sync_op_trash_local
        SyncOpType.CONFLICT_COPY -> R.string.sync_op_conflict_copy
        SyncOpType.MOVE_LOCAL -> R.string.sync_op_move_local
        SyncOpType.MOVE_REMOTE -> R.string.sync_op_move_remote
    }
)

@Composable
internal fun SyncMode.label(): String = stringResource(
    when (this) {
        SyncMode.BIDIRECTIONAL -> R.string.sync_mode_bidirectional
        SyncMode.UPLOAD_ONLY -> R.string.sync_mode_upload_only
        SyncMode.UPLOAD_OVERWRITE -> R.string.sync_mode_upload_overwrite
        SyncMode.DOWNLOAD_ONLY -> R.string.sync_mode_download_only
        SyncMode.DOWNLOAD_RESTORE -> R.string.sync_mode_download_restore
    }
)

@Composable
internal fun ConflictStrategy.label(): String = stringResource(
    when (this) {
        ConflictStrategy.CONFLICT_COPY -> R.string.sync_conflict_copy
        ConflictStrategy.LOCAL_FIRST -> R.string.sync_conflict_local_first
        ConflictStrategy.REMOTE_FIRST -> R.string.sync_conflict_remote_first
    }
)

@Composable
internal fun SyncInterval.label(): String = stringResource(
    when (this) {
        SyncInterval.OFF -> R.string.sync_interval_off
        SyncInterval.M15 -> R.string.sync_interval_m15
        SyncInterval.M30 -> R.string.sync_interval_m30
        SyncInterval.H1 -> R.string.sync_interval_h1
        SyncInterval.H4 -> R.string.sync_interval_h4
        SyncInterval.H8 -> R.string.sync_interval_h8
    }
)
