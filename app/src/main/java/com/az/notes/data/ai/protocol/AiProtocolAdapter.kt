package com.az.notes.data.ai.protocol

import com.az.notes.data.ai.AiDiag
import com.az.notes.data.ai.SseHttpException
import com.az.notes.data.ai.SseReader
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.StreamEvent
import com.az.notes.domain.ai.ToolSpec
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Request

/** 协议适配接口（§4.2）：单次请求，内部完成 SSE 读取与事件映射。 */
interface AiProtocolAdapter {
    /** 以流式方式请求一次（含历史消息与系统提示词）。 */
    fun stream(req: ChatRequest, apiKey: String): Flow<StreamEvent>
}

/** 单次请求描述（B3 起含工具定义）。 */
data class ChatRequest(
    val provider: AiProvider,
    val model: AiModel,
    val systemPrompt: String?,
    val messages: List<TransportMessage>,
    val maxOutputTokens: Int,
    /** 工具定义（null / 空 = 不带工具）。 */
    val tools: List<ToolSpec>? = null
)

/**
 * adapter 传输态消息（三 adapter 各自序列化）：
 * - 文本消息：role + text
 * - 工具调用：assistant 的 [toolCalls] 与 text 可共存
 * - 工具结果：role = TOOL，[toolCallId] 关联对应调用
 */
data class TransportMessage(
    val role: ChatRole,
    val text: String,
    val toolCalls: List<RawToolCall> = emptyList(),
    val toolCallId: String? = null
)

/**
 * 三 adapter 共享的「SSE → 统一事件流」外壳：
 * - 请求构建失败（URL 非法）→ Unknown；非 2xx / 网络 / 未知异常 → 对应 AiError
 * - [terminal] 跟踪：已收到终态事件（停止 / 失败 / 工具调用，见 [isTerminal]）后不再补发或误报
 * - [flush]：流自然结束且未 terminal 时先补发（工具调用累积兜底：中转站不发 finish_reason 的场景）
 * - 取消（停止生成）原样抛出，保证 SseReader 的断流语义
 */
internal fun sseEventFlow(
    sse: SseReader,
    buildRequest: () -> Request,
    parse: (String) -> List<StreamEvent>,
    flush: () -> List<StreamEvent> = { emptyList() }
): Flow<StreamEvent> = flow {
    val request = try {
        buildRequest()
    } catch (e: IllegalArgumentException) {
        emit(StreamEvent.Failure(AiError.Unknown))
        return@flow
    }
    var terminal = false
    try {
        sse.dataLines(request).collect { payload ->
            parse(payload).forEach { event ->
                if (event.isTerminal()) terminal = true
                emit(event)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SseHttpException) {
        AiDiag.emit("flow_http_error", mapOf("code" to e.code, "terminal" to terminal))
        if (!terminal) emit(StreamEvent.Failure(AdapterErrors.mapHttp(e)))
        return@flow
    } catch (e: IOException) {
        AiDiag.emit("flow_io_error", mapOf("terminal" to terminal, "msg" to e.message.orEmpty()))
        if (!terminal) emit(StreamEvent.Failure(AiError.Network))
        return@flow
    } catch (e: Exception) {
        if (!terminal) emit(StreamEvent.Failure(AiError.Unknown))
        return@flow
    }
    if (!terminal) {
        val flushed = flush()
        if (flushed.isNotEmpty()) AiDiag.emit("flow_flush", mapOf("flushed" to flushed.size))
        flushed.forEach {
            terminal = true
            emit(it)
        }
    }
    if (!terminal) emit(StreamEvent.MessageStop)
    AiDiag.emit("flow_end", mapOf("terminal" to terminal))
}

/** 终态判定：停止 / 失败 / 工具调用（工具调用后由上层进入下一轮，不再期待同流内文本）。 */
internal fun StreamEvent.isTerminal(): Boolean =
    this is StreamEvent.MessageStop ||
        this is StreamEvent.Failure ||
        this is StreamEvent.ToolCallRequested

/** 三 adapter 共享的错误映射（§4.4）。 */
internal object AdapterErrors {
    /** HTTP 状态码 + 错误体 → 统一 AiError。 */
    fun mapHttp(e: SseHttpException): AiError = when {
        e.code == 401 || e.code == 403 -> AiError.Unauthorized
        e.code == 402 -> AiError.InsufficientBalance
        e.code == 404 -> AiError.NotFound
        e.code == 429 -> AiError.RateLimited
        e.code in 500..599 -> AiError.Server
        looksContextOverflow(e.body) -> AiError.ContextOverflow
        else -> AiError.Unknown
    }

    /** 上下文超限识别（§4.4：按错误文案识别 context / length / token）。 */
    private fun looksContextOverflow(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("context") || lower.contains("length") || lower.contains("token")
    }
}
