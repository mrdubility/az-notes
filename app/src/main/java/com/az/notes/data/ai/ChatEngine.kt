package com.az.notes.data.ai

import com.az.notes.data.ai.protocol.AiProtocolAdapter
import com.az.notes.data.ai.protocol.AnthropicAdapter
import com.az.notes.data.ai.protocol.ChatRequest
import com.az.notes.data.ai.protocol.OpenAiChatAdapter
import com.az.notes.data.ai.protocol.OpenAiResponsesAdapter
import com.az.notes.data.ai.protocol.TransportImage
import com.az.notes.data.ai.protocol.TransportMessage
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.PromptTemplates
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.StreamEvent
import com.az.notes.domain.ai.ToolCallRecord
import com.az.notes.domain.ai.ToolExchange
import com.az.notes.domain.ai.ToolSpecs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

/**
 * 对话编排：读 Key → 按协议选 adapter → 每轮请求携带工具定义；
 * 模型请求工具时本地执行（[VaultToolExecutor]，IO 线程）→ 回填传输态 → 下一轮，直至无工具调用。
 * - 历史组装：仅终态消息（COMPLETE / CANCELED）参与；USER 经 [ContextAssembler] 渲染（含附件文档）
 *   并逐张编码附加图片（[AiImagePreparer]，失败单张降级文本占位）；
 *   ASSISTANT 按 toolExchanges 展开工具往返（assistant(tool_calls) → tool 结果）后再接正文
 * - 安全阀：轮数超过 [MAX_TOOL_ROUNDS] 或工具累计超过 [MAX_TOOL_CALLS] 时，执行额度内调用并
 *   附「请基于已获取的信息作答」提示以 tools=null 收尾；收尾轮仍请求工具时丢弃调用并再
 *   强制作答一次，仍不收敛才结束——零正文时转 Failure，绝不静默空收尾
 * - 对外契约：仅透传 Text / Reasoning / Started / Completed / Stop / Failure
 *   （ToolCallRequested 为内部消化信号）
 * - 不发送采样参数（用户已确认用服务端默认）；上下文压缩检查由 VM 在 stream 之前前置执行
 */
