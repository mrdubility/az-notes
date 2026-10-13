package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 领域层纯 JVM 单元测试示例（§4.1：不依赖 Android framework，可直接在 CI 跑）。
 */
class HeadingExtractorTest {

    @Test
    fun `extracts headings with levels and lines`() {
        val md = """
            # Title
            intro text
            ## Section A
            body
            ### Sub
            ## Section B
        """.trimIndent()

        val headings = HeadingExtractor.extract(md)
        assertEquals(4, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals("Title", headings[0].text)
        assertEquals(2, headings[1].level)
        assertEquals("Section A", headings[1].text)
        assertEquals(3, headings[2].level)
        assertEquals("Sub", headings[2].text)
        assertEquals("Section B", headings[3].text)
    }

    @Test
    fun `ignores headings inside code fence`() {
        val md = """
            # Real
            ```
            # Not a heading
            ```
            ## Also real
        """.trimIndent()

        val headings = HeadingExtractor.extract(md)
        assertEquals(2, headings.size)
        assertEquals("Real", headings[0].text)
        assertEquals("Also real", headings[1].text)
    }

    @Test
    fun `findBlockByTitle matches exact then case-insensitive then prefix`() {
        val headings = HeadingExtractor.extract(
            """
            # 安装
            body
            # 安装步骤
            body
            # Install Guide
            body
            """.trimIndent()
        )
        // 精确命中优先于前缀
        assertEquals(headings[0].blockIndex, HeadingExtractor.findBlockByTitle(headings, "安装"))
        // 忽略大小写
        assertEquals(headings[2].blockIndex, HeadingExtractor.findBlockByTitle(headings, "install guide"))
        // 无精确命中时前缀命中
        assertEquals(headings[1].blockIndex, HeadingExtractor.findBlockByTitle(headings, "安装步"))
        // 无命中 / 空串返回 null
        assertNull(HeadingExtractor.findBlockByTitle(headings, "不存在"))
        assertNull(HeadingExtractor.findBlockByTitle(headings, "  "))
    }
}
