package com.az.notes.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.az.notes.R
import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.debug.DebugLogType
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.markdown.ImageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次图片导入的产出。 */
data class ImportedMedia(
    /** 写入笔记正文的链接（相对笔记所在目录，已做 Markdown 安全编码：空格 / 括号等转 %XX）。 */
    val link: String,
    val absolutePath: String,
    val bytesBefore: Long,
    val bytesAfter: Long,
    /** 是否真正发生了重编码（开关开 + 位图 + 压缩后更小才为 true）。 */
    val compressed: Boolean
) {
    /** 压缩是否有收益（决定 Snackbar 是否展示前后体积）。 */
    val shrank: Boolean get() = compressed && bytesAfter < bytesBefore
}

/**
 * 图片导入管线：把系统选择器 / 分享得到的 content URI 规范落到
 * 「笔记同目录 `assets/` 附件夹」，并按设置开关决定是否压缩（默认开）。
 *
 * 规范位置：`<笔记所在目录>/assets/<可读名>-yyyyMMdd-HHmmss.<ext>`；
 * 附件夹已存在则复用，重名追加 ` (2)`，绝不覆盖已有附件。
 *
 * 数据安全红线：源文件先复制到 App 私有缓存再处理（不搬动用户原图）；
 * 目标路径必须严格落在仓库根内并经 [VaultRepository.isSafeTarget] 护栏。
 */