@Singleton
class ChatEngine @Inject constructor(
    private val keyStore: AiKeyStore,
    private val openAiChat: OpenAiChatAdapter,
    private val openAiResponses: OpenAiResponsesAdapter,
    private val anthropic: AnthropicAdapter,
    private val settingsRepository: SettingsRepository,
    private val toolExecutor: VaultToolExecutor,
    private val imagePreparer: AiImagePreparer
) {

    /** 发起一次流式生成（[history] 为会话消息快照；输出统一 [StreamEvent] 流）。 */
    fun stream(provider: AiProvider, model: AiModel, history: List<ChatMessage>): Flow<StreamEvent> =
        flow {
            val apiKey = keyStore.getKey(provider.id)
            if (apiKey.isEmpty()) {
                emit(StreamEvent.Failure(AiError.Unauthorized))
                return@flow
            }
            val transport = buildTransport(history).toMutableList()
            if (transport.isEmpty()) {
                emit(StreamEvent.Failure(AiError.Unknown))
                return@flow
            }
            val adapter: AiProtocolAdapter = when (provider.protocol) {
                AiProtocol.OPENAI_CHAT -> openAiChat
                AiProtocol.OPENAI_RESPONSES -> openAiResponses
                AiProtocol.ANTHROPIC_MESSAGES -> anthropic
            }
            var rounds = 0
            var toolCallCount = 0
            var toolsEnabled = true
            // 收尾轮（tools=null）强制作答尝试次数
            var forceAttempts = 0
            // 全程是否产出过正文文本（「零正文结束」一律视为异常）
            var producedAnyText = false

            /** 执行一批工具调用并回填传输态：状态行先行（preview），执行后发 Completed。 */
            suspend fun runTools(
                out: FlowCollector<StreamEvent>,
                callsToRun: List<RawToolCall>,
                text: String
            ) {
                // 状态行先出：执行开始即展示「正在读取…」（argsSummary 预览，resultSummary 空串占位）
                out.emit(StreamEvent.ToolCallStarted(callsToRun.map { toolExecutor.preview(it) }))
                val executions = withContext(Dispatchers.IO) {
                    val vaultRoot = runCatching {
                        settingsRepository.settings.first().vaultPath
                    }.getOrNull()
                    callsToRun.map { call ->
                        if (vaultRoot.isNullOrBlank()) {
                            ToolExecutionResult(
                                resultText = "错误：未找到当前仓库",
                                record = ToolCallRecord(call.name, "", "错误：未找到当前仓库")
                            )
                        } else {
                            toolExecutor.execute(vaultRoot, call)
                        }
                    }
                }
                val records = executions.map { it.record }
                // 传输态回填：本轮 assistant(tool_calls) + 每个调用一条 tool 结果（id 配对）
                transport += TransportMessage(
                    role = ChatRole.ASSISTANT,
                    text = text,
                    toolCalls = callsToRun
                )
                callsToRun.forEachIndexed { index, call ->
                    transport += TransportMessage(
                        role = ChatRole.TOOL,
                        text = executions[index].resultText,
                        toolCallId = call.id
                    )
                }
                out.emit(
                    StreamEvent.ToolCallCompleted(
                        records,
                        ToolExchange(callsToRun, executions.map { it.resultText })
                    )
                )
                toolCallCount += callsToRun.size
            }

            while (true) {
                rounds++
                val request = ChatRequest(
                    provider = provider,
                    model = model,
                    systemPrompt = PromptTemplates.CHAT_SYSTEM,
                    messages = transport.toList(),
                    maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                    tools = if (toolsEnabled) ToolSpecs.ALL else null
                )
                // 单轮事件泵：统计并在其内部把正文 / 思考 / 失败事件转发到本 flow 输出（VM）
                val stats = TurnStats()
                adapter.stream(request, apiKey).pumpTurn(this, stats)
                if (stats.producedAnyText) producedAnyText = true
                // 线路诊断：每轮事件统计（正文长度 / 工具调用数 / 是否失败）
                AiDiag.emit(
                    "ai_round",
                    mapOf(
                        "round" to rounds,
                        "tools" to toolsEnabled,
                        "textChars" to stats.turnText.length,
                        "calls" to (stats.requestedCalls?.size ?: 0),
                        "failed" to stats.failed
                    )
                )
                if (stats.failed) return@flow
                val calls = stats.requestedCalls
                if (calls.isNullOrEmpty()) {
                    // 无工具请求：本轮正常结束。全程零正文 = 流被提前掐断 / 工具分片被剥离 /
                    // 模型空响应的典型形态（工具结果已展示但回答缺失），转 Failure 让用户可重试，
                    // 绝不静默空收尾
                    if (stats.turnText.isBlank() && !producedAnyText) {
                        AiDiag.emit("ai_end", mapOf("reason" to "empty_as_failure"))
                        emit(StreamEvent.Failure(AiError.Unknown))
                    } else {
                        AiDiag.emit("ai_end", mapOf("reason" to "stop"))
                        emit(StreamEvent.MessageStop)
                    }
                    return@flow
                }
                if (!toolsEnabled) {
                    // 收尾轮（tools=null）模型仍请求工具：丢弃调用并回填本轮文本，
                    // 最多再追加一次强制作答提示；仍请求工具 → 有文本则收尾、零正文转 Failure
                    if (stats.turnText.isNotBlank()) {
                        transport += TransportMessage(role = ChatRole.ASSISTANT, text = stats.turnText.toString())
                    }
                    if (forceAttempts == 0) {
                        forceAttempts = 1
                        transport += TransportMessage(role = ChatRole.USER, text = FORCE_ANSWER_PROMPT_STRICT)
                        continue
                    }
                    emit(
                        if (producedAnyText) StreamEvent.MessageStop
                        else StreamEvent.Failure(AiError.Unknown)
                    )
                    AiDiag.emit(
                        "ai_end",
                        mapOf("reason" to "force_exhausted", "producedText" to producedAnyText)
                    )
                    return@flow
                }
                if (rounds > MAX_TOOL_ROUNDS || toolCallCount + calls.size > MAX_TOOL_CALLS) {
                    // 安全阀触顶：保留本轮已产出文本；仍有额度则执行额度内调用（尽量回填信息），
                    // 随后附提示以 tools=null 收尾请求
                    if (stats.turnText.isNotBlank()) {
                        transport += TransportMessage(role = ChatRole.ASSISTANT, text = stats.turnText.toString())
                    }
                    val remaining = MAX_TOOL_CALLS - toolCallCount
                    AiDiag.emit(
                        "ai_safety_valve",
                        mapOf("round" to rounds, "calls" to calls.size, "remaining" to remaining)
                    )
                    if (rounds <= MAX_TOOL_ROUNDS && remaining > 0) {
                        runTools(this, calls.take(remaining), "")
                    }
                    transport += TransportMessage(role = ChatRole.USER, text = FORCE_ANSWER_PROMPT)
                    toolsEnabled = false
                    continue
                }
                runTools(this, calls, stats.turnText.toString())
            }
        }

    /**
     * 历史 → 传输态（suspend：图片编码需读盘）：
     * - 只取终态消息（COMPLETE / CANCELED）；ERROR / STREAMING 排除
     * - USER：Text + 附件文档经 [ContextAssembler] 渲染为单条文本；附加图片逐张
     *   [AiImagePreparer.encodeForTransport] 编码（失败单张降级 `[图片: 名称]` 文本占位）；
     *   纯图无文本消息照常成条目
     * - ASSISTANT：先按 toolExchanges 展开往返（assistant(tool_calls) → tool 结果…），再接正文文本
     * - SYSTEM：system 条目直传（压缩摘要；空文本条目不进入传输）
     */
    private suspend fun buildTransport(history: List<ChatMessage>): List<TransportMessage> {
        val out = mutableListOf<TransportMessage>()
        history.filter {
            it.status == MessageStatus.COMPLETE || it.status == MessageStatus.CANCELED
        }.forEach { message ->
            when (message.role) {
                ChatRole.USER -> {
                    val text = ContextAssembler.renderUserText(message)
                    val images = mutableListOf<TransportImage>()
                    val parts = mutableListOf<String>()
                    if (text.isNotBlank()) parts += text
                    message.parts.filterIsInstance<ChatPart.Image>().forEach { image ->
                        val encoded = imagePreparer.encodeForTransport(image)
                        if (encoded != null) {
                            images += encoded
                        } else {
                            // 单张降级：读取失败 / 产物缺失 / 超限 → 文本占位，不影响其余图片
                            parts += "[图片: ${image.name}]"
                        }
                    }
                    val merged = parts.joinToString("\n")
                    if (merged.isNotBlank() || images.isNotEmpty()) {
                        out += TransportMessage(role = ChatRole.USER, text = merged, images = images)
                    }
                }
                ChatRole.ASSISTANT -> {
                    message.toolExchanges.forEach { exchange ->
                        out += TransportMessage(
                            role = ChatRole.ASSISTANT,
                            text = "",
                            toolCalls = exchange.calls
                        )
                        exchange.calls.forEachIndexed { index, call ->
                            out += TransportMessage(
                                role = ChatRole.TOOL,
                                text = exchange.results.getOrElse(index) { "" },
                                toolCallId = call.id
                            )
                        }
                    }
                    if (message.text.isNotBlank()) {
                        out += TransportMessage(role = ChatRole.ASSISTANT, text = message.text)
                    }
                }
                ChatRole.SYSTEM -> {
                    if (message.text.isNotBlank()) {
                        out += TransportMessage(role = ChatRole.SYSTEM, text = message.text)
                    }
                }
                // 仅传输态存在；历史组装不产生
                ChatRole.TOOL -> Unit
            }
        }
        return out
    }

    private companion object {
        /** 输出上限（§4.1：Anthropic max_tokens 必填，默认 4096）。 */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4096

        /** 安全阀：工具循环最大轮数。 */
        const val MAX_TOOL_ROUNDS = 8

        /** 安全阀：单次生成期间工具调用累计上限。 */
        const val MAX_TOOL_CALLS = 12

        /** 安全阀触顶收尾提示（作为用户消息追加）。 */
        const val FORCE_ANSWER_PROMPT = "请基于已获取的信息直接作答，不要再调用工具。"

        /** 收尾轮仍请求工具时的强制作答提示（最后一次机会）。 */
        const val FORCE_ANSWER_PROMPT_STRICT =
            "工具调用已被忽略。现在必须基于已有对话内容直接输出最终回答正文，不得再调用任何工具。"
    }
}

