package com.az.notes

import android.app.Application
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口。[@HiltAndroidApp] 触发 Hilt 代码生成并作为依赖图根。
 * 进程启动时对齐自动同步任务（§6.4）：周期任务按配置重建 + 可选启动后延迟同步。
 */
@HiltAndroidApp
class AzNotesApp : Application() {

    @Inject
    lateinit var syncScheduler: SyncScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            syncScheduler.reschedulePeriodic()
            syncScheduler.scheduleStartupSync()
        }
    }
}
