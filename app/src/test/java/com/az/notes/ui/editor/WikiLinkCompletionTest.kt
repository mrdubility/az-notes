package com.az.notes.ui.editor

import com.az.notes.domain.markdown.NoteAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[[` 内联补全纯逻辑单测（不依赖 Android，JVM 直跑）。
 * 覆盖：光标上下文检测（触发 / 保护场景 / 已闭合整体替换）与候选构建、过滤排序。
 */
class WikiLinkCompletionTest {

    // ---------------------------------------------------------------- 上下文检测

    @Test
    fun `query detected after open brackets`() {
        assertEquals(WikiLinkQuery(0, 2, ""), wikiLinkQueryAt("[[", 2))
        assertEquals(WikiLinkQuery(0, 3, "番"), wikiLinkQueryAt("[[番", 3))
        assertEquals(WikiLinkQuery(2, 7, "a b"), wikiLinkQueryAt("x [[a b", 7))
    }

    @Test
    fun `closed link gets replaced as a whole`() {
        // 光标在已闭合链接内：替换范围含尾部 `]]`，避免残留括号
        assertEquals(WikiLinkQuery(0, 7, "abc"), wikiLinkQueryAt("[[abc]]", 5))
    }

    @Test
    fun `no query in protected or ambiguous contexts`() {
        assertNull(wikiLinkQueryAt("\\[[a", 4))
        assertNull(wikiLinkQueryAt("`[[a", 4))
        assertNull(wikiLinkQueryAt("```\n[[a", 7))
        assertNull(wikiLinkQueryAt("[[a\nb", 5))
        assertNull(wikiLinkQueryAt("[[a]] ", 6))
        assertNull(wikiLinkQueryAt("[[a#", 4))
        assertNull(wikiLinkQueryAt("[[a|", 4))
        assertNull(wikiLinkQueryAt("[[a[", 4))
        assertNull(wikiLinkQueryAt("", 0))
        assertNull(wikiLinkQueryAt("abc", 3))
    }

    // ---------------------------------------------------------------- 候选构建与过滤

    private val names = mapOf("番茄工作法.md" to listOf("notes/番茄工作法.md"))
    private val aliases = mapOf("番茄钟" to listOf(NoteAlias("番茄钟", "notes/番茄工作法.md")))

    @Test
    fun `builds name and alias candidates`() {
        val all = buildWikiLinkCandidates(names, aliases)
        assertEquals(2, all.size)
        val name = all.first { !it.isAlias }
        assertEquals("番茄工作法", name.insertText)
        assertEquals("notes", name.detail)
        val alias = all.first { it.isAlias }
        assertEquals("番茄工作法|番茄钟", alias.insertText)
        assertEquals("番茄钟", alias.label)
        assertEquals("番茄工作法", alias.detail)
    }

    @Test
    fun `ambiguous names insert relative path`() {
        val all = buildWikiLinkCandidates(mapOf("a.md" to listOf("x/a.md", "y/a.md")), emptyMap())
        assertEquals(listOf("x/a", "y/a"), all.map { it.insertText }.sorted())
    }

    @Test
    fun `filter ranks prefix and matches alias`() {
        val all = buildWikiLinkCandidates(names, aliases)
        val byAlias = filterWikiLinkCandidates(all, "番茄钟", 20)
        assertEquals(listOf("番茄钟"), byAlias.map { it.label })
        assertEquals(2, filterWikiLinkCandidates(all, "", 20).size)
        assertTrue(filterWikiLinkCandidates(all, "不存在", 20).isEmpty())
    }

    @Test
    fun `filter limit caps results`() {
        val all = buildWikiLinkCandidates(
            (1..30).associate { "n$it.md" to listOf("n$it.md") },
            emptyMap()
        )
        assertEquals(20, filterWikiLinkCandidates(all, "n", 20).size)
    }
}
