package com.az.notes.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片保存到系统相册（Pictures/AzNotes/）。
 *
 * 数据流向：Vault 内图片（读）→ MediaStore / ContentResolver（写），应用自身不直接以
 * java.io.File 写公共存储路径。
 * - Android 10+（API 29+）：RELATIVE_PATH + IS_PENDING 发布流程，零权限；
 * - Android 8.0–9.0（API 26–28）：DATA 列指向 Pictures/AzNotes（写入的是固定常量
 *   路径的相册目录，不涉及用户其他文件），需 WRITE 权限（Manifest 已声明
 *   maxSdkVersion=29，由 UI 层按需运行时请求）。
 */
object GallerySaver {

    /** 相册子目录名（Pictures/AzNotes）。 */
    private const val ALBUM_NAME = "AzNotes"

    /**
     * 保存 [source] 到系统相册，返回媒体库 Uri；源文件不存在或写入失败返回 null。
     * 同名文件由媒体库自动命名去重（API 26–28 旧模型由 [uniqueFile] 兜底）。
     */
    suspend fun save(context: Context, source: File): Uri? = withContext(Dispatchers.IO) {
        if (!source.isFile) return@withContext null
        val name = source.name
        val mime = mimeOf(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveModern(context, source, name, mime)
        } else {
            saveLegacy(context, source, name, mime)
        }
    }

    /** API 29+：插入占位行（IS_PENDING）→ 写流 → 发布（相册立即可见）。 */
    private fun saveModern(context: Context, source: File, name: String, mime: String?): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            if (mime != null) put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return writeAndPublish(context, source, uri, publishPending = true)
    }

    /** API 26–28：DATA 列定位 Pictures/AzNotes；目录缺失时创建固定相册目录。 */
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, source: File, name: String, mime: String?): Uri? {
        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_NAME
        )
        if (!album.isDirectory && !album.mkdirs()) return null
        val target = uniqueFile(album, name)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, target.name)
            if (mime != null) put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATA, target.absolutePath)
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return writeAndPublish(context, source, uri, publishPending = false)
    }

    /** 写入源文件内容并按需发布；失败时清理本次插入的媒体库记录（仅限刚创建的行）。 */
    private fun writeAndPublish(
        context: Context,
        source: File,
        uri: Uri,
        publishPending: Boolean
    ): Uri? {
        val resolver = context.contentResolver
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("openOutputStream null")
            out.use { target -> source.inputStream().use { it.copyTo(target) } }
            if (publishPending) {
                val publish = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            }
            uri
        } catch (e: Exception) {
            // 仅删除本函数刚插入的媒体库记录（含其空占位文件），不触碰用户既有文件
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** 扩展名 → MIME；无法识别返回 null（交由媒体库自行嗅探）。 */
    internal fun mimeOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    /** 冲突时追加「 (n)」后缀（仅 API 26–28 旧媒体库模型的命名兜底）。 */
    internal fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }
}
