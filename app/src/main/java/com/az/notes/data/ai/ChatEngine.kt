package com.az.notes.data.ai

import com.az.notes.data.ai.protocol.AiProtocolAdapter
import com.az.notes.data.ai.protocol.AnthropicAdapter
import com.az.notes.data.ai.protocol.ChatRequest
import com.az.notes.data.ai.protocol.OpenAiChatAdapter
import com.az.notes.data.ai.protocol.OpenAiResponsesAdapter
import com.az.notes.data.ai.protocol.TransportMessage
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.PromptTemplates
import com.az.notes.domain.ai.StreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * 对话编排（B2 单轮流式版）：读 Key → 按协议选 adapter → 返回统一事件流。
 * - 历史组装：仅终态消息（COMPLETE / CANCELED）参与；ERROR / STREAMING 排除、空文本跳过
 * - 不发送采样参数（用户已确认用服务端默认）；§4.2 的「400 去参重试」随采样参数一并
 *   留作未来引入时的挂点（当前无参数可去，无触发路径）
 * - 压缩检查（B4）与工具循环（B3）将在此扩展
 */
@Singleton
class ChatEngine @Inject constructor(
    private val keyStore: AiKeyStore,
    private val openAiChat: OpenAiChatAdapter,
    private val openAiResponses: OpenAiResponsesAdapter,
    private val anthropic: AnthropicAdapter
) {

    /** 发起一次流式生成（[history] 为会话消息快照；输出统一 [StreamEvent] 流）。 */
    fun stream(provider: AiProvider, model: AiModel, history: List<ChatMessage>): Flow<StreamEvent> =
        flow {
            val apiKey = keyStore.getKey(provider.id)
            if (apiKey.isEmpty()) {
                emit(StreamEvent.Failure(AiError.Unauthorized))
                return@flow
            }
            val messages = history
                .filter {
                    it.status == MessageStatus.COMPLETE || it.status == MessageStatus.CANCELED
                }
                .filter { it.text.isNotBlank() }
                .map { TransportMessage(role = it.role, text = it.text) }
            if (messages.isEmpty()) {
                emit(StreamEvent.Failure(AiError.Unknown))
                return@flow
            }
            val adapter: AiProtocolAdapter = when (provider.protocol) {
                AiProtocol.OPENAI_CHAT -> openAiChat
                AiProtocol.OPENAI_RESPONSES -> openAiResponses
                AiProtocol.ANTHROPIC_MESSAGES -> anthropic
            }
            val request = ChatRequest(
                provider = provider,
                model = model,
                systemPrompt = PromptTemplates.CHAT_SYSTEM,
                messages = messages,
                maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS
            )
            adapter.stream(request, apiKey)
                .transformWhile { event ->
                    emit(event)
                    // 终态事件（结束 / 失败）后主动停流：立即断开 socket，
                    // 不依赖服务端主动关连接（部分服务商发完 message_stop 后不关）
                    event !is StreamEvent.MessageStop && event !is StreamEvent.Failure
                }
                .collect { emit(it) }
        }

    private companion object {
        /** 输出上限（§4.1：Anthropic max_tokens 必填，默认 4096）。 */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
    }
}
