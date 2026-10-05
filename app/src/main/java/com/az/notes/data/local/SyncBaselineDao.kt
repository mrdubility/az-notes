package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** 同步基线 DAO（§4.2 / §6.1）。 */
@Dao
interface SyncBaselineDao {

    @Query("SELECT * FROM sync_baseline")
    suspend fun getAll(): List<SyncBaselineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<SyncBaselineEntity>)

    @Query("DELETE FROM sync_baseline")
    suspend fun clear()

    /** 整体替换基线（CommitBaseline 阶段调用）。 */
    @Transaction
    suspend fun replaceAll(entries: List<SyncBaselineEntity>) {
        clear()
        upsertAll(entries)
    }
}
