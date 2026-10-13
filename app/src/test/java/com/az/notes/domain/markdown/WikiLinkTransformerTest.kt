package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wikilink 转换纯逻辑单测（不依赖 Android，JVM 直跑）。
 * 覆盖：核心语法（裸名 / 路径 / 别名 / 锚点 / 组合）、图片嵌入与降级、
 * 边界保护（围栏 / 行内代码 / 转义 / 标题行 / 未闭合）、编码往返与幂等。
 */
class WikiLinkTransformerTest {

    @Test
    fun `link variants to az-note scheme`() {
        assertEquals("[笔记](az-note:笔记)", WikiLinkTransformer.transform("[[笔记]]"))
        assertEquals("[folder/note](az-note:folder/note)", WikiLinkTransformer.transform("[[folder/note]]"))
        assertEquals("[别名](az-note:note)", WikiLinkTransformer.transform("[[note|别名]]"))
        assertEquals("[note > 标题](az-note:note%23标题)", WikiLinkTransformer.transform("[[note#标题]]"))
        assertEquals("[别名](az-note:note%23标题)", WikiLinkTransformer.transform("[[note#标题|别名]]"))
        assertEquals("[标题](az-note:%23标题)", WikiLinkTransformer.transform("[[#标题]]"))
    }

    @Test
    fun `targets with spaces and explicit extension`() {
        assertEquals("[my note](az-note:my%20note)", WikiLinkTransformer.transform("[[my note]]"))
        assertEquals("[note.md](az-note:note.md)", WikiLinkTransformer.transform("[[note.md]]"))
    }

    @Test
    fun `empty anchor degrades to plain target`() {
        assertEquals("[note](az-note:note)", WikiLinkTransformer.transform("[[note#]]"))
    }

    @Test
    fun `multiple links in one line`() {
        assertEquals(
            "[a](az-note:a) 与 [b](az-note:b)",
            WikiLinkTransformer.transform("[[a]] 与 [[b]]")
        )
    }

    @Test
    fun `embed resolves image to standard syntax`() {
        val embed: (String) -> String? = { target -> if (target == "img.png") "assets/img.png" else null }
        assertEquals("![](/assets/img.png)", WikiLinkTransformer.transform("![[img.png]]", embed))
    }

    @Test
    fun `embed size param rides as fragment`() {
        val embed: (String) -> String? = { target -> if (target == "img.png") "assets/img.png" else null }
        assertEquals(
            "![](/assets/img.png#az-size=100)",
            WikiLinkTransformer.transform("![[img.png|100]]", embed)
        )
        assertEquals(
            "![](/assets/img.png#az-size=100x245)",
            WikiLinkTransformer.transform("![[img.png|100x245]]", embed)
        )
        // 非尺寸参数（如 alt 文本）忽略，渲染按原始尺寸
        assertEquals("![](/assets/img.png)", WikiLinkTransformer.transform("![[img.png|alt]]", embed))
    }

    @Test
    fun `size spec parse and read back`() {
        assertEquals(100 to null, WikiLinkTransformer.parseSizeSpec("100"))
        assertEquals(100 to 245, WikiLinkTransformer.parseSizeSpec("100x245"))
        assertEquals(100 to 245, WikiLinkTransformer.parseSizeSpec(" 100x245 "))
        assertNull(WikiLinkTransformer.parseSizeSpec("abc"))
        assertNull(WikiLinkTransformer.parseSizeSpec("0"))
        assertNull(WikiLinkTransformer.parseSizeSpec("100x0"))
        assertNull(WikiLinkTransformer.parseSizeSpec(null))
        assertEquals(100 to 245, WikiLinkTransformer.sizeSpecOf("/a/b.png#az-size=100x245"))
        assertNull(WikiLinkTransformer.sizeSpecOf("/a/b.png"))
        assertNull(WikiLinkTransformer.sizeSpecOf("/a/b.png#other"))
    }

    @Test
    fun `embed keeps raw text when missing with resolver`() {
        val embed: (String) -> String? = { null }
        assertEquals("![[missing.png]]", WikiLinkTransformer.transform("![[missing.png]]", embed))
    }

    @Test
    fun `embed without resolver becomes link`() {
        assertEquals("[img.png](az-note:img.png)", WikiLinkTransformer.transform("![[img.png]]"))
    }

    @Test
    fun `embed of note or non-image file degrades to link`() {
        assertEquals(
            "[Note](az-note:Note)",
            WikiLinkTransformer.transform("![[Note]]") { "notes/Note.md" }
        )
        assertEquals(
            "[doc.pdf](az-note:doc.pdf)",
            WikiLinkTransformer.transform("![[doc.pdf]]") { "assets/doc.pdf" }
        )
    }

    @Test
    fun `embed and link coexist`() {
        val embed: (String) -> String? = { target -> if (target == "img.png") "assets/img.png" else null }
        assertEquals(
            "![](/assets/img.png) 和 [note](az-note:note)",
            WikiLinkTransformer.transform("![[img.png]] 和 [[note]]", embed)
        )
    }

    @Test
    fun `inline code and fences are protected`() {
        assertEquals("`[[x]]`", WikiLinkTransformer.transform("`[[x]]`"))
        val md = "[[real]]\n```\n[[fake]]\n```\n[[tail]]"
        assertEquals(
            "[real](az-note:real)\n```\n[[fake]]\n```\n[tail](az-note:tail)",
            WikiLinkTransformer.transform(md)
        )
    }

    @Test
    fun `escaped and unclosed links are untouched`() {
        assertEquals("\\[[x]]", WikiLinkTransformer.transform("\\[[x]]"))
        assertEquals("[[x", WikiLinkTransformer.transform("[[x"))
        assertEquals("[[]]", WikiLinkTransformer.transform("[[]]"))
    }

    @Test
    fun `heading lines are protected`() {
        assertEquals("## 参见 [[x]]", WikiLinkTransformer.transform("## 参见 [[x]]"))
        assertEquals("参见 [[x]]\n===", WikiLinkTransformer.transform("参见 [[x]]\n==="))
    }

    @Test
    fun `transform output decodes back to raw target`() {
        val out = WikiLinkTransformer.transform("[[my note#标题]]")
        assertEquals("[my note > 标题](az-note:my%20note%23标题)", out)
        val encoded = out.substringAfter("az-note:").substringBefore(")")
        assertEquals("my note#标题", WikiLinkTransformer.decodeTarget(encoded))
    }

    @Test
    fun `transform is idempotent`() {
        val once = WikiLinkTransformer.transform("[[a]] ![[b.png]] [[c#h]]")
        assertEquals(once, WikiLinkTransformer.transform(once))
    }
}
