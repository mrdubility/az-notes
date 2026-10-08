package com.az.notes.data.ai.protocol

import com.az.notes.data.ai.SseHttpException
import com.az.notes.data.ai.SseReader
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.StreamEvent
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

/** 单次请求描述（B3 工具循环将增设 tools 字段）。 */
data class ChatRequest(
    val provider: AiProvider,
    val model: AiModel,
    val systemPrompt: String?,
    val messages: List<TransportMessage>,
    val maxOutputTokens: Int
)

/** adapter 传输态消息（本批仅文本；B3 / B4 扩展图片 / 文档 / 工具段，三 adapter 各自序列化）。 */
data class TransportMessage(
    val role: ChatRole,
    val text: String
)

/**
 * 三 adapter 共享的「SSE → 统一事件流」外壳：
 * - 请求构建失败（URL 非法）→ Unknown；非 2xx / 网络 / 未知异常 → 对应 AiError
 * - [terminal] 跟踪：已收到 MessageStop / Failure 后不再补发或误报
 *   （覆盖「服务端发完完成事件后被动断流」的场景）
 * - 取消（停止生成）原样抛出，保证 SseReader 的断流语义
 */
internal fun sseEventFlow(
    sse: SseReader,
    buildRequest: () -> Request,
    parse: (String) -> List<StreamEvent>
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
                if (event is StreamEvent.MessageStop || event is StreamEvent.Failure) {
                    terminal = true
                }
                emit(event)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SseHttpException) {
        if (!terminal) emit(StreamEvent.Failure(AdapterErrors.mapHttp(e)))
        return@flow
    } catch (e: IOException) {
        if (!terminal) emit(StreamEvent.Failure(AiError.Network))
        return@flow
    } catch (e: Exception) {
        if (!terminal) emit(StreamEvent.Failure(AiError.Unknown))
        return@flow
    }
    if (!terminal) emit(StreamEvent.MessageStop)
}

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
