package com.az.notes.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 待办行级读写纯逻辑单测（不依赖 Android，JVM 直跑）：
 * 覆盖提取、勾选切换、文本替换（保留勾选态）、删除（仅任务行）与非法输入拒绝。
 */
class TaskExtractorTest {

    private val body = "intro\n- [ ] first\n- [x] second\noutro"

    @Test
    fun `extract keeps line indexes and checked state`() {
        val tasks = TaskExtractor.extract(body)
        assertEquals(listOf(1, 2), tasks.map { it.lineIndex })
        assertEquals(listOf(false, true), tasks.map { it.checked })
        assertEquals(listOf("first", "second"), tasks.map { it.text })
    }

    @Test
    fun `toggle flips checkbox only`() {
        assertEquals(
            "intro\n- [x] first\n- [x] second\noutro",
            TaskExtractor.toggle(body, 1, true)
        )
        assertNull(TaskExtractor.toggle(body, 0, true))
    }

    @Test
    fun `update replaces text and keeps checked state`() {
        assertEquals(
            "intro\n- [ ] renamed\n- [x] second\noutro",
            TaskExtractor.update(body, 1, "renamed")
        )
        assertEquals(
            "intro\n- [ ] first\n- [x] renamed\noutro",
            TaskExtractor.update(body, 2, "renamed")
        )
    }

    @Test
    fun `update rejects blank text and non task line`() {
        assertNull(TaskExtractor.update(body, 1, "  "))
        assertNull(TaskExtractor.update(body, 0, "x"))
    }

    @Test
    fun `delete removes only task line`() {
        assertEquals("intro\n- [x] second\noutro", TaskExtractor.delete(body, 1))
        assertNull(TaskExtractor.delete(body, 0))
    }
}
