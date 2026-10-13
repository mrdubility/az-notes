package com.az.notes.domain.markdown

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Wikilink 目标解析纯逻辑单测（不依赖 Android，JVM 直跑）。
 * 覆盖：同目录优先、仓库根回退、nameIndex 跨目录命中与消歧（层少者先 / 同级字典序）、
 * 根相对路径、缺失与 `../` 越界护栏。
 */
class WikiLinkResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `bare name prefers note directory`() {
        val vault = temp.newFolder("vault")
        val dir = File(vault, "sub").apply { mkdirs() }
        val near = File(dir, "x.md").apply { writeText("a") }
        File(vault, "x.md").writeText("b")
        assertEquals(near.absolutePath, WikiLinkResolver.resolve("x", vault, dir)?.absolutePath)
    }

    @Test
    fun `bare name falls back to vault root`() {
        val vault = temp.newFolder("vault")
        val other = File(vault, "sub").apply { mkdirs() }
        val rootNote = File(vault, "x.md").apply { writeText("b") }
        assertEquals(rootNote.absolutePath, WikiLinkResolver.resolve("x", vault, other)?.absolutePath)
    }

    @Test
    fun `index resolves cross directory and disambiguates`() {
        val vault = temp.newFolder("vault")
        File(vault, "a/b").mkdirs()
        File(vault, "c").mkdirs()
        File(vault, "a/b/dup.md").writeText("x")
        val shallow = File(vault, "c/dup.md").apply { writeText("x") }
        val index = mapOf("dup.md" to listOf("a/b/dup.md", "c/dup.md"))
        // 层级少者先
        assertEquals(shallow.absolutePath, WikiLinkResolver.resolve("dup", vault, vault, index)?.absolutePath)
        // 显式后缀走同一键
        assertEquals(shallow.absolutePath, WikiLinkResolver.resolve("dup.md", vault, vault, index)?.absolutePath)
    }

    @Test
    fun `index ties pick lexical path`() {
        val vault = temp.newFolder("vault")
        File(vault, "a").mkdirs()
        File(vault, "b").mkdirs()
        File(vault, "b/same.md").writeText("x")
        val first = File(vault, "a/same.md").apply { writeText("x") }
        val index = mapOf("same.md" to listOf("b/same.md", "a/same.md"))
        assertEquals(first.absolutePath, WikiLinkResolver.resolve("same", vault, vault, index)?.absolutePath)
    }

    @Test
    fun `slashed target resolves from vault root`() {
        val vault = temp.newFolder("vault")
        val note = File(vault, "notes/n.md").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        val elsewhere = File(vault, "other").apply { mkdirs() }
        assertEquals(note.absolutePath, WikiLinkResolver.resolve("notes/n", vault, elsewhere)?.absolutePath)
        assertEquals(note.absolutePath, WikiLinkResolver.resolve("/notes/n.md", vault, elsewhere)?.absolutePath)
    }

    @Test
    fun `missing and escaping targets return null`() {
        val vault = temp.newFolder("vault")
        assertNull(WikiLinkResolver.resolve("nope", vault, vault))
        assertNull(WikiLinkResolver.resolve("../outside", vault, vault))
    }
}