@Singleton
class ImageImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debugLogRepository: DebugLogRepository
) {

    /**
     * 导入一张图片。
     *
     * @param vaultRoot 当前仓库根绝对路径（护栏与链接计算用）
     * @param noteFile 目标笔记文件（决定附件夹位置）
     * @param compress 是否压缩（来自设置开关）
     * @return 导入结果；任一环节失败返回 null（调用方提示并留正文不变，不产生孤儿附件）
     */
    suspend fun import(
        sourceUri: Uri,
        vaultRoot: String,
        noteFile: File,
        compress: Boolean
    ): ImportedMedia? = withContext(Dispatchers.IO) {
        val root = File(vaultRoot).normalize()
        val noteDir = noteFile.parentFile?.normalize() ?: return@withContext null
        if (!root.isDirectory || noteDir.absolutePath != root.absolutePath &&
            !noteDir.absolutePath.startsWith(root.absolutePath + File.separator)
        ) {
            return@withContext null
        }
        val displayName = resolveDisplayName(sourceUri)
        val temp = copyToCache(sourceUri, displayName) ?: return@withContext null
        var artifact: File? = null
        try {
            val extBefore = temp.name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
            val isBitmap = VaultRepository.isBitmapImageName("x.$extBefore")
            val bytesBefore = temp.length()
            val staged = if (compress) {
                ImageCompressor.compressToTempFile(temp, isBitmap = isBitmap)
            } else {
                ImageCompressor.Result(temp, false)
            }
            if (staged.file !== temp) artifact = staged.file
            val ext = staged.file.name.substringAfterLast('.', extBefore).lowercase(Locale.ROOT)
            val target = planTarget(noteDir, root, displayName, ext) ?: return@withContext null
            if (!writeAtomically(target, staged.file)) {
                log(DebugLogLevel.ERROR, "图片落盘失败", displayName, staged.file.length(), bytesBefore, false, target.absolutePath)
                runCatching { target.delete() }
                return@withContext null
            }
            log(
                if (staged.compressed) DebugLogLevel.INFO else DebugLogLevel.DEBUG,
                "图片导入完成",
                displayName,
                staged.file.length(),
                bytesBefore,
                staged.compressed,
                target.absolutePath
            )
            ImportedMedia(
                // 相对路径编码为 Markdown 安全形态（空格 / # / % / 括号 → %XX），
                // 避免「文件名含空格 → 正文裸空格 → 解析被 title 规则截断」的坏链
                link = ImageReference.encodeTarget(target.relativeToNoteDir(noteDir), escapePercent = true),
                absolutePath = target.absolutePath,
                bytesBefore = bytesBefore,
                bytesAfter = target.length(),
                compressed = staged.compressed
            )
        } finally {
            // 临时原图与压缩产物均落在 App 私有缓存，落盘后一律清理
            runCatching { if (temp.exists()) temp.delete() }
            runCatching { if (artifact?.exists() == true) artifact.delete() }
        }
    }

    /**
     * 跨仓库移动笔记时，把「仅本文引用」的附件按**同名**复制到目标笔记所在目录的
     * `assets/`，使正文里的 `assets/xxx` 引用在目标仓库同样成立（同目录语义天然一致）。
     *
     * 目标已存在同名附件时**跳过不覆盖**（可能被目标仓库其他笔记引用）——引用本就能
     * 解析到该文件，视同已就位；目标越出 [targetVaultRoot] 或未通过
     * [VaultRepository.isSafeTarget] 护栏的附件一律跳过；单张失败不影响其余，
     * 返回成功复制的张数（调用方据此提示「附件未全部跟随」）。
     */
    suspend fun copyAttachmentsAcrossVaults(
        sources: List<File>,
        targetNoteDir: File,
        targetVaultRoot: String
    ): Int = withContext(Dispatchers.IO) {
        val root = File(targetVaultRoot).normalize()
        if (!root.isDirectory || sources.isEmpty()) return@withContext 0
        val dir = File(targetNoteDir.normalize(), VaultRepository.ATTACHMENT_DIR)
        var ok = 0
        sources.forEach { src ->
            if (!src.isFile) return@forEach
            val dest = File(dir, src.name).normalize()
            if (dest.absolutePath != root.absolutePath &&
                !dest.absolutePath.startsWith(root.absolutePath + File.separator)
            ) return@forEach
            if (!VaultRepository.isSafeTarget(dest.absolutePath)) return@forEach
            // 目标已有同名附件：不覆盖（可能被目标仓库其他笔记引用），视同已就位
            if (dest.exists()) {
                ok++
                return@forEach
            }
            val done = runCatching {
                (dir.exists() || dir.mkdirs()) && writeAtomically(dest, src)
            }.getOrDefault(false)
            if (done) ok++
        }
        ok
    }

    // ---------------------------------------------------------------- 内部

    /** 解析 content URI 的显示名；拿不到时按 MIME / 占位名兜底，保证目标名可读。 */
    private fun resolveDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        var ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!ext.matches(Regex("[a-z0-9]{2,5}"))) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ext = mime?.substringAfter('/')?.substringBefore('+').orEmpty()
                .lowercase(Locale.ROOT).ifBlank { "jpg" }
        }
        if (ext == "jpeg") ext = "jpg"
        val dotted = name.substringBeforeLast('.', "")
        val rawBase = if (dotted.isNotBlank()) dotted else name
        val base = VaultRepository.sanitizeEntryName(rawBase)
            ?: context.getString(R.string.image_default_base_name)
        return "$base.$ext"
    }

    /** 把 content URI 完整复制到 App 私有缓存，后续压缩/落盘都基于该本地文件。 */
    private fun copyToCache(uri: Uri, displayName: String): File? {
        val dir = File(context.cacheDir, "image_import").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-$displayName")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null && target.isFile && target.length() > 0
        }.getOrDefault(false)
        if (!ok) {
            runCatching { if (target.exists()) target.delete() }
            debugLogRepository.log(
                DebugLogLevel.ERROR,
                DebugLogType.IMAGE,
                "读取图片内容失败",
                mapOf("name" to displayName, "uri" to uri.toString())
            )
            return null
        }
        return target
    }

    /**
     * 计算目标附件文件（含唯一名）并按需创建附件夹。
     * 越出仓库、命中护栏或建目录失败时返回 null。
     */
    private fun planTarget(noteDir: File, root: File, displayName: String, ext: String): File? {
        val dir = File(noteDir, VaultRepository.ATTACHMENT_DIR)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val base = displayName.substringBeforeLast('.').ifBlank {
            context.getString(R.string.image_default_base_name)
        }
        var candidate = File(dir, "$base-$stamp.$ext")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base-$stamp ($index).$ext")
            index++
        }
        val normalized = candidate.normalize()
        // 数据安全红线：目标必须仍在仓库内，且通过敏感路径护栏
        if (normalized.absolutePath != root.absolutePath &&
            !normalized.absolutePath.startsWith(root.absolutePath + File.separator)
        ) {
            return null
        }
        if (!VaultRepository.isSafeTarget(normalized.absolutePath)) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        return candidate
    }

    /** 原子写二进制（先 `.tmp` 再 rename 覆盖）；失败清理临时文件，不留半成品附件。
     *  不先删旧目标：Unix 语义下 rename 原子替换；rename 失败（个别文件系统拒绝覆盖）
     *  时回退 copy——旧文件在回退成功前始终保留，避免「先删后写失败」造成文件丢失。 */
    private fun writeAtomically(target: File, source: File): Boolean {
        if (target.absolutePath == source.absolutePath) return target.exists()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        return runCatching {
            source.inputStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) source.copyTo(target, overwrite = true)
            runCatching { tmp.delete() }
            target.exists()
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
    }

    private fun File.relativeToNoteDir(noteDir: File): String =
        absolutePath.removePrefix(noteDir.absolutePath).trimStart('/', '\\')

    private fun log(
        level: DebugLogLevel,
        msg: String,
        displayName: String,
        after: Long,
        before: Long,
        compressed: Boolean,
        targetPath: String
    ) {
        debugLogRepository.log(
            level,
            DebugLogType.IMAGE,
            msg,
            mapOf(
                "name" to displayName,
                "before" to before,
                "after" to after,
                "compressed" to compressed,
                "target" to targetPath
            )
        )
    }
}
