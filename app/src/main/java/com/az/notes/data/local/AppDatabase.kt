package com.az.notes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用本地数据库（§4.2）。
 * v2：随 M3 同步引擎接入 `sync_baseline` / `sync_log`。
 * v3：M4 接入 `conflict_record`（冲突指纹与解决方式）。
 * `move_hint` 未采用：重命名改用“即时 MOVE + 扫描期 size 配对启发式”，
 * 无需跨会话保存改名提示（见 SyncEngine）。
 */
@Database(
    entities = [
        FileIndexEntity::class,
        ReadProgressEntity::class,
        SyncBaselineEntity::class,
        SyncLogEntity::class,
        ConflictRecordEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun readProgressDao(): ReadProgressDao
    abstract fun syncBaselineDao(): SyncBaselineDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun conflictRecordDao(): ConflictRecordDao

    companion object {
        const val NAME = "az_notes.db"
    }
}
