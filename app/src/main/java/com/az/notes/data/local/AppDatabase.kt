package com.az.notes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用本地数据库（§4.2）。
 * Demo（M1/M2）阶段先落 `file_index` 与 `read_progress` 两张表；
 * `sync_baseline` / `sync_log` / `conflict_record` / `move_hint` 随 M3 同步引擎接入时补充。
 */
@Database(
    entities = [
        FileIndexEntity::class,
        ReadProgressEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun readProgressDao(): ReadProgressDao

    companion object {
        const val NAME = "az_notes.db"
    }
}
