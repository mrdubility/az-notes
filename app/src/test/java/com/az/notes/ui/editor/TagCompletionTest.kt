package com.az.notes.ui.editor

import com.az.notes.data.storage.TagRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `#标签` 补全上下文检测与候选过滤纯逻辑单测（不依赖 Android，JVM 直跑）。
 */
class TagCompletionTest {

    @Test
    fun `cursor after inline tag fragment triggers`() {
        val content = "正文 #ta"
        val query = tagQueryAt(content, content.length)
        assertEquals(TagQuery(content.indexOf('#'), content.length, "ta"), query)
    }

    @Test
    fun `line start tag fragment triggers`() {
        val content = "#ta"
        val query = tagQueryAt(content, content.length)
        assertEquals(TagQuery(0, 3, "ta"), query)
    }

    @Test
    fun `bare hash does not trigger`() {
        assertNull(tagQueryAt("#", 1))
        assertNull(tagQueryAt("正文 #", 4))
    }

    @Test
    fun `hash followed by space is heading and does not trigger`() {
        assertNull(tagQueryAt("# 标题", 4))
        assertNull(tagQueryAt("# 标题", 2))
    }

    @Test
    fun `letter or url chars before hash do not trigger`() {
        assertNull(tagQueryAt("abc#ta", 6))
        assertNull(tagQueryAt("https://x/#ta", 13))
        assertNull(tagQueryAt("path/#ta", 9))
    }

    @Test
    fun `chinese punctuation before hash triggers`() {
        val content = "。#标签"
        val query = tagQueryAt(content, content.length)
        assertEquals(TagQuery(1, 4, "标签"), query)
    }

    @Test
    fun `completed tag followed by space does not trigger`() {
        assertNull(tagQueryAt("#tag ", 5))
    }

    @Test
    fun `hash beyond line break does not trigger`() {
        // `#` 在光标前一行的文本中：光标所在行无未闭合 `#`
        assertNull(tagQueryAt("#a\n行二", 5))
        // 光标与 `#` 同处第二行时正常触发
        assertEquals(TagQuery(3, 6, "tb"), tagQueryAt("#a\n#tb", 6))
    }

    @Test
    fun `escaped hash does not trigger`() {
        assertNull(tagQueryAt("\\#ta", 4))
    }

    @Test
    fun `inline code and fence contexts do not trigger`() {
        assertNull(tagQueryAt("`#ta`", 4))
        assertNull(tagQueryAt("```\n#ta", 6))
    }

    @Test
    fun `candidate filter ranks prefix then count then name`() {
        val all = listOf(
            TagRow("apple", 3),
            TagRow("application", 10),
            TagRow("banana", 5)
        )
        // 前缀命中优先；同为前缀时计数降序
        assertEquals(
            listOf("application", "apple"),
            filterTagCandidates(all, "app", 10).map { it.name }
        )
        // 空查询按索引顺序取前 N
        assertEquals(
            listOf("apple", "application"),
            filterTagCandidates(all, "", 2).map { it.name }
        )
        // 无命中
        assertEquals(emptyList<TagRow>(), filterTagCandidates(all, "zzz", 10))
    }
}
