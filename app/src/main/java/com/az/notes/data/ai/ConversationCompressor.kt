package com.az.notes.data.ai

import com.az.notes.data.ai.protocol.AiProtocolAdapter
import com.az.notes.data.ai.protocol.AnthropicAdapter
import com.az.notes.data.ai.protocol.ChatRequest
import com.az.notes.data.ai.protocol.OpenAiChatAdapter
import com.az.notes.data.ai.protocol.OpenAiResponsesAdapter
import com.az.notes.data.ai.protocol.TransportMessage
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.ContextBudget
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.PromptTemplates
import com.az.notes.domain.ai.StreamEvent
import com.az.notes.domain.ai.SystemNote
import com.az.notes.domain.ai.SystemNoteMode
import com.az.notes.domain.ai.TokenEstimator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 上下文压缩（§6）：三级策略——
 * 1. 摘要压缩（首选）：可压缩区转录 → COMPRESS_SYSTEM 独立指令非流式调用（静默收集正文）→
 *    SYSTEM 摘要消息置头（替换旧摘要）；失败 / 超时（90s）降级滑动窗口
 * 2. 滑动窗口降级：按轮从旧到新整条丢弃直至进入预算（旧摘要保留），不再超预算且确实丢弃过时
 *    记录 SYSTEM 条目（[SystemNoteMode.TRIMMED] + 丢弃条数）
 * 3. 仍超预算：文档截断（§6.3，头 80% + 标记 + 尾 10%）
 *
 * 保护规则（§6.3）：最后 2 个 USER 消息起为保护区；压缩以**整条** [ChatMessage]（含
 * toolExchanges）为移除粒度——天然保证 tool_use / tool_result 配对与 Anthropic 角色交替
 * 不被拆断。非终态消息（STREAMING / ERROR）不参与估算也不被移除（[CompressionRules.mergeCompressed]
 * 原位保留），发射中的占位消息因此安然渡过压缩替换。
 */
