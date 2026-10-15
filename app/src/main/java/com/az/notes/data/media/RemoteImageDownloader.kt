package com.az.notes.data.media

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络图片保存到相册：经受控客户端（[com.az.notes.di.CoilHolder.client]）拉取原图字节，
 * 直写媒体库（不落临时文件）。
 *
 * 与行内加载完全同一套护栏：体积上限（声明 + 流式硬限）、超时、不跟随重定向
 * （3xx 直接失败）、请求头白名单。任一护栏拒绝 / 网络失败 / 无效地址均使
 * [saveToGallery] 返回 null（调用方统一呈现「保存失败」）。
 */
object RemoteImageDownloader {

    /** 文件名主体长度上限：UTF-8 中文最坏 3 字节/字符，64 字符 ≤ 192 字节（低于常见文件名长度上限）。 */
    private const val MAX_NAME_CHARS = 64

    /** 路径分隔与控制台/文件系统通用非法文件名字符（防御媒体库与外部文件系统）。 */
    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"

    /**
     * 拉取 [url] 原图写入系统相册（Pictures/AzNotes）；成功返回媒体库 Uri，失败返回 null。
     * [defaultBase] 为无法从 URL / 内容类型推断文件名时的兜底主体名（由调用方提供文案资源）。
     */
    suspend fun saveToGallery(
        context: Context,
        client: OkHttpClient,
        url: String,
        defaultBase: String
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body
                val contentType = resp.header("Content-Type")
                    ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                val name = inferName(url, contentType, defaultBase)
                val mime = contentType?.takeIf { it.startsWith("image/", ignoreCase = true) }
                    ?: GallerySaver.mimeOf(name)
                // 在响应关闭前完成写入：读流超限 / 超时由护栏抛 IOException → runCatching 收敛为失败
                GallerySaver.save(context, { body.byteStream() }, name, mime)
            }
        }.getOrNull()
    }

    /**
     * 从 URL 路径末段与响应内容类型推断相册文件名：末段自带扩展名则直接沿用；
     * 否则补内容类型对应扩展名；均无则用 [defaultBase]。
     */
    internal fun inferName(url: String, contentType: String?, defaultBase: String): String {
        val segment = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        val candidate = sanitize(segment)
        if (hasExtension(candidate)) return candidate
        val base = candidate.ifBlank { defaultBase }.trimEnd('.')
        val ext = extensionOf(contentType)
        return if (ext != null) "$base.$ext" else base
    }

    /** 末段是否自带扩展名（点位于开头或结尾视为无扩展，与 GallerySaver.mimeOf 同口径）。 */
    private fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length - 1
    }

    /** 文件名消毒：滤除控制字符与通用非法字符、去首尾空白、限长。 */
    private fun sanitize(raw: String): String =
        raw.filter { it.code >= 0x20 && it !in ILLEGAL_CHARS }.trim().take(MAX_NAME_CHARS)

    /** 内容类型子类型 → 扩展名（常见图片格式；无命中返回 null，交由媒体库自行嗅探）。 */
    private fun extensionOf(contentType: String?): String? =
        when (contentType?.lowercase()?.substringBefore(';')?.trim()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/avif" -> "avif"
            "image/heic", "image/heif" -> "heic"
            "image/svg+xml" -> "svg"
            "image/vnd.microsoft.icon", "image/x-icon" -> "ico"
            else -> null
        }
}
