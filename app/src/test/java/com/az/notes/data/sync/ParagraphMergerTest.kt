package com.az.notes.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段落级三方合并单测（纯逻辑，JVM 直跑）。
 * 覆盖：不同段落各改一处、同一段落改成不同内容、段删除、尾部各插入、无祖先、
 * 代码围栏整体不可切分。
 */
class ParagraphMergerTest {

    @Test
    fun `disjoint edits merge into one clean file`() {
        val base = "A1\n\nB1\n\nC1"
        val local = "A2\n\nB1\n\nC1"
        val remote = "A1\n\nB1\n\nC2"

        val result = ParagraphMerger.merge(base, local, remote)

        assertEquals(0, result.conflicts)
        assertEquals("A2\n\nB1\n\nC2", result.text)
    }

    @Test
    fun `same paragraph changed differently is a conflict`() {
        val base = "A1\n\nB1"
        val local = "A1\n\nB2"
        val remote = "A1\n\nB3"

        val result = ParagraphMerger.merge(base, local, remote)

        assertTrue(result.conflicts > 0)
        // 冲突时退回本地原文，绝不写半合并结果
        assertEquals(local, result.text)
    }

    @Test
    fun `paragraph deleted locally stays deleted when remote untouched`() {
        val base = "A\n\nB\n\nC"
        val local = "A\n\nC"
        val remote = "A\n\nB\n\nC"

        val result = ParagraphMerger.merge(base, local, remote)

        assertEquals(0, result.conflicts)
        assertEquals("A\n\nC", result.text)
    }

    @Test
    fun `different blocks appended on both sides is a conflict`() {
        val base = "A"
        val local = "A\n\nL"
        val remote = "A\n\nR"

        val result = ParagraphMerger.merge(base, local, remote)

        assertTrue(result.conflicts > 0)
    }

    @Test
    fun `identical additions on both sides merge cleanly`() {
        val base = "A"
        val local = "A\n\nN"
        val remote = "A\n\nN"

        val result = ParagraphMerger.merge(base, local, remote)

        assertEquals(0, result.conflicts)
        assertEquals("A\n\nN", result.text)
    }

    @Test
    fun `missing base falls back without merging`() {
        val result = ParagraphMerger.merge(null, "local", "remote")

        assertFalse(result.usedBase)
        assertTrue(result.conflicts > 0)
        assertEquals("local", result.text)
    }

    @Test
    fun `code fence is never split`() {
        val base = "A1\n\n```\nx = 1\ny = 2\n```\n\nB1"
        val local = "A2\n\n```\nx = 1\ny = 2\n```\n\nB1"
        val remote = "A1\n\n```\nx = 1\ny = 2\n```\n\nB2"

        val result = ParagraphMerger.merge(base, local, remote)

        assertEquals(0, result.conflicts)
        assertEquals("A2\n\n```\nx = 1\ny = 2\n```\n\nB2", result.text)
    }

    @Test
    fun `remote deletion wins when local untouched`() {
        val base = "A\n\nB\n\nC"
        val local = "A\n\nB\n\nC"
        val remote = "A\n\nC"

        val result = ParagraphMerger.merge(base, local, remote)

        assertEquals(0, result.conflicts)
        assertEquals("A\n\nC", result.text)
    }

    @Test
    fun `delete versus modify is treated as conflict`() {
        val base = "A\n\nB\n\nC"
        val local = "A\n\nB2\n\nC"
        val remote = "A\n\nC"

        val result = ParagraphMerger.merge(base, local, remote)

        assertTrue(result.conflicts > 0)
    }
}
