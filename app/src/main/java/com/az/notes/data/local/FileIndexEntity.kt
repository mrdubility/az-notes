package com.az.notes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 文件索引（§4.2）。目录树与文件名搜索的数据源，避免每次实时扫盘。
 * 主键为相对 Vault 根的路径。
 */
@Entity(tableName = "file_index")
data class FileIndexEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_dir") val isDir: Boolean,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
    @ColumnInfo(name = "parent_path") val parentPath: String,
    @ColumnInfo(name = "depth") val depth: Int
)
