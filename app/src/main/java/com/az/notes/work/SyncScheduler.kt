package com.az.notes.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.az.notes.data.sync.SyncConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动同步调度器（§6.4）：WorkManager 周期任务 + 启动后延迟 + 保存后防抖，
 * 全部带 `NetworkType.CONNECTED` 约束；关闭 / 未配置时撤销对应任务。
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncConfigRepository: SyncConfigRepository
) {

    /** 周期任务与当前配置对齐（配置变更 / 应用启动时调用）。 */
    suspend fun reschedulePeriodic() {
        val config = runCatching { syncConfigRepository.config.first() }.getOrNull() ?: return
        val minutes = config.periodicInterval.minutes
        val manager = WorkManager.getInstance(context)
        if (!config.configured || minutes == null) {
            manager.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncWorker.TRIGGER_PERIODIC))
            .build()
        manager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 启动后延迟 10s 拉取一次（§6.4，可关）。 */
    suspend fun scheduleStartupSync() {
        val config = runCatching { syncConfigRepository.config.first() }.getOrNull() ?: return
        if (!config.configured || !config.autoSyncOnStart) return
        enqueueOneTime(WORK_STARTUP, STARTUP_DELAY_MS, SyncWorker.TRIGGER_STARTUP)
    }

    /** 编辑保存后 30s 防抖触发一次性同步（§6.4）：连续保存只保留最后一次。 */
    suspend fun scheduleSaveSync() {
        val config = runCatching { syncConfigRepository.config.first() }.getOrNull() ?: return
        if (!config.configured || !config.syncAfterSave) return
        enqueueOneTime(WORK_SAVE, SAVE_DEBOUNCE_MS, SyncWorker.TRIGGER_SAVE)
    }

    private fun enqueueOneTime(name: String, delayMs: Long, trigger: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraints())
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val WORK_PERIODIC = "az_notes_sync_periodic"
        const val WORK_STARTUP = "az_notes_sync_startup"
        const val WORK_SAVE = "az_notes_sync_save"
        const val STARTUP_DELAY_MS = 10_000L
        const val SAVE_DEBOUNCE_MS = 30_000L
    }
}
