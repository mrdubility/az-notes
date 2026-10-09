package com.az.notes.data.ai

import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart

/**
 * 用户消息上下文组装（B3）：文本块拼接 + 附加文档渲染为 `<document>` 文本块。
 * 单文档超过 [MAX_DOCUMENT_CHARS] 时截断并附标注，提示模型可继续追问或改用 read_note 分段读取。
 */
object ContextAssembler {

    /** 单文档渲染上限（字符）；与 read_note 单次返回上限一致（32768）。 */
    const val MAX_DOCUMENT_CHARS = 32_768

    /** 用户消息 → 发送文本：Text 拼接 + Document 依次渲染（文档间空行分隔）。 */
    fun renderUserText(message: ChatMessage): String {
        val text = message.text
        val documents = message.parts.filterIsInstance<ChatPart.Document>()
        if (documents.isEmpty()) return text
        val rendered = documents.joinToString("\n\n") { renderDocument(it) }
        return if (text.isBlank()) rendered else "$text\n\n$rendered"
    }

    /** 单文档：`<document path="相对路径">正文</document>`；超限截断并附标注。 */
    private fun renderDocument(document: ChatPart.Document): String {
        val content = if (document.content.length > MAX_DOCUMENT_CHARS) {
            document.content.take(MAX_DOCUMENT_CHARS) + "\n\n[…已截断…]"
        } else {
            document.content
        }
        return "<document path=\"${document.vaultRelPath}\">\n$content\n</document>"
    }
}
