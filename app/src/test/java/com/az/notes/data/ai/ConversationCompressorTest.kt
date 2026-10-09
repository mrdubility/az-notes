package com.az.notes.data.ai

import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.ToolExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩纯逻辑单测（§6.3）：保护区边界（最后 2 轮 USER）/ 整条消息粒度（工具配对不拆）/
 * 按轮丢弃计数 / 文档 80-10 截断 / 非终态消息原位保留。
 */
class ConversationCompressorTest {

    private fun user(id: String, text: String = "u-$id") = ChatMessage(
        id = id,
        role = ChatRole.USER,
        parts = listOf(ChatPart.Text(text)),
        status = MessageStatus.COMPLETE,
        timestamp = 0L
    )

    private fun assistant(id: String, text: String = "a-$id") = ChatMessage(
        id = id,
        role = ChatRole.ASSISTANT,
        parts = listOf(ChatPart.Text(text)),
        status = MessageStatus.COMPLETE,
        timestamp = 0L
    )

    @Test
    fun `two user turns leave nothing compressible`() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        // 倒数第 2 个 USER = u1（下标 0）→ 保护区覆盖全部
        assertEquals(0, CompressionRules.protectedFrom(messages))
    }

    @Test
    fun `three turns protect the last two and align boundary at user`() {
        val messages = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            user("u3"), assistant("a3")
        )
        val from = CompressionRules.protectedFrom(messages)
        assertEquals(2, from)
        assertEquals(ChatRole.USER, messages[from].role)
        // 压缩区 = u1 + a1（两条）
        assertEquals(2, messages.take(from).count { it.role != ChatRole.SYSTEM })
    }

    @Test
    fun `assistant with tool exchanges moves as a whole round`() {
        val toolAssistant = assistant("a1").copy(
            toolExchanges = listOf(
                ToolExchange(
                    calls = listOf(RawToolCall("c1", "read_note", """{"path":"x.md"}""")),
                    results = listOf("正文")
                )
            )
        )
        val messages = listOf(user("u1"), toolAssistant, user("u2"), assistant("a2"), user("u3"), assistant("a3"))
        val dropped = CompressionRules.dropOldestRound(messages)
        // 丢最旧一轮：u1 与 a1（含其工具往返）整体离开，无悬挂工具消息残留
        assertEquals(listOf("u2", "a2", "u3", "a3"), dropped?.map { it.id })
    }

    @Test
    fun `trim drops oldest rounds one by one and counts`() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"), assistant("a3"))
        val first = CompressionRules.dropOldestRound(messages)
        assertEquals(2, messages.size - (first?.size ?: 0))
        val second = CompressionRules.dropOldestRound(first!!)
        assertEquals(listOf("u3", "a3"), second?.map { it.id })
        // 最后一轮丢弃后为空（无 USER / 无残留）
        assertEquals(emptyList<ChatMessage>(), CompressionRules.dropOldestRound(second!!))
    }

    @Test
    fun `document truncation keeps head mark and tail`() {
        val content = "0123456789".repeat(100) // 1000 字符
        val truncated = CompressionRules.truncateDocument(content)
        assertTrue(truncated.startsWith(content.take(800)))
        assertTrue(truncated.endsWith(content.takeLast(100)))
        assertTrue(truncated.contains("[…已截断…]"))
        assertTrue(truncated.length < content.length)
        // 已截断内容不再二次处理；过短内容原样返回
        assertEquals(truncated, CompressionRules.truncateDocument(truncated))
        val short = "短文档"
        assertEquals(short, CompressionRules.truncateDocument(short))
    }

    @Test
    fun `truncate to budget shrinks documents until within budget`() {
        val document = ChatPart.Document(name = "n", vaultRelPath = "a.md", content = "x".repeat(2000))
        val message = ChatMessage(
            id = "u1",
            role = ChatRole.USER,
            parts = listOf(ChatPart.Text("hi"), document),
            status = MessageStatus.COMPLETE,
            timestamp = 0L
        )
        val before = CompressionRules.estimateTokens(listOf(message))
        val after = CompressionRules.truncateDocumentsToBudget(listOf(message), before - 10)
        assertTrue(CompressionRules.estimateTokens(after) <= before - 10)
        val truncatedDoc = after[0].parts.filterIsInstance<ChatPart.Document>().first()
        assertTrue(truncatedDoc.content.contains("[…已截断…]"))
        // 原消息未被就地修改（截断产出新列表）
        assertFalse(document.content.contains("[…已截断…]"))
    }

    @Test
    fun `merge compressed keeps non terminal messages in place`() {
        val error = assistant("a-err").copy(status = MessageStatus.ERROR)
        val streaming = assistant("a-stream").copy(status = MessageStatus.STREAMING)
        val original = listOf(
            user("u1"), error, user("u2"), assistant("a2"), user("u3"), streaming
        )
        // 压缩结果：u1 区替换为摘要；保护区 u2/a2/u3 保留；streaming 不参与
        val summary = ChatMessage(
            id = "sys",
            role = ChatRole.SYSTEM,
            parts = listOf(ChatPart.Text("摘要")),
            status = MessageStatus.COMPLETE,
            timestamp = 0L
        )
        val compressed = listOf(summary, user("u2"), assistant("a2"), user("u3"))
        val merged = CompressionRules.mergeCompressed(original, compressed)
        // 终态槽位按序装填压缩结果；非终态消息（a-err / a-stream）原位保留
        assertEquals(listOf("sys", "a-err", "u2", "a2", "u3", "a-stream"), merged.map { it.id })
    }
}
