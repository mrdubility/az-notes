package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 同步日志 DAO（§4.2 / §6.5-4）；多仓库按 vault_id 隔离。 */
@Dao
interface SyncLogDao {

    @Insert
    suspend fun insertAll(entries: List<SyncLogEntity>)

    /** 指定仓库最近 [limit] 条（倒序），供同步页展示。 */
    @Query("SELECT * FROM sync_log WHERE vault_id = :vaultId ORDER BY id DESC LIMIT :limit")
    fun recent(vaultId: String, limit: Int): Flow<List<SyncLogEntity>>

    /** 指定仓库仅保留最近 [keep] 条，防止日志无限膨胀。 */
    @Query(
        "DELETE FROM sync_log WHERE vault_id = :vaultId AND id NOT IN " +
            "(SELECT id FROM sync_log WHERE vault_id = :vaultId ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimTo(vaultId: String, keep: Int)

    /** 清除指定仓库全部日志（移除仓库时调用）。 */
    @Query("DELETE FROM sync_log WHERE vault_id = :vaultId")
    suspend fun clear(vaultId: String)
}
