package com.az.notes.data.ai

import com.az.notes.data.ai.protocol.AnthropicAdapter
import com.az.notes.data.ai.protocol.ChatRequest
import com.az.notes.data.ai.protocol.OpenAiChatAdapter
import com.az.notes.data.ai.protocol.OpenAiResponsesAdapter
import com.az.notes.data.ai.protocol.TransportMessage
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 三协议 adapter.stream 流级集成单测（MockWebServer 真 SSE 链路，§12.2 补充）：
 * 覆盖 sseEventFlow 的终态判定与 flush 兜底——工具分片随流成组发出；
 * 流被中转站提前掐断（无 finish_reason / message_stop / response.completed）时，
 * flush 补发累积的工具调用，纯文本流自然结束补发 MessageStop（不静默空收尾）。
 */
class ChatAdapterStreamTest {

    private lateinit var server: MockWebServer

    private val openAiChat = OpenAiChatAdapter(SseReader(AiHttpClient()))
    private val openAiResponses = OpenAiResponsesAdapter(SseReader(AiHttpClient()))
    private val anthropic = AnthropicAdapter(SseReader(AiHttpClient()))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(protocol: AiProtocol) = AiProvider(
        id = "p1",
        name = "Test",
        protocol = protocol,
        baseUrl = server.url("/").toString()
    )

    private fun chatRequest(protocol: AiProtocol) = ChatRequest(
        provider = provider(protocol),
        model = AiModel(id = "m-1"),
        systemPrompt = "SYS",
        messages = listOf(TransportMessage(ChatRole.USER, "你好")),
        maxOutputTokens = 4096
    )

    private fun sse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    // ---------- OpenAI Chat Completions ----------

    @Test
    fun `openai emits tool request on finish reason tool calls`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                    "\"type\":\"function\",\"function\":{\"name\":\"read_note\",\"arguments\":\"{\\\"pa\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                    "\"function\":{\"arguments\":\"th\\\":\\\"a.md\\\"}\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("call_1", "read_note", """{"path":"a.md"}"""))
                )
            ),
            openAiChat.stream(chatRequest(AiProtocol.OPENAI_CHAT), "sk").toList()
        )
    }

    @Test
    fun `openai flushes accumulated tools when stream truncated without finish reason`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                    "\"type\":\"function\",\"function\":{\"name\":\"read_note\"," +
                    "\"arguments\":\"{\\\"path\\\":\\\"a.md\\\"}\"}}]}}]}\n\n"
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("call_1", "read_note", """{"path":"a.md"}"""))
                )
            ),
            openAiChat.stream(chatRequest(AiProtocol.OPENAI_CHAT), "sk").toList()
        )
    }

    @Test
    fun `openai emits text then message stop on natural stream end`() = runTest {
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n")
        )
        assertEquals(
            listOf(StreamEvent.TextDelta("hi"), StreamEvent.MessageStop),
            openAiChat.stream(chatRequest(AiProtocol.OPENAI_CHAT), "sk").toList()
        )
    }

    // ---------- Anthropic Messages ----------

    @Test
    fun `anthropic emits tool request on message stop`() = runTest {
        server.enqueue(
            sse(
                "data: {\"type\":\"content_block_start\",\"index\":1," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"read_note\"}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":1," +
                    "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"a.md\\\"}\"}}\n\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n"
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("tool_1", "read_note", """{"path":"a.md"}"""))
                )
            ),
            anthropic.stream(chatRequest(AiProtocol.ANTHROPIC_MESSAGES), "sk").toList()
        )
    }

    @Test
    fun `anthropic flushes accumulated tools when message stop missing`() = runTest {
        server.enqueue(
            sse(
                "data: {\"type\":\"content_block_start\",\"index\":1," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"read_note\"}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":1," +
                    "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"a.md\\\"}\"}}\n\n"
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("tool_1", "read_note", """{"path":"a.md"}"""))
                )
            ),
            anthropic.stream(chatRequest(AiProtocol.ANTHROPIC_MESSAGES), "sk").toList()
        )
    }

    // ---------- OpenAI Responses ----------

    @Test
    fun `responses emits tool request on completed`() = runTest {
        server.enqueue(
            sse(
                "data: {\"type\":\"response.output_item.added\"," +
                    "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_x\"," +
                    "\"name\":\"list_notes\",\"arguments\":\"\"}}\n\n" +
                    "data: {\"type\":\"response.function_call_arguments.delta\"," +
                    "\"item_id\":\"fc_1\",\"delta\":\"{\\\"dir\\\":\\\"sub\\\"}\"}\n\n" +
                    "data: {\"type\":\"response.output_item.done\"," +
                    "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_x\"," +
                    "\"name\":\"list_notes\",\"arguments\":\"{\\\"dir\\\":\\\"sub\\\"}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\"}\n\n"
            )
        )
        assertEquals(
            listOf(
                StreamEvent.ToolCallRequested(
                    listOf(RawToolCall("call_x", "list_notes", """{"dir":"sub"}"""))
                )
            ),
            openAiResponses.stream(chatRequest(AiProtocol.OPENAI_RESPONSES), "sk").toList()
        )
    }
}
