package com.az.notes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 双链索引持久化缓存的读写入口。
 *
 * 只按仓库作用域整批读写，不做跨文件关联查询：派生表（正向 / 反向）在内存里重算，
 * 持久化只负责保存"原始引用 + 文件指纹 + 别名声明"。
 */
@Dao
interface LinkCacheDao {

    @Query("SELECT * FROM link_note WHERE scope = :scope")
    suspend fun notes(scope: String): List<LinkNoteEntity>

    @Query("SELECT * FROM link_ref WHERE scope = :scope ORDER BY source_path, ordinal")
    suspend fun refs(scope: String): List<LinkRefEntity>

    @Query("SELECT * FROM link_alias WHERE scope = :scope")
    suspend fun aliases(scope: String): List<LinkAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(notes: List<LinkNoteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRefs(refs: List<LinkRefEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(aliases: List<LinkAliasEntity>)

    @Query("DELETE FROM link_note WHERE scope = :scope AND path IN (:paths)")
    suspend fun deleteNotes(scope: String, paths: List<String>)

    @Query("DELETE FROM link_ref WHERE scope = :scope AND source_path IN (:paths)")
    suspend fun deleteRefs(scope: String, paths: List<String>)

    @Query("DELETE FROM link_alias WHERE scope = :scope AND relative_path IN (:relativePaths)")
    suspend fun deleteAliases(scope: String, relativePaths: List<String>)

    @Query("DELETE FROM link_note WHERE scope = :scope")
    suspend fun clearNotes(scope: String)

    @Query("DELETE FROM link_ref WHERE scope = :scope")
    suspend fun clearRefs(scope: String)

    @Query("DELETE FROM link_alias WHERE scope = :scope")
    suspend fun clearAliases(scope: String)
}
