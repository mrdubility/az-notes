package com.az.notes.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI 纯逻辑单测（§12.2 补充）：
 * - 工具路径归一：read_note 轨迹去重与展示的路径变体归一——统一分隔符、剥前导斜杠与
 *   `.` 段，保证 `./a.md` 与 `a.md`、`/folder/a.md` 与 `folder/a.md` 视为同一文档
 *   （症状「只选一篇却显示读取多篇」的根治验证）；
 * - 流式分块切分 / 尾块补全：splitStreamingBlocks 的块边界（空行切点、围栏内不切）
 *   与无损不变量，autocloseTail 的未闭合围栏补全。
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

    // ---------------------------------------------------------------- 流式分块切分

    @Test
    fun `split keeps everything as tail when no safe blank line`() {
        val text = "第一段\n仍然第一段"
        val result = splitStreamingBlocks(text)
        assertEquals(emptyList<String>(), result.blocks)
        assertEquals(text, result.tail)
    }

    @Test
    fun `split freezes completed paragraphs and keeps last as tail`() {
        val result = splitStreamingBlocks("A\n\nB\n\nC")
        assertEquals(listOf("A\n\n", "B\n\n"), result.blocks)
        assertEquals("C", result.tail)
    }

    @Test
    fun `split merges consecutive blank lines into previous block`() {
        val result = splitStreamingBlocks("A\n\n\nB")
        assertEquals(listOf("A\n\n\n"), result.blocks)
        assertEquals("B", result.tail)
    }

    @Test
    fun `split does not cut inside fenced code block`() {
        val text = "A\n\n```\ncode\n\nmore\n```\n\nB"
        val result = splitStreamingBlocks(text)
        assertEquals(listOf("A\n\n", "```\ncode\n\nmore\n```\n\n"), result.blocks)
        assertEquals("B", result.tail)
    }

    @Test
    fun `split keeps unclosed fence and everything after in tail`() {
        val result = splitStreamingBlocks("A\n\n```\ncode")
        assertEquals(listOf("A\n\n"), result.blocks)
        assertEquals("```\ncode", result.tail)
    }

    @Test
    fun `split is lossless across representative inputs`() {
        val samples = listOf(
            "",
            "单段无空行",
            "A\n\nB",
            "A\n\n",
            "\n\nB",
            "```\na\n\nb",
            "A\n\n~~~\nx\n\n~~~\n\nB",
            "text\n\n\n\nmore\ntext"
        )
        samples.forEach { text ->
            val result = splitStreamingBlocks(text)
            assertEquals(text, result.blocks.joinToString("") + result.tail, "lossless for: $text")
        }
    }

    // ---------------------------------------------------------------- 尾块补全

    @Test
    fun `autoclose appends closing fence when unclosed`() {
        assertEquals("```\ncode\n```", autocloseTail("```\ncode"))
    }

    @Test
    fun `autoclose keeps text untouched when fence closed or absent`() {
        assertEquals("plain", autocloseTail("plain"))
        assertEquals("```\na\n```", autocloseTail("```\na\n```"))
        assertEquals("~~~\na\n~~~", autocloseTail("~~~\na\n~~~"))
    }

    @Test
    fun `autoclose uses matching marker for tilde fence`() {
        assertEquals("~~~\nx\n~~~", autocloseTail("~~~\nx"))
    }
}
