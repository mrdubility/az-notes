package com.az.notes.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * 图片导入压缩（固定参数，由设置页开关控制，默认开启）：
 * 长边压到 [MAX_DIMENSION]、JPEG 质量 [JPEG_QUALITY]，按 EXIF 方向摆正。
 *
 * 参数刻意不开放配置：本批只要求「有开关 + 规范结果」，避免多组数值带来的组合爆炸。
 * 保守边界（见 [decisionFor]）：非位图格式、已足够小的文件不重编码；
 * 压缩后不小于原文件则保留原字节，绝不让「压缩」把图变大。
 */
object ImageCompressor {

    /** 长边上限（像素）。 */
    const val MAX_DIMENSION = 1568

    /** JPEG 编码质量。 */
    const val JPEG_QUALITY = 80

    /** 小于该体积不重编码（已经是缩略图/图标级别，重编码只会损失质量）。 */
    const val SKIP_BELOW_BYTES = 200_000L

    /** PNG 编码无质量旋钮，固定 100（仅用于选择输出格式，不参与体积比较）。 */
    private const val PNG_QUALITY = 100

    /** 是否参与重编码的判定结果。 */
    enum class Decision {
        /** 原样复制（非位图格式 / 文件已足够小 / 开关关闭）。 */
        COPY_AS_IS,

        /** 重编码为 JPEG（无透明通道）。 */
        TO_JPEG,

        /** 重编码为 PNG（含透明通道，转 JPEG 会丢透明度）。 */
        TO_PNG
    }

    /**
     * 纯判定（可 JVM 单测）：按扩展名与原图体积决定要不要重编码、编成什么格式。
     * [isBitmap] 由调用方用 [com.az.notes.data.storage.VaultRepository.isBitmapImageName] 给出。
     */
    fun decisionFor(isBitmap: Boolean, sizeBytes: Long, hasAlpha: Boolean): Decision = when {
        !isBitmap -> Decision.COPY_AS_IS
        sizeBytes in 1..SKIP_BELOW_BYTES -> Decision.COPY_AS_IS
        hasAlpha -> Decision.TO_PNG
        else -> Decision.TO_JPEG
    }

    /** 目标宽高：长边不超过 [MAX_DIMENSION]，等比缩放；已在阈值内则原尺寸返回。 */
    fun targetSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longSide = maxOf(width, height)
        if (longSide <= maxDimension) return width to height
        val ratio = maxDimension.toDouble() / longSide
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    /** BitmapFactory 的 2 的幂降采样比例：解码后仍不小于目标尺寸（避免放大失真）。 */
    fun computeInSampleSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    /** 按决策给出输出扩展名（不改扩展名时返回 null）。 */
    fun extensionFor(decision: Decision): String? = when (decision) {
        Decision.COPY_AS_IS -> null
        Decision.TO_JPEG -> "jpg"
        Decision.TO_PNG -> "png"
    }

    /** 压缩结果：目标文件与是否真正发生了重编码。 */
    data class Result(val file: File, val compressed: Boolean)

    /**
     * 就地压缩 [source]（同目录产出 `.cmp.<ext>`，成功返回新文件，原文件保留待调用方清理）。
     *
     * 任一环节失败（解码不了、编码写不出、压缩后反而更大）都返回「原文件 + compressed=false」，
     * 让导入流程照常完成——压缩属于体验优化，不能阻断图片插入。
     */
    fun compressToTempFile(source: File, isBitmap: Boolean): Result {
        val originalSize = source.length()
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeFile(source.absolutePath, it) }
        }.getOrNull()
        val width = bounds?.outWidth ?: 0
        val height = bounds?.outHeight ?: 0
        if (!isBitmap || width <= 0 || height <= 0) return Result(source, false)

        // 先降采样解码（2 的幂），再按精确比例 + EXIF 方向做一次矩阵变换
        val inSample = computeInSampleSize(width, height)
        val decoded = runCatching {
            BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    this.inSampleSize = inSample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.getOrNull() ?: return Result(source, false)

        var bitmap: Bitmap? = null
        var out: File? = null
        try {
            val orientation = runCatching {
                ExifInterface(source.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val (targetW, targetH) = targetSize(width, height)
            val matrix = Matrix().apply {
                applyExifOrientation(this, orientation)
                postScale(
                    targetW.toFloat() / decoded.width,
                    targetH.toFloat() / decoded.height
                )
            }
            bitmap = if (matrix.isIdentity && decoded.width == targetW && decoded.height == targetH) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }

            // 透明通道实测：格式判定用解码后的真实状态，不看扩展名
            val hasAlpha = bitmap.hasAlpha()
            when (decisionFor(isBitmap = true, sizeBytes = originalSize, hasAlpha = hasAlpha)) {
                Decision.COPY_AS_IS -> return Result(source, false)
                else -> {}
            }
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (hasAlpha) PNG_QUALITY else JPEG_QUALITY
            val ext = if (hasAlpha) "png" else "jpg"
            val candidate = File(source.parentFile, "${source.name}.cmp.$ext")
            val written = runCatching {
                FileOutputStream(candidate).use { stream ->
                    bitmap.compress(format, quality, stream)
                    stream.flush()
                }
                candidate.exists() && candidate.length() > 0
            }.getOrDefault(false)
            if (!written) {
                runCatching { candidate.delete() }
                return Result(source, false)
            }
            // 兜底：压缩后反而更大（已高度优化的 JPEG）则保留原图
            if (candidate.length() >= originalSize) {
                runCatching { candidate.delete() }
                return Result(source, false)
            }
            out = candidate
            return Result(candidate, true)
        } catch (e: Throwable) {
            runCatching { out?.delete() }
            return Result(source, false)
        } finally {
            if (bitmap != null && bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    /** EXIF 方向 → 旋转/镜像矩阵（不改动入参之外的状态，纯几何变换）。 */
    private fun applyExifOrientation(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f); matrix.postScale(-1f, 1f)
            }
            else -> {}
        }
    }
}
