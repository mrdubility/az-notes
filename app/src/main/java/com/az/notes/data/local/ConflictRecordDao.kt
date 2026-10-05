package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 冲突记录 DAO（§4.2 / §6.2）。 */
@Dao
interface ConflictRecordDao {

    @Insert
    suspend fun insert(entry: ConflictRecordEntity)

    /** 最近 [limit] 条（倒序），供同步页展示。 */
    @Query("SELECT * FROM conflict_record ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ConflictRecordEntity>>

    /** 全部记录（同步完成时清理副本已合并 / 删除的条目用）。 */
    @Query("SELECT * FROM conflict_record")
    suspend fun getAll(): List<ConflictRecordEntity>

    /** 按 id 批量删除（已解决的冲突条目）。 */
    @Query("DELETE FROM conflict_record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 全部清除（用户确认已人工合并后调用）。 */
    @Query("DELETE FROM conflict_record")
    suspend fun clear()

    /** 仅保留最近 [keep] 条，防止无界增长。 */
    @Query(
        "DELETE FROM conflict_record WHERE id NOT IN " +
            "(SELECT id FROM conflict_record ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)
}
