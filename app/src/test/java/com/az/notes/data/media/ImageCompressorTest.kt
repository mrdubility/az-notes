package com.az.notes.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 压缩决策的纯函数单测（不触碰 Android 位图编码路径）：
 * 覆盖格式/是否重编码判定、目标宽高、降采样比例、输出扩展名。
 */
class ImageCompressorTest {

    @Test
    fun `decisionFor skips non-bitmap and small files`() {
        // 非位图（svg/gif/webp…）一律原样复制
        assertEquals(ImageCompressor.Decision.COPY_AS_IS, ImageCompressor.decisionFor(isBitmap = false, sizeBytes = 5_000_000, hasAlpha = false))
        // 位图但小于阈值不重编码
        assertEquals(ImageCompressor.Decision.COPY_AS_IS, ImageCompressor.decisionFor(isBitmap = true, sizeBytes = 100, hasAlpha = false))
        assertEquals(ImageCompressor.Decision.COPY_AS_IS, ImageCompressor.decisionFor(isBitmap = true, sizeBytes = ImageCompressor.SKIP_BELOW_BYTES, hasAlpha = true))
    }

    @Test
    fun `decisionFor picks jpeg without alpha and png with alpha`() {
        val big = ImageCompressor.SKIP_BELOW_BYTES + 1
        assertEquals(ImageCompressor.Decision.TO_JPEG, ImageCompressor.decisionFor(isBitmap = true, sizeBytes = big, hasAlpha = false))
        assertEquals(ImageCompressor.Decision.TO_PNG, ImageCompressor.decisionFor(isBitmap = true, sizeBytes = big, hasAlpha = true))
    }

    @Test
    fun `targetSize scales longest edge down proportionally`() {
        assertEquals(1568 to 1045, ImageCompressor.targetSize(3000, 2000))
        // 已在阈值内：原尺寸返回
        assertEquals(1000 to 800, ImageCompressor.targetSize(1000, 800))
        // 非法尺寸原样返回
        assertEquals(0 to 50, ImageCompressor.targetSize(0, 50))
    }

    @Test
    fun `computeInSampleSize uses power-of-two downsample`() {
        assertEquals(1, ImageCompressor.computeInSampleSize(1500, 1000))
        assertEquals(2, ImageCompressor.computeInSampleSize(4000, 3000))
        assertEquals(4, ImageCompressor.computeInSampleSize(8000, 6000))
        assertEquals(1, ImageCompressor.computeInSampleSize(0, 0))
    }

    @Test
    fun `extensionFor maps decision to file extension`() {
        assertNull(ImageCompressor.extensionFor(ImageCompressor.Decision.COPY_AS_IS))
        assertEquals("jpg", ImageCompressor.extensionFor(ImageCompressor.Decision.TO_JPEG))
        assertEquals("png", ImageCompressor.extensionFor(ImageCompressor.Decision.TO_PNG))
    }
}
