package com.az.notes.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 同步后台任务（§6.4）：周期 / 启动 / 保存后防抖三种触发的统一入口。
 *
 * 不引入 hilt-work（额外依赖 + 自定义 WorkerFactory），改用 Hilt EntryPoint
 * 从应用容器取 [AutoSyncRunner]（与手动同步共享同一会话锁与执行器）。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = EntryPointAccessors
            .fromApplication(applicationContext, AutoSyncEntryPoint::class.java)
            .autoSyncRunner()
        runner.run(inputData.getString(KEY_TRIGGER) ?: TRIGGER_PERIODIC)
        return Result.success()
    }

    /** 应用级 Hilt EntryPoint（编译期生成访问器，无需 WorkerFactory）。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutoSyncEntryPoint {
        fun autoSyncRunner(): AutoSyncRunner
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_PERIODIC = "周期同步"
        const val TRIGGER_STARTUP = "启动同步"
        const val TRIGGER_SAVE = "保存后同步"
    }
}
