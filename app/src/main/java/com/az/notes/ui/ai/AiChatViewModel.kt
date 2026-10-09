package com.az.notes.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.ai.AiChatSession
import com.az.notes.data.ai.AiProviderRepository
import com.az.notes.data.ai.ChatEngine
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.ai.AiError
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.MessageStatus
import com.az.notes.domain.ai.StreamEvent
import com.az.notes.domain.ai.ToolCallRecord
import com.az.notes.domain.model.FileNode
import com.az.notes.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前选择的「供应商 + 模型」（id 引用；显示与请求时按 providers 列表解析）。 */
data class ChatSelection(val providerId: String, val modelId: String)

/** 错误条状态：关联消息 id + 本地化文案 + 是否附「去设置」（401/404 配置类错误）。 */
data class ChatErrorState(val messageId: String, val text: UiText, val showSettings: Boolean)

/** 文档选择器条目：节点 + 正文预览（列表页同款结构；预览分批并行读取）。 */
data class DocPickerItem(val node: FileNode, val preview: String = "")

/**
 * 对话页 ViewModel（B3 工具循环版）：
 * - 会话消息由 [AiChatSession]（@Singleton 内存态）透出：退出页面重进保留，杀进程即丢
 * - 发送管线：追加 USER（文本 + 待发附件） + ASSISTANT(STREAMING) → ChatEngine.stream →
 *   TextDelta 50ms 节流合并 → MessageStop → COMPLETE；Failure → ERROR（错误条）；stop → CANCELED（空内容移除）
 * - 工具循环：ToolCallStarted → toolStatus 状态行；ToolCallCompleted → 轨迹 / 传输态回写消息
 * - 历史组装由 ChatEngine 完成（仅 COMPLETE / CANCELED 参与，ERROR 排除）
 * - 文档附件（B3）：选择器打开时扫描仓库；待发附件随发送并入用户消息
 * - 模型选择：默认按供应商 lastModelId 恢复；切换即写回记忆（repository.update）
 */
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val repository: AiProviderRepository,
    private val session: AiChatSession,
    private val engine: ChatEngine,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** 会话消息（内存态；退出页面重进保留）。 */
    val messages: StateFlow<List<ChatMessage>> = session.messages

    /** 供应商列表（模型选择菜单数据源）。 */
    val providers: StateFlow<List<AiProvider>> = repository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 首次回流完成（避免「无供应商」引导在数据到达前一闪而过）。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 输入框文本（编辑重发需回填，故由 VM 持有）。 */
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** 生成中（发送按钮变停止）。 */
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** 当前选择（null = 无可用供应商 / 模型 → 空态显示配置引导）。 */
    private val _selection = MutableStateFlow<ChatSelection?>(null)
    val selection: StateFlow<ChatSelection?> = _selection.asStateFlow()

    /** 当前错误条（null = 无；生成失败时指向对应 AI 消息）。 */
    private val _error = MutableStateFlow<ChatErrorState?>(null)
    val error: StateFlow<ChatErrorState?> = _error.asStateFlow()

    /** 新会话二次确认（有消息时点 ⊕ 弹出）。 */
    private val _showNewSessionConfirm = MutableStateFlow(false)
    val showNewSessionConfirm: StateFlow<Boolean> = _showNewSessionConfirm.asStateFlow()

    /** 待发附件（会话级；发送后 / 新会话时清空）。 */
    val pendingAttachments: StateFlow<List<ChatPart.Document>> = session.pendingAttachments

    /** 工具执行状态行（null = 无；ToolCallStarted 置入，Completed / 流结束清空）。 */
    private val _toolStatus = MutableStateFlow<List<ToolCallRecord>?>(null)
    val toolStatus: StateFlow<List<ToolCallRecord>?> = _toolStatus.asStateFlow()

    /** 文档选择器条目（null = 未打开；打开期间先置空列表再 IO 填充）。 */
    private val _docPickerItems = MutableStateFlow<List<DocPickerItem>?>(null)
    val docPickerItems: StateFlow<List<DocPickerItem>?> = _docPickerItems.asStateFlow()

    /** 文档选择器搜索词（过滤在 VM，预览随当前过滤视图分批读取）。 */
    private val _docPickerQuery = MutableStateFlow("")
    val docPickerQuery: StateFlow<String> = _docPickerQuery.asStateFlow()

    /** 文档选择器是否还有未装载预览的后续条目（滚动加载指示）。 */
    private val _docPickerHasMore = MutableStateFlow(false)
    val docPickerHasMore: StateFlow<Boolean> = _docPickerHasMore.asStateFlow()

    /** 文档选择器装载中（首次扫描 / 搜索过滤期间；空列表时区分「加载中」与「无结果」）。 */
    private val _docPickerLoading = MutableStateFlow(false)
    val docPickerLoading: StateFlow<Boolean> = _docPickerLoading.asStateFlow()

    private var streamJob: Job? = null

    /** 流式中的 AI 消息 id（onCleared 兜底收尾用）。 */
    private var streamingMessageId: String? = null

    /** 选择器全量节点（null = 首次扫描未完成；完成后为修改时间倒序）。 */
    private var docPickerAll: List<FileNode>? = null

    /** 当前视图尚未装载预览的剩余节点（滚动续载）。 */
    private var docPickerPending: List<FileNode> = emptyList()

    /** 选择器装载世代：搜索词变化 / 关闭重开时旧批次作废（对齐列表页 listingGeneration 先例）。 */
    private var docPickerGeneration = 0
    private var docPickerLoadJob: Job? = null

    init {
        viewModelScope.launch {
            val list = repository.providers.first()
            _selection.value = defaultSelection(list)
            _loaded.value = true
            // 重进恢复：末尾为 ERROR 消息时用通用文案恢复错误条（保留重试能力）
            val last = session.messages.value.lastOrNull()
            if (last != null && last.status == MessageStatus.ERROR) {
                _error.value = ChatErrorState(
                    messageId = last.id,
                    text = UiText.of(R.string.ai_chat_error_unknown),
                    showSettings = false
                )
            }
        }
    }

    // ---------------------------------------------------------------- 输入与发送

    fun setInput(value: String) {
        _input.value = value
    }

    /** 发送：追加用户消息（文本 + 待发附件）→ 开启一条新的 AI 流式消息。 */
    fun send() {
        if (_generating.value) return
        val provider = currentProvider() ?: return
        val model = currentModel(provider) ?: return
        val text = _input.value.trim()
        val attachments = session.consumePendingAttachments()
        if (text.isEmpty() && attachments.isEmpty()) return
        _input.value = ""
        _error.value = null
        val parts = mutableListOf<ChatPart>()
        if (text.isNotEmpty()) parts.add(ChatPart.Text(text))
        parts.addAll(attachments)
        session.append(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatRole.USER,
                parts = parts,
                status = MessageStatus.COMPLETE,
                timestamp = System.currentTimeMillis()
            )
        )
        launchStream(provider, model)
    }

    /** 停止生成：取消流协程；收尾（flush / 置 CANCELED / 空内容移除）在协程取消路径完成。 */
    fun stop() {
        if (!_generating.value) return
        streamJob?.cancel()
    }

    /** 重新生成（错误条「重试」与操作行「重新生成」共用）：截断该 AI 条及之后重发。 */
    fun regenerate(messageId: String) {
        if (_generating.value) return
        val message = session.messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatRole.ASSISTANT) return
        val provider = currentProvider() ?: return
        val model = currentModel(provider) ?: return
        session.truncateFrom(messageId)
        _error.value = null
        launchStream(provider, model)
    }

    /** 编辑重发：回填输入框与已发送文档（附回对话框）并截断该用户条及之后。 */
    fun editResend(messageId: String) {
        if (_generating.value) return
        val message = session.messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatRole.USER) return
        _input.value = message.text
        // 文档附回待发区（沿用消息内的内容快照；去重）
        session.restorePendingAttachments(message.parts.filterIsInstance<ChatPart.Document>())
        session.truncateFrom(messageId)
        _error.value = null
    }

    // ---------------------------------------------------------------- 文档附件

    /** 单文档加入待发附件（失败 / 重复静默）。 */
    fun attachDocument(absolutePath: String) {
        viewModelScope.launch { session.attachDocument(absolutePath) }
    }

    /** 移除待发附件（按仓库相对路径）。 */
    fun removePendingAttachment(vaultRelPath: String) {
        session.removePendingAttachment(vaultRelPath)
    }

    /** 选择器确认：批量加入（IO 逐个读取；失败 / 重复跳过）。 */
    fun confirmDocumentSelection(paths: List<String>) {
        if (paths.isEmpty()) {
            closeDocumentPicker()
            return
        }
        viewModelScope.launch {
            paths.forEach { session.attachDocument(it) }
            closeDocumentPicker()
        }
    }

    /**
     * 打开文档选择器：先置空列表（加载中），IO 扫描仓库 Markdown——按修改时间倒序
     * （最新在前，与主页默认排序一致）——首批并行读预览后填充，其余滚动加载。
     */
    fun openDocumentPicker() {
        if (_docPickerItems.value != null) return
        _docPickerItems.value = emptyList()
        _docPickerLoading.value = true
        _docPickerQuery.value = ""
        docPickerAll = null
        docPickerPending = emptyList()
        docPickerGeneration++
        val generation = docPickerGeneration
        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                val vaultPath = runCatching {
                    settingsRepository.settings.first().vaultPath
                }.getOrNull()
                vaultPath?.let { path ->
                    vaultRepository.scanTree(path)
                        .filter { it.isMarkdown }
                        .sortedByDescending { it.lastModified }
                } ?: emptyList()
            }
            if (generation != docPickerGeneration || _docPickerItems.value == null) return@launch
            docPickerAll = nodes
            applyDocPickerView(generation)
        }
    }

    /** 选择器搜索词更新：按文件名 / 相对路径过滤后重新装载首批预览。 */
    fun setDocPickerQuery(query: String) {
        if (_docPickerQuery.value == query) return
        _docPickerQuery.value = query
        if (_docPickerItems.value == null) return
        // 首次扫描未完成：仅记录查询词，扫描完成后按最新词统一装载（避免空数据出图）
        if (docPickerAll == null) return
        _docPickerLoading.value = true
        docPickerGeneration++
        val generation = docPickerGeneration
        viewModelScope.launch { applyDocPickerView(generation) }
    }

    /**
     * 应用当前过滤视图：过滤全量节点 → 首批预览并行读取（只读文件头，列表页同款
     * [VaultRepository.readPreview]）→ 提交 UI；其余由 [loadMoreDocPicker] 滚动加载。
     */
    private suspend fun applyDocPickerView(generation: Int) {
        val all = docPickerAll ?: return
        val keyword = _docPickerQuery.value.trim()
        val filtered = if (keyword.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                    it.relativePath.contains(keyword, ignoreCase = true)
            }
        }
        val previewChars = currentPreviewChars()
        val firstBatch = filtered.take(DOC_PICKER_PAGE_SIZE)
        val items = withContext(Dispatchers.IO) {
            firstBatch.map { node ->
                async {
                    DocPickerItem(
                        node = node,
                        preview = vaultRepository.readPreview(node.absolutePath, previewChars)
                    )
                }
            }.awaitAll()
        }
        if (generation != docPickerGeneration || _docPickerItems.value == null) return
        docPickerPending = filtered.drop(DOC_PICKER_PAGE_SIZE)
        _docPickerItems.value = items
        _docPickerHasMore.value = docPickerPending.isNotEmpty()
        _docPickerLoading.value = false
    }

    /** 滚动接近末尾时加载下一批（含预览并行读取；仅追加，避免已显示条目闪动）。 */
    fun loadMoreDocPicker() {
        val remaining = docPickerPending
        if (remaining.isEmpty() || docPickerLoadJob?.isActive == true) return
        val generation = docPickerGeneration
        docPickerLoadJob = viewModelScope.launch {
            val batch = remaining.take(DOC_PICKER_PAGE_SIZE)
            val previewChars = currentPreviewChars()
            val newItems = withContext(Dispatchers.IO) {
                batch.map { node ->
                    async {
                        DocPickerItem(
                            node = node,
                            preview = vaultRepository.readPreview(node.absolutePath, previewChars)
                        )
                    }
                }.awaitAll()
            }
            if (generation != docPickerGeneration || _docPickerItems.value == null) return@launch
            docPickerPending = remaining.drop(DOC_PICKER_PAGE_SIZE)
            _docPickerItems.update { current -> current?.plus(newItems) }
            _docPickerHasMore.value = docPickerPending.isNotEmpty()
        }
    }

    /** 预览字符数设置（读取失败时回退默认值，与 AppSettings.previewChars 默认一致）。 */
    private suspend fun currentPreviewChars(): Int =
        runCatching { settingsRepository.settings.first().previewChars }
            .getOrDefault(DEFAULT_PREVIEW_CHARS)

    fun closeDocumentPicker() {
        docPickerGeneration++
        docPickerAll = null
        docPickerPending = emptyList()
        _docPickerQuery.value = ""
        _docPickerItems.value = null
        _docPickerHasMore.value = false
        _docPickerLoading.value = false
    }

    // ---------------------------------------------------------------- 会话与模型

    /** ⊕ 新会话：有消息时弹二次确认（空会话无需确认）。 */
    fun requestNewSession() {
        if (session.messages.value.isEmpty()) return
        _showNewSessionConfirm.value = true
    }

    fun confirmNewSession() {
        _showNewSessionConfirm.value = false
        stop()
        session.clear()
        _error.value = null
    }

    fun dismissNewSession() {
        _showNewSessionConfirm.value = false
    }

    /** 切换模型：更新选择并写回供应商 lastModelId（下次进入恢复）。 */
    fun switchModel(providerId: String, modelId: String) {
        val provider = providers.value.firstOrNull { it.id == providerId } ?: return
        if (provider.models.none { it.id == modelId }) return
        _selection.value = ChatSelection(providerId, modelId)
        viewModelScope.launch {
            repository.update(providerId) { it.copy(lastModelId = modelId) }
        }
    }

    // ---------------------------------------------------------------- 流式管线

    /** 追加 AI 流式消息并启动生成。 */
    private fun launchStream(provider: AiProvider, model: AiModel) {
        val assistantId = UUID.randomUUID().toString()
        session.append(
            ChatMessage(
                id = assistantId,
                role = ChatRole.ASSISTANT,
                parts = emptyList(),
                status = MessageStatus.STREAMING,
                timestamp = System.currentTimeMillis(),
                modelLabel = "${provider.name} · ${model.label}"
            )
        )
        streamingMessageId = assistantId
        _generating.value = true
        streamJob = viewModelScope.launch { runStream(provider, model, assistantId) }
    }

    /**
     * 流式收集：TextDelta / ReasoningDelta 写入缓冲，由 50ms ticker 合并更新
     * （§9.3 流式渲染节流）；流结束 / 取消 / 失败前强制 flush。
     */
    private suspend fun runStream(provider: AiProvider, model: AiModel, assistantId: String) =
        coroutineScope {
            val textBuffer = StringBuilder()
            val reasoningBuffer = StringBuilder()
            var textDirty = false
            var reasoningDirty = false
            var failure: AiError? = null
            // 是否执行过工具（零内容兜底判定用：有轨迹不算「空」）
            var toolCompleted = false

            fun flush() {
                if (!textDirty && !reasoningDirty) return
                textDirty = false
                reasoningDirty = false
                val text = textBuffer.toString()
                val reasoning = reasoningBuffer.toString().ifEmpty { null }
                session.updateMessage(assistantId) {
                    it.copy(parts = listOf(ChatPart.Text(text)), reasoning = reasoning)
                }
            }

            val ticker = launch {
                while (isActive) {
                    delay(STREAM_FLUSH_INTERVAL_MS)
                    flush()
                }
            }
            try {
                engine.stream(provider, model, session.messages.value).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            textBuffer.append(event.text)
                            textDirty = true
                        }

                        is StreamEvent.ReasoningDelta -> {
                            reasoningBuffer.append(event.text)
                            reasoningDirty = true
                        }

                        is StreamEvent.Failure -> failure = event.error

                        // 终态事件：状态流转在流结束后统一处理
                        StreamEvent.MessageStop -> Unit

                        // 工具循环状态事件（ToolCallRequested 为 engine 内部消化信号）
                        is StreamEvent.ToolCallStarted -> _toolStatus.value = event.records

                        is StreamEvent.ToolCallCompleted -> {
                            toolCompleted = true
                            _toolStatus.value = null
                            session.updateMessage(assistantId) { message ->
                                message.copy(
                                    toolTrail = message.toolTrail + event.records,
                                    toolExchanges = message.toolExchanges + event.exchange
                                )
                            }
                        }

                        is StreamEvent.ToolCallRequested -> Unit
                    }
                }
                flush()
                val error = failure
                // 零内容兜底：无正文 / 无思考 / 无工具轨迹 = 中转站静默空响应（渲染为零高
                // 不可见气泡），统一收敛为错误条保留重试能力，绝不留静默空泡
                val empty = textBuffer.isEmpty() && reasoningBuffer.isEmpty() && !toolCompleted
                if (error == null && !empty) {
                    session.updateMessage(assistantId) { it.copy(status = MessageStatus.COMPLETE) }
                } else {
                    session.updateMessage(assistantId) { it.copy(status = MessageStatus.ERROR) }
                    val (text, showSettings) = error?.let { errorDisplay(it) }
                        ?: (UiText.of(R.string.ai_chat_error_empty) to false)
                    _error.value = ChatErrorState(assistantId, text, showSettings)
                }
            } catch (e: CancellationException) {
                flush()
                // 真实取消（用户停止 / 退页）：CANCELED 收尾、空泡移除；伪取消（内部中止
                // 异常泄漏等、自身 Job 仍活跃）：按失败收敛，绝不静默删泡
                if (coroutineContext[Job]?.isCancelled == true) {
                    finalizeCanceled(assistantId)
                    throw e
                }
                session.updateMessage(assistantId) { it.copy(status = MessageStatus.ERROR) }
                _error.value = ChatErrorState(
                    messageId = assistantId,
                    text = UiText.of(R.string.ai_chat_error_unknown),
                    showSettings = false
                )
            } catch (e: Exception) {
                flush()
                session.updateMessage(assistantId) { it.copy(status = MessageStatus.ERROR) }
                _error.value = ChatErrorState(
                    messageId = assistantId,
                    text = UiText.of(R.string.ai_chat_error_unknown),
                    showSettings = false
                )
            } finally {
                ticker.cancel()
                _toolStatus.value = null
                _generating.value = false
                streamingMessageId = null
            }
        }

    /** 取消收尾：有内容 → CANCELED；完全为空（正文与思考都无）→ 移除气泡。 */
    private fun finalizeCanceled(messageId: String) {
        val message = session.messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.text.isEmpty() && message.reasoning.isNullOrEmpty()) {
            session.removeMessage(messageId)
        } else {
            session.updateMessage(messageId) { it.copy(status = MessageStatus.CANCELED) }
        }
    }

    override fun onCleared() {
        // 生成中退出：viewModelScope 取消 → runStream 取消路径完成收尾；
        // 此处兜底（协程未及收尾时），按当前内容置 CANCELED / 移除空消息。
        val id = streamingMessageId
        if (id != null &&
            session.messages.value.any { it.id == id && it.status == MessageStatus.STREAMING }
        ) {
            finalizeCanceled(id)
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 默认选择：首个有模型的供应商；模型优先该供应商的 lastModelId，否则第一个。 */
    private fun defaultSelection(list: List<AiProvider>): ChatSelection? {
        val provider = list.firstOrNull { it.models.isNotEmpty() } ?: return null
        val model = provider.models.firstOrNull { it.id == provider.lastModelId }
            ?: provider.models.first()
        return ChatSelection(provider.id, model.id)
    }

    private fun currentProvider(): AiProvider? {
        val ref = _selection.value ?: return null
        return providers.value.firstOrNull { it.id == ref.providerId }
    }

    private fun currentModel(provider: AiProvider): AiModel? {
        val ref = _selection.value ?: return null
        return provider.models.firstOrNull { it.id == ref.modelId }
    }

    companion object {
        /** 流式渲染节流窗口（§9.3：50ms 缓冲合并后再更新 StateFlow）。 */
        private const val STREAM_FLUSH_INTERVAL_MS = 50L

        /** 文档选择器每批装载数（首批预览并行读、其余滚动加载；对齐列表页分页先例）。 */
        private const val DOC_PICKER_PAGE_SIZE = 60

        /** 预览字符数兜底默认值（与 AppSettings.previewChars 默认一致）。 */
        private const val DEFAULT_PREVIEW_CHARS = 100

        /** AiError → 错误条文案 + 是否附「去设置」（§4.4 / §9.3：401/404 为配置类错误）。 */
        fun errorDisplay(error: AiError): Pair<UiText, Boolean> = when (error) {
            AiError.Unauthorized -> UiText.of(R.string.ai_chat_error_unauthorized) to true
            AiError.NotFound -> UiText.of(R.string.ai_chat_error_not_found) to true
            AiError.InsufficientBalance ->
                UiText.of(R.string.ai_chat_error_insufficient_balance) to false

            AiError.RateLimited -> UiText.of(R.string.ai_chat_error_rate_limited) to false
            AiError.Server -> UiText.of(R.string.ai_chat_error_server) to false
            AiError.ContextOverflow -> UiText.of(R.string.ai_chat_error_context_overflow) to false
            AiError.Network -> UiText.of(R.string.ai_chat_error_network) to false
            is AiError.Api -> UiText.of(R.string.ai_chat_error_api, error.message) to false
            AiError.Unknown -> UiText.of(R.string.ai_chat_error_unknown) to false
        }
    }
}
