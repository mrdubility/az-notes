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

    @Test
    fun `alias index resolves note by declared alias`() {
        val vault = temp.newFolder("vault")
        val note = File(vault, "notes/番茄工作法.md").apply {
            parentFile!!.mkdirs()
            writeText("---\naliases:\n  - 番茄钟\n  - Pomodoro\n---\n")
        }
        val elsewhere = File(vault, "other").apply { mkdirs() }
        val aliases = mapOf(
            "番茄钟" to listOf(NoteAlias("番茄钟", "notes/番茄工作法.md")),
            "pomodoro" to listOf(NoteAlias("Pomodoro", "notes/番茄工作法.md"))
        )
        // 别名大小写不敏感命中
        assertEquals(note.absolutePath, WikiLinkResolver.resolve("番茄钟", vault, elsewhere, emptyMap(), aliases)?.absolutePath)
        assertEquals(note.absolutePath, WikiLinkResolver.resolve("pomodoro", vault, elsewhere, emptyMap(), aliases)?.absolutePath)
    }

    @Test
    fun `filename wins over alias`() {
        val vault = temp.newFolder("vault")
        File(vault, "other").mkdirs()
        val realFile = File(vault, "x.md").apply { writeText("a") }
        File(vault, "other/真名.md").writeText("b")
        val aliases = mapOf("x" to listOf(NoteAlias("x", "other/真名.md")))
        assertEquals(realFile.absolutePath, WikiLinkResolver.resolve("x", vault, vault, emptyMap(), aliases)?.absolutePath)
    }

    @Test
    fun `alias disambiguation prefers same directory`() {
        val vault = temp.newFolder("vault")
        val targetDir = File(vault, "a").apply { mkdirs() }
        File(vault, "b").mkdirs()
        val near = File(targetDir, "near.md").apply { writeText("a") }
        File(vault, "b/far.md").writeText("b")
        val aliases = mapOf(
            "短链" to listOf(NoteAlias("短链", "b/far.md"), NoteAlias("短链", "a/near.md"))
        )
        assertEquals(near.absolutePath, WikiLinkResolver.resolve("短链", vault, targetDir, emptyMap(), aliases)?.absolutePath)
    }

    @Test
    fun `createNotePath appends md and resolves from vault root`() {
        val vault = temp.newFolder("vault")
        assertEquals(
            File(vault, "folder/my note.md").absolutePath,
            WikiLinkResolver.createNotePath("folder/my note", vault)?.absolutePath
        )
        assertEquals(
            File(vault, "note.md").absolutePath,
            WikiLinkResolver.createNotePath("/note", vault)?.absolutePath
        )
        assertEquals(
            File(vault, "x.md").absolutePath,
            WikiLinkResolver.createNotePath("x.md#标题", vault)?.absolutePath
        )
    }

    @Test
    fun `createNotePath rejects empty and escaping targets`() {
        val vault = temp.newFolder("vault")
        assertNull(WikiLinkResolver.createNotePath("", vault))
        assertNull(WikiLinkResolver.createNotePath("../outside", vault))
    }
}
