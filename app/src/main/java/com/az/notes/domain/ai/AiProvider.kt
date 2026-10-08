package com.az.notes.domain.ai

import kotlinx.serialization.Serializable

/** AI 供应商的接口协议（§3.2）：三套主流 API 形态，各自独立 adapter 适配。 */
@Serializable
enum class AiProtocol {
    /** OpenAI Chat Completions（{base}/chat/completions）。 */
    OPENAI_CHAT,

    /** OpenAI Responses（{base}/responses）。 */
    OPENAI_RESPONSES,

    /** Anthropic Messages（{base}/messages）。 */
    ANTHROPIC_MESSAGES
}

/** 供应商下的一个模型。 */
@Serializable
data class AiModel(
    val id: String,
    /** 显示名；默认与 [id] 相同。 */
    val label: String = id,
    /** 上下文窗口（tokens）；未知为 null（压缩预算用全局默认兜底）。 */
    val contextWindow: Int? = null,
    /** 是否支持图片输入（决定对话页图片附件是否可用）。 */
    val vision: Boolean = false
)

/**
 * 模型供应商（全局配置，不随仓库变化）。
 * API Key 不在此模型内：按供应商 id 走 AiKeyStore 加密存储（§11）。
 */
@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    val protocol: AiProtocol,
    /** 规范化后的 base（不含 /chat/completions 等端点路径）；adapter 按协议直接追加。 */
    val baseUrl: String,
    /** 可选自定义请求头（中转站场景；本期保留字段，无编辑 UI）。 */
    val headers: Map<String, String> = emptyMap(),
    val models: List<AiModel> = emptyList(),
    /** 对话页最近选中的模型 id（切换模型时记忆；B2 起使用）。 */
    val lastModelId: String? = null
)

/**
 * baseUrl 规范化（§3.2）：去首尾空白与尾部 `/`；
 * 仅接受 http:// 或 https:// 开头的地址，其余返回 null（表单校验据此拦截）。
 */
object BaseUrlNormalizer {
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        // 仅协议头无主机名（如 "http:///x"）：视为非法
        val rest = trimmed.substringAfter("://")
        if (rest.isEmpty() || rest.startsWith("/")) return null
        return trimmed
    }
}
