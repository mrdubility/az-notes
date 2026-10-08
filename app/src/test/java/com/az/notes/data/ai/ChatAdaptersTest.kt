package com.az.notes.data.ai

import com.az.notes.data.ai.protocol.AnthropicAdapter
import com.az.notes.data.ai.protocol.ChatRequest
import com.az.notes.data.ai.protocol.OpenAiChatAdapter
import com.az.notes.data.ai.protocol.OpenAiResponsesAdapter
import com.az.notes.data.ai.protocol.TransportMessage
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三协议 Adapter 单测（§12.2）：
 * - 请求体 JSON 断言：system 位置（OpenAI messages[0] / Responses instructions / Anthropic 顶层）、
 *   stream=true、Anthropic max_tokens 必填与专属请求头
 * - 事件映射纯函数断言：text / reasoning / 结束 / error 透传、非 JSON 安全跳过
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
}
