package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** 同步基线 DAO（§4.2 / §6.1）；多仓库按 vault_id 隔离。 */
@Dao
interface SyncBaselineDao {

    @Query("SELECT * FROM sync_baseline WHERE vault_id = :vaultId")
    suspend fun getAll(vaultId: String): List<SyncBaselineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<SyncBaselineEntity>)

    @Query("DELETE FROM sync_baseline WHERE vault_id = :vaultId")
    suspend fun clear(vaultId: String)

    /** 删除单条基线（MOVE 写透时移除源路径条目）。 */
    @Query("DELETE FROM sync_baseline WHERE vault_id = :vaultId AND path = :path")
    suspend fun deleteByPath(vaultId: String, path: String)

    /** 整体替换指定仓库的基线（CommitBaseline 阶段调用）。 */
    @Transaction
    suspend fun replaceAll(vaultId: String, entries: List<SyncBaselineEntity>) {
        clear(vaultId)
        upsertAll(entries)
    }
}
