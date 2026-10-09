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
 * - 工具（B3）：tools 扁平 function 定义；assistant 的调用回填为 `function_call` item、role=tool
 *   结果回填为 `function_call_output`（call_id 关联）；`output_item.added` / 参数分片 / `output_item.done`
 *   累积，`response.completed` 时成组发出（否则 → MessageStop）
 * - `response.failed` / error 事件 → 透传文案
 * - 图片（B4）时扩展 input item 结构
 */
@Singleton
class OpenAiResponsesAdapter @Inject constructor(
    private val sse: SseReader
) : AiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent> {
        val tools = ResponsesToolAccumulator()
        return sseEventFlow(
            sse,
            buildRequest = { buildRequest(req, apiKey) },
            parse = { parseEvent(it, tools) },
            // response.completed 缺失（连接自然关闭）的兜底
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
            put("max_output_tokens", req.maxOutputTokens)
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
            req.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("type", "function")
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", json.parseToJsonElement(spec.parametersJson))
                        }
                    }
                }
            }
            putJsonArray("input") {
                req.messages.forEach { message ->
                    if (message.role == ChatRole.TOOL) {
                        // 工具结果：function_call_output（call_id 关联回填）
                        addJsonObject {
                            put("type", "function_call_output")
                            put("call_id", message.toolCallId.orEmpty())
                            put("output", message.text)
                        }
                        return@forEach
                    }
                    if (message.text.isNotBlank()) {
                        addJsonObject {
                            put("role", message.role.name.lowercase())
                            put("content", message.text)
                        }
                    }
                    message.toolCalls.forEach { call ->
                        // assistant 的工具调用：function_call（call_id 关联回填）
                        addJsonObject {
                            put("type", "function_call")
                            put("call_id", call.id)
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        }
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

    /**
     * payload → 0..N 个事件；非 JSON / 无关事件类型跳过（安全退化）。
     * [tools] 非空时累积 function_call（added / 参数分片 / done），response.completed 时成组发出。
     */
    internal fun parseEvent(payload: String, tools: ResponsesToolAccumulator? = null): List<StreamEvent> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyList()
        return when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "response.output_text.delta" -> deltaEvent(root) { StreamEvent.TextDelta(it) }
            "response.reasoning_summary_text.delta" -> deltaEvent(root) { StreamEvent.ReasoningDelta(it) }
            "response.output_item.added" -> {
                (root["item"] as? JsonObject)?.let { tools?.added(it) }
                emptyList()
            }
            "response.function_call_arguments.delta" -> {
                val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull
                if (!delta.isNullOrEmpty()) {
                    tools?.argumentsDelta((root["item_id"] as? JsonPrimitive)?.contentOrNull, delta)
                }
                emptyList()
            }
            "response.output_item.done" -> {
                (root["item"] as? JsonObject)?.let { tools?.done(it) }
                emptyList()
            }
            "response.completed" -> {
                val calls = tools?.drain().orEmpty()
                if (calls.isEmpty()) listOf(StreamEvent.MessageStop)
                else listOf(StreamEvent.ToolCallRequested(calls))
            }
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

/**
 * Responses SSE 工具调用累积器：`output_item.added` 建档 → `function_call_arguments.delta`
 * 分片累积 → `output_item.done` 定型；response.completed 时 drain。
 */
internal class ResponsesToolAccumulator {
    private class Pending(val callId: String, val name: String, val args: StringBuilder)

    private val active = linkedMapOf<String, Pending>()
    private val completed = mutableListOf<RawToolCall>()

    /** output_item.added：type=function_call 的 item 建档（key = item id）。 */
    fun added(item: JsonObject) {
        if ((item["type"] as? JsonPrimitive)?.contentOrNull != "function_call") return
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return
        active[id] = Pending(
            callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: id,
            name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            args = StringBuilder((item["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        )
    }

    /** function_call_arguments.delta：按 item id 追加参数分片。 */
    fun argumentsDelta(itemId: String?, delta: String) {
        val key = itemId ?: return
        active[key]?.args?.append(delta)
    }

    /** output_item.done：以 item 完整参数定型（服务器权威，覆盖分片累积结果）。 */
    fun done(item: JsonObject) {
        if ((item["type"] as? JsonPrimitive)?.contentOrNull != "function_call") return
        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
        val pending = itemId?.let { active.remove(it) }
        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
            ?: pending?.callId
            ?: itemId.orEmpty()
        val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: pending?.name.orEmpty()
        val fullArgs = (item["arguments"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: pending?.args?.toString()?.ifBlank { "{}" }
            ?: "{}"
        completed += RawToolCall(callId, name, fullArgs)
    }

    /** response.completed / 流结束：清空并返回本轮全部工具调用（done 缺失的残留项兜底定型）。 */
    fun drain(): List<RawToolCall> {
        active.values.forEach { pending ->
            completed += RawToolCall(pending.callId, pending.name, pending.args.toString().ifBlank { "{}" })
        }
        active.clear()
        if (completed.isEmpty()) return emptyList()
        return completed.toList().also { completed.clear() }
    }
}
