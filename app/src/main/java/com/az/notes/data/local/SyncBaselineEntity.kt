package com.az.notes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步基线（§4.2 / §6.1）：上次成功同步后的“两端一致状态”快照。
 * 主键为相对 Vault 根的路径（'/' 分隔）。
 * 本地变化用 size + mtime 短路比较；远端变化用 size + etag（宽松模式，§6.1）。
 * 只在两端都有该文件（状态已一致）时记录；仅单侧存在的路径不写入基线，
 * 使“删除传播”可被下一轮 Diff 识别。
 */
@Entity(tableName = "sync_baseline")
data class SyncBaselineEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "local_size") val localSize: Long,
    @ColumnInfo(name = "local_mtime") val localMtime: Long,
    @ColumnInfo(name = "remote_size") val remoteSize: Long,
    @ColumnInfo(name = "remote_mtime") val remoteMtime: Long,
    @ColumnInfo(name = "remote_etag") val remoteEtag: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long
)
