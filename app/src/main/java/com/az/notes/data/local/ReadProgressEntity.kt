package com.az.notes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 阅读进度（§4.2 / §5.5）。仅存本机，跨会话恢复滚动位置。
 */
@Entity(tableName = "read_progress")
data class ReadProgressEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "scroll_index") val scrollIndex: Int,
    @ColumnInfo(name = "scroll_offset") val scrollOffset: Int,
    @ColumnInfo(name = "percent") val percent: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
