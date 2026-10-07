package com.az.notes

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogType
import com.az.notes.di.CoilHolder
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口。[@HiltAndroidApp] 触发 Hilt 代码生成并作为依赖图根。
 * 进程启动时对齐自动同步任务（§6.4）：周期任务按配置重建 + 可选启动后延迟同步；
 * 并安装未捕获异常记录器（写入调试日志，需在设置中开启收集）。
 */
@HiltAndroidApp
class AzNotesApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var debugLogRepository: DebugLogRepository

    @Inject
    lateinit var coilHolder: CoilHolder

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        appScope.launch {
            coilHolder.bootstrap()
            syncScheduler.reschedulePeriodic()
            syncScheduler.scheduleStartupSync()
        }
    }

    /**
     * App 级单例 ImageLoader：网络图片（http/https/data）经 [CoilHolder] 的受控 OkHttp
     * 客户端加载（体积上限 + 超时 + 不跟随重定向 + 请求头白名单，见 NetworkImageGuard）；
     * 关闭磁盘缓存，避免仓库外图片内容长期落盘。本地文件加载不受影响。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { coilHolder.client() }
                    )
                )
            }
            .diskCache(null)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    /**
     * 未捕获异常写入调试日志（type=APP，等级 ERROR；未开启收集时自动忽略）。
     * 记录后转发原处理器，不改变系统默认崩溃行为。栈截断 4000 字符防止单行过长。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                debugLogRepository.log(
                    DebugLogLevel.ERROR,
                    DebugLogType.APP,
                    "未捕获异常",
                    mapOf(
                        "thread" to thread.name,
                        "exception" to throwable::class.java.name,
                        "message" to throwable.message,
                        "stack" to throwable.stackTraceToString().take(4000)
                    )
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
