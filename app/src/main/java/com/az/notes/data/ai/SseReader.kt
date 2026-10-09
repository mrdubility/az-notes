package com.az.notes.data.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/** SSE 请求非 2xx：携带状态码与错误体，由上层映射为统一 AiError。 */
class SseHttpException(val code: Int, val body: String) : IOException("SSE HTTP $code")

/**
 * 自研 SSE 读取器（§4.3）：OkHttp 流式响应 → `data:` payload 行流。
 * - 按行读：忽略空行、`:` 注释行与 `event:` 等字段行；`data:` 后 payload 逐行发出
 * - 无数据超时 90s：从 streaming 客户端派生 readTimeout（丢心跳的中转站主动断开 → IOException）
 * - 取消即断流：协程取消（停止生成）时 call.cancel()，阻塞中的 socket 读立即释放
 * - `[DONE]` 终标记：结束流（adapter 将「流自然结束」统一映射为 MessageStop）
 * - 非 JSON payload 不在此层判断：原样发出，adapter 解析时容错跳过（对齐 WebDavClient 安全退化）
 */
@Singleton
class SseReader @Inject constructor(
    private val http: AiHttpClient
) {

    /** 执行流式请求，返回逐条 `data:` payload（不含 `[DONE]`）。 */
    fun dataLines(request: Request): Flow<String> = flow {
        val client = http.streaming.newBuilder()
            .readTimeout(NO_DATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(request)
        // 取消即断流：上游协程结束（取消/正常）时断开连接，阻塞中的读随之抛出
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = call.await()
        response.use { resp ->
            // 线路诊断：响应码与内容类型（中转站「200 但非 SSE」等异常的第一证据）
            AiDiag.emit(
                "sse_http",
                mapOf("code" to resp.code, "ctype" to resp.header("Content-Type").orEmpty())
            )
            if (!resp.isSuccessful) {
                throw SseHttpException(resp.code, resp.body?.string().orEmpty())
            }
            val source = resp.body?.source() ?: throw IOException("empty SSE body")
            var payloads = 0
            var rawLines = 0
            var rawFirst = ""
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    // 首行原文摘录：非 SSE 响应体（整包 JSON 等）时 payloads=0 而 rawFirst 有值
                    if (rawLines == 0) rawFirst = line.take(160)
                    rawLines++
                    val payload = extractPayload(line) ?: continue
                    if (payload == DONE_MARKER) break
                    payloads++
                    emit(payload)
                }
            } catch (e: IOException) {
                AiDiag.emit(
                    "sse_io_error",
                    mapOf("canceled" to call.isCanceled(), "msg" to e.message.orEmpty())
                )
                // 取消场景（call.cancel() 中断阻塞读）：转为取消异常，避免被上层误报网络错误
                if (call.isCanceled()) throw CancellationException("SSE request canceled")
                throw e
            }
            AiDiag.emit(
                "sse_end",
                mapOf("payloads" to payloads, "rawLines" to rawLines, "rawFirst" to rawFirst)
            )
        }
    }.flowOn(Dispatchers.IO)

    /** `data:` 行 → payload；空行 / 注释行（`:` 开头）/ `event:` 等其他字段行返回 null。 */
    private fun extractPayload(line: String): String? {
        if (line.isEmpty() || line.startsWith(":")) return null
        if (!line.startsWith(DATA_PREFIX)) return null
        return line.removePrefix(DATA_PREFIX).trim().ifEmpty { null }
    }

    /** 可取消的调用等待：取消时 cancel() 断开连接。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private companion object {
        /** 无数据超时（§4.3）：流开始后 90s 无任何字节视为假死。 */
        const val NO_DATA_TIMEOUT_SECONDS = 90L
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
    }
}
