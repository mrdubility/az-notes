package com.az.notes.data.ai

import com.az.notes.domain.ai.ChatMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 内存会话（@Singleton，§1.2 不持久化）：
 * 进程内退出对话页不丢；杀进程 / 系统回收即丢（设计使然，导出留 B4）。
 * B3 将在此扩展待发附件（pendingAttachments）。
 */
@Singleton
class AiChatSession @Inject constructor() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun append(message: ChatMessage) {
        _messages.update { it + message }
    }

    /** 就地变换指定消息（流式增量 / 状态流转共用）。 */
    fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 移除指定消息（停止生成时的空内容消息清理）。 */
    fun removeMessage(id: String) {
        _messages.update { list -> list.filterNot { it.id == id } }
    }

    /** 截断：删除 [id] 及其之后的全部消息（重新生成 / 编辑重发）。 */
    fun truncateFrom(id: String) {
        _messages.update { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) list else list.take(index)
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }
}
