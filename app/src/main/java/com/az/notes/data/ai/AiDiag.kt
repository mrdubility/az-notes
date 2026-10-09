package com.az.notes.data.ai

/**
 * AI 链路线路级诊断汇（排「模型无回答」类问题用）：
 * - 默认空实现：单测与未接线场景零成本、零副作用（[emit] 内部 runCatching 兜底）
 * - [AzNotesApp] 启动时挂接到 DebugLogRepository（type=AI）：开启收集后记录
 *   SSE 响应码 / 原始包数与首包摘录 / 每轮事件统计与终止原因，
 *   用于定位中转站「200 但无正文 / 非 SSE 格式 / 流被掐断」等线路异常
 */
object AiDiag {

    /** 诊断汇：msg 为事件名，extra 为结构化上下文（数值 / 布尔原样落盘）。 */
    @Volatile
    var sink: ((msg: String, extra: Map<String, Any?>) -> Unit)? = null

    /** 写一条诊断（未接线 / 写入异常均静默忽略，绝不影响对话主链路）。 */
    fun emit(msg: String, extra: Map<String, Any?> = emptyMap()) {
        runCatching { sink?.invoke(msg, extra) }
    }
}
