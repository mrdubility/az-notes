package com.az.notes.di

import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogType
import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.ui.reader.NetworkImageGuard
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Coil 网络图片客户端持有者：按设置页的「体积上限 / 超时」构建受控 [OkHttpClient]
 * （见 [NetworkImageGuard]），配置变化时重建，供 App 级 ImageLoader 取用。
 *
 * 体积上限走 provider 闭包（读设置快照），设置改动后无需重建客户端即可作用于下一次读取；
 * 超时属于客户端级参数，改动时整体重建。
 */
@Singleton
class CoilHolder @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val debugLogRepository: DebugLogRepository
) {

    /** 当前生效配置（超时变化时更新，渲染层以此为 key 重建 painter）。 */
    val config = MutableStateFlow(
        GuardConfig(NetworkImageGuard.DEFAULT_MAX_MB.toLong() * 1024 * 1024, NetworkImageGuard.DEFAULT_TIMEOUT_SECONDS)
    )

    private var cached: OkHttpClient? = null
    private var cachedTimeout = -1

    /** 取用受控客户端（惰性构建；超时配置变化时重建）。 */
    @Synchronized
    fun client(): OkHttpClient {
        val timeout = config.value.timeoutSeconds
        val existing = cached
        if (existing != null && cachedTimeout == timeout) return existing
        val built = NetworkImageGuard.newClient(
            maxBytesProvider = { currentMaxBytes() },
            timeoutSeconds = timeout,
            // 连接诊断落 IMAGE 日志：远程图命中不明来源的 504/403 时，据此判断
            // 实际连到的 IP / 是否走本地代理 / 协商的协议与 TLS（未开日志时静默）。
            logEvent = { data ->
                debugLogRepository.log(
                    DebugLogLevel.INFO, DebugLogType.IMAGE, "image-conn", data
                )
            }
        )
        cached = built
        cachedTimeout = timeout
        return built
    }

    /** 单张图片体积上限（字节）；0 = 不加载网络图片。 */
    fun currentMaxBytes(): Long = config.value.maxBytes

    /** 把设置回流到护栏配置（由主 ViewModel 在 settings 流上调用）。 */
    fun applySettings(maxBytes: Long, timeoutSeconds: Int) {
        val next = GuardConfig(maxBytes, timeoutSeconds)
        if (config.value != next) {
            // 超时变化需要新客户端；仅体积变化则复用（provider 已读到新值）
            config.value = next
            if (next.timeoutSeconds != cachedTimeout) cached = null
        }
    }

    /** 启动时同步一次当前设置（异步回流前也有可用配置）。 */
    suspend fun bootstrap() {
        val s = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return
        applySettings(s.remoteImageMaxBytes, s.remoteImageTimeoutSeconds)
    }

    /** 护栏配置快照。 */
    data class GuardConfig(val maxBytes: Long, val timeoutSeconds: Int)
}
