package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `block ids map to top level blocks and skip code fences`() {
        val md = "para one ^a1\n\npara two\n\npara three ^b2\n\n```\ncode ^c3\n```\n"
        val result = HeadingExtractor.parse(md)
        // 代码块内的 ^id 不识别；两个段落 id 均识别且指向不同块
        assertEquals(setOf("a1", "b2"), result.blocks.keys)
        assertTrue(result.blocks.getValue("a1") < result.blocks.getValue("b2"))
        assertTrue(result.blocks.getValue("b2") < result.blockCount)
    }

    @Test
    fun `block id at block start with trailing spaces is accepted`() {
        val md = "first\n\n^solo   \n"
        val blocks = HeadingExtractor.parse(md).blocks
        assertTrue(blocks.containsKey("solo"))
        assertTrue(blocks.getValue("solo") > 0)
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
