package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * frontmatter aliases 提取纯逻辑单测（不依赖 Android，JVM 直跑）。
 * 覆盖：块列表 / 行内列表 / 引号包裹 / 单值 / 无声明 / 无属性块。
 */
class FrontmatterTest {

    @Test
    fun `block list aliases`() {
        val split = FrontmatterParser.split(
            "---\naliases:\n  - 番茄钟\n  - \"Pomodoro\"\n---\nbody"
        )
        assertEquals(listOf("番茄钟", "Pomodoro"), split.aliases())
    }

    @Test
    fun `inline list and single value aliases`() {
        assertEquals(
            listOf("a", "b"),
            FrontmatterParser.split("---\naliases: [a, b]\n---\n").aliases()
        )
        assertEquals(
            listOf("单值"),
            FrontmatterParser.split("---\naliases: 单值\n---\n").aliases()
        )
    }

    @Test
    fun `no aliases or no frontmatter yields empty`() {
        assertTrue(FrontmatterParser.split("---\ntag: log\n---\nbody").aliases().isEmpty())
        assertTrue(FrontmatterParser.split("plain body").aliases().isEmpty())
        assertTrue(FrontmatterParser.split("---\naliases:\n---\n").aliases().isEmpty())
    }

    @Test
    fun `build writes single multi and empty values`() {
        val text = FrontmatterParser.build(
            listOf(
                FrontmatterAttribute("title", listOf("Hello")),
                FrontmatterAttribute("tags", listOf("a", "b")),
                FrontmatterAttribute("flag", emptyList())
            ),
            "body"
        )
        assertEquals("---\ntitle: Hello\ntags:\n  - a\n  - b\nflag:\n---\nbody", text)
    }

    @Test
    fun `update replaces attributes and keeps body`() {
        val original = "---\ntitle: Old\ntags: x\n---\nline\n"
        val updated = FrontmatterParser.update(
            original,
            listOf(
                FrontmatterAttribute("title", listOf("New")),
                FrontmatterAttribute("done", listOf("true"))
            )
        )
        val split = FrontmatterParser.split(updated)
        assertEquals(listOf("title", "done"), split.entries.map { it.key })
        assertEquals(listOf("New"), split.value("title")?.let(::listOf))
        assertEquals("true", split.value("done"))
        assertEquals("line\n", split.body)
    }

    @Test
    fun `empty attributes drop frontmatter block`() {
        assertEquals("body", FrontmatterParser.update("---\na: b\n---\nbody", emptyList()))
    }

    @Test
    fun `tags supports block and inline lists with optional hash prefix`() {
        assertEquals(
            listOf("recipe", "cooking"),
            FrontmatterParser.split("---\ntags:\n  - recipe\n  - cooking\n---\n").tags()
        )
        assertEquals(
            listOf("a", "b"),
            FrontmatterParser.split("---\ntags: [a, #b]\n---\n").tags()
        )
        assertEquals(
            listOf("单值"),
            FrontmatterParser.split("---\ntags: 单值\n---\n").tags()
        )
    }

    @Test
    fun `tags filters invalid values and empty declarations`() {
        assertEquals(
            listOf("ok"),
            FrontmatterParser.split("---\ntags: [ok, 1984, \"has space\"]\n---\n").tags()
        )
        assertTrue(FrontmatterParser.split("---\ntags:\n---\n").tags().isEmpty())
        assertTrue(FrontmatterParser.split("plain body").tags().isEmpty())
    }

    // ---------------------------------------------------------------- patch（行区间外科手术）

    @Test
    fun `patch keeps nested yaml comments and untouched lines`() {
        val original = "---\ntitle: 项目\nauthor:\n  name: az\n  url: https://x\n# 备注行\ntags: [a, b]\n---\nbody"
        val entries = FrontmatterParser.split(original).entries.map {
            if (it.key == "title") it.copy(values = listOf("新项目")) else it
        }
        val patched = FrontmatterParser.patchAll(original, entries)
        assertTrue(patched is FrontmatterPatchResult.Ok)
        val text = (patched as FrontmatterPatchResult.Ok).text

        assertEquals(
            "---\ntitle: 新项目\nauthor:\n  name: az\n  url: https://x\n# 备注行\ntags: [a, b]\n---\nbody",
            text
        )
    }

    @Test
    fun `patch refuses editing key whose yaml is inexpressible`() {
        val original = "---\nauthor:\n  name: az\n---\nbody"
        val entries = FrontmatterParser.split(original).entries.map {
            if (it.key == "author") it.copy(values = listOf("az")) else it
        }
        val result = FrontmatterParser.patchAll(original, entries)
        assertTrue(result is FrontmatterPatchResult.Refused)
        assertEquals(listOf("author"), (result as FrontmatterPatchResult.Refused).keys)
    }

    @Test
    fun `patch rewrites only the changed key lines`() {
        val original = "---\ntags:\n  - a\n  - b\ntitle: t\n---\nbody"
        val entries = FrontmatterParser.split(original).entries.map {
            if (it.key == "tags") it.copy(values = listOf("c")) else it
        }
        val text = (FrontmatterParser.patchAll(original, entries) as FrontmatterPatchResult.Ok).text
        assertEquals("---\ntags: c\ntitle: t\n---\nbody", text)
    }

    @Test
    fun `patch appends new keys and drops removed ones`() {
        val original = "---\ntitle: t\n# 说明\nstatus: draft\n---\nbody"
        val entries = FrontmatterParser.split(original).entries.filter { it.key != "status" } +
            FrontmatterAttribute("done", listOf("true"))
        val text = (FrontmatterParser.patchAll(original, entries) as FrontmatterPatchResult.Ok).text
        assertEquals("---\ntitle: t\n# 说明\ndone: true\n---\nbody", text)
    }

    @Test
    fun `patch without changes keeps original block bytes`() {
        val original = "---\ntitle: t\nauthor:\n  name: az\n---\nbody"
        val entries = FrontmatterParser.split(original).entries
        val text = (FrontmatterParser.patchAll(original, entries) as FrontmatterPatchResult.Ok).text
        assertEquals(original, text)
    }

    @Test
    fun `patch with empty entries drops block`() {
        val text = (FrontmatterParser.patchAll("---\na: b\n---\nbody", emptyList()) as FrontmatterPatchResult.Ok).text
        assertEquals("body", text)
    }

    @Test
    fun `patch creates block when none exists`() {
        val text = (
            FrontmatterParser.patchAll("body", listOf(FrontmatterAttribute("title", listOf("t"))))
                as FrontmatterPatchResult.Ok
            ).text
        assertEquals("---\ntitle: t\n---\nbody", text)
    }
}
