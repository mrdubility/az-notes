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
    fun `block ids map to top level block index`() {
        val md = "段落一 ^a1\n\n段落二\n\n段落三 ^b2\n\n```\ncode ^c3\n```\n"
        val result = HeadingExtractor.parse(md)
        assertEquals(0, result.blocks["a1"])
        assertEquals(2, result.blocks["b2"])
        // 代码块内的 ^id 不识别
        assertNull(result.blocks["c3"])
    }

    @Test
    fun `block id at block start and trailing spaces are accepted`() {
        val md = "首段\n\n^solo   \n"
        assertEquals(1, HeadingExtractor.parse(md).blocks["solo"])
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
    fun `heading text unwraps note links`() {
        val headings = HeadingExtractor.extract("# 参见 [目标](az-note:target)")
        assertEquals("参见 目标", headings.single().text)
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
