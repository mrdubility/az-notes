package com.az.notes.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiImagePreparer 纯判定单测（§7）：白名单校验与 base64 长度阈值换算。
 * 涉及 Android Context / 文件 IO 的管线部分由真机验收覆盖。
 */
class AiImagePreparerTest {

    @Test
    fun `whitelist accepts jpg png webp and rejects others`() {
        listOf("jpg", "jpeg", "png", "webp", "JPG", "PNG", "WebP").forEach {
            assertTrue("应通过白名单: $it", AiImagePreparer.isSupportedExtension(it))
        }
        listOf("gif", "heic", "heif", "svg", "bmp", "avif", "tiff", "").forEach {
            assertFalse("应拒绝: $it", AiImagePreparer.isSupportedExtension(it))
        }
    }

    @Test
    fun `base64 length follows four per three bytes`() {
        assertEquals(0L, AiImagePreparer.base64Length(0))
        assertEquals(4L, AiImagePreparer.base64Length(1))
        assertEquals(4L, AiImagePreparer.base64Length(2))
        assertEquals(4L, AiImagePreparer.base64Length(3))
        assertEquals(8L, AiImagePreparer.base64Length(4))
        assertEquals(8L, AiImagePreparer.base64Length(6))
        // 上限换算：2_752_512 字节 → 正好 3_670_016 字符
        assertEquals(3_670_016L, AiImagePreparer.base64Length(2_752_512))
    }

    @Test
    fun `base64 limit boundary rejects one byte beyond`() {
        // 3_670_016 字符 = 上限本身（允许）；再多 1 字节 → 3_670_020 字符（超限）
        assertFalse(AiImagePreparer.exceedsBase64Limit(2_752_512))
        assertTrue(AiImagePreparer.exceedsBase64Limit(2_752_513))
    }

    @Test
    fun `limits are three point five mib`() {
        assertEquals(3_670_016L, AiImagePreparer.MAX_FILE_BYTES)
        assertEquals(3_670_016, AiImagePreparer.MAX_BASE64_CHARS)
    }
}
