package com.az.notes.domain.ai

/**
 * 统一错误（§4.4）：data 层只做分类，文案由 UI 层映射（对齐 AiTestResult 先例）。
 */
sealed class AiError {
    /** 401 / 403：API Key 无效或无权限。 */
    data object Unauthorized : AiError()

    /** 402：账户余额不足。 */
    data object InsufficientBalance : AiError()

    /** 404：接口路径错误（检查 Base URL）或模型不存在。 */
    data object NotFound : AiError()

    /** 429：请求过于频繁，请稍后再试。 */
    data object RateLimited : AiError()

    /** 5xx：服务端错误（服务商暂时不可用）。 */
    data object Server : AiError()

    /** 上下文超限（按状态码 / 错误文案识别 context / length / token；自动压缩重试留 B4）。 */
    data object ContextOverflow : AiError()

    /** 网络异常（DNS / 连接 / 超时 / SSL）。 */
    data object Network : AiError()

    /** 流中 error 事件 / 服务商透传文案（UI 展示时附前缀）。 */
    data class Api(val message: String) : AiError()

    data object Unknown : AiError()
}
