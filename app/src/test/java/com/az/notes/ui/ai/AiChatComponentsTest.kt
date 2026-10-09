package com.az.notes.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI 工具路径归一单测（§12.2 补充）：read_note 轨迹去重与展示用的路径变体归一——
 * 统一分隔符、剥前导斜杠与 `.` 段，保证 `./a.md` 与 `a.md`、`/folder/a.md` 与
 * `folder/a.md` 视为同一文档（症状「只选一篇却显示读取多篇」的根治验证）。
 */
class AiChatComponentsTest {

    @Test
    fun `normalize tool path unifies variants to single form`() {
        assertEquals("a.md", normalizeToolPath("a.md"))
        assertEquals("a.md", normalizeToolPath("./a.md"))
        assertEquals("a.md", normalizeToolPath("/a.md"))
        assertEquals("a.md", normalizeToolPath(" /a.md "))
        assertEquals("folder/a.md", normalizeToolPath("folder/a.md"))
        assertEquals("folder/a.md", normalizeToolPath("/folder/a.md"))
        assertEquals("folder/a.md", normalizeToolPath("./folder/a.md"))
        assertEquals("folder/a.md", normalizeToolPath("folder\\a.md"))
        assertEquals("folder/a.md", normalizeToolPath("./folder//a.md"))
        assertEquals("folder/a.md", normalizeToolPath("\\folder\\a.md"))
    }

    @Test
    fun `normalize tool path keeps parent segments and collapses to blank for root only`() {
        // 仅作展示归一，不做越界消解：.. 段原样保留
        assertEquals("folder/../a.md", normalizeToolPath("folder/../a.md"))
        assertEquals("", normalizeToolPath(""))
        assertEquals("", normalizeToolPath("/"))
        assertEquals("", normalizeToolPath("./"))
        assertEquals("", normalizeToolPath("   "))
    }
}
