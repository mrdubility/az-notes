package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 同步日志 DAO（§4.2 / §6.5-4）。 */
@Dao
interface SyncLogDao {

    @Insert
    suspend fun insertAll(entries: List<SyncLogEntity>)

    /** 最近 [limit] 条（倒序），供同步页展示。 */
    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SyncLogEntity>>

    /** 仅保留最近 [keep] 条，防止日志无限膨胀。 */
    @Query(
        "DELETE FROM sync_log WHERE id NOT IN " +
            "(SELECT id FROM sync_log ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)
}
