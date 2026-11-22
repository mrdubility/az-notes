package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双链语法提取纯逻辑单测（不依赖 Android，JVM 直跑）。
 * 覆盖：核心语法、嵌入与自锚点、保护规则（围栏 / 行内代码 / 转义 / 未闭合）、
 * 标题行提取、行号、上下文片段与单篇上限。
 */
class WikiLinkExtractorTest {

    @Test
    fun `extracts core variants`() {
        val refs = WikiLinkExtractor.extract(
            "[[笔记]] [[folder/note|别名]] [[note#标题]] [[note#标题|别名]] [[#本文]]"
        )
        assertEquals(5, refs.size)
        assertEquals("笔记", refs[0].target)
        assertNull(refs[0].anchor)
        assertNull(refs[0].alias)
        assertEquals("folder/note", refs[1].target)
        assertEquals("别名", refs[1].alias)
        assertEquals("note", refs[2].target)
        assertEquals("标题", refs[2].anchor)
        assertEquals("别名", refs[3].alias)
        assertTrue(WikiLinkExtractor.isSelfAnchor(refs[4]))
        assertEquals("本文", refs[4].anchor)
    }

    @Test
    fun `extracts embeds and flags them`() {
        val refs = WikiLinkExtractor.extract("![[图.png]] 与 ![[笔记#节]]")
        assertEquals(2, refs.size)
        assertTrue(refs[0].isEmbed)
        assertEquals("图.png", refs[0].target)
        assertTrue(refs[1].isEmbed)
        assertEquals("节", refs[1].anchor)
    }

    @Test
    fun `ignores fenced code blocks`() {
        val refs = WikiLinkExtractor.extract("```\n[[a]]\n```\n[[b]]")
        assertEquals(listOf("b"), refs.map { it.target })
        assertEquals(3, refs[0].line)
    }

    @Test
    fun `ignores inline code spans`() {
        val refs = WikiLinkExtractor.extract("`[[a]]` 与 [[b]]")
        assertEquals(listOf("b"), refs.map { it.target })
    }

    @Test
    fun `ignores escaped opening brackets`() {
        val refs = WikiLinkExtractor.extract("\\[[a]] 与 [[b]]")
        assertEquals(listOf("b"), refs.map { it.target })
    }

    @Test
    fun `ignores unclosed brackets`() {
        assertTrue(WikiLinkExtractor.extract("[[a").isEmpty())
        assertTrue(WikiLinkExtractor.extract("[[a] 与 [[b]").isEmpty())
    }

    @Test
    fun `heading lines are extracted`() {
        val refs = WikiLinkExtractor.extract("## 见 [[笔记]]\n正文")
        assertEquals(1, refs.size)
        assertEquals("笔记", refs[0].target)
    }

    @Test
    fun `multiple links in one line`() {
        val refs = WikiLinkExtractor.extract("[[a]] 与 [[b]]")
        assertEquals(listOf("a", "b"), refs.map { it.target })
        assertEquals(0, refs[0].line)
        assertEquals(0, refs[1].line)
    }

    @Test
    fun `empty anchor target is dropped`() {
        assertTrue(WikiLinkExtractor.extract("[[#]]").isEmpty())
    }

    @Test
    fun `snippet strips wiki markers`() {
        assertEquals("前缀 别名 后缀", WikiLinkExtractor.plainLine("前缀 [[A|别名]] 后缀"))
        assertEquals("A > H", WikiLinkExtractor.plainLine("[[A#H]]"))
        assertEquals("本文", WikiLinkExtractor.plainLine("[[#本文]]"))
        val refs = WikiLinkExtractor.extract("- 见 [[A|别名]]")
        assertEquals("- 见 别名", refs[0].snippet)
    }

    @Test
    fun `snippet is truncated`() {
        val refs = WikiLinkExtractor.extract("前" + "文".repeat(200) + " [[a]]")
        assertTrue(refs[0].snippet.endsWith("…"))
        assertEquals(WikiLinkExtractor.SNIPPET_MAX_CHARS + 1, refs[0].snippet.length)
    }

    @Test
    fun `refs per note capped`() {
        val line = (1..(WikiLinkExtractor.MAX_REFS_PER_NOTE + 100)).joinToString(" ") { "[[n$it]]" }
        assertEquals(WikiLinkExtractor.MAX_REFS_PER_NOTE, WikiLinkExtractor.extract(line).size)
    }
}
