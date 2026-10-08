package com.az.notes.ui.ai

import com.az.notes.R
import com.az.notes.domain.ai.AiProtocol

/**
 * 新增供应商时的预设模板（§3.2）：仅预填「名称 + 协议 + baseUrl」，
 * 一律不预填模型 ID（模型迭代快、各账号可用集合不同，由用户按服务商控制台自行填写）。
 *
 * 各平台参数已联网按官方文档核实（2026-10）：
 * - OpenAI Chat：https://api.openai.com/v1
 * - Anthropic Messages：https://api.anthropic.com/v1
 * - 阿里云百炼（OpenAI 兼容，Bearer sk-xxx）：https://dashscope.aliyuncs.com/compatible-mode/v1
 *   官方已推出业务空间专属新域名 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
 *   （需 WorkspaceId），旧域名仍可正常使用，故模板默认旧域名，用户可自行替换
 * - 字节火山方舟（OpenAI 兼容，Bearer ARK API Key）：https://ark.cn-beijing.volces.com/api/v3
 *   官方亦提供 Anthropic 兼容端点 https://ark.cn-beijing.volces.com/api/compatible
 *   （消息端点为其下 /v1/messages），可自行切换协议并按 .../api/compatible/v1 填写
 * - DeepSeek：https://api.deepseek.com/v1；Kimi：https://api.moonshot.cn/v1
 * - 智谱 GLM：https://open.bigmodel.cn/api/paas/v4
 * - Ollama（本地部署，IP 按实际修改）：http://192.168.1.100:11434/v1
 */
data class AiProviderPreset(
    val nameResId: Int,
    val protocol: AiProtocol,
    val baseUrl: String
)

/** 预置平台模板（点击后进入表单，可继续修改任意字段）。 */
val aiProviderPresets: List<AiProviderPreset> = listOf(
    AiProviderPreset(R.string.ai_preset_openai, AiProtocol.OPENAI_CHAT, "https://api.openai.com/v1"),
    AiProviderPreset(R.string.ai_preset_anthropic, AiProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1"),
    AiProviderPreset(R.string.ai_preset_bailian, AiProtocol.OPENAI_CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    AiProviderPreset(R.string.ai_preset_ark, AiProtocol.OPENAI_CHAT, "https://ark.cn-beijing.volces.com/api/v3"),
    AiProviderPreset(R.string.ai_preset_deepseek, AiProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1"),
    AiProviderPreset(R.string.ai_preset_kimi, AiProtocol.OPENAI_CHAT, "https://api.moonshot.cn/v1"),
    AiProviderPreset(R.string.ai_preset_glm, AiProtocol.OPENAI_CHAT, "https://open.bigmodel.cn/api/paas/v4"),
    AiProviderPreset(R.string.ai_preset_ollama, AiProtocol.OPENAI_CHAT, "http://192.168.1.100:11434/v1")
)

/** 空白模板（自定义服务商 / 中转站）：协议默认 Chat，地址与模型全手填。 */
val blankProviderPreset = AiProviderPreset(
    R.string.ai_provider_template_blank,
    AiProtocol.OPENAI_CHAT,
    ""
)