@Singleton
class ConversationCompressor @Inject constructor(
    private val keyStore: AiKeyStore,
    private val openAiChat: OpenAiChatAdapter,
    private val openAiResponses: OpenAiResponsesAdapter,
    private val anthropic: AnthropicAdapter
) {

    /** 压缩结果：压缩后的完整会话列表（含原位保留的非终态消息）+ 方式 + 移除条数。 */
    data class CompressOutcome(
        val messages: List<ChatMessage>,
        val mode: SystemNoteMode,
        val removedCount: Int
    )

    /** 可压缩消息条数（保护区之前的非摘要消息数；0 = 无压缩空间——UI 以此置灰按钮）。 */
    fun compressibleCount(history: List<ChatMessage>): Int {
        val terminal = history.filter { CompressionRules.isTerminalStatus(it.status) }
        val from = CompressionRules.protectedFrom(terminal)
        if (from <= 0) return 0
        return terminal.take(from).count { it.role != ChatRole.SYSTEM }
    }

    /**
     * 检查并按需压缩。
     *
     * @param force true = 无论本地估算是否超预算都尝试（手动入口 / ContextOverflow 重试）；
     *              false = 未超预算返回 null（自动前置检查）
     * @return null = 无需 / 无法压缩（未超预算、无可压缩区、摘要失败且无预算压力）
     */
    suspend fun compressIfNeeded(
        history: List<ChatMessage>,
        provider: AiProvider,
        model: AiModel,
        force: Boolean = false
    ): CompressOutcome? {
        val terminal = history.filter { CompressionRules.isTerminalStatus(it.status) }
        val budget = ContextBudget(model.contextWindow ?: ContextBudget.DEFAULT_WINDOW)
        if (!force && CompressionRules.estimateTokens(terminal) <= budget.usable) return null
        val from = CompressionRules.protectedFrom(terminal)
        if (from <= 0) return null
        val head = terminal.take(from)
        val protectedTail = terminal.drop(from)

        // 一级：摘要压缩（独立指令 + 单条 USER 转录、无工具；失败 / 超时降级）
        val summary = try {
            withTimeout(SUMMARY_TIMEOUT_MS) { requestSummary(provider, model, head) }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!summary.isNullOrBlank()) {
            // 仅统计压缩区（保护区不动；旧摘要为替换而非移除，不计入）
            val removed = head.count { it.role != ChatRole.SYSTEM }
            val summaryMessage = summaryMessage(summary, removed)
            val compressed = listOf(summaryMessage) + protectedTail
            return CompressOutcome(
                messages = CompressionRules.mergeCompressed(history, compressed),
                mode = SystemNoteMode.SUMMARIZED,
                removedCount = removed
            )
        }

        // 二级：滑动窗口降级（保留旧摘要；按轮从旧到新丢弃）
        var keptHead = head
        var droppedCount = 0
        while (CompressionRules.estimateTokens(keptHead + protectedTail) > budget.usable) {
            val next = CompressionRules.dropOldestRound(keptHead) ?: break
            droppedCount += keptHead.size - next.size
            keptHead = next
        }
        if (droppedCount == 0) return null

        // 三级：仍超预算 → 文档截断
        val rebuilt = CompressionRules.truncateDocumentsToBudget(
            keptHead + trimMessage(droppedCount) + protectedTail,
            budget.usable
        )
        return CompressOutcome(
            messages = CompressionRules.mergeCompressed(history, rebuilt),
            mode = SystemNoteMode.TRIMMED,
            removedCount = droppedCount
        )
    }

    // ---------------------------------------------------------------- 内部

    /** 摘要调用：按协议选 adapter，独立系统指令 + 单条 USER 转录，非流式静默收集正文。 */
    private suspend fun requestSummary(
        provider: AiProvider,
        model: AiModel,
        compressible: List<ChatMessage>
    ): String? {
        val apiKey = keyStore.getKey(provider.id)
        if (apiKey.isEmpty()) return null
        val transcript = CompressionRules.renderTranscript(compressible)
        if (transcript.isBlank()) return null
        val adapter: AiProtocolAdapter = when (provider.protocol) {
            AiProtocol.OPENAI_CHAT -> openAiChat
            AiProtocol.OPENAI_RESPONSES -> openAiResponses
            AiProtocol.ANTHROPIC_MESSAGES -> anthropic
        }
        val request = ChatRequest(
            provider = provider,
            model = model,
            systemPrompt = PromptTemplates.COMPRESS_SYSTEM,
            messages = listOf(TransportMessage(role = ChatRole.USER, text = transcript)),
            maxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS
        )
        val text = StringBuilder()
        var failed = false
        adapter.stream(request, apiKey).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> text.append(event.text)
                is StreamEvent.Failure -> failed = true
                else -> Unit
            }
        }
        if (failed || text.isBlank()) return null
        return text.toString().trim().ifBlank { null }
    }

    private fun summaryMessage(summary: String, removed: Int): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = ChatRole.SYSTEM,
        parts = listOf(ChatPart.Text(summary)),
        status = MessageStatus.COMPLETE,
        timestamp = System.currentTimeMillis(),
        systemNote = SystemNote(SystemNoteMode.SUMMARIZED, removed)
    )

    /** 滑动窗口记录条目：空文本（不进入传输），仅携带 [SystemNoteMode.TRIMMED] 元信息。 */
    private fun trimMessage(count: Int): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = ChatRole.SYSTEM,
        parts = emptyList(),
        status = MessageStatus.COMPLETE,
        timestamp = System.currentTimeMillis(),
        systemNote = SystemNote(SystemNoteMode.TRIMMED, count)
    )

    private companion object {
        /** 摘要调用超时（§6.2）。 */
        const val SUMMARY_TIMEOUT_MS = 90_000L

        /** 摘要输出上限（与对话输出一致）。 */
        const val SUMMARY_MAX_OUTPUT_TOKENS = 4096
    }
}

/**
 * 压缩纯逻辑（internal，供单测）：保护区计算 / 预算估算 / 按轮丢弃 / 文档截断 / 转录渲染 /
 * 结果合并。全部为无副作用函数，不依赖 Android 与协程。
 */
internal object CompressionRules {

    /** 每张附加图片的 token 估算（§6.1：图片按视觉 token 预算粗估）。 */
    private const val IMAGE_TOKENS = 1500

    /** 文档截断的最小长度（低于此值截断无收益，且「80%+标记+10%」可能反而变长）。 */
    private const val TRUNCATE_MIN_CHARS = 500

    /** 文档截断标记。 */
    private const val TRUNCATE_MARK = "\n\n[…已截断…]\n\n"

    /** 终态判定：仅 COMPLETE / CANCELED 参与传输与压缩（ERROR / STREAMING 排除）。 */
    fun isTerminalStatus(status: MessageStatus): Boolean =
        status == MessageStatus.COMPLETE || status == MessageStatus.CANCELED

    /**
     * 保护区起点：倒数第 2 个 USER 消息的下标（含）；不足 2 个 USER 消息返回 0。
     * 压缩边界永远对齐 USER 消息前，「整条消息」粒度保证工具配对不被拆断。
     */
    fun protectedFrom(messages: List<ChatMessage>): Int {
        var seen = 0
        for (i in messages.indices.reversed()) {
            if (messages[i].role == ChatRole.USER) {
                seen++
                if (seen == 2) return i
            }
        }
        return 0
    }

    /** 总估算（tokens）：系统提示词 + 各消息（USER 经 [ContextAssembler] 渲染；图片按张计）。 */
    fun estimateTokens(
        messages: List<ChatMessage>,
        systemPrompt: String = PromptTemplates.CHAT_SYSTEM
    ): Int = TokenEstimator.estimate(systemPrompt) + messages.sumOf { estimateMessage(it) }

