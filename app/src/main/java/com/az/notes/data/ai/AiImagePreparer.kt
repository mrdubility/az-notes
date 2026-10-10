package com.az.notes.data.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.az.notes.data.ai.protocol.TransportImage
import com.az.notes.data.media.ImageCompressor
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.ai.ChatPart
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片预处理结果。 */
sealed interface ImagePrepResult {
    /** 成功：产物已落 `cacheDir/ai_images/`（发送时再编码）。 */
    data class Success(val image: ChatPart.Image) : ImagePrepResult

    /** 失败原因（UI 按 [ImagePrepFailure] 映射文案）。 */
    data class Failure(val reason: ImagePrepFailure) : ImagePrepResult
}

/** 预处理失败原因。 */
enum class ImagePrepFailure {
    /** 非白名单格式（gif / heic / svg / bmp 等不在支持之列）。 */
    UNSUPPORTED_FORMAT,

    /** 体积超限（压缩后仍超 base64 长度上限）。 */
    TOO_LARGE,

    /** 读取 / 复制 / 落盘失败。 */
    READ_FAILED
}

/**
 * AI 对话图片预处理（§7.2）：相册 / 文档取图两条来源统一管线——
 * 白名单校验（jpg / png / webp）→ 复制到 App 私有缓存 → 压缩（1568px / JPEG80，
 * PNG 含 alpha 保透明；webp 不重编码直通）→ 产物落 `cacheDir/ai_images/`（`ai-<uuid8>.<ext>`）。
 * 发送时逐张 [encodeForTransport] 转 base64 传输态；[cleanup] 在新建会话时清空缓存。
 *
 * 数据安全红线：源文件（content URI / 仓库文件）一律先复制到私有缓存再处理——
 * [ImageCompressor] 的压缩产物写在源文件同目录，直接处理仓库文件会污染用户仓库。
 */
