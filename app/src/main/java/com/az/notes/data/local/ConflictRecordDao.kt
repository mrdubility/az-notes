package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 冲突记录 DAO（§4.2 / §6.2）；多仓库按 vault_id 隔离。 */
@Dao
interface ConflictRecordDao {

    @Insert
    suspend fun insert(entry: ConflictRecordEntity)

    /** 指定仓库最近 [limit] 条（倒序），供同步页展示。 */
    @Query("SELECT * FROM conflict_record WHERE vault_id = :vaultId ORDER BY id DESC LIMIT :limit")
    fun recent(vaultId: String, limit: Int): Flow<List<ConflictRecordEntity>>

    /** 指定仓库全部记录（同步完成时清理副本已合并 / 删除的条目用）。 */
    @Query("SELECT * FROM conflict_record WHERE vault_id = :vaultId")
    suspend fun getAll(vaultId: String): List<ConflictRecordEntity>

    /** 按 id 批量删除（已解决的冲突条目）。 */
    @Query("DELETE FROM conflict_record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 清除指定仓库全部记录（用户确认已人工合并 / 移除仓库时调用）。 */
    @Query("DELETE FROM conflict_record WHERE vault_id = :vaultId")
    suspend fun clear(vaultId: String)

    /** 指定仓库仅保留最近 [keep] 条，防止无界增长。 */
    @Query(
        "DELETE FROM conflict_record WHERE vault_id = :vaultId AND id NOT IN " +
            "(SELECT id FROM conflict_record WHERE vault_id = :vaultId ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimTo(vaultId: String, keep: Int)
}
