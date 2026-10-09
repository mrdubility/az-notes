package com.az.notes.domain.ai

/**
 * 上下文预算（§6.1）：可用输入预算 = (窗口 − 输出预留) × 安全系数，
 * 安全系数为 [TokenEstimator] 的估算误差留出余量。
 */
data class ContextBudget(
    /** 模型上下文窗口（tokens）。 */
    val window: Int,
    /** 输出预留（tokens）。 */
    val reserveOutput: Int = RESERVE_OUTPUT,
    /** 安全系数。 */
    val safetyRatio: Float = SAFETY_RATIO
) {
    /** 可用输入预算（tokens）。 */
    val usable: Int get() = ((window - reserveOutput) * safetyRatio).toInt()

    companion object {
        /** 模型未标注上下文窗口时的兜底窗口。 */
        const val DEFAULT_WINDOW = 32_000

        /** 默认输出预留（tokens）。 */
        const val RESERVE_OUTPUT = 4_096

        /** 默认安全系数。 */
        const val SAFETY_RATIO = 0.85f
    }
}
