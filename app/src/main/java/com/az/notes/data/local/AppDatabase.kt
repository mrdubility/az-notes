package com.az.notes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用本地数据库（§4.2）。
 * v2：随 M3 同步引擎接入 `sync_baseline` / `sync_log`。
 * v3：M4 接入 `conflict_record`（冲突指纹与解决方式）。
 * v4：移除废弃的 `file_index`（目录列表直接读文件系统，不再维护索引表）。
 * v5：多仓库——同步基线 / 冲突记录 / 同步日志按 `vault_id` 隔离
 *     （基线主键改为 (vault_id, path)；破坏性升级，历史同步数据自动重建）。
 * v6：移除阅读进度（read_progress）——预览不再恢复上次阅读位置、主页不再展示进度圆点
 *     （破坏性升级，自动重建）。
 * v7：冲突记录增 `merge_state` / `marker_count`（同文件内合并的状态与残留块数）。
 * v8：双链索引持久化——`link_note` / `link_ref` / `link_alias` 三表按仓库作用域保存
 *     文件指纹、原始引用与别名声明，冷启动只做 stat 比对、不再重读全库正文
 *     （破坏性升级，首次启动重建一次索引）。
 * `move_hint` 未采用：重命名改用“即时 MOVE + 扫描期 size 配对启发式”，
 * 无需跨会话保存改名提示（见 SyncEngine）。
 */
@Database(
    entities = [
        SyncBaselineEntity::class,
        SyncLogEntity::class,
        ConflictRecordEntity::class,
        LinkNoteEntity::class,
        LinkRefEntity::class,
        LinkAliasEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncBaselineDao(): SyncBaselineDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun conflictRecordDao(): ConflictRecordDao
    abstract fun linkCacheDao(): LinkCacheDao

    companion object {
        const val NAME = "az_notes.db"
    }
}
