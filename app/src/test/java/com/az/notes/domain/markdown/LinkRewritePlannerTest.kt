package com.az.notes.domain.markdown

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 改名 / 移动后的链接改写计划单测（纯逻辑，不写盘）。
 * 覆盖：裸名、带路径写法、锚点、别名、别名等于旧名、嵌入、指向他处不动、未解析不动、
 * 偏移与期望原文。
 */
class LinkRewritePlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ref(
        raw: String,
        target: String,
        anchor: String? = null,
        alias: String? = null,
        embed: Boolean = false,
        start: Int = 0,
        end: Int = start + raw.length
    ): RawLinkRef = RawLinkRef(
        line = 0,
        raw = raw,
        target = target,
        anchor = anchor,
        alias = alias,
        isEmbed = embed,
        snippet = raw,
        startOffset = start,
        endOffset = end
    )

    private fun plan(
        vault: File,
        refs: List<RawLinkRef>,
        old: File,
        new: File,
        resolve: (RawLinkRef) -> String? = { old.absolutePath }
    ): NoteRewritePlan? = LinkRewritePlanner.planForSource(
        sourcePath = File(vault, "src.md").absolutePath,
        refs = refs,
        moveByOldPath = mapOf(old.absolutePath to new.absolutePath),
        vaultRoot = vault,
        resolve = resolve
    )

    @Test
    fun `bare name rewritten to new name`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val result = plan(vault, listOf(ref("[[Old]]", "Old")), old, new)
        assertEquals("[[New]]", result!!.edits.single().replacement)
    }

    @Test
    fun `path form is rewritten to the new relative path`() {
        val vault = temp.newFolder("v")
        File(vault, "a").mkdirs()
        File(vault, "b").mkdirs()
        val old = File(vault, "a/Old.md")
        val new = File(vault, "b/New.md")
        val result = plan(vault, listOf(ref("[[a/Old]]", "a/Old")), old, new)
        assertEquals("[[b/New]]", result!!.edits.single().replacement.replace('\\', '/'))
    }

    @Test
    fun `anchor and alias are preserved`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val refs = listOf(
            ref("[[Old#H]]", "Old", anchor = "H"),
            ref("[[Old|显示名]]", "Old", alias = "显示名")
        )
        val result = plan(vault, refs, old, new)!!
        assertEquals(listOf("[[New#H]]", "[[New|显示名]]"), result.edits.map { it.replacement })
    }

    @Test
    fun `alias equal to old name follows the rename`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val result = plan(vault, listOf(ref("[[Old|Old]]", "Old", alias = "Old")), old, new)
        assertEquals("[[New|New]]", result!!.edits.single().replacement)
    }

    @Test
    fun `embed keeps the bang prefix`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val result = plan(vault, listOf(ref("![[Old]]", "Old", embed = true)), old, new)
        assertEquals("![[New]]", result!!.edits.single().replacement)
    }

    @Test
    fun `references resolving elsewhere are untouched`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val other = File(vault, "Other.md")
        val result = plan(vault, listOf(ref("[[Old]]", "Old")), old, new) { other.absolutePath }
        assertNull(result)
    }

    @Test
    fun `unresolved references are untouched`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val result = plan(vault, listOf(ref("[[Missing]]", "Missing")), old, new) { null }
        assertNull(result)
    }

    @Test
    fun `edits keep body offsets and expected raw`() {
        val vault = temp.newFolder("v")
        val old = File(vault, "Old.md")
        val new = File(vault, "New.md")
        val link = ref("[[Old]]", "Old", start = 3, end = 10)
        val edit = plan(vault, listOf(link), old, new)!!.edits.single()
        assertEquals(3, edit.startInBody)
        assertEquals(10, edit.endInBody)
        assertEquals("[[Old]]", edit.expectedRaw)
        assertEquals(old.absolutePath, edit.movedFrom)
    }
}
