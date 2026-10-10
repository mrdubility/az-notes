package com.az.notes.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.az.notes.data.sync.SyncConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

    /** 打开应用后立即拉取一次（§6.4，可关）：尽早上到其他端的新增 / 修改。 */
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

    /**
     * 冲刷排队中的“保存后同步”：App 退到后台（onStop）时调用——若 30 秒防抖任务仍在等待，
     * 替换为即时任务立即执行，缩小“退出应用后变更未上传”的窗口。
     * （任务本身持久化在 WorkManager，进程被杀也会由系统兜底执行，此方法只是提前触发。）
     */
    suspend fun flushPendingSaveSync() {
        val config = runCatching { syncConfigRepository.config.first() }.getOrNull() ?: return
        if (!config.configured || !config.syncAfterSave) return
        val infos = runCatching {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_SAVE).get()
            }
        }.getOrNull() ?: return
        if (infos.any { it.state == WorkInfo.State.ENQUEUED }) {
            enqueueOneTime(WORK_SAVE, 0L, SyncWorker.TRIGGER_SAVE)
        }
    }

    /**
     * 入队一次性同步任务：同名任务【运行中】时追加而非取消（Fix C）——
     * REPLACE 会取消正在进行的同步会话，使其跳过基线提交，下一轮把已同步文件
     * 误判为“首次共存冲突”（自动保存每秒级高频触发时曾复现）；
     * 未运行时仍用 REPLACE：防抖合并连续保存，只保留最后一次。
     */
    private suspend fun enqueueOneTime(name: String, delayMs: Long, trigger: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraints())
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger))
            .build()
        val manager = WorkManager.getInstance(context)
        val running = runCatching {
            withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(name).get() }
        }.getOrDefault(emptyList()).any { it.state == WorkInfo.State.RUNNING }
        manager.enqueueUniqueWork(
            name,
            if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val WORK_PERIODIC = "az_notes_sync_periodic"
        const val WORK_STARTUP = "az_notes_sync_startup"
        const val WORK_SAVE = "az_notes_sync_save"

        /** 打开应用后立即触发（0 延迟；仍带网络约束，离线时由 WorkManager 等网络恢复） */
        const val STARTUP_DELAY_MS = 0L
        const val SAVE_DEBOUNCE_MS = 30_000L
    }
}
