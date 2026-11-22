package com.az.notes.domain.notes

import com.az.notes.domain.model.FileNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表装配纯逻辑单测：置顶切分（顺序 / 不重复 / 跨目录隔离）与启动目录解析。 */
class ListingOrderTest {

    private fun note(relative: String) = FileNode(
        relativePath = relative,
        absolutePath = "/vault/$relative",
        name = relative.substringAfterLast('/'),
        isDirectory = false,
        size = 0L,
        lastModified = 0L,
        depth = 1,
        isMarkdown = true,
        isAttachment = false
    )

    private fun paths(notes: List<FileNode>) = notes.map { it.relativePath }

    // ---------------------------------------------------------------- 置顶切分

    @Test
    fun `no pinned keeps input order and empty pinned zone`() {
        val notes = listOf(note("a.md"), note("b.md"), note("c.md"))
        val (pinned, rest) = splitPinnedNotes(notes, emptySet())
        assertTrue(pinned.isEmpty())
        assertEquals(listOf("a.md", "b.md", "c.md"), paths(rest))
    }

    @Test
    fun `pinned note moves to pinned zone and is not duplicated`() {
        val notes = listOf(note("a.md"), note("b.md"), note("c.md"))
        val (pinned, rest) = splitPinnedNotes(notes, setOf("b.md"))
        assertEquals(listOf("b.md"), paths(pinned))
        assertEquals(listOf("a.md", "c.md"), paths(rest))
        assertEquals(notes.size, pinned.size + rest.size)
    }

    @Test
    fun `multiple pinned keep relative order`() {
        val notes = listOf(note("a.md"), note("b.md"), note("c.md"), note("d.md"))
        val (pinned, rest) = splitPinnedNotes(notes, setOf("d.md", "b.md"))
        assertEquals(listOf("b.md", "d.md"), paths(pinned))
        assertEquals(listOf("a.md", "c.md"), paths(rest))
    }

    @Test
    fun `pinned paths of other folders or stale entries do not affect split`() {
        val notes = listOf(note("work/a.md"), note("work/b.md"))
        val (pinned, rest) = splitPinnedNotes(
            notes,
            setOf("work/a.md", "other/c.md", "deleted/d.md")
        )
        assertEquals(listOf("work/a.md"), paths(pinned))
        assertEquals(listOf("work/b.md"), paths(rest))
    }

    // ------------------------------------------------------------ 启动目录解析

    @Test
    fun `existing sub folder resolves to absolute path`() {
        val result = resolveStartFolder("/vault", "work/2026", isDirectory = { it == "/vault/work/2026" })
        assertEquals("/vault/work/2026", result)
    }

    @Test
    fun `trailing slash is normalized and blank falls back to root`() {
        // 尾随斜杠归一化后仍命中目录
        assertEquals(
            "/vault/work",
            resolveStartFolder("/vault", "work/", isDirectory = { it == "/vault/work" })
        )
        // 未配置（null）/ 空白：回落根目录
        assertNull(resolveStartFolder("/vault", null, isDirectory = { true }))
        assertNull(resolveStartFolder("/vault", "   ", isDirectory = { true }))
    }

    @Test
    fun `escaping paths are rejected`() {
        assertNull(resolveStartFolder("/vault", "../etc", isDirectory = { true }))
        assertNull(resolveStartFolder("/vault", "work/../..", isDirectory = { true }))
        assertNull(resolveStartFolder("/vault", "/etc", isDirectory = { true }))
        assertNull(resolveStartFolder("/vault", "./work", isDirectory = { true }))
    }

    @Test
    fun `missing directory falls back to root`() {
        assertNull(resolveStartFolder("/vault", "gone", isDirectory = { false }))
    }
}
