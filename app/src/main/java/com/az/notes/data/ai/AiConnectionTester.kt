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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** 模型列表获取结果（B1 补丁「获取模型」快速添加；失败降级为手填）。 */
sealed class ModelListResult {
    /** 成功：服务端返回的模型 ID 列表（可能为空——格式不符或没有模型，UI 按「未返回」提示）。 */
    data class Success(val modelIds: List<String>) : ModelListResult()

    /** 失败：分类与连接测试一致（文案由 UI 层映射）。 */
    data class Failure(val kind: AiTestResult.FailureKind) : ModelListResult()
}

/**
 * 供应商连通性测试（B1）：按协议请求列表接口（GET {base}/models）验证地址与 Key。
 * 200 时尽力解析 data 数组长度（OpenAI / Anthropic 的列表响应均为 {"data": [...]}），
 * 解析失败（部分中转站返回其他格式）仍算连接成功、模型数为 null。
 * B1 补丁：同接口另供 [fetchModels] 拉取模型 ID 列表，供表单「获取模型」快速添加。
 */
@Singleton
class AiConnectionTester @Inject constructor(
    private val http: AiHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(provider: AiProvider, apiKey: String): AiTestResult =
        withContext(Dispatchers.IO) {
            // URL 非法（理论上已被保存前校验拦截）；不携带异常文本避免 Key 泄露
            val request = buildModelsRequest(provider, apiKey)
                ?: return@withContext AiTestResult.Failure(AiTestResult.FailureKind.UNKNOWN)
            try {
                http.api.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        AiTestResult.Success(parseModelCount(response.body?.string()))
                    } else {
                        AiTestResult.Failure(classify(response.code))
                    }
                }
            } catch (e: IOException) {
                AiTestResult.Failure(AiTestResult.FailureKind.NETWORK)
            } catch (e: Exception) {
                AiTestResult.Failure(AiTestResult.FailureKind.UNKNOWN)
            }
        }

    /**
     * 拉取模型列表（B1 补丁「获取模型」）：同一 GET {base}/models 接口，解析 data[].id。
     * 任何失败仅影响本次获取（UI 降级为手填），不改变已保存配置。
     */
    suspend fun fetchModels(provider: AiProvider, apiKey: String): ModelListResult =
        withContext(Dispatchers.IO) {
            val request = buildModelsRequest(provider, apiKey)
                ?: return@withContext ModelListResult.Failure(AiTestResult.FailureKind.UNKNOWN)
            try {
                http.api.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        ModelListResult.Success(parseModelIds(response.body?.string()))
                    } else {
                        ModelListResult.Failure(classify(response.code))
                    }
                }
            } catch (e: IOException) {
                ModelListResult.Failure(AiTestResult.FailureKind.NETWORK)
            } catch (e: Exception) {
                ModelListResult.Failure(AiTestResult.FailureKind.UNKNOWN)
            }
        }

    /** 构建 GET {base}/models 请求（test / fetchModels 共用）；URL 非法返回 null。 */
    private fun buildModelsRequest(provider: AiProvider, apiKey: String): Request? = try {
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
        null
    }

    /** 非 2xx 状态码 → 失败分类（test / fetchModels 共用）。 */
    private fun classify(code: Int): AiTestResult.FailureKind = when {
        code == 401 || code == 403 -> AiTestResult.FailureKind.UNAUTHORIZED
        code == 404 -> AiTestResult.FailureKind.NOT_FOUND
        code == 429 -> AiTestResult.FailureKind.RATE_LIMITED
        code in 500..599 -> AiTestResult.FailureKind.SERVER
        else -> AiTestResult.FailureKind.UNKNOWN
    }

    /** 解析 {"data": [...]} 的条目数；格式不符返回 null（不视为失败）。 */
    private fun parseModelCount(body: String?): Int? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            (root["data"] as? JsonArray)?.size
        }.getOrNull()
    }

    /**
     * 解析 {"data": [{"id": ...}]} 的模型 ID 列表（去重保序、过滤空白项）；
     * 格式不符返回空列表——UI 提示「未返回模型」并保留手填，不视为网络失败。
     */
    private fun parseModelIds(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val data = runCatching {
            json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
        }.getOrNull() ?: return emptyList()
        val ids = LinkedHashSet<String>()
        data.forEach { item ->
            val id = ((item as? JsonObject)?.get("id") as? JsonPrimitive)
                ?.content?.trim().orEmpty()
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids.toList()
    }
}
