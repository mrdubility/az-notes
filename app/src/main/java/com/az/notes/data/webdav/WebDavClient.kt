package com.az.notes.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 轻量 WebDAV 客户端（§2.3 / §6.6）。
 *
 * 用 OkHttp 直接实现同步引擎所需原语：PROPFIND / GET / PUT / MKCOL / DELETE，
 * 免去 dav4jvm 的 core library desugaring 依赖（见 gradle 版本目录注释）。
 * 鉴权为 Basic（坚果云“应用密码”、Nextcloud 等自托管通用）。
 *
 * 请求纪律（参考坚果云官方 Obsidian 同步插件的限流策略，避免触发服务端限流）：
 * 传输类请求严格串行、相邻间隔不小于 [MIN_REQUEST_INTERVAL_MS]；
 * 目录扫描（PROPFIND）走独立并发通道（[SCAN_CONCURRENCY] 并发 + [SCAN_REQUEST_INTERVAL_MS] 间隔），
 * 并在探测到限流时自动放慢回保守节奏；
 * 遇 503 / 429（限流）自动等待 [RATE_LIMIT_RETRY_DELAY_MS] 后重试，
 * 并通过 [onRateLimited] 向调用方反馈等待状态。
 *
 * 所有公开方法均切换到 [Dispatchers.IO] 执行；[baseUrl] 为同步根目录
 * （如 `https://dav.jianguoyun.com/dav/az-notes/`），远端路径一律相对该根。
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    /** 限流等待回调（attempt 从 1 开始），用于向 UI 展示“正在等待重试”。 */
    private val onRateLimited: (attempt: Int) -> Unit = {}
) {

    /** 远端条目（相对同步根的路径）。 */
    data class RemoteEntry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        /** epoch millis；解析失败为 0 */
        val lastModified: Long,
        val etag: String?
    )

    /**
     * 一次远端全树扫描的结果。
     *
     * [truncatedDirs]：条目数达到服务端单次返回上限（坚果云为 750 个）的目录，
     * 其列表可能被分页截断而不完整（续取协议未公开），调用方必须避免仅凭
     * “远端没列出”就对本地文件做删除类决策。
     */
    data class ScanResult(
        val entries: List<RemoteEntry>,
        val truncatedDirs: Set<String>
    )

    private val rootUrl: String = baseUrl.trimEnd('/') + "/"
    private val basePath: String = runCatching { rootUrl.toHttpUrl().encodedPath }
        .getOrDefault("/")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 请求闸门：保证传输类请求串行执行（对应官方插件 Bottleneck maxConcurrent = 1）。 */
    private val requestGate = Mutex()
    private var lastRequestAtMillis = 0L

    /** 扫描闸门：目录扫描按 [SCAN_CONCURRENCY] 并发，独立于传输串行通道。 */
    private val scanGate = Semaphore(SCAN_CONCURRENCY)
    private val scanSlotMutex = Mutex()
    private var lastScanRequestAtMillis = 0L

    /** 本次会话累计被限流次数：> 0 时扫描自动降速（每次 +50ms，封顶 [MIN_REQUEST_INTERVAL_MS]）。 */
    @Volatile
    private var rateLimitHits = 0

    // ------------------------------------------------------------------ 连接测试

    /** 连接测试（§6.6）：PROPFIND 根目录 Depth:0，验证地址与凭据。 */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = auth(Request.Builder().url(rootUrl))
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .header("Depth", "0")
                .build()
            execute(request).use { resp ->
                if (resp.code !in 200..299) {
                    throw httpError("连接测试失败", resp)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 列目录

    /**
     * PROPFIND Depth:1 逐层递归拉取全部条目（§6.1 Scan）。
     * 层内目录并发扫描（走 [scanGate]，受扫描间隔节流），共享状态（结果 / 已访问 /
     * 截断目录）只在主协程汇总写入；[visited] 保证每个目录只请求一次——
     * 服务端 Depth:1 响应会包含被请求目录自身，若不剔除会被反复入队重复扫描。
     */
    suspend fun listAll(
        onDirectoryScanned: (scannedDirs: Int, discoveredFiles: Int) -> Unit = { _, _ -> }
    ): ScanResult =
        withContext(Dispatchers.IO) {
            val result = ArrayList<RemoteEntry>()
            val truncated = HashSet<String>()
            val visited = HashSet<String>()
            visited += ""
            var scanned = 0
            var discoveredFiles = 0
            var layer = listOf("")
            while (layer.isNotEmpty()) {
                // 同一层目录并发拉取；任一层失败即整体失败（与原串行行为一致）
                val layerEntries = coroutineScope {
                    layer.map { dir -> async { dir to propfind(dir) } }.awaitAll()
                }
                val next = ArrayList<String>()
                for ((dir, all) in layerEntries) {
                    // 达到服务端单次返回上限（坚果云 750 个）：可能仍有未列出的条目，标记为不可信目录
                    if (all.size >= SINGLE_PAGE_LIMIT) truncated += dir
                    scanned++
                    for (entry in all) {
                        // Depth:1 响应包含被请求目录自身（href 与请求路径一致），剔除避免重复入队
                        if (entry.isDirectory && entry.path == dir) continue
                        if (entry.path.isBlank()) continue
                        if (!entry.isDirectory) discoveredFiles++
                        result += entry
                        if (entry.isDirectory && visited.add(entry.path)) {
                            next += entry.path
                        }
                    }
                }
                layer = next
                onDirectoryScanned(scanned, discoveredFiles)
            }
            ScanResult(result, truncated)
        }

    /** PROPFIND Depth:1 拉取单个目录的原始条目（含被请求目录自身；截断判定见 [listAll]）。 */
    private suspend fun propfind(relativeDir: String): List<RemoteEntry> {
        val request = auth(Request.Builder().url(dirUrl(relativeDir)))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
            .header("Depth", "1")
            .build()
        execute(request) { awaitScanSlot() }.use { resp ->
            if (resp.code !in 200..299) {
                throw httpError("列目录失败（${relativeDir.ifBlank { "/" }}）", resp)
            }
            return parseMultiStatus(resp.body?.string().orEmpty())
        }
    }

    /**
     * 取单个远端条目的最新属性（PROPFIND Depth:0）；不存在返回 null。
     * 用于上传后刷新基线（etag / size / mtime），避免为少量变更重扫全树。
     */
    suspend fun stat(relativePath: String): RemoteEntry? = withContext(Dispatchers.IO) {
        val request = auth(Request.Builder().url(fileUrl(relativePath)))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
            .header("Depth", "0")
            .build()
        execute(request).use { resp ->
            if (resp.code == 404) return@withContext null
            if (resp.code !in 200..299) {
                throw httpError("读取远端属性 $relativePath 失败", resp)
            }
            parseMultiStatus(resp.body?.string().orEmpty()).firstOrNull()
        }
    }

    // ------------------------------------------------------------------ 传输

    /** 下载到本地 [target]（先写 `.part` 再改名，避免半截文件）。远端不存在返回 false。 */
    suspend fun download(relativePath: String, target: File): Boolean = withContext(Dispatchers.IO) {
        val request = auth(Request.Builder().url(fileUrl(relativePath))).get().build()
        execute(request).use { resp ->
            if (resp.code == 404) return@withContext false
            if (resp.code !in 200..299) {
                throw httpError("下载 $relativePath 失败", resp)
            }
            val body = resp.body ?: throw IOException("下载 $relativePath 失败：响应为空")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }
    }

    /** 上传 [source] 到 [relativePath]（自动逐级 MKCOL 建目录）。 */
    suspend fun upload(source: File, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        ensureParentDirectories(relativePath)
        val media = mediaTypeFor(relativePath)
        val request = auth(Request.Builder().url(fileUrl(relativePath)))
            .put(source.asRequestBody(media))
            .build()
        execute(request).use { resp ->
            if (resp.code !in 200..299) {
                throw httpError("上传 $relativePath 失败", resp)
            }
        }
        true
    }

    /** 删除远端文件 / 目录；404 视为已不存在（成功）。 */
    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val request = auth(Request.Builder().url(fileUrl(relativePath))).delete().build()
        execute(request).use { resp ->
            resp.code in 200..299 || resp.code == 404
        }
    }

    /**
     * 移动 / 重命名远端条目（MOVE，`Overwrite: F` 不覆盖已存在的目标）。
     * 用于本地重命名后同步到云端，避免退化为“删除旧路径 + 重新上传新路径”。
     * 源不存在（404）视为无需处理，返回 false 交由常规同步处理。
     */
    suspend fun move(
        fromRelative: String,
        toRelative: String,
        isDirectory: Boolean = false
    ): Boolean =
        withContext(Dispatchers.IO) {
            // 集合（文件夹）的 Destination 需以 '/' 结尾，部分服务端据此区分文件与目录
            val destination = fileUrl(toRelative).let { if (isDirectory) "$it/" else it }
            val request = auth(Request.Builder().url(fileUrl(fromRelative)))
                .method("MOVE", null)
                .header("Destination", destination)
                .header("Overwrite", "F")
                .build()
            execute(request).use { resp -> resp.code in 200..299 }
        }

    // ------------------------------------------------------------------ 内部

    /**
     * 统一请求入口（请求纪律，对齐坚果云官方 Obsidian 插件的 Bottleneck 配置）：
     * [slot] 负责节流与并发约束（默认传输串行通道，扫描传入 [awaitScanSlot]）；
     * 收到 503 / 429（服务端限流）时等待后自动重试。
     */
    private suspend fun execute(
        request: Request,
        slot: suspend () -> Unit = { awaitRequestSlot() }
    ): Response {
        var attempt = 0
        while (true) {
            slot()
            val response = client.newCall(request).execute()
            if (response.code == 503 || response.code == 429) {
                response.close()
                attempt++
                rateLimitHits++
                if (attempt > RATE_LIMIT_MAX_RETRIES) {
                    throw IOException(
                        "服务端请求限流（HTTP ${response.code}），已自动等待重试 $RATE_LIMIT_MAX_RETRIES 次仍被拒绝，请稍后再试"
                    )
                }
                onRateLimited(attempt)
                delay(RATE_LIMIT_RETRY_DELAY_MS)
                continue
            }
            return response
        }
    }

    /** 传输通道：保证相邻请求间隔不小于 [MIN_REQUEST_INTERVAL_MS]（多协程下也串行排队）。 */
    private suspend fun awaitRequestSlot() = requestGate.withLock {
        val wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMillis)
        if (wait > 0) delay(wait)
        lastRequestAtMillis = System.currentTimeMillis()
    }

    /**
     * 扫描通道：[SCAN_CONCURRENCY] 并发 + 相邻间隔 [SCAN_REQUEST_INTERVAL_MS]；
     * 探测到限流后间隔自动放慢（[currentScanIntervalMs]），避免持续触发风控。
     */
    private suspend fun awaitScanSlot() = scanGate.withPermit {
        scanSlotMutex.withLock {
            val wait = currentScanIntervalMs() - (System.currentTimeMillis() - lastScanRequestAtMillis)
            if (wait > 0) delay(wait)
            lastScanRequestAtMillis = System.currentTimeMillis()
        }
    }

    /** 扫描间隔：基础 [SCAN_REQUEST_INTERVAL_MS]，每次限流 +50ms，封顶 [MIN_REQUEST_INTERVAL_MS]。 */
    private fun currentScanIntervalMs(): Long =
        (SCAN_REQUEST_INTERVAL_MS + rateLimitHits * 50L).coerceAtMost(MIN_REQUEST_INTERVAL_MS)

    /** 构造带服务端有效信息的友好错误（优先提取 WebDAV 错误 XML 中的 exception / message）。 */
    private fun httpError(action: String, response: Response): IOException {
        val serverMessage = runCatching { response.body?.string() }
            .getOrNull()
            ?.let { body -> RE_SERVER_MESSAGE.find(body)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val friendly = when (response.code) {
            401 -> "身份验证失败，请检查账号与应用密码（坚果云需使用应用密码）"
            403 -> "服务器拒绝访问，如频繁出现可能触发限流，请稍后重试"
            423 -> "远端资源已被锁定"
            507 -> "云端存储空间不足"
            in 500..599 -> "服务器暂时不可用，请稍后重试"
            else -> null
        }
        val detail = serverMessage ?: friendly
        val suffix = if (detail != null) "$detail（HTTP ${response.code}）"
        else "HTTP ${response.code} ${response.message}"
        return IOException("$action：$suffix")
    }

    private fun auth(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))

    private suspend fun ensureParentDirectories(relativePath: String) {
        val dirs = relativePath.split('/').dropLast(1)
        var acc = ""
        for (dir in dirs) {
            acc = if (acc.isEmpty()) dir else "$acc/$dir"
            val request = auth(Request.Builder().url(dirUrl(acc))).method("MKCOL", null).build()
            execute(request).use { resp ->
                // 201 创建成功；405 / 301 表示已存在或已重定向；其余视为失败
                if (resp.code !in 200..299 && resp.code != 405 && resp.code != 301) {
                    throw httpError("创建远端目录 $acc", resp)
                }
            }
        }
    }

    private fun parseMultiStatus(xml: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        RE_RESPONSE.findAll(xml).forEach { match ->
            val block = match.groupValues[1]
            val hrefRaw = RE_HREF.find(block)?.groupValues?.get(1) ?: return@forEach
            val relative = hrefRelative(hrefRaw) ?: return@forEach
            if (relative.isBlank()) return@forEach
            val isDir = RE_COLLECTION.containsMatchIn(block)
            val size = RE_LENGTH.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val mtime = RE_LASTMOD.find(block)?.groupValues?.get(1)
                ?.let { parseHttpDate(it.trim()) } ?: 0L
            val etag = RE_ETAG.find(block)?.groupValues?.get(1)?.trim()
            out += RemoteEntry(relative, isDir, size, mtime, etag)
        }
        return out
    }

    /** 把服务器返回的 href 换算为相对同步根的路径（先按编码匹配前缀，再解码尾部）。 */
    private fun hrefRelative(hrefRaw: String): String? {
        val path = when {
            hrefRaw.startsWith("http://", ignoreCase = true) ||
                hrefRaw.startsWith("https://", ignoreCase = true) ->
                runCatching { hrefRaw.toHttpUrl().encodedPath }.getOrNull() ?: return null
            else -> hrefRaw
        }
        if (!path.startsWith(basePath)) return null
        val tail = path.removePrefix(basePath)
        // URLDecoder 会把 '+' 当空格，先保护路径中的 '+'
        val decoded = runCatching { URLDecoder.decode(tail.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(tail)
        return decoded.trim('/')
    }

    private fun parseHttpDate(value: String): Long {
        for (pattern in HTTP_DATE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(value)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun fileUrl(relativePath: String): String = rootUrl + encodeRelative(relativePath)

    private fun dirUrl(relativeDir: String): String {
        val encoded = encodeRelative(relativeDir)
        return if (encoded.isEmpty()) rootUrl else "$rootUrl$encoded/"
    }

    /** 逐段 URL 编码（保留 '/' 分隔符，空格转 %20）。 */
    private fun encodeRelative(relativePath: String): String =
        relativePath.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun mediaTypeFor(relativePath: String) =
        if (relativePath.endsWith(".md", ignoreCase = true)) MARKDOWN_MEDIA else BINARY_MEDIA

    private companion object {
        /** 传输类请求的最小间隔毫秒数：对齐坚果云官方插件的 Bottleneck minTime = 200。 */
        private const val MIN_REQUEST_INTERVAL_MS = 200L

        /** 目录扫描的并发度与相邻请求间隔（仅用于 PROPFIND；探测到限流时自动放慢）。 */
        private const val SCAN_CONCURRENCY = 3
        private const val SCAN_REQUEST_INTERVAL_MS = 100L
        /** 触发限流（503 / 429）后的等待时长与最大自动重试次数。 */
        private const val RATE_LIMIT_RETRY_DELAY_MS = 30_000L
        private const val RATE_LIMIT_MAX_RETRIES = 3

        /**
         * 服务端单次 PROPFIND 返回的条目上限（坚果云官方说明为 750 个，超出分页）。
         * 达到该值即视为可能截断（续取协议未公开），由调用方跳过删除类决策。
         */
        private const val SINGLE_PAGE_LIMIT = 750

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/>
</d:prop></d:propfind>"""

        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private val MARKDOWN_MEDIA = "text/markdown; charset=utf-8".toMediaType()
        private val BINARY_MEDIA = "application/octet-stream".toMediaType()

        private val HTTP_DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )

        // 兼容 d: / D: / 无前缀三种命名空间写法
        private val RE_RESPONSE = Regex(
            "<(?:\\w+:)?response[^>]*>(.*?)</(?:\\w+:)?response>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        private val RE_HREF = Regex(
            "<(?:\\w+:)?href[^>]*>(.*?)</(?:\\w+:)?href>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        private val RE_COLLECTION = Regex("<(?:\\w+:)?collection", RegexOption.IGNORE_CASE)
        private val RE_LENGTH = Regex("<(?:\\w+:)?getcontentlength[^>]*>(\\d+)", RegexOption.IGNORE_CASE)
        private val RE_LASTMOD = Regex("<(?:\\w+:)?getlastmodified[^>]*>([^<]+)", RegexOption.IGNORE_CASE)
        private val RE_ETAG = Regex("<(?:\\w+:)?getetag[^>]*>([^<]+)", RegexOption.IGNORE_CASE)

        /** 从 WebDAV 错误 XML 提取服务端描述（sabre/dav 的 s:message 或 d:exception）。 */
        private val RE_SERVER_MESSAGE = Regex(
            "<(?:\\w+:)?(?:exception|message)[^>]*>([^<]+)",
            RegexOption.IGNORE_CASE
        )
    }
}
