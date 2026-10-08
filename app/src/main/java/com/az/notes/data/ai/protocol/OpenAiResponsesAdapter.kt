package com.az.notes.data.ai.protocol

import com.az.notes.data.ai.SseReader
import com.az.notes.domain.ai.AiError
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
 * OpenAI Responses 适配（§4.1）：
 * - 顶层 instructions=system；input 全量消息（content 字符串便捷形态）；max_output_tokens；stream=true
 * - `response.output_text.delta` → TextDelta；`response.reasoning_summary_text.delta` → ReasoningDelta
 * - `response.completed` → MessageStop；`response.failed` / error 事件 → 透传文案
 * - 图片（B4）/ 工具（B3）时扩展 input item 结构
 */
@Singleton
class OpenAiResponsesAdapter @Inject constructor(
    private val sse: SseReader
) : AiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent> =
        sseEventFlow(sse, buildRequest = { buildRequest(req, apiKey) }, parse = ::parseEvent)

    internal fun buildRequest(req: ChatRequest, apiKey: String): Request {
        val body = buildJsonObject {
            put("model", req.model.id)
            put("stream", true)
            put("max_output_tokens", req.maxOutputTokens)
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
            putJsonArray("input") {
                req.messages.forEach { message ->
                    addJsonObject {
                        put("role", message.role.name.lowercase())
                        put("content", message.text)
                    }
                }
            }
        }
        return Request.Builder()
            .url("${req.provider.baseUrl}/responses")
            .header("Authorization", "Bearer $apiKey")
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
            "response.output_text.delta" -> deltaEvent(root, ::StreamEvent.TextDelta)
            "response.reasoning_summary_text.delta" -> deltaEvent(root, ::StreamEvent.ReasoningDelta)
            "response.completed" -> listOf(StreamEvent.MessageStop)
            "response.failed" -> listOf(StreamEvent.Failure(AiError.Api(failedMessage(root))))
            "error" -> listOf(StreamEvent.Failure(AiError.Api(errorMessage(root))))
            else -> emptyList()
        }
    }

    private fun deltaEvent(root: JsonObject, factory: (String) -> StreamEvent): List<StreamEvent> {
        val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull
        return if (delta.isNullOrEmpty()) emptyList() else listOf(factory(delta))
    }

    /** response.failed → response.error.message。 */
    private fun failedMessage(root: JsonObject): String {
        val response = root["response"] as? JsonObject
        val message = ((response?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)
            ?.contentOrNull
        return message ?: "response failed"
    }

    /** error 事件 → message 或 error.message。 */
    private fun errorMessage(root: JsonObject): String {
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull
            ?: ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        return message ?: "stream error"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
