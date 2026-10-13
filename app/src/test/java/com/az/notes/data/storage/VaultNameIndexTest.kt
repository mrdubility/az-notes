package com.az.notes.data.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 全库名称索引单测（不依赖 Android，JVM 直跑）。
 * 覆盖：文件名索引（隐藏项排除）与别名索引（frontmatter 扫描 / 原文大小写保留 / 小写键）。
 */
class VaultNameIndexTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `names indexes files and skips hidden entries`() {
        val vault = temp.newFolder("vault")
        File(vault, "a.md").writeText("x")
        File(vault, "sub").mkdirs()
        File(vault, "sub/b.png").writeText("x")
        File(vault, ".hidden").mkdirs()
        File(vault, ".hidden/c.md").writeText("x")
        val index = VaultNameIndex()
        val names = index.names(vault)
        assertTrue(names.containsKey("a.md"))
        assertTrue(names.containsKey("b.png"))
        assertFalse(names.containsKey("c.md"))
    }

    @Test
    fun `aliases scan frontmatter keeping original case with lowercase keys`() {
        val vault = temp.newFolder("vault")
        File(vault, "notes").mkdirs()
        File(vault, "notes/番茄工作法.md").writeText(
            "---\naliases:\n  - 番茄钟\n  - Pomodoro\n---\nbody"
        )
        File(vault, "plain.md").writeText("no frontmatter")
        val index = VaultNameIndex()
        val aliases = index.aliases(vault)
        assertEquals(2, aliases.size)
        assertEquals("notes/番茄工作法.md", aliases["番茄钟"]?.first()?.relativePath)
        assertEquals("Pomodoro", aliases["pomodoro"]?.first()?.name)
    }

    @Test
    fun `invalidate clears both indexes`() {
        val vault = temp.newFolder("vault")
        File(vault, "a.md").writeText("x")
        val index = VaultNameIndex()
        assertTrue(index.names(vault).containsKey("a.md"))
        assertTrue(index.aliases(vault).isEmpty())
        index.invalidate()
        // 失效后重建不抛错、结果一致
        assertTrue(index.names(vault).containsKey("a.md"))
    }
}