@Singleton
class AiImagePreparer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 相册 / 系统选择器路径：content URI → 预处理产物。
     * 显示名从 [OpenableColumns.DISPLAY_NAME] 解析（MIME 兜底，对齐 ImageImportRepository 做法）。
     */
    suspend fun prepareFromUri(uri: Uri): ImagePrepResult = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri)
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!isSupportedExtension(ext)) {
            return@withContext ImagePrepResult.Failure(ImagePrepFailure.UNSUPPORTED_FORMAT)
        }
        val temp = copyUriToTemp(uri, ext)
            ?: return@withContext ImagePrepResult.Failure(ImagePrepFailure.READ_FAILED)
        try {
            stageAndDeliver(temp, ext, displayName)
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 文档取图路径：仓库内图片文件 → 预处理产物（name 为展示名，用于失败降级 / 导出占位）。 */
    suspend fun prepareFromVaultFile(file: File, name: String): ImagePrepResult =
        withContext(Dispatchers.IO) {
            val ext = file.extension.lowercase(Locale.ROOT)
            if (!isSupportedExtension(ext)) {
                return@withContext ImagePrepResult.Failure(ImagePrepFailure.UNSUPPORTED_FORMAT)
            }
            if (!file.isFile) {
                return@withContext ImagePrepResult.Failure(ImagePrepFailure.READ_FAILED)
            }
            val temp = copyFileToTemp(file)
                ?: return@withContext ImagePrepResult.Failure(ImagePrepFailure.READ_FAILED)
            try {
                stageAndDeliver(temp, ext, name)
            } finally {
                runCatching { temp.delete() }
            }
        }

    /**
     * 发送时编码为传输态（无换行 base64）。
     * 读取失败 / 产物缺失 / 超长返回 null——调用方按单张降级（追加文本占位），不影响其余图片。
     */
    suspend fun encodeForTransport(image: ChatPart.Image): TransportImage? =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { File(image.localPath).readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@withContext null
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (encoded.length > MAX_BASE64_CHARS) return@withContext null
            TransportImage(image.mime, encoded)
        }

    /**
     * 清理图片缓存目录（新会话时调用；失败静默——缓存目录可随时重建）。
     * [keep] 中仍被引用的文件（待发图片的 localPath）保留。
     */
    suspend fun cleanup(keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        runCatching {
            File(context.cacheDir, DIR_NAME).listFiles()?.forEach { file ->
                if (file.absolutePath !in keep) file.delete()
            }
        }
        Unit
    }

    // ---------------------------------------------------------------- 内部

    /** 压缩管线（统一入口）：webp 直通；jpg/png 复用导入压缩；产物更名复制到缓存目录。 */
    private fun stageAndDeliver(temp: File, ext: String, name: String): ImagePrepResult {
        val staged = if (VaultRepository.isBitmapImageName("x.$ext")) {
            ImageCompressor.compressToTempFile(temp, isBitmap = true)
        } else {
            // webp 等非位图：不重编码直通（源文件超上限直接拒绝）
            if (temp.length() > MAX_FILE_BYTES) {
                return ImagePrepResult.Failure(ImagePrepFailure.TOO_LARGE)
            }
            ImageCompressor.Result(temp, false)
        }
        try {
            if (exceedsBase64Limit(staged.file.length())) {
                return ImagePrepResult.Failure(ImagePrepFailure.TOO_LARGE)
            }
            val outExt = staged.file.name.substringAfterLast('.', ext).lowercase(Locale.ROOT)
            val dir = ensureDir() ?: return ImagePrepResult.Failure(ImagePrepFailure.READ_FAILED)
            val target = File(dir, "ai-${uuid8()}.$outExt")
            val copied = runCatching {
                staged.file.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.isFile && target.length() > 0
            }.getOrDefault(false)
            if (!copied) {
                runCatching { target.delete() }
                return ImagePrepResult.Failure(ImagePrepFailure.READ_FAILED)
            }
            return ImagePrepResult.Success(
                ChatPart.Image(localPath = target.absolutePath, mime = mimeFor(outExt), name = name)
            )
        } finally {
            // 压缩产物（与源不同文件时）一并清理；源临时文件由调用方 finally 删除
            if (staged.file !== temp) runCatching { staged.file.delete() }
        }
    }

    /** 解析 content URI 的显示名（对齐 ImageImportRepository 做法；拿不到扩展名时按 MIME 兜底）。 */
    private fun resolveDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    name = cursor.getString(index)
                }
            }
        }
        if (name.substringAfterLast('.', "").isEmpty()) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val ext = mime?.substringAfter('/')?.substringBefore('+').orEmpty()
                .lowercase(Locale.ROOT).ifBlank { "jpg" }
            name = "image.$ext"
        }
        return name
    }

    /** content URI 完整复制到私有缓存临时文件（后续压缩 / 落盘都基于本地副本）。 */
    private fun copyUriToTemp(uri: Uri, ext: String): File? {
        val dir = ensureDir() ?: return null
        val target = File(dir, "tmp-${uuid8()}.$ext")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null && target.isFile && target.length() > 0
        }.getOrDefault(false)
        if (!ok) {
            runCatching { target.delete() }
            return null
        }
        return target
    }

    /** 仓库图片文件复制到私有缓存临时文件（只读源文件，绝不写回仓库）。 */
    private fun copyFileToTemp(source: File): File? {
        val dir = ensureDir() ?: return null
        val target = File(dir, "tmp-${uuid8()}.${source.extension.lowercase(Locale.ROOT)}")
        val ok = runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.isFile && target.length() > 0
        }.getOrDefault(false)
        if (!ok) {
            runCatching { target.delete() }
            return null
        }
        return target
    }

    private fun ensureDir(): File? {
        val dir = File(context.cacheDir, DIR_NAME)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    /** 最终产物扩展名 → MIME（压缩可能改扩展名：不透明 PNG → jpg 等）。 */
    private fun mimeFor(ext: String): String = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun uuid8(): String = UUID.randomUUID().toString().substring(0, 8)

    companion object {
        /** 单消息图片张数上限（VM 守卫用）。 */
        const val MAX_IMAGES = 3

        /** 单图 base64 载荷长度上限（≈3.5MB 字符数近似；超限请求体过大风险高）。 */
        internal const val MAX_BASE64_CHARS = 3_670_016

        /** webp 直通文件的体积上限（3.5 MiB；超出直接拒绝）。 */
        internal const val MAX_FILE_BYTES = 3_670_016L

        private const val DIR_NAME = "ai_images"

        /** 白名单：仅支持服务端普遍接受的 jpg / png / webp。 */
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        /** 扩展名白名单判定（纯函数，供单测）。 */
        internal fun isSupportedExtension(ext: String): Boolean =
            ext.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS

        /** base64 编码后长度：4 × ⌈n / 3⌉（纯函数，供单测）。 */
        internal fun base64Length(byteSize: Long): Long = (byteSize + 2) / 3 * 4

        /** 产物字节数换算的 base64 长度是否超上限（纯函数，供单测）。 */
        internal fun exceedsBase64Limit(byteSize: Long): Boolean =
            base64Length(byteSize) > MAX_BASE64_CHARS
    }
}
