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

    /** 未处理冲突数（顶栏角标）。 */
    @Query("SELECT COUNT(*) FROM conflict_record")
    fun count(): Flow<Int>

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
