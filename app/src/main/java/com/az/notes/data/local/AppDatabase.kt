package com.az.notes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用本地数据库（§4.2）。
 * v2：随 M3 同步引擎接入 `sync_baseline` / `sync_log` 两张表
 * （`conflict_record` / `move_hint` 留待 M4）。
 */
@Database(
    entities = [
        FileIndexEntity::class,
        ReadProgressEntity::class,
        SyncBaselineEntity::class,
        SyncLogEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun readProgressDao(): ReadProgressDao
    abstract fun syncBaselineDao(): SyncBaselineDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        const val NAME = "az_notes.db"
    }
}
