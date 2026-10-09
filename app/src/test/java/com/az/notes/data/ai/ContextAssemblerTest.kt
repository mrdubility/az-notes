package com.az.notes.data.ai

import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户消息上下文组装单测（B3）：无文档原样输出、Text+Document 渲染格式、
 * 多文档空行分隔、仅文档无文本、超 32K 截断标注。
 */
class ContextAssemblerTest {

    private fun message(text: String, documents: List<ChatPart.Document> = emptyList()) =
        ChatMessage(
            id = "u1",
            role = ChatRole.USER,
            parts = listOf(ChatPart.Text(text)) + documents,
            status = MessageStatus.COMPLETE,
            timestamp = 0L
        )

    private fun document(name: String, relPath: String, content: String) =
        ChatPart.Document(name = name, vaultRelPath = relPath, content = content)

    @Test
    fun `plain text without documents passes through unchanged`() {
        assertEquals("你好", ContextAssembler.renderUserText(message("你好")))
    }

    @Test
    fun `text and document render with document tag`() {
        val rendered = ContextAssembler.renderUserText(
            message("看看这个", listOf(document("a.md", "folder/a.md", "正文A")))
        )
        assertEquals(
            "看看这个\n\n<document path=\"folder/a.md\">\n正文A\n</document>",
            rendered
        )
    }

    @Test
    fun `documents only render without leading blank lines`() {
        val rendered = ContextAssembler.renderUserText(
            message("", listOf(document("a.md", "a.md", "A")))
        )
        assertEquals("<document path=\"a.md\">\nA\n</document>", rendered)
    }

    @Test
    fun `multiple documents are separated by blank lines`() {
        val rendered = ContextAssembler.renderUserText(
            message(
                "q",
                listOf(
                    document("a.md", "a.md", "A"),
                    document("b.md", "sub/b.md", "B")
                )
            )
        )
        assertEquals(
            "q\n\n<document path=\"a.md\">\nA\n</document>\n\n" +
                "<document path=\"sub/b.md\">\nB\n</document>",
            rendered
        )
    }

    @Test
    fun `oversized document is clipped at limit with marker`() {
        val content = "x".repeat(ContextAssembler.MAX_DOCUMENT_CHARS + 100)
        val rendered = ContextAssembler.renderUserText(
            message("", listOf(document("big.md", "big.md", content)))
        )
        assertEquals(
            "<document path=\"big.md\">\n" +
                "x".repeat(ContextAssembler.MAX_DOCUMENT_CHARS) +
                "\n\n[…已截断…]\n</document>",
            rendered
        )
    }

    @Test
    fun `document at exactly the limit is not clipped`() {
        val content = "y".repeat(ContextAssembler.MAX_DOCUMENT_CHARS)
        val rendered = ContextAssembler.renderUserText(
            message("", listOf(document("edge.md", "edge.md", content)))
        )
        assertTrue(!rendered.contains("[…已截断…]"))
        assertEquals(
            "<document path=\"edge.md\">\n" +
                "y".repeat(ContextAssembler.MAX_DOCUMENT_CHARS) +
                "\n</document>",
            rendered
        )
    }
}
