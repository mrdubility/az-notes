package com.az.notes.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲突标记识别与消解单测（纯逻辑，JVM 直跑）。
 * 覆盖：新格式识别 / 计数、保留本地、保留云端、未闭合块不吞正文、无标记原样返回、
 * 旧格式（`<<<<<<<` 那一套）仍可消解、与合并输出端到端可消解。
 */
class ConflictMarkerResolverTest {

    private val marked = buildString {
        appendLine("开头")
        appendLine(ParagraphMerger.MARK_LOCAL)
        appendLine("本地内容")
        appendLine(ParagraphMerger.MARK_SEPARATOR)
        appendLine(ParagraphMerger.MARK_REMOTE)
        appendLine("云端内容")
        appendLine(ParagraphMerger.MARK_END)
        append("结尾")
    }

    @Test
    fun `detects and counts markers`() {
        assertTrue(ConflictMarkerResolver.hasMarkers(marked))
        assertEquals(1, ConflictMarkerResolver.countMarkers(marked))
        assertFalse(ConflictMarkerResolver.hasMarkers("普通笔记 [[链接]] #标签"))
    }

    @Test
    fun `keepLocal removes cloud block and markers`() {
        val resolved = ConflictMarkerResolver.keepLocal(marked)

        assertTrue(resolved.contains("本地内容"))
        assertFalse(resolved.contains("云端内容"))
        assertFalse(resolved.contains(ParagraphMerger.MARK_LOCAL))
        assertFalse(resolved.contains(ParagraphMerger.MARK_SEPARATOR))
        assertFalse(resolved.contains(ParagraphMerger.MARK_REMOTE))
        assertFalse(resolved.contains(ParagraphMerger.MARK_END))
    }

    @Test
    fun `keepRemote removes local block and markers`() {
        val resolved = ConflictMarkerResolver.keepRemote(marked)

        assertTrue(resolved.contains("云端内容"))
        assertFalse(resolved.contains("本地内容"))
        assertFalse(resolved.contains(ParagraphMerger.MARK_LOCAL))
    }

    @Test
    fun `keep local keeps surrounding paragraphs`() {
        val resolved = ConflictMarkerResolver.keepLocal(marked)

        assertTrue(resolved.startsWith("开头"))
        assertTrue(resolved.trimEnd().endsWith("结尾"))
    }

    @Test
    fun `unclosed block keeps content and never swallows text`() {
        val text = "开头\n${ParagraphMerger.MARK_LOCAL}\n仅本地内容"

        val resolved = ConflictMarkerResolver.keepLocal(text)

        assertTrue(resolved.contains("开头"))
        assertTrue(resolved.contains("仅本地内容"))
    }

    @Test
    fun `text without markers is returned unchanged`() {
        val plain = "# 标题\n\n正文"

        assertEquals(plain, ConflictMarkerResolver.keepLocal(plain))
        assertEquals(plain, ConflictMarkerResolver.keepRemote(plain))
    }

    @Test
    fun `legacy git style markers are still resolvable`() {
        val legacy = "开头\n<<<<<<< 本地\n本地内容\n=======\n云端内容\n>>>>>>> 云端\n结尾"

        assertTrue(ConflictMarkerResolver.hasMarkers(legacy))
        assertEquals(1, ConflictMarkerResolver.countMarkers(legacy))

        val keptLocal = ConflictMarkerResolver.keepLocal(legacy)
        assertTrue(keptLocal.contains("本地内容"))
        assertFalse(keptLocal.contains("云端内容"))
        assertFalse(keptLocal.contains("<<<<<<<"))

        val keptRemote = ConflictMarkerResolver.keepRemote(legacy)
        assertTrue(keptRemote.contains("云端内容"))
        assertFalse(keptRemote.contains("本地内容"))
    }

    @Test
    fun `merger output is resolvable end to end`() {
        val merged = ParagraphMerger.merge("A\n\nB1", "A\n\nB2", "A\n\nB3")
        assertEquals(1, merged.conflicts)

        val keptLocal = ConflictMarkerResolver.keepLocal(merged.text)
        assertTrue(keptLocal.contains("B2"))
        assertFalse(keptLocal.contains("B3"))
        assertFalse(ConflictMarkerResolver.hasMarkers(keptLocal))
    }
}
