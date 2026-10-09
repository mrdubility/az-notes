package com.az.notes.data.ai

import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.StreamEvent
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单轮事件泵（[pumpTurn]）回归测试（2026-10「工具行有、回答无」事故）：
 * 锁定「正文 / 思考 / 失败事件必须转发到引擎输出收集器（VM）」契约——
 * 原实现 `transformWhile { ... }.collect { }` 空收集把输出流全部丢弃，
 * 仅剩工具状态行（out.emit 直发）可达 VM，界面表现为已读取文档但回答恒空。
 */
class ChatEngineTurnPumpTest {

    /** 记录全部转发事件的引擎输出收集器。 */
    private fun collectorInto(received: MutableList<StreamEvent>): FlowCollector<StreamEvent> =
        object : FlowCollector<StreamEvent> {
            override suspend fun emit(value: StreamEvent) {
                received.add(value)
            }
        }

    @Test
    fun `text deltas are forwarded and message stop is intercepted`() = runTest {
        val received = mutableListOf<StreamEvent>()
        val stats = TurnStats()
        flowOf(
            StreamEvent.TextDelta("你"),
            StreamEvent.TextDelta("好"),
            StreamEvent.MessageStop
        ).pumpTurn(collectorInto(received), stats)

        assertEquals(
            listOf(StreamEvent.TextDelta("你"), StreamEvent.TextDelta("好")),
            received
        )
        assertEquals("你好", stats.turnText.toString())
        assertTrue(stats.producedAnyText)
        assertNull(stats.requestedCalls)
    }

    @Test
    fun `reasoning delta is forwarded`() = runTest {
        val received = mutableListOf<StreamEvent>()
        val stats = TurnStats()
        flowOf(
            StreamEvent.ReasoningDelta("先思考"),
            StreamEvent.MessageStop
        ).pumpTurn(collectorInto(received), stats)

        assertEquals(listOf(StreamEvent.ReasoningDelta("先思考")), received)
    }

    @Test
    fun `failure is forwarded before the stream stops`() = runTest {
        val received = mutableListOf<StreamEvent>()
        val stats = TurnStats()
        flowOf(
            StreamEvent.TextDelta("开头"),
            StreamEvent.Failure(AiError.Server),
            StreamEvent.TextDelta("不应到达")
        ).pumpTurn(collectorInto(received), stats)

        assertEquals(
            listOf(StreamEvent.TextDelta("开头"), StreamEvent.Failure(AiError.Server)),
            received
        )
        assertTrue(stats.failed)
    }

    @Test
    fun `tool call request is captured and stops without forwarding`() = runTest {
        val received = mutableListOf<StreamEvent>()
        val stats = TurnStats()
        val call = RawToolCall("call_1", "read_note", """{"path":"a.md"}""")
        flowOf(
            StreamEvent.TextDelta("前缀"),
            StreamEvent.ToolCallRequested(listOf(call)),
            StreamEvent.TextDelta("不应到达")
        ).pumpTurn(collectorInto(received), stats)

        assertEquals(listOf(StreamEvent.TextDelta("前缀")), received)
        assertEquals(listOf(call), stats.requestedCalls)
    }
}
