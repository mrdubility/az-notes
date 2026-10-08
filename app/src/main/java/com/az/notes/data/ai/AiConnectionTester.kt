package com.az.notes.data.ai

import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/** 测试连接结果：成功（尽力带上发现的模型数）/ 失败（分类原因，文案由 UI 层映射）。 */
sealed class AiTestResult {
    data class Success(val modelCount: Int?) : AiTestResult()

    data class Failure(val kind: FailureKind) : AiTestResult()

    /** 失败分类：data 层不依赖 Android 资源，文案由 UI 层按类型映射。 */
    enum class FailureKind {
        /** 401 / 403：API Key 无效或无权限。 */
        UNAUTHORIZED,

        /** 404：地址错误（服务端没有该接口）。 */
        NOT_FOUND,

        /** 429：请求被限流。 */
        RATE_LIMITED,

        /** 网络异常（DNS / 连接 / 超时 / SSL）。 */
        NETWORK,

        /** 5xx：服务端错误。 */
        SERVER,

        /** 其他（含非法 URL 等）。 */
        UNKNOWN
    }
}

/**
 * 供应商连通性测试（B1）：按协议请求列表接口（GET {base}/models）验证地址与 Key；
 * 仅做连通验证，不导入模型列表（用户已确认）。
 * 200 时尽力解析 data 数组长度（OpenAI / Anthropic 的列表响应均为 {"data": [...]}），
 * 解析失败（部分中转站返回其他格式）仍算连接成功、模型数为 null。
 */
@Singleton
class AiConnectionTester @Inject constructor(
    private val http: AiHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(provider: AiProvider, apiKey: String): AiTestResult =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url("${provider.baseUrl}/models")
                    .apply {
                        if (provider.protocol == AiProtocol.ANTHROPIC_MESSAGES) {
                            header("x-api-key", apiKey)
                            header("anthropic-version", "2023-06-01")
                        } else {
                            header("Authorization", "Bearer $apiKey")
                        }
                        // 自定义请求头（中转站场景）最后应用：允许覆盖上述默认头
                        provider.headers.forEach { (name, value) -> header(name, value) }
                    }
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                // URL 非法（理论上已被保存前校验拦截）；不携带异常文本避免 Key 泄露
                return@withContext AiTestResult.Failure(AiTestResult.FailureKind.UNKNOWN)
            }

            try {
                http.api.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful ->
                            AiTestResult.Success(parseModelCount(response.body?.string()))

                        response.code == 401 || response.code == 403 ->
                            AiTestResult.Failure(AiTestResult.FailureKind.UNAUTHORIZED)

                        response.code == 404 ->
                            AiTestResult.Failure(AiTestResult.FailureKind.NOT_FOUND)

                        response.code == 429 ->
                            AiTestResult.Failure(AiTestResult.FailureKind.RATE_LIMITED)

                        response.code in 500..599 ->
                            AiTestResult.Failure(AiTestResult.FailureKind.SERVER)

                        else -> AiTestResult.Failure(AiTestResult.FailureKind.UNKNOWN)
                    }
                }
            } catch (e: IOException) {
                AiTestResult.Failure(AiTestResult.FailureKind.NETWORK)
            } catch (e: Exception) {
                AiTestResult.Failure(AiTestResult.FailureKind.UNKNOWN)
            }
        }

    /** 解析 {"data": [...]} 的条目数；格式不符返回 null（不视为失败）。 */
    private fun parseModelCount(body: String?): Int? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            (root["data"] as? JsonArray)?.size
        }.getOrNull()
    }
}
