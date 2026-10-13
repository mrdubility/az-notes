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
}