/**
 * 单轮事件统计（[pumpTurn] 填充）：正文累计 / 本轮是否产出正文 / 工具调用请求 / 是否失败。
 * [producedAnyText] 为单轮语义，调用方汇总到全程「产出过正文」标记。
 */
internal class TurnStats {
    val turnText = StringBuilder()
    var producedAnyText = false
    var requestedCalls: List<RawToolCall>? = null
    var failed = false
}

/**
 * 单轮事件泵：adapter 事件流 → 统计（[stats]）并转发到引擎输出收集器 [out]。
 * - TextDelta / ReasoningDelta / Failure：转发到 [out]（正文 / 思考 / 失败必须到达 VM）
 * - ToolCallRequested / MessageStop：拦截、不转发（前者由循环执行，后者由引擎统一补发终态）
 *
 * 警示（2026-10「有工具行、无回答」事故根因）：transformWhile 的 transform 内 emit
 * 只会发进其自身输出流，必须由下游 collect 显式转发到 [out]——空收集会把正文 /
 * 失败事件全部静默丢弃（工具状态行走 out.emit 直发，故仅表现「已读取文档但无回答」）。
 * 回归测试：ChatEngineTurnPumpTest。
 */
internal suspend fun Flow<StreamEvent>.pumpTurn(out: FlowCollector<StreamEvent>, stats: TurnStats) {
    transformWhile { event ->
        when (event) {
            is StreamEvent.TextDelta -> {
                stats.turnText.append(event.text)
                stats.producedAnyText = true
                emit(event)
                true
            }

            is StreamEvent.ReasoningDelta -> {
                emit(event)
                true
            }

            // 收到工具调用即主动停流（立即断开连接），进入本地执行
            is StreamEvent.ToolCallRequested -> {
                stats.requestedCalls = event.calls
                false
            }

            is StreamEvent.MessageStop -> false

            is StreamEvent.Failure -> {
                emit(event)
                stats.failed = true
                false
            }

            // ToolCallStarted / ToolCallCompleted 为 engine 自身产物，不会来自 adapter
            else -> true
        }
    }
        // 关键转发：transformWhile 输出 → 引擎输出收集器（正文 / 失败事件的唯一出口）
        .collect { out.emit(it) }
}
