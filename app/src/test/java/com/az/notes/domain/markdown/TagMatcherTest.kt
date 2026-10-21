package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #标签 识别与渲染转换纯逻辑单测（不依赖 Android，JVM 直跑）。
 * 覆盖：边界（前导 / 尾随 / 中文标点 / URL 锚点）、全数字、长度上限、
 * 嵌套与 `_`/`-`、Unicode、保护口径（代码块 / 行内代码 / 链接目标 / 标题行）、
 * 转义、transform 产物与大小写归一化。
 */
class TagMatcherTest {

    private fun tags(content: String): List<String> =
        TagMatcher.extract(content).map { it.tag }

    @Test
    fun `basic inline tags`() {
        assertEquals(listOf("tag"), tags("#tag 文本"))
        assertEquals(listOf("tag"), tags("文本 #tag 结束"))
        assertEquals(listOf("tag"), tags("行尾 #tag"))
        assertEquals(listOf("标签"), tags("。#标签"))
    }

    @Test
    fun `leading letter digit or url chars block detection`() {
        assertTrue(tags("abc#tag").isEmpty())
        assertTrue(tags("https://x.com/#frag").isEmpty())
        assertTrue(tags("https://x.com/path?q=1#frag").isEmpty())
        assertTrue(tags("path/#frag").isEmpty())
    }

    @Test
    fun `all digit tags not recognized`() {
        assertTrue(tags("#1984").isEmpty())
        assertEquals(listOf("y1984"), tags("#y1984"))
    }

    @Test
    fun `length limit enforced as a whole`() {
        val tag63 = "t".repeat(63)
        val tag64 = "t".repeat(64)
        val tag65 = "t".repeat(65)
        assertEquals(listOf(tag64), tags("#$tag64"))
        assertTrue(tags("#$tag65").isEmpty())
        assertEquals(listOf(tag63), tags("#$tag63"))
    }

    @Test
    fun `nested underscore and hyphen tags`() {
        assertEquals(listOf("inbox/to-read"), tags("#inbox/to-read"))
        assertEquals(listOf("snake_case"), tags("#snake_case"))
        assertEquals(listOf("kebab-case"), tags("#kebab-case"))
        assertEquals(listOf("inbox"), tags("#inbox"))
    }

    @Test
    fun `multiple tags in one text`() {
        assertEquals(listOf("a", "b"), tags("#a 和 #b"))
        // 连写：后一个 `#` 前导为字母数字，整串只识别第一个标签
        assertEquals(listOf("a"), tags("#a#b"))
    }

    @Test
    fun `protected contexts not recognized`() {
        assertTrue(tags("```\n#code\n```").isEmpty())
        assertTrue(tags("`#inline`").isEmpty())
        assertTrue(tags("[链接](https://x.com/#frag)").isEmpty())
        assertTrue(tags("<https://x.com/#frag>").isEmpty())
        assertTrue(tags("\\#escaped").isEmpty())
    }

    @Test
    fun `heading lines not recognized`() {
        assertTrue(tags("# 标题 #tag").isEmpty())
        assertTrue(tags("## 标题 #tag").isEmpty())
        assertTrue(tags("Title #tag\n===").isEmpty())
        assertTrue(tags("Title #tag\n---").isEmpty())
        // 非标题行内的标签仍识别
        assertEquals(listOf("tag"), tags("正文 #tag"))
    }

    @Test
    fun `transform produces clickable links`() {
        assertEquals(
            "[#tag](az-tag:tag)",
            TagMatcher.transform("#tag")
        )
        assertEquals(
            "前缀 [#tag](az-tag:tag) 后缀",
            TagMatcher.transform("前缀 #tag 后缀")
        )
        // 嵌套标签名编码：/ 编码为 %2F
        assertEquals(
            "[#inbox/to-read](az-tag:inbox%2Fto-read)",
            TagMatcher.transform("#inbox/to-read")
        )
        // 无标签文本原样返回
        assertEquals("纯文本", TagMatcher.transform("纯文本"))
        // 代码块内不转换
        assertEquals(
            "```\n#code\n```",
            TagMatcher.transform("```\n#code\n```")
        )
    }

    @Test
    fun `key normalization is case insensitive`() {
        assertEquals("tag", TagMatcher.keyOf("Tag"))
        assertEquals("tag", TagMatcher.keyOf("TAG"))
        assertEquals(TagMatcher.keyOf("标签"), TagMatcher.keyOf("标签"))
    }

    @Test
    fun `isValid rules`() {
        assertTrue(TagMatcher.isValid("tag"))
        assertTrue(TagMatcher.isValid("标签"))
        assertTrue(TagMatcher.isValid("inbox/to-read"))
        assertTrue(TagMatcher.isValid("snake_case"))
        assertFalse(TagMatcher.isValid("1984"))
        assertFalse(TagMatcher.isValid("has space"))
        assertFalse(TagMatcher.isValid("with.dot"))
        assertFalse(TagMatcher.isValid("t".repeat(65)))
        assertFalse(TagMatcher.isValid(""))
    }

    @Test
    fun `extract reports original offsets`() {
        val content = "前缀 #tag 后缀"
        val matches = TagMatcher.extract(content)
        assertEquals(1, matches.size)
        assertEquals(3, matches[0].start)
        assertEquals(7, matches[0].end)
        assertEquals("#tag", content.substring(matches[0].start, matches[0].end))
    }
}
