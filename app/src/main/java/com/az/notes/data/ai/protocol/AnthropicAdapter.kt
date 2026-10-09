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
import kotlinx.serialization.json.JsonElement
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
 * Anthropic Messages 适配（§4.1）：
 * - x-api-key + anthropic-version: 2023-06-01（对齐 AiConnectionTester 先例）；system 为顶层字段
 * - messages 仅 user / assistant，content 为标准块数组；相邻同角色合并为单条（工具往返后
 *   assistant(tool_use) 与 user(tool_result) 严格交替回填）；max_tokens 必填（默认 4096）
 * - 工具（B3）：tools 以 input_schema 定义；`content_block_start`(tool_use) / `input_json_delta`
 *   分片 / `content_block_stop` 定型，message_stop 时成组发出
 * - text_delta → TextDelta；thinking_delta → ReasoningDelta；message_stop → MessageStop
 * - error 事件 → 透传服务商文案
 */
@Singleton
class AnthropicAdapter @Inject constructor(
    private val sse: SseReader
) : AiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent> {
        val tools = AnthropicToolAccumulator()
        return sseEventFlow(
            sse,
            buildRequest = { buildRequest(req, apiKey) },
            parse = { parseEvent(it, tools) },
            // message_stop 缺失（连接自然关闭）的兜底
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
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            req.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                putJsonArray("tools") {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("input_schema", json.parseToJsonElement(spec.parametersJson))
                        }
                    }
                }
            }
            putJsonArray("messages") {
                mergedMessages(req.messages).forEach { (role, blocks) ->
                    addJsonObject {
                        put("role", role)
                        putJsonArray("content") { blocks.forEach { block -> add(block) } }
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

    /**
     * Anthropic messages 构造：每条消息转 content 块后相邻同角色合并为单条。
     * TOOL 结果包 user 角色（`tool_result` 块必须紧随对应 `tool_use`）；
     * assistant 的文本与 `tool_use` 块共存于同一消息（文本在前）；
     * 附加图片渲染为 `image` 块（置于文本块之后）。
     */
    private fun mergedMessages(messages: List<TransportMessage>): List<Pair<String, List<JsonElement>>> {
        val merged = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        messages.forEach { message ->
            val role = if (message.role == ChatRole.ASSISTANT) "assistant" else "user"
            val blocks = mutableListOf<JsonElement>()
            if (message.role == ChatRole.TOOL) {
                blocks += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", message.toolCallId.orEmpty())
                    put("content", message.text)
                }
            } else {
                if (message.text.isNotBlank()) {
                    blocks += buildJsonObject {
                        put("type", "text")
                        put("text", message.text)
                    }
                }
                message.images.forEach { image ->
                    blocks += buildJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", image.mime)
                            put("data", image.base64)
                        }
                    }
                }
                message.toolCalls.forEach { call ->
                    blocks += buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", toolInput(call.argumentsJson))
                    }
                }
            }
            if (blocks.isEmpty()) return@forEach
            val sameRole = merged.lastOrNull()?.takeIf { it.first == role }?.second
            if (sameRole != null) sameRole += blocks else merged += role to blocks
        }
        return merged
    }

    /** 工具参数（字符串）→ input 对象；非法 JSON 退化空对象（与执行侧容错一致，不打断请求）。 */
    private fun toolInput(argumentsJson: String): JsonElement =
        runCatching { json.parseToJsonElement(argumentsJson) }.getOrElse { buildJsonObject { } }

    /**
     * payload → 0..N 个事件；非 JSON / 无关事件类型跳过（安全退化）。
     * [tools] 非空时累积 tool_use 块（start / input_json_delta / stop），message_stop 时成组发出。
     */
    internal fun parseEvent(payload: String, tools: AnthropicToolAccumulator? = null): List<StreamEvent> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyList()
        return when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "content_block_start" -> {
                tools?.start(root.blockIndex(), root["content_block"] as? JsonObject)
                emptyList()
            }
            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                val deltaType = (delta?.get("type") as? JsonPrimitive)?.contentOrNull
                if (delta != null && deltaType == "input_json_delta") {
                    tools?.acceptDelta(root.blockIndex(), delta)
                    emptyList()
                } else {
                    parseContentBlockDelta(root)
                }
            }
            "content_block_stop" -> {
                tools?.stop(root.blockIndex())
                emptyList()
            }
            "message_stop" -> {
                val calls = tools?.drain().orEmpty()
                if (calls.isEmpty()) listOf(StreamEvent.MessageStop)
                else listOf(StreamEvent.ToolCallRequested(calls))
            }
            "error" -> {
                val message = ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)
                    ?.contentOrNull
                listOf(StreamEvent.Failure(AiError.Api(message ?: "stream error")))
            }
            else -> emptyList()
        }
    }

    /** content block 序号（工具累积器 key；缺省 0）。 */
    private fun JsonObject.blockIndex(): Int =
        (this["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

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

/**
 * Anthropic SSE 工具调用累积器：`content_block_start`(tool_use) 建档 →
 * `input_json_delta` 分片累积 → `content_block_stop` 定型；message_stop 时 drain。
 */
internal class AnthropicToolAccumulator {
    private class Pending(val id: String, val name: String, val args: StringBuilder)

    private val active = mutableMapOf<Int, Pending>()
    private val completed = mutableListOf<RawToolCall>()

    /** content_block_start：type=tool_use 的块建档（id 缺省按序号生成）。 */
    fun start(index: Int, block: JsonObject?) {
        if (block == null) return
        if ((block["type"] as? JsonPrimitive)?.contentOrNull != "tool_use") return
        active[index] = Pending(
            id = (block["id"] as? JsonPrimitive)?.contentOrNull ?: "tool_$index",
            name = (block["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            args = StringBuilder()
        )
    }

    /** content_block_delta：input_json_delta 的参数分片追加。 */
    fun acceptDelta(index: Int, delta: JsonObject) {
        if ((delta["type"] as? JsonPrimitive)?.contentOrNull != "input_json_delta") return
        val partial = (delta["partial_json"] as? JsonPrimitive)?.contentOrNull ?: return
        active[index]?.args?.append(partial)
    }

    /** content_block_stop：该块定型入队（保序）。 */
    fun stop(index: Int) {
        val block = active.remove(index) ?: return
        completed += RawToolCall(block.id, block.name, block.args.toString().ifBlank { "{}" })
    }

    /** message_stop / 流结束：清空并返回本轮全部工具调用（stop 缺失的异常流按序号补定型）。 */
    fun drain(): List<RawToolCall> {
        active.toSortedMap().values.forEach { block ->
            completed += RawToolCall(block.id, block.name, block.args.toString().ifBlank { "{}" })
        }
        active.clear()
        if (completed.isEmpty()) return emptyList()
        return completed.toList().also { completed.clear() }
    }
}
