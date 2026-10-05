package com.az.notes.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
 * 所有公开方法均切换到 [Dispatchers.IO] 执行；[baseUrl] 为同步根目录
 * （如 `https://dav.jianguoyun.com/dav/az-notes/`），远端路径一律相对该根。
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String
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

    private val rootUrl: String = baseUrl.trimEnd('/') + "/"
    private val basePath: String = runCatching { rootUrl.toHttpUrl().encodedPath }
        .getOrDefault("/")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ 连接测试

    /** 连接测试（§6.6）：PROPFIND 根目录 Depth:0，验证地址与凭据。 */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = auth(Request.Builder().url(rootUrl))
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code !in 200..299) {
                    throw IOException("HTTP ${resp.code} ${resp.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 列目录

    /**
     * PROPFIND Depth:1 逐目录递归拉取全部条目（§6.1 Scan）。
     * 顺序 BFS（与坚果云插件策略一致），避免并发过高触发服务端限流。
     */
    suspend fun listAll(onDirectoryScanned: (Int) -> Unit = {}): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<RemoteEntry>()
            val queue = ArrayDeque<String>()
            queue += ""
            var scanned = 0
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                val entries = propfind(dir)
                scanned++
                onDirectoryScanned(scanned)
                for (entry in entries) {
                    if (entry.path.isBlank()) continue
                    result += entry
                    if (entry.isDirectory) queue += entry.path
                }
            }
            result
        }

    private fun propfind(relativeDir: String): List<RemoteEntry> {
        val request = auth(Request.Builder().url(dirUrl(relativeDir)))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
            .header("Depth", "1")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299) {
                throw IOException("列目录失败（${relativeDir.ifBlank { "/" }}）：HTTP ${resp.code}")
            }
            val xml = resp.body?.string().orEmpty()
            return parseMultiStatus(xml)
        }
    }

    // ------------------------------------------------------------------ 传输

    /** 下载到本地 [target]（先写 `.part` 再改名，避免半截文件）。远端不存在返回 false。 */
    suspend fun download(relativePath: String, target: File): Boolean = withContext(Dispatchers.IO) {
        val request = auth(Request.Builder().url(fileUrl(relativePath))).get().build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext false
            if (resp.code !in 200..299) {
                throw IOException("下载 $relativePath 失败：HTTP ${resp.code}")
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
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299) {
                throw IOException("上传 $relativePath 失败：HTTP ${resp.code}")
            }
        }
        true
    }

    /** 删除远端文件 / 目录；404 视为已不存在（成功）。 */
    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val request = auth(Request.Builder().url(fileUrl(relativePath))).delete().build()
        client.newCall(request).execute().use { resp ->
            resp.code in 200..299 || resp.code == 404
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun auth(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))

    private fun ensureParentDirectories(relativePath: String) {
        val dirs = relativePath.split('/').dropLast(1)
        var acc = ""
        for (dir in dirs) {
            acc = if (acc.isEmpty()) dir else "$acc/$dir"
            val request = auth(Request.Builder().url(dirUrl(acc))).method("MKCOL", null).build()
            client.newCall(request).execute().use { resp ->
                // 201 创建成功；405 / 301 表示已存在或已重定向；其余视为失败
                if (resp.code !in 200..299 && resp.code != 405 && resp.code != 301) {
                    throw IOException("创建远端目录 $acc 失败：HTTP ${resp.code}")
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
    }
}
