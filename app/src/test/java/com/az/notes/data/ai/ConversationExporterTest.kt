package com.az.notes.data.ai

import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.SystemNote
import com.az.notes.domain.ai.SystemNoteMode
import com.az.notes.domain.ai.ToolCallRecord
import com.az.notes.domain.ai.ToolSpecs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出纯拼装单测（§8.2）：frontmatter / 分节标题格式 / reasoning 与工具轨迹永不导出 /
 * 文档引用行 / SYSTEM 条目 / 图片链接与占位降级 / 勾选子集保持会话顺序 / 空分节跳过。
 */
class ConversationExporterTest {

    private val baseTs = 1_700_000_000_000L

    private fun user(
        id: String,
        text: String = "u-$id",
        extraParts: List<ChatPart> = emptyList()
    ) = ChatMessage(
        id = id,
        role = ChatRole.USER,
        parts = listOf(ChatPart.Text(text)) + extraParts,
        status = MessageStatus.COMPLETE,
        timestamp = baseTs
    )

    private fun assistant(
        id: String,
        text: String = "a-$id",
        status: MessageStatus = MessageStatus.COMPLETE,
        modelLabel: String? = null,
        reasoning: String? = null,
        toolTrail: List<ToolCallRecord> = emptyList()
    ) = ChatMessage(
        id = id,
        role = ChatRole.ASSISTANT,
        parts = listOf(ChatPart.Text(text)),
        reasoning = reasoning,
        status = status,
        timestamp = baseTs,
        modelLabel = modelLabel,
        toolTrail = toolTrail
    )

    private fun systemNote(id: String, mode: SystemNoteMode, count: Int, text: String = "") = ChatMessage(
        id = id,
        role = ChatRole.SYSTEM,
        parts = if (text.isEmpty()) emptyList() else listOf(ChatPart.Text(text)),
        status = MessageStatus.COMPLETE,
        timestamp = baseTs,
        systemNote = SystemNote(mode, count)
    )

    private fun hm(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    private fun dateTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    @Test
    fun `frontmatter carries title date and first labelled model`() {
        val messages = listOf(
            user("u1"),
            assistant("a1", modelLabel = "DeepSeek · deepseek-chat"),
            assistant("a2", modelLabel = "其他厂商 · m")
        )
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertTrue(
            md.startsWith(
                "---\ntitle: AI 对话\ndate: ${dateTime(baseTs)}\n" +
                    "model: DeepSeek · deepseek-chat\n---\n\n"
            )
        )
    }

    @Test
    fun `section headers use role badge and HH mm`() {
        val messages = listOf(user("u1"), assistant("a1"))
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertTrue(md.contains("## 用户 · ${hm(baseTs)}\n\n"))
        assertTrue(md.contains("## AI · ${hm(baseTs)}\n\n"))
    }

    @Test
    fun `reasoning is never exported`() {
        val messages = listOf(user("u1"), assistant("a1", reasoning = "内心推理过程"))
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertFalse(md.contains("内心推理过程"))
    }

    @Test
    fun `tool trail is never exported`() {
        val trail = listOf(
            ToolCallRecord(ToolSpecs.READ_NOTE, "a.md", ""),
            ToolCallRecord(ToolSpecs.SEARCH_NOTES, "q", "")
        )
        val messages = listOf(user("u1"), assistant("a1", text = "正文", toolTrail = trail))
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertTrue(md.contains("正文"))
        assertFalse(md.contains("已读取"))
        assertFalse(md.contains("工具调用"))
    }

    @Test
    fun `document part exports as reference quote without full content`() {
        val doc = ChatPart.Document(name = "n.md", vaultRelPath = "folder/n.md", content = "文档全文内容")
        val messages = listOf(user("u1", extraParts = listOf(doc)))
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertTrue(md.contains("> 引用文档：folder/n.md"))
        assertFalse(md.contains("文档全文内容"))
    }

    @Test
    fun `system notes export as quote lines`() {
        val messages = listOf(
            systemNote("s1", SystemNoteMode.SUMMARIZED, 6, text = "摘要正文"),
            systemNote("s2", SystemNoteMode.TRIMMED, 4)
        )
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertTrue(md.contains("> 已压缩 6 条早期消息"))
        assertTrue(md.contains("> 已丢弃 4 条早期消息"))
        // 摘要正文不导出（仅条目元信息）
        assertFalse(md.contains("摘要正文"))
    }

    @Test
    fun `image parts export link or placeholder in order`() {
        val doc = ChatPart.Document(name = "n.md", vaultRelPath = "folder/n.md", content = "文")
        val linked = ChatPart.Image(localPath = "/cache/a.jpg", mime = "image/jpeg", name = "a.jpg")
        val failed = ChatPart.Image(localPath = "/cache/b.png", mime = "image/png", name = "b.png")
        val messages = listOf(user("u1", text = "看图", extraParts = listOf(doc, linked, failed)))
        val md = buildMarkdown(messages, timestamp = baseTs) { image ->
            if (image.name == "a.jpg") "assets/ai-12345678.jpg" else null
        }
        // 顺序：文档引用 → 正文 → 图片（链接成功在前、失败占位在后）
        assertTrue(
            md.contains(
                "> 引用文档：folder/n.md\n\n看图\n\n" +
                    "![图片](assets/ai-12345678.jpg)\n\n[图片: b.png]"
            )
        )
    }

    @Test
    fun `empty sections are skipped`() {
        val emptyAssistant = assistant("a-err", text = "", status = MessageStatus.ERROR, reasoning = "仅有思考")
        val messages = listOf(user("u1"), emptyAssistant)
        val md = buildMarkdown(messages, timestamp = baseTs) { null }
        assertFalse(md.contains("## AI"))
        assertFalse(md.contains("仅有思考"))
    }

    @Test
    fun `selection keeps conversation order and drops the rest`() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        val selected = selectForExport(messages, setOf("a2", "u1"))
        assertEquals(listOf("u1", "a2"), selected.map { it.id })
    }
}
