package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [MarkdownImageLifter] 的纯 JVM 单元测试：覆盖笔记常见排版与需要跳过的特殊上下文。
 */
class MarkdownImageLifterTest {

    @Test
    fun `no image returns same instance (short circuit)`() {
        val md = "# Title\n\nplain paragraph with [link](url)"
        assertSame(md, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `lifts image embedded in mixed text line`() {
        val md = "前面的文字 ![a](x.png) 后面的文字"
        val expected = "前面的文字\n\n![a](x.png)\n\n后面的文字"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `surrounds standalone image line with blank lines`() {
        val md = "上一段\n![a](x.png)\n下一段"
        val expected = "上一段\n\n![a](x.png)\n\n下一段"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `already separated image stays put without extra blank lines`() {
        val md = "上一段\n\n![a](x.png)\n\n下一段"
        val expected = "上一段\n\n![a](x.png)\n\n下一段"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `multiple images in one line split into separate blocks`() {
        val md = "开头 ![a](1.png) 中间 ![b](2.png) 结尾"
        val expected = "开头\n\n![a](1.png)\n\n中间\n\n![b](2.png)\n\n结尾"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `image in list item is not lifted`() {
        val md = "- 项目 ![a](x.png)\n- 另一项"
        // 列表项整体跳过，不做抽出
        assertEquals("- 项目 ![a](x.png)\n- 另一项", MarkdownImageLifter.lift(md))
    }

    @Test
    fun `image in blockquote is not lifted`() {
        val md = "> 引用 ![a](x.png)"
        assertEquals("> 引用 ![a](x.png)", MarkdownImageLifter.lift(md))
    }

    @Test
    fun `image inside fenced code block is not lifted`() {
        val md = "```kotlin\nval x = \"![a](x.png)\"\n```"
        // 围栏代码块内容原样保留（逐行重建后字符串内容相等，不依赖实例同一）
        assertEquals(md, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `image with title attribute is matched`() {
        val md = """前面 ![a](x.png "标题") 后面"""
        val expected = "前面\n\n![a](x.png \"标题\")\n\n后面"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `preserves trailing newline style when original ends with newline`() {
        val md = "段落 ![a](x.png)\n"
        val lifted = MarkdownImageLifter.lift(md)
        assertEquals("段落\n\n![a](x.png)\n", lifted)
    }

    @Test
    fun `heading line with image is not lifted`() {
        val md = "# 标题 ![a](x.png)"
        assertEquals("# 标题 ![a](x.png)", MarkdownImageLifter.lift(md))
    }

    @Test
    fun `table cell with image is not lifted`() {
        val md = "| 名称 | 图 |\n| --- | --- |\n| a | ![x](y.png) |"
        // 含 `|` 的行整行跳过
        assertEquals(md, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `image at very start of document`() {
        val md = "![a](x.png)\n后面段落"
        val expected = "![a](x.png)\n\n后面段落"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }

    @Test
    fun `image at very end of document`() {
        val md = "前面段落\n![a](x.png)"
        val expected = "前面段落\n\n![a](x.png)"
        assertEquals(expected, MarkdownImageLifter.lift(md))
    }
}
