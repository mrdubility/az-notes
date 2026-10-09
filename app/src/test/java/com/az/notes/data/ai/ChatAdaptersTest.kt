package com.az.notes.data.ai

import com.az.notes.data.ai.protocol.AnthropicAdapter
import com.az.notes.data.ai.protocol.AnthropicToolAccumulator
import com.az.notes.data.ai.protocol.ChatRequest
import com.az.notes.data.ai.protocol.OpenAiChatAdapter
import com.az.notes.data.ai.protocol.OpenAiResponsesAdapter
import com.az.notes.data.ai.protocol.OpenAiToolAccumulator
import com.az.notes.data.ai.protocol.ResponsesToolAccumulator
import com.az.notes.data.ai.protocol.TransportImage
import com.az.notes.data.ai.protocol.TransportMessage
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.StreamEvent
import com.az.notes.domain.ai.ToolSpecs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 三协议 Adapter 单测（§12.2）：
 * - 请求体 JSON 断言：system 位置（OpenAI messages[0] / Responses instructions / Anthropic 顶层）、
 *   stream=true、Anthropic max_tokens 必填与专属请求头
 * - 事件映射纯函数断言：text / reasoning / 结束 / error 透传、非 JSON 安全跳过
 * - 工具（B3）：tools 定义序列化、assistant tool_calls / tool 结果消息序列化（Anthropic 角色合并）、
 *   分片累积器 → ToolCallRequested 流事件解析
 * - 图片：三协议含图消息序列化（OpenAI content 数组 + data URI、Anthropic image 块、Responses input_image）
 */
class ChatAdaptersTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val openAiChat = OpenAiChatAdapter(SseReader(AiHttpClient()))
    private val openAiResponses = OpenAiResponsesAdapter(SseReader(AiHttpClient()))
    private val anthropic = AnthropicAdapter(SseReader(AiHttpClient()))

    private fun provider(protocol: AiProtocol) = AiProvider(
        id = "p1",
        name = "Test",
        protocol = protocol,
        baseUrl = "https://api.example.com/v1"
    )

    private fun chatRequest(protocol: AiProtocol, systemPrompt: String? = "SYS") = ChatRequest(
        provider = provider(protocol),
        model = AiModel(id = "m-1"),
        systemPrompt = systemPrompt,
        messages = listOf(
            TransportMessage(ChatRole.USER, "你好"),
            TransportMessage(ChatRole.ASSISTANT, "hi")
        ),
        maxOutputTokens = 4096
    )

    private fun toolRequest(protocol: AiProtocol) =
        chatRequest(protocol).copy(tools = ToolSpecs.ALL)

    private fun Request.bodyJson(): JsonObject {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return json.parseToJsonElement(buffer.readUtf8()) as JsonObject
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.objAt(arrayKey: String, index: Int): JsonObject =
        (this[arrayKey] as JsonArray)[index] as JsonObject

    private fun JsonObject.sizeOf(arrayKey: String): Int = (this[arrayKey] as JsonArray).size

    // ---------- OpenAI Chat Completions ----------

    @Test
    fun `openai chat request puts system first and enables stream`() {
        val request = openAiChat.buildRequest(chatRequest(AiProtocol.OPENAI_CHAT), "sk-test")
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("https://api.example.com/v1/chat/completions", request.url.toString())

        val body = request.bodyJson()
        assertEquals("m-1", body.str("model"))
        assertEquals("true", body.str("stream"))
        assertEquals("4096", body.str("max_tokens"))
        assertEquals(3, body.sizeOf("messages"))
        assertEquals("system", body.objAt("messages", 0).str("role"))
        assertEquals("SYS", body.objAt("messages", 0).str("content"))
        assertEquals("user", body.objAt("messages", 1).str("role"))
        assertEquals("assistant", body.objAt("messages", 2).str("role"))
    }

    @Test
    fun `openai chat omits system message when prompt blank`() {
        val body = openAiChat
            .buildRequest(chatRequest(AiProtocol.OPENAI_CHAT, systemPrompt = "  "), "sk")
            .bodyJson()
        assertEquals(2, body.sizeOf("messages"))
        assertEquals("user", body.objAt("messages", 0).str("role"))
    }

    @Test
    fun `openai chat maps reasoning and content deltas`() {
        val events = openAiChat.parseChunk(
            """{"choices":[{"delta":{"reasoning_content":"r1","content":"c1"}}]}"""
        )
        assertEquals(
            listOf(StreamEvent.ReasoningDelta("r1"), StreamEvent.TextDelta("c1")),
            events
        )
    }

    @Test
    fun `openai chat maps error payload and skips non json`() {
        assertEquals(
            listOf(StreamEvent.Failure(AiError.Api("boom"))),
            openAiChat.parseChunk("""{"error":{"message":"boom"}}""")
        )
        assertEquals(emptyList<StreamEvent>(), openAiChat.parseChunk("not-a-json"))
        assertEquals(emptyList<StreamEvent>(), openAiChat.parseChunk("""{"choices":[]}"""))
    }

    // ---------- OpenAI Responses ----------

    @Test
    fun `responses request uses instructions input and max_output_tokens`() {
        val body = openAiResponses
            .buildRequest(chatRequest(AiProtocol.OPENAI_RESPONSES), "sk")
            .bodyJson()
        assertEquals("m-1", body.str("model"))
        assertEquals("true", body.str("stream"))
        assertEquals("4096", body.str("max_output_tokens"))
        assertEquals("SYS", body.str("instructions"))
        assertEquals(2, body.sizeOf("input"))
        assertEquals("user", body.objAt("input", 0).str("role"))
        assertEquals("你好", body.objAt("input", 0).str("content"))
    }

    @Test
    fun `responses maps text reasoning completion and failure events`() {
        assertEquals(
            listOf(StreamEvent.TextDelta("hello")),
            openAiResponses.parseEvent("""{"type":"response.output_text.delta","delta":"hello"}""")
        )
        assertEquals(
            listOf(StreamEvent.ReasoningDelta("think")),
            openAiResponses.parseEvent(
                """{"type":"response.reasoning_summary_text.delta","delta":"think"}"""
            )
        )
        assertEquals(
            listOf(StreamEvent.MessageStop),
            openAiResponses.parseEvent("""{"type":"response.completed"}""")
        )
        assertEquals(
            listOf(StreamEvent.Failure(AiError.Api("nope"))),
            openAiResponses.parseEvent(
                """{"type":"response.failed","response":{"error":{"message":"nope"}}}"""
            )
        )
        assertEquals(emptyList<StreamEvent>(), openAiResponses.parseEvent("""{"type":"other"}"""))
    }

    // ---------- Anthropic Messages ----------

    @Test
    fun `anthropic request carries required headers max_tokens and system field`() {
        val request = anthropic.buildRequest(chatRequest(AiProtocol.ANTHROPIC_MESSAGES), "key-1")
        assertEquals("key-1", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("https://api.example.com/v1/messages", request.url.toString())

        val body = request.bodyJson()
        assertEquals("SYS", body.str("system"))
        assertEquals("4096", body.str("max_tokens"))
        assertEquals("true", body.str("stream"))
        assertEquals(2, body.sizeOf("messages"))
        assertEquals("user", body.objAt("messages", 0).str("role"))
        val firstContent = (body.objAt("messages", 0)["content"] as JsonArray)[0] as JsonObject
        assertEquals("text", firstContent.str("type"))
        assertEquals("你好", firstContent.str("text"))
    }

    @Test
    fun `anthropic maps text thinking stop and error events`() {
        assertEquals(
            listOf(StreamEvent.TextDelta("hi")),
            anthropic.parseEvent(
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}"""
            )
        )
        assertEquals(
            listOf(StreamEvent.ReasoningDelta("hmm")),
            anthropic.parseEvent(
                """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hmm"}}"""
            )
        )
        assertEquals(
            listOf(StreamEvent.MessageStop),
            anthropic.parseEvent("""{"type":"message_stop"}""")
        )
        assertEquals(
            listOf(StreamEvent.Failure(AiError.Api("overloaded"))),
            anthropic.parseEvent("""{"type":"error","error":{"message":"overloaded"}}""")
        )
        assertEquals(emptyList<StreamEvent>(), anthropic.parseEvent("junk"))
    }

    // ---------- 工具：请求序列化 ----------

    @Test
    fun `openai chat serializes tool definitions and tool messages`() {
        val messages = listOf(
            TransportMessage(ChatRole.USER, "读一下 a.md"),
            TransportMessage(
                ChatRole.ASSISTANT,
                "",
                toolCalls = listOf(RawToolCall("call_1", "read_note", """{"path":"a.md"}"""))
            ),
            TransportMessage(ChatRole.TOOL, "正文A", toolCallId = "call_1")
        )
        val body = openAiChat
            .buildRequest(toolRequest(AiProtocol.OPENAI_CHAT).copy(messages = messages), "sk")
            .bodyJson()

        val tools = body["tools"] as JsonArray
        assertEquals(3, tools.size)
        val first = tools[0] as JsonObject
        assertEquals("function", first.str("type"))
        val function = first["function"] as JsonObject
        assertEquals("list_notes", function.str("name"))
        assertEquals("object", (function["parameters"] as JsonObject).str("type"))

        // system + user + assistant(tool_calls) + tool
        assertEquals(4, body.sizeOf("messages"))
        val assistant = body.objAt("messages", 2)
        assertEquals("assistant", assistant.str("role"))
        val toolCall = (assistant["tool_calls"] as JsonArray)[0] as JsonObject
        assertEquals("call_1", toolCall.str("id"))
        assertEquals("function", toolCall.str("type"))
        assertEquals("read_note", (toolCall["function"] as JsonObject).str("name"))
        assertEquals("""{"path":"a.md"}""", (toolCall["function"] as JsonObject).str("arguments"))

        val tool = body.objAt("messages", 3)
        assertEquals("tool", tool.str("role"))
        assertEquals("call_1", tool.str("tool_call_id"))
        assertEquals("正文A", tool.str("content"))
    }

    @Test
    fun `anthropic serializes tools merges roles and wraps tool results in user`() {
        val messages = listOf(
            TransportMessage(ChatRole.USER, "q1"),
            TransportMessage(ChatRole.USER, "q2"),
            TransportMessage(
                ChatRole.ASSISTANT,
                "回答",
                toolCalls = listOf(RawToolCall("tool_1", "search_notes", """{"query":"k"}"""))
            ),
            TransportMessage(ChatRole.TOOL, "r1", toolCallId = "tool_1"),
            TransportMessage(ChatRole.TOOL, "r2", toolCallId = "tool_2")
        )
        val body = anthropic
            .buildRequest(toolRequest(AiProtocol.ANTHROPIC_MESSAGES).copy(messages = messages), "sk")
            .bodyJson()

        val tools = body["tools"] as JsonArray
        assertEquals(3, tools.size)
        val first = tools[0] as JsonObject
        assertEquals("list_notes", first.str("name"))
        assertEquals("object", (first["input_schema"] as JsonObject).str("type"))

        // 相邻同角色合并：user(q1+q2) / assistant(text+tool_use) / user(tool_result ×2)
        assertEquals(3, body.sizeOf("messages"))
        val firstMsg = body.objAt("messages", 0)
        assertEquals("user", firstMsg.str("role"))
        assertEquals(2, (firstMsg["content"] as JsonArray).size)

        val secondMsg = body.objAt("messages", 1)
        assertEquals("assistant", secondMsg.str("role"))
        val blocks = secondMsg["content"] as JsonArray
        assertEquals("text", (blocks[0] as JsonObject).str("type"))
        val toolUse = blocks[1] as JsonObject
        assertEquals("tool_use", toolUse.str("type"))
        assertEquals("tool_1", toolUse.str("id"))
        assertEquals("k", ((toolUse["input"] as JsonObject)["query"] as? JsonPrimitive)?.contentOrNull)

        val thirdMsg = body.objAt("messages", 2)
        assertEquals("user", thirdMsg.str("role"))
        val results = thirdMsg["content"] as JsonArray
        assertEquals(2, results.size)
        assertEquals("tool_result", (results[0] as JsonObject).str("type"))
        assertEquals("tool_1", (results[0] as JsonObject).str("tool_use_id"))
        assertEquals("r1", (results[0] as JsonObject).str("content"))
        assertEquals("tool_2", (results[1] as JsonObject).str("tool_use_id"))
    }

    @Test
    fun `responses serializes flat tools and function call items`() {
        val messages = listOf(
            TransportMessage(ChatRole.USER, "帮我看看"),
            TransportMessage(
                ChatRole.ASSISTANT,
                "",
                toolCalls = listOf(RawToolCall("call_x", "list_notes", "{}"))
            ),
            TransportMessage(ChatRole.TOOL, "结果", toolCallId = "call_x")
        )
        val body = openAiResponses
            .buildRequest(toolRequest(AiProtocol.OPENAI_RESPONSES).copy(messages = messages), "sk")
            .bodyJson()

        val tools = body["tools"] as JsonArray
        assertEquals(3, tools.size)
        val first = tools[0] as JsonObject
        assertEquals("function", first.str("type"))
        assertEquals("list_notes", first.str("name"))
        assertEquals("object", (first["parameters"] as JsonObject).str("type"))
        assertNull(first.str("input_schema"))

        val input = body["input"] as JsonArray
        assertEquals(3, input.size)
        assertEquals("user", (input[0] as JsonObject).str("role"))
        val fnCall = input[1] as JsonObject
        assertEquals("function_call", fnCall.str("type"))
        assertEquals("call_x", fnCall.str("call_id"))
        assertEquals("list_notes", fnCall.str("name"))
        val fnOut = input[2] as JsonObject
        assertEquals("function_call_output", fnOut.str("type"))
        assertEquals("call_x", fnOut.str("call_id"))
        assertEquals("结果", fnOut.str("output"))
    }

    // ---------- 工具：流式事件（累积器 → ToolCallRequested） ----------

    @Test
    fun `openai chat accumulates tool call fragments and emits on finish reason`() {
        val accumulator = OpenAiToolAccumulator()
        assertEquals(
            emptyList<StreamEvent>(),
            openAiChat.parseChunk(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_note","arguments":"{\"pa"}}]}}]}""",
                accumulator
            )
        )
        assertEquals(
            emptyList<StreamEvent>(),
            openAiChat.parseChunk(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.md\"}"}}]}}]}""",
                accumulator
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("call_1", "read_note", """{"path":"a.md"}"""))
                )
            ),
            openAiChat.parseChunk(
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                accumulator
            )
        )
    }

    @Test
    fun `anthropic accumulates tool use block and emits on message stop`() {
        val accumulator = AnthropicToolAccumulator()
        assertEquals(
            emptyList<StreamEvent>(),
            anthropic.parseEvent(
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool_1","name":"read_note"}}""",
                accumulator
            )
        )
        assertEquals(
            emptyList<StreamEvent>(),
            anthropic.parseEvent(
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""",
                accumulator
            )
        )
        assertEquals(
            emptyList<StreamEvent>(),
            anthropic.parseEvent(
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"th\":\"a.md\"}"}}""",
                accumulator
            )
        )
        assertEquals(
            emptyList<StreamEvent>(),
            anthropic.parseEvent("""{"type":"content_block_stop","index":1}""", accumulator)
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("tool_1", "read_note", """{"path":"a.md"}"""))
                )
            ),
            anthropic.parseEvent("""{"type":"message_stop"}""", accumulator)
        )
    }

    @Test
    fun `responses accumulates function call item and emits on completed`() {
        val accumulator = ResponsesToolAccumulator()
        assertEquals(
            emptyList<StreamEvent>(),
            openAiResponses.parseEvent(
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_x","name":"list_notes","arguments":""}}""",
                accumulator
            )
        )
        assertEquals(
            emptyList<StreamEvent>(),
            openAiResponses.parseEvent(
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"dir\":\"s"}""",
                accumulator
            )
        )
        // done：服务器权威参数覆盖分片累积结果
        assertEquals(
            emptyList<StreamEvent>(),
            openAiResponses.parseEvent(
                """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_x","name":"list_notes","arguments":"{\"dir\":\"sub\"}"}}""",
                accumulator
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("call_x", "list_notes", """{"dir":"sub"}"""))
                )
            ),
            openAiResponses.parseEvent("""{"type":"response.completed"}""", accumulator)
        )
    }

    // ---------- 图片：请求序列化 ----------

    private val imageMessage = TransportMessage(
        ChatRole.USER,
        "看这张图",
        images = listOf(TransportImage("image/jpeg", "QUJD"))
    )

    private val imageOnlyMessage = TransportMessage(
        ChatRole.USER,
        "",
        images = listOf(TransportImage("image/png", "REVG"))
    )

    @Test
    fun `openai chat serializes images as content array with data uri`() {
        val body = openAiChat
            .buildRequest(
                chatRequest(AiProtocol.OPENAI_CHAT)
                    .copy(messages = listOf(imageMessage, imageOnlyMessage)),
                "sk"
            )
            .bodyJson()
        // system + 2 条 user
        assertEquals(3, body.sizeOf("messages"))
        val content = body.objAt("messages", 1)["content"] as JsonArray
        assertEquals(2, content.size)
        assertEquals("text", (content[0] as JsonObject).str("type"))
        assertEquals("看这张图", (content[0] as JsonObject).str("text"))
        val imageUrl = content[1] as JsonObject
        assertEquals("image_url", imageUrl.str("type"))
        assertEquals("data:image/jpeg;base64,QUJD", (imageUrl["image_url"] as JsonObject).str("url"))

        // 纯图无文本：content 数组仅含 image_url 块
        val only = body.objAt("messages", 2)["content"] as JsonArray
        assertEquals(1, only.size)
        assertEquals("image_url", (only[0] as JsonObject).str("type"))
    }

    @Test
    fun `anthropic serializes images as base64 blocks after text`() {
        val body = anthropic
            .buildRequest(
                chatRequest(AiProtocol.ANTHROPIC_MESSAGES).copy(messages = listOf(imageMessage)),
                "sk"
            )
            .bodyJson()
        assertEquals(1, body.sizeOf("messages"))
        val blocks = body.objAt("messages", 0)["content"] as JsonArray
        assertEquals(2, blocks.size)
        assertEquals("text", (blocks[0] as JsonObject).str("type"))
        val image = blocks[1] as JsonObject
        assertEquals("image", image.str("type"))
        val source = image["source"] as JsonObject
        assertEquals("base64", source.str("type"))
        assertEquals("image/jpeg", source.str("media_type"))
        assertEquals("QUJD", source.str("data"))
    }

    @Test
    fun `responses serializes images as input content array`() {
        val body = openAiResponses
            .buildRequest(
                chatRequest(AiProtocol.OPENAI_RESPONSES)
                    .copy(messages = listOf(imageMessage, imageOnlyMessage)),
                "sk"
            )
            .bodyJson()
        assertEquals(2, body.sizeOf("input"))
        val first = body.objAt("input", 0)
        assertEquals("user", first.str("role"))
        val content = first["content"] as JsonArray
        assertEquals(2, content.size)
        assertEquals("input_text", (content[0] as JsonObject).str("type"))
        assertEquals("看这张图", (content[0] as JsonObject).str("text"))
        val image = content[1] as JsonObject
        assertEquals("input_image", image.str("type"))
        assertEquals("data:image/jpeg;base64,QUJD", image.str("image_url"))

        // 纯图无文本：仍成条目，content 仅含 input_image
        val second = body.objAt("input", 1)
        assertEquals("user", second.str("role"))
        val only = second["content"] as JsonArray
        assertEquals(1, only.size)
        assertEquals("input_image", (only[0] as JsonObject).str("type"))
    }
}
