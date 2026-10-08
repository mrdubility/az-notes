package com.az.notes.data.ai

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * SseReader 桩流单测（§12.2）：行提取（忽略注释 / 空行 / 字段行）、[DONE] 终标记、
 * 非 JSON payload 透传、非 2xx 抛 SseHttpException、取首条后取消不挂起（断流路径）。
 */
class SseReaderTest {

    private lateinit var server: MockWebServer
    private lateinit var reader: SseReader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        reader = SseReader(AiHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = Request.Builder().url(server.url("/chat/completions")).build()

    private fun sse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    @Test
    fun `extracts data payloads ignoring comments blanks and field lines`() = runTest {
        server.enqueue(
            sse(
                ": keep-alive\n" +
                    "\n" +
                    "event: message\n" +
                    "data: {\"a\":1}\n" +
                    "\n" +
                    "data:{\"b\":2}\n" +
                    "\n"
            )
        )
        assertEquals(
            listOf("{\"a\":1}", "{\"b\":2}"),
            reader.dataLines(request()).toList()
        )
    }

    @Test
    fun `done marker ends stream and drops later lines`() = runTest {
        server.enqueue(
            sse(
                "data: {\"a\":1}\n" +
                    "\n" +
                    "data: [DONE]\n" +
                    "\n" +
                    "data: {\"after\":true}\n"
            )
        )
        assertEquals(listOf("{\"a\":1}"), reader.dataLines(request()).toList())
    }

    @Test
    fun `non json payload passes through untouched`() = runTest {
        server.enqueue(sse("data: not-a-json\n\n"))
        assertEquals(listOf("not-a-json"), reader.dataLines(request()).toList())
    }

    @Test
    fun `non 2xx throws SseHttpException with code and body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))
        try {
            reader.dataLines(request()).toList()
            fail("expected SseHttpException")
        } catch (e: SseHttpException) {
            assertEquals(401, e.code)
            assertTrue(e.body.contains("bad key"))
        }
    }

    @Test
    fun `cancel after first payload stops without hanging`() = runTest {
        server.enqueue(sse("data: one\n\ndata: two\n\n"))
        assertEquals("one", reader.dataLines(request()).first())
    }
}
