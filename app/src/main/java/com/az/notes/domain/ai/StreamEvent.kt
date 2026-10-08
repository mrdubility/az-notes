package com.az.notes.domain.ai

/** 流式事件（三协议映射到统一抽象，§3.4）。 */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent

    data class ReasoningDelta(val text: String) : StreamEvent

    /** 一轮结束时的完整工具调用（B3 工具循环使用）。 */
    data class ToolCallRequested(val calls: List<RawToolCall>) : StreamEvent

    /** 流正常结束（[DONE] / response.completed / message_stop / 连接自然关闭）。 */
    data object MessageStop : StreamEvent

    data class Failure(val error: AiError) : StreamEvent
}

/** 原始工具调用（adapter 解析产物，B3 引入）。 */
data class RawToolCall(val id: String, val name: String, val argumentsJson: String)
