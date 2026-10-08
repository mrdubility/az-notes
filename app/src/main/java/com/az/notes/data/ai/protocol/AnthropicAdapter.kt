package com.az.notes.data.ai.protocol

import com.az.notes.data.ai.SseReader
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.StreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages 适配（§4.1）：
 * - x-api-key + anthropic-version: 2023-06-01（对齐 AiConnectionTester 先例）；system 为顶层字段
 * - messages 仅 user / assistant，content 为标准 text 块数组；max_tokens 必填（默认 4096）
 * - text_delta → TextDelta；thinking_delta → ReasoningDelta；message_stop → MessageStop
 * - error 事件 → 透传服务商文案
 */
@Singleton
class AnthropicAdapter @Inject constructor(
    private val sse: SseReader
) : AiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent> =
        sseEventFlow(sse, buildRequest = { buildRequest(req, apiKey) }, parse = ::parseEvent)

    internal fun buildRequest(req: ChatRequest, apiKey: String): Request {
        val body = buildJsonObject {
            put("model", req.model.id)
            put("stream", true)
            put("max_tokens", req.maxOutputTokens)
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            putJsonArray("messages") {
                req.messages.forEach { message ->
                    addJsonObject {
                        put("role", if (message.role == ChatRole.ASSISTANT) "assistant" else "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            }
                        }
                    }
                }
            }
        }
        return Request.Builder()
            .url("${req.provider.baseUrl}/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .apply { req.provider.headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** payload → 0..N 个事件；非 JSON / 无关事件类型跳过（安全退化）。 */
    internal fun parseEvent(payload: String): List<StreamEvent> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyList()
        return when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "content_block_delta" -> parseContentBlockDelta(root)
            "message_stop" -> listOf(StreamEvent.MessageStop)
            "error" -> {
                val message = ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)
                    ?.contentOrNull
                listOf(StreamEvent.Failure(AiError.Api(message ?: "stream error")))
            }
            else -> emptyList()
        }
    }

    private fun parseContentBlockDelta(root: JsonObject): List<StreamEvent> {
        val delta = root["delta"] as? JsonObject ?: return emptyList()
        return when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
            "text_delta" -> {
                val text = (delta["text"] as? JsonPrimitive)?.contentOrNull
                if (text.isNullOrEmpty()) emptyList() else listOf(StreamEvent.TextDelta(text))
            }
            "thinking_delta" -> {
                val thinking = (delta["thinking"] as? JsonPrimitive)?.contentOrNull
                if (thinking.isNullOrEmpty()) emptyList()
                else listOf(StreamEvent.ReasoningDelta(thinking))
            }
            else -> emptyList()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
