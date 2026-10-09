package com.az.notes

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.az.notes.data.ai.AiDiag
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
        // AI 线路诊断挂接：开启调试日志收集后 type=AI 记录 SSE 包数 / 首包摘录 / 每轮事件
        AiDiag.sink = { msg, extra ->
            debugLogRepository.log(DebugLogLevel.INFO, DebugLogType.AI, msg, extra)
        }
        appScope.launch {
            coilHolder.bootstrap()
            logBuildFingerprint()
            syncScheduler.reschedulePeriodic()
            syncScheduler.scheduleStartupSync()
        }
    }

    /**
     * App 级单例 ImageLoader：网络图片（http/https/data）经 [CoilHolder] 的受控 OkHttp
     * 客户端加载（体积上限 + 超时 + 不跟随重定向 + 请求头白名单，见 NetworkImageGuard）；
     * 关闭磁盘缓存，避免仓库外图片内容长期落盘。本地文件加载不受影响。
     *
     * 注意：严禁设置 networkCachePolicy(DISABLED)——Coil 会把「网络读 + 磁盘读均关闭」
     * 翻译成请求头 `Cache-Control: no-cache, only-if-cached`（coil-network-core 的
     * NetworkFetcher.newRequest），无 HTTP 缓存的 OkHttp 由 CacheInterceptor 直接
     * 本地合成 504 Unsatisfiable Request：请求不发出、无头无体、毫秒级失败，远程图
     * 全灭且不产生任何网络层日志（2026-10 实机事故根因，见 conn 诊断字段）。
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
            .build()
    }

    /**
     * 构建指纹写日志（type=APP，msg=app-start）：版本 / 版本号 / CI 构建 commit。
     * 排障时先核对这条与下载的 Actions 运行号是否一致，可立即识别「装错包 / 未覆盖安装」。
     * 等日志配置首次回流后再写（配置快照未就绪时 log() 会静默丢弃）。
     */
    private suspend fun logBuildFingerprint() {
        // 等配置快照首次回流完成再写（固定 delay 在慢设备上可能未就绪而丢首条日志）
        runCatching { debugLogRepository.awaitConfigReady() }
        debugLogRepository.log(
            DebugLogLevel.INFO,
            DebugLogType.APP,
            "app-start",
            mapOf(
                "version" to BuildConfig.VERSION_NAME,
                "code" to BuildConfig.VERSION_CODE,
                "commit" to BuildConfig.BUILD_COMMIT
            )
        )
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
