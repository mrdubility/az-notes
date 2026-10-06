package com.az.notes.data.debug

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 日志等级（写入条件：level.ordinal >= 配置的最低等级）。 */
enum class DebugLogLevel { DEBUG, INFO, WARN, ERROR }

/** 日志类型（按业务归类，配置里可多选）。 */
enum class DebugLogType {
    /** 同步引擎：plan 决策 / 执行 / 基线提交。 */
    SYNC,

    /** 网络：WebDAV 请求重试与异常（并入 SYNC 失败详情）。 */
    NET,

    /** 后台调度：自动同步触发与安全阀。 */
    WORK,

    /** 文件读写与回收站。 */
    FILE,

    /** 应用生命周期与未捕获崩溃。 */
    APP
}

/** 日志配置快照。 */
data class DebugLogConfig(
    val enabled: Boolean = false,
    val minLevel: DebugLogLevel = DebugLogLevel.INFO,
    val types: Set<DebugLogType> = DebugLogType.entries.toSet()
)

/** 存储占用状态（设置页展示）。 */
data class DebugLogStatus(
    val segmentCount: Int = 0,
    val totalBytes: Long = 0,
    val dirPath: String = ""
)

private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "az_notes_debug")

/**
 * Debug 日志收集（排查同步等问题用，默认关闭）。
 *
 * 格式：JSON Lines——每行一条独立 JSON（ts/level/type/msg/extra），便于 AI 与脚本解析。
 * 分片：单片超过 [SEGMENT_MAX_BYTES] 即切换新片，最多保留 [SEGMENT_LIMIT] 片（超出删最旧），
 * 总占用上限约 2 MB。
 * 写入路径全部同步落盘（synchronized + appendText）：崩溃捕获处理器等非协程上下文无法挂起，
 * 且单行追加耗时极低；配置经内存缓存同步读取（DataStore 仅异步持久化配置本身）。
 */
@Singleton
class DebugLogRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val MIN_LEVEL = stringPreferencesKey("min_level")
        val TYPES = stringPreferencesKey("types")
    }

    /** 配置快照缓存：写入路径（含崩溃处理器）需同步判定，不能挂起读 DataStore。 */
    @Volatile
    private var snapshot = DebugLogConfig()

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val logDir: File get() = File(context.filesDir, DIR_NAME)

    /** 供设置页观察的配置流。 */
    val config: Flow<DebugLogConfig> = context.debugDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            DebugLogConfig(
                enabled = prefs[Keys.ENABLED] ?: false,
                minLevel = prefs[Keys.MIN_LEVEL]
                    ?.let { runCatching { DebugLogLevel.valueOf(it) }.getOrNull() }
                    ?: DebugLogLevel.INFO,
                types = prefs[Keys.TYPES]
                    ?.split(',')
                    ?.mapNotNull { runCatching { DebugLogType.valueOf(it) }.getOrNull() }
                    ?.toSet()
                    ?: DebugLogType.entries.toSet()
            )
        }

    init {
        // 应用启动即跟踪配置变化：开关调整后无需重启即刻生效
        scope.launch {
            runCatching { config.collect { snapshot = it } }
        }
    }

    suspend fun setEnabled(enabled: Boolean) =
        context.debugDataStore.edit { it[Keys.ENABLED] = enabled }

    suspend fun setMinLevel(level: DebugLogLevel) =
        context.debugDataStore.edit { it[Keys.MIN_LEVEL] = level.name }

    /** 类型多选：至少保留一种（空集合回退为全选，避免“开着但什么都不记”）。 */
    suspend fun setTypes(types: Set<DebugLogType>) = context.debugDataStore.edit {
        it[Keys.TYPES] = (types.ifEmpty { DebugLogType.entries.toSet() })
            .joinToString(",") { type -> type.name }
    }

    /**
     * 写一条日志（同步落盘，任意线程可调；未启用 / 等级或类型不匹配时静默丢弃）。
     * [extra] 为结构化上下文：值为数值 / 布尔原样输出，其余转字符串，字段顺序保持稳定。
     */
    fun log(
        level: DebugLogLevel,
        type: DebugLogType,
        msg: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val cfg = snapshot
        if (!cfg.enabled || level.ordinal < cfg.minLevel.ordinal || type !in cfg.types) return
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        val line = buildString {
            append("{\"ts\":\"").append(ts).append('"')
            append(",\"level\":\"").append(level.name).append('"')
            append(",\"type\":\"").append(type.name).append('"')
            append(",\"msg\":\"").append(escape(msg)).append('"')
            if (extra.isNotEmpty()) {
                append(",\"extra\":{")
                var first = true
                for ((key, value) in extra) {
                    if (!first) append(',')
                    first = false
                    append('"').append(escape(key)).append("\":")
                    append(valueToJson(value))
                }
                append('}')
            }
            append('}')
        }
        write(line)
    }

    /** 存储占用状态（设置页展示）。 */
    fun status(): DebugLogStatus = synchronized(lock) {
        val segments = listSegments()
        DebugLogStatus(
            segmentCount = segments.size,
            totalBytes = segments.sumOf { it.length() },
            dirPath = logDir.absolutePath
        )
    }

    /** 读取全部日志行（按分片时间序 + 片内写入序；未启用过则为空）。 */
    fun readAllLines(): List<String> = synchronized(lock) {
        runCatching {
            listSegments().flatMap { seg ->
                seg.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** 清空全部分片。 */
    fun clearAll() {
        synchronized(lock) {
            runCatching { logDir.listFiles()?.forEach { it.delete() } }
        }
    }

    /** 导出为单个 JSONL 文件到 SAF 目标（合并全部现有分片）；返回导出行数。 */
    suspend fun exportTo(target: Uri): Int = withContext(Dispatchers.IO) {
        val lines = readAllLines()
        val out = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IOException("无法打开导出目标")
        out.use { stream ->
            for (line in lines) {
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        }
        lines.size
    }

    // ---------------------------------------------------------------- 内部

    private fun write(line: String) {
        synchronized(lock) {
            runCatching {
                logDir.mkdirs()
                currentSegment().appendText(line + "\n", Charsets.UTF_8)
                trimSegments()
            }
        }
    }

    /** 当前待写分片：最新一片未超限则续写，否则新建（文件名内嵌时间戳，文件名序即时间序）。 */
    private fun currentSegment(): File {
        val latest = listSegments().lastOrNull()
        return if (latest != null && latest.length() < SEGMENT_MAX_BYTES) latest
        else File(logDir, "$SEGMENT_PREFIX${fileStampFormat.format(Date())}.jsonl")
    }

    private fun listSegments(): List<File> =
        logDir.listFiles { f -> f.isFile && f.name.startsWith(SEGMENT_PREFIX) && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun trimSegments() {
        val segments = listSegments()
        if (segments.size > SEGMENT_LIMIT) {
            segments.take(segments.size - SEGMENT_LIMIT).forEach { it.delete() }
        }
    }

    private fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private companion object {
        const val DIR_NAME = "debug_logs"
        const val SEGMENT_PREFIX = "debug-"

        /** 单分片大小上限（256 KB）。 */
        const val SEGMENT_MAX_BYTES = 256 * 1024L

        /** 分片数量上限（超出删最旧；8 片 × 256 KB ≈ 2 MB 总量上限）。 */
        const val SEGMENT_LIMIT = 8

        val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
