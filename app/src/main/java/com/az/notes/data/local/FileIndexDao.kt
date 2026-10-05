package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 文件索引 DAO（§4.1 / §4.2）。 */
@Dao
interface FileIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<FileIndexEntity>)

    @Query("DELETE FROM file_index")
    suspend fun clear()

    /** 按父目录列出直接子项：目录在前、按名称排序（§5.1）。 */
    @Query(
        """
        SELECT * FROM file_index
        WHERE parent_path = :parentPath
        ORDER BY is_dir DESC, name COLLATE NOCASE ASC
        """
    )
    fun childrenOf(parentPath: String): Flow<List<FileIndexEntity>>

    /** 文件名搜索（v1 仅文件名，§1.2）。 */
    @Query(
        """
        SELECT * FROM file_index
        WHERE is_dir = 0 AND name LIKE '%' || :keyword || '%'
        ORDER BY name COLLATE NOCASE ASC
        LIMIT 200
        """
    )
    fun searchByName(keyword: String): Flow<List<FileIndexEntity>>

    /** 全量重建索引。 */
    @Transaction
    suspend fun rebuild(entries: List<FileIndexEntity>) {
        clear()
        upsertAll(entries)
    }
}
