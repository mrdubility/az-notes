package com.az.notes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 同步日志（§4.2 / §6.5-4）：记录每次同步的操作与结果，同步页可查；多仓库按 vault_id 隔离。 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属仓库 id */
    @ColumnInfo(name = "vault_id") val vaultId: String,
    @ColumnInfo(name = "ts") val ts: Long,
    /** 操作类型名（SyncOpType / BASELINE / SESSION 等） */
    @ColumnInfo(name = "op") val op: String,
    @ColumnInfo(name = "path") val path: String,
    /** OK / FAIL */
    @ColumnInfo(name = "result") val result: String,
    @ColumnInfo(name = "detail") val detail: String? = null
)
