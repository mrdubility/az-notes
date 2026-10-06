package com.az.notes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 冲突记录（§4.2 / §6.2）：一次冲突的双方指纹与解决方式。
 *
 * 仅在两端内容无法自动收敛、需要人工介入时写入；败方内容已另存为
 * 冲突副本（[backupPath]），SHA-1 为尽力而为（读取失败记 null）。
 */
@Entity(tableName = "conflict_record")
data class ConflictRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属仓库 id（多仓库隔离） */
    @ColumnInfo(name = "vault_id") val vaultId: String,
    /** 冲突文件相对仓库根的路径 */
    @ColumnInfo(name = "path") val path: String,
    /** 基线版本的 SHA-1（当前基线不含内容指纹，通常为 null） */
    @ColumnInfo(name = "base_sha1") val baseSha1: String?,
    /** 本地版本的 SHA-1（备份或胜出内容） */
    @ColumnInfo(name = "local_sha1") val localSha1: String?,
    /** 远端版本的 SHA-1（备份或胜出内容） */
    @ColumnInfo(name = "remote_sha1") val remoteSha1: String?,
    /** 解决方式：CONFLICT_COPY:local / CONFLICT_COPY:remote / LOCAL_FIRST / REMOTE_FIRST */
    @ColumnInfo(name = "resolved_by") val resolvedBy: String,
    /** 败方备份相对路径（无备份时为空串） */
    @ColumnInfo(name = "backup_path") val backupPath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
