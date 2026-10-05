package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 阅读进度 DAO（§5.5）。 */
@Dao
interface ReadProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadProgressEntity)

    @Query("SELECT * FROM read_progress WHERE path = :path LIMIT 1")
    suspend fun getProgress(path: String): ReadProgressEntity?

    @Query("SELECT * FROM read_progress WHERE path = :path LIMIT 1")
    fun observeProgress(path: String): Flow<ReadProgressEntity?>

    /** 全量进度（主页列表阅读进度小圆点用）。 */
    @Query("SELECT * FROM read_progress")
    suspend fun getAll(): List<ReadProgressEntity>

    @Query("DELETE FROM read_progress WHERE path = :path")
    suspend fun delete(path: String)
}
