package com.az.notes.data.ai.protocol

import com.az.notes.data.ai.SseReader
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.StreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions 适配（§4.1）：
 * - system → messages[0]（role=system）；stream=true
 *   （不发 stream_options：不做 usage 展示，避免老中转站 400）
 * - `delta.content` → TextDelta；`delta.reasoning_content` → ReasoningDelta；data 内 error 字段 → 透传
 * - 工具（B3）：tools 随请求下发；assistant `tool_calls` 与 role=tool 结果按 OpenAI 形态序列化；
 *   `delta.tool_calls` 分片按 index 累积，`finish_reason=="tool_calls"` 或流自然结束（flush 兜底）时成组发出
 * - 流结束：[DONE]（SseReader 拦截）或连接自然关闭 → MessageStop
 */
@Singleton
class OpenAiChatAdapter @Inject constructor(
    private val sse: SseReader
) : AiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent> {
        val tools = OpenAiToolAccumulator()
        return sseEventFlow(
            sse,
            buildRequest = { buildRequest(req, apiKey) },
            parse = { parseChunk(it, tools) },
            // 中转站不发 finish_reason 的兜底：流自然结束时补发已累积的工具调用
            flush = {
                val calls = tools.drain()
                if (calls.isEmpty()) emptyList() else listOf(StreamEvent.ToolCallRequested(calls))
            }
        )
    }

    internal fun buildRequest(req: ChatRequest, apiKey: String): Request {
        val body = buildJsonObject {
            put("model", req.model.id)
            put("stream", true)
            put("max_tokens", req.maxOutputTokens)
            req.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", json.parseToJsonElement(spec.parametersJson))
                            }
                        }
                    }
                }
            }
            putJsonArray("messages") {
                req.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                }
                req.messages.forEach { message -> addMessage(message) }
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

    /** 单条消息 → messages 项：TOOL 结果走 tool_call_id；带图消息 content 数组形态；assistant 带 tool_calls 时附嵌套 function 数组。 */
    private fun JsonArrayBuilder.addMessage(message: TransportMessage) {
        if (message.role == ChatRole.TOOL) {
            addJsonObject {
                put("role", "tool")
                put("tool_call_id", message.toolCallId.orEmpty())
                put("content", message.text)
            }
            return
        }
        addJsonObject {
            put("role", message.role.name.lowercase())
            if (message.images.isEmpty()) {
                put("content", message.text)
            } else {
                // 带图消息：content 数组形态（文本块 + image_url data URI 块）
                putJsonArray("content") {
                    if (message.text.isNotBlank()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    }
                    message.images.forEach { image ->
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${image.mime};base64,${image.base64}")
                            }
                        }
                    }
                }
            }
            if (message.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * payload → 0..N 个事件；非 JSON / 无关结构跳过（安全退化）。
     * [tools] 非空时累积 `delta.tool_calls` 分片，`finish_reason=="tool_calls"` 时成组发出。
     */
    internal fun parseChunk(payload: String, tools: OpenAiToolAccumulator? = null): List<StreamEvent> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyList()
        (root["error"] as? JsonObject)?.let { err ->
            val message = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "stream error"
            return listOf(StreamEvent.Failure(AiError.Api(message)))
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        (choice["delta"] as? JsonObject)?.let { delta ->
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += StreamEvent.ReasoningDelta(it) }
            (delta["content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += StreamEvent.TextDelta(it) }
            (delta["tool_calls"] as? JsonArray)?.let { tools?.accept(it) }
        }
        if ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull == "tool_calls") {
            val calls = tools?.drain().orEmpty()
            if (calls.isNotEmpty()) events += StreamEvent.ToolCallRequested(calls)
        }
        return events
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * OpenAI SSE 工具调用分片累积器：`delta.tool_calls` 按 index 下发，
 * 首片带 id / name，后续片只带 arguments 增量；`finish_reason=="tool_calls"` 或流自然结束时 drain。
 */
internal class OpenAiToolAccumulator {
    private class Pending(val id: String?, val name: String?, val args: StringBuilder)

    private val pending = sortedMapOf<Int, Pending>()

    /** delta.tool_calls 数组：index 缺省时按数组下标（容错部分中转站）。 */
    fun accept(chunks: JsonArray) {
        chunks.forEachIndexed { fallback, element ->
            val chunk = element as? JsonObject ?: return@forEachIndexed
            val index = (chunk["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: fallback
            val function = chunk["function"] as? JsonObject
            val existing = pending[index]
            if (existing == null) {
                pending[index] = Pending(
                    id = (chunk["id"] as? JsonPrimitive)?.contentOrNull,
                    name = (function?.get("name") as? JsonPrimitive)?.contentOrNull,
                    args = StringBuilder(
                        (function?.get("arguments") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    )
                )
            } else {
                (function?.get("arguments") as? JsonPrimitive)?.contentOrNull
                    ?.let { existing.args.append(it) }
            }
        }
    }

    /** 清空并定型：id 缺省补 `call_<index>`，参数缺省补空对象 `{}`。 */
    fun drain(): List<RawToolCall> {
        if (pending.isEmpty()) return emptyList()
        val calls = pending.map { (index, item) ->
            RawToolCall(
                id = item.id ?: "call_$index",
                name = item.name.orEmpty(),
                argumentsJson = item.args.toString().ifBlank { "{}" }
            )
        }
        pending.clear()
        return calls
    }
}
