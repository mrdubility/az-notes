package com.az.notes.domain.ai

/** 消息角色：SYSTEM 用于压缩摘要 / 系统条目（B4 引入）；TOOL 为工具结果（仅传输态使用）。 */
enum class ChatRole { USER, ASSISTANT, SYSTEM, TOOL }

/** 消息状态：STREAMING 生成中；CANCELED 已停止（内容保留）；ERROR 失败（不参与历史重组）。 */
enum class MessageStatus { STREAMING, COMPLETE, CANCELED, ERROR }

/** 消息内容块（Image 供 B4；Document 为加入对话的仓库文档，B3 引入）。 */
sealed interface ChatPart {
    data class Text(val text: String) : ChatPart

    /** 手动附加的图片：仅存本地临时路径，发送时转 base64。 */
    data class Image(val localPath: String, val mime: String, val name: String) : ChatPart

    /** 加入对话的仓库文档：发送时渲染为 <document> 文本块。 */
    data class Document(val name: String, val vaultRelPath: String, val content: String) : ChatPart
}

/** 会话消息（@Singleton 内存态，不持久化；杀进程即丢，设计使然）。 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val parts: List<ChatPart>,
    /** 思考过程（被动收集）：仅本地展示——不回传、不导出、不参与压缩摘要。 */
    val reasoning: String? = null,
    val status: MessageStatus,
    val timestamp: Long,
    /** 生成该条 AI 消息时的「供应商 · 模型」（导出用）。 */
    val modelLabel: String? = null,
    /** 本轮工具调用轨迹（B3 引入；UI 展示 + 导出附注，仅摘要）。 */
    val toolTrail: List<ToolCallRecord> = emptyList(),
    /** 工具往返传输态（历史重组回填工具消息用；UI/导出仅用 toolTrail 摘要）。 */
    val toolExchanges: List<ToolExchange> = emptyList()
) {
    /** 拼接全部文本块：流式展示 / 复制 / 历史重组共用。 */
    val text: String get() = parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }
}

/** 工具调用轨迹（展示用；真实协议回传走 adapter 内部传输态）。 */
data class ToolCallRecord(val tool: String, val argsSummary: String, val resultSummary: String)

/** 工具往返传输态：一轮调用的完整参数与结果（供历史重组；results 与 calls 按下标对齐）。 */
data class ToolExchange(val calls: List<RawToolCall>, val results: List<String>)