    /** 单条消息估算：USER 渲染全文；ASSISTANT 正文 + 工具往返（参数与结果）；SYSTEM 正文。 */
    fun estimateMessage(message: ChatMessage): Int {
        var tokens = when (message.role) {
            ChatRole.USER -> TokenEstimator.estimate(ContextAssembler.renderUserText(message))

            ChatRole.ASSISTANT -> TokenEstimator.estimate(message.text) +
                message.toolExchanges.sumOf { exchange ->
                    exchange.calls.sumOf { TokenEstimator.estimate(it.argumentsJson) } +
                        exchange.results.sumOf { TokenEstimator.estimate(it) }
                }

            ChatRole.SYSTEM -> TokenEstimator.estimate(message.text)

            ChatRole.TOOL -> 0
        }
        tokens += message.parts.count { it is ChatPart.Image } * IMAGE_TOKENS
        return tokens
    }

    /**
     * 丢最旧一轮：头部 SYSTEM（旧摘要）不动，从第一个 USER 起丢到下一个 USER 之前；
     * 无可丢轮返回 null。
     */
    fun dropOldestRound(head: List<ChatMessage>): List<ChatMessage>? {
        val firstUser = head.indexOfFirst { it.role == ChatRole.USER }
        if (firstUser < 0) return null
        val nextUser = head.drop(firstUser + 1).indexOfFirst { it.role == ChatRole.USER }
        val end = if (nextUser < 0) head.size else firstUser + 1 + nextUser
        return head.take(firstUser) + head.drop(end)
    }

    /**
     * 文档截断（§6.3）：从最旧消息起逐个将 Document 内容替换为 头 80% + 标记 + 尾 10%，
     * 每替换一处即重新估算，直至进入预算或无文档可截。
     */
    fun truncateDocumentsToBudget(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        var current = messages
        while (estimateTokens(current) > budgetTokens) {
            var changed = false
            for (i in current.indices) {
                val message = current[i]
                val index = message.parts.indexOfFirst { part ->
                    part is ChatPart.Document && isTruncatable(part.content)
                }
                if (index < 0) continue
                val part = message.parts[index] as ChatPart.Document
                val newParts = message.parts.toMutableList().also {
                    it[index] = part.copy(content = truncateDocument(part.content))
                }
                current = current.toMutableList().also { it[i] = message.copy(parts = newParts) }
                changed = true
                break
            }
            if (!changed) break
        }
        return current
    }

    /** 单文档截断：头 80% + 标记 + 尾 10%（已截断或过短不再处理）。 */
    fun truncateDocument(content: String): String {
        if (!isTruncatable(content)) return content
        val head = (content.length * 0.8).toInt()
        val tail = (content.length * 0.1).toInt()
        return content.take(head) + TRUNCATE_MARK + content.takeLast(tail)
    }

    private fun isTruncatable(content: String): Boolean =
        content.length > TRUNCATE_MIN_CHARS && !content.contains(TRUNCATE_MARK)

    /** 可压缩区 → 摘要输入转录（USER 渲染 / AI 正文 / 旧摘要 / 工具轨迹行；图片以名称占位）。 */
    fun renderTranscript(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            when (message.role) {
                ChatRole.USER -> {
                    append("用户：").append(ContextAssembler.renderUserText(message))
                    message.parts.filterIsInstance<ChatPart.Image>().forEach { image ->
                        append("（附图片：").append(image.name).append("）")
                    }
                    append('\n')
                }

                ChatRole.ASSISTANT -> {
                    if (message.text.isNotBlank()) {
                        append("AI：").append(message.text).append('\n')
                    }
                    if (message.toolExchanges.isNotEmpty()) {
                        append("AI：[工具调用] ")
                        message.toolExchanges.forEach { exchange ->
                            exchange.calls.forEach { call -> append(call.name).append(' ') }
                        }
                        append('\n')
                    }
                }

                ChatRole.SYSTEM -> if (message.text.isNotBlank()) {
                    append("此前摘要：").append(message.text).append('\n')
                }

                ChatRole.TOOL -> Unit
            }
        }
    }.trim()

    /**
     * 压缩结果合并回原历史：终态槽位按序装填 [compressed]，非终态消息（STREAMING / ERROR）
     * 原位保留；压缩结果多于终态槽位时追加到尾部（防御，正常不出现）。
     */
    fun mergeCompressed(
        original: List<ChatMessage>,
        compressed: List<ChatMessage>
    ): List<ChatMessage> {
        val queue = ArrayDeque(compressed)
        val result = ArrayList<ChatMessage>(original.size)
        original.forEach { message ->
            if (isTerminalStatus(message.status)) {
                queue.removeFirstOrNull()?.let { result += it }
            } else {
                result += message
            }
        }
        while (queue.isNotEmpty()) result += queue.removeFirst()
        return result
    }
}
