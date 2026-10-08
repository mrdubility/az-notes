package com.az.notes.data.ai.protocol

import com.az.notes.data.ai.SseReader
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.StreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * OpenAI Chat Completions 适配（§4.1）：
 * - system → messages[0]（role=system）；stream=true
 *   （不发 stream_options：不做 usage 展示，避免老中转站 400）
 * - `delta.content` → TextDelta；`delta.reasoning_content` → ReasoningDelta；data 内 error 字段 → 透传
 * - 流结束：[DONE]（SseReader 拦截）或连接自然关闭 → MessageStop
 */
@Singleton
class OpenAiChatAdapter @Inject constructor(
    private val sse: SseReader
) : AiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent> =
        sseEventFlow(sse, buildRequest = { buildRequest(req, apiKey) }, parse = ::parseChunk)

    internal fun buildRequest(req: ChatRequest, apiKey: String): Request {
        val body = buildJsonObject {
            put("model", req.model.id)
            put("stream", true)
            put("max_tokens", req.maxOutputTokens)
            putJsonArray("messages") {
                req.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                }
                req.messages.forEach { message ->
                    addJsonObject {
                        put("role", message.role.name.lowercase())
                        put("content", message.text)
                    }
                }
            }
        }
        return Request.Builder()
            .url("${req.provider.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .apply { req.provider.headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** payload → 0..N 个事件；非 JSON / 无关结构跳过（安全退化）。 */
    internal fun parseChunk(payload: String): List<StreamEvent> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyList()
        (root["error"] as? JsonObject)?.let { err ->
            val message = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "stream error"
            return listOf(StreamEvent.Failure(AiError.Api(message)))
        }
        val delta = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("delta") as? JsonObject
            ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { events += StreamEvent.ReasoningDelta(it) }
        (delta["content"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { events += StreamEvent.TextDelta(it) }
        return events
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
