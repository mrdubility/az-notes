package com.az.notes.ui.ai

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.ai.AiChatSession
import com.az.notes.data.ai.AiImagePreparer
import com.az.notes.data.ai.AiProviderRepository
import com.az.notes.data.ai.ChatEngine
import com.az.notes.data.ai.ConversationCompressor
import com.az.notes.data.ai.ConversationExporter
import com.az.notes.data.ai.ImagePrepFailure
import com.az.notes.data.ai.ImagePrepResult
import com.az.notes.data.media.AttachmentRepository
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
import com.az.notes.domain.ai.SystemNoteMode
import com.az.notes.domain.ai.ToolCallRecord
import com.az.notes.domain.markdown.ImageReference
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.FileNode
import com.az.notes.ui.common.UiText
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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
import kotlinx.coroutines.flow.combine
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

/** 图片选择条目（文档取图 / 软件图库共用）：来源标签（文档名或仓库相对路径）+ 文件 + 体积。 */
data class DocImageItem(val sourceLabel: String, val file: File, val name: String, val sizeBytes: Long)

/** 图片选择对话框来源：文档取图（解析会话文档图片引用）/ 软件图库（仓库全部图片）。 */
enum class ImagePickSource { DOCUMENT, GALLERY }

/** 导出完成数据（Snackbar「已导出到…」+「查看」动作跳预览页；locationLabel null = 仓库根）。 */
data class ExportDone(val fileName: String, val absolutePath: String, val locationLabel: String?)

/** 导出目标文件夹选择器状态（targets = null 表示目录扫描中；含仓库根与全部子目录）。 */
data class ExportFolderPickerState(val vaultPath: String, val targets: List<FileNode>?)

/**
 * 对话页 ViewModel：
 * - 会话消息由 [AiChatSession]（@Singleton 内存态）透出：退出页面重进保留，杀进程即丢
 * - 发送管线：隐私一次性告知（未确认先弹框，§11.3）→ 追加 USER（文本 + 待发附件 + 待发图片）+ 
 *   ASSISTANT(STREAMING) → 前置自动压缩（§6.1）→ ChatEngine.stream → TextDelta 50ms 节流合并 →
 *   MessageStop → COMPLETE；Failure → ERROR（错误条）；stop → CANCELED（空内容移除）
 * - 压缩（§6）：自动前置 + 手动按钮（防重入）+ ContextOverflow 强制压缩后重试一次
 * - 图片（§7）：相册 / 文档取图两条来源，[AiImagePreparer] 统一预处理
 * - 导出（§8）：[ConversationExporter] 写所选目录（默认仓库根）+ 调度保存同步 + Snackbar「查看」跳预览
 * - 工具循环：ToolCallStarted → toolStatus 状态行；ToolCallCompleted → 轨迹 / 传输态回写消息
 * - 历史组装由 ChatEngine 完成（仅 COMPLETE / CANCELED 参与，ERROR 排除）
 * - 模型选择：默认按供应商 lastModelId 恢复；切换即写回记忆（repository.update）
 */
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val repository: AiProviderRepository,
    private val session: AiChatSession,
    private val engine: ChatEngine,
    private val compressor: ConversationCompressor,
    private val exporter: ConversationExporter,
    private val imagePreparer: AiImagePreparer,
    private val syncScheduler: SyncScheduler,
    private val vaultRepository: VaultRepository,
    private val attachmentRepository: AttachmentRepository,
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

    /** 待发图片（会话级；发送后 / 新会话时清空；3 张上限由 VM 守卫）。 */
    val pendingImages: StateFlow<List<ChatPart.Image>> = session.pendingImages

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

    /** 文档选择器「只看收藏夹」过滤开关（本地集合判断，与搜索叠加生效）。 */
    private val _docPickerFavoritesOnly = MutableStateFlow(false)
    val docPickerFavoritesOnly: StateFlow<Boolean> = _docPickerFavoritesOnly.asStateFlow()

    /** 文档选择器装载中（首次扫描 / 搜索过滤期间；空列表时区分「加载中」与「无结果」）。 */
    private val _docPickerLoading = MutableStateFlow(false)
    val docPickerLoading: StateFlow<Boolean> = _docPickerLoading.asStateFlow()

    /** 应用设置（隐私确认键读取；DataStore 异步到达，默认值兜底）。
     *  Eagerly：VM 内多处直读 .value（隐私确认等），须立即订阅上游保证实时生效。 */
    private val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** 当前模型是否支持图片（vision=false → 「添加图片」「文档取图」置灰 + 提示，§7.3）。 */
    val visionEnabled: StateFlow<Boolean> = combine(selection, providers) { sel, list ->
        if (sel == null) return@combine false
        val provider = list.firstOrNull { it.id == sel.providerId } ?: return@combine false
        provider.models.firstOrNull { it.id == sel.modelId }?.vision == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 压缩进行中（手动按钮防重入 + 显示「正在压缩…」）。 */
    private val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    /** 手动「压缩上下文」可用性：存在可压缩区且空闲（非生成中 / 非压缩中，§6.4）。 */
    val canCompress: StateFlow<Boolean> =
        combine(messages, generating, compressing) { list, busy, compressingNow ->
            !busy && !compressingNow && compressor.compressibleCount(list) > 0
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 图片选择条目（null = 未打开；打开期间先置空列表再 IO 填充）。 */
    private val _docImageItems = MutableStateFlow<List<DocImageItem>?>(null)
    val docImageItems: StateFlow<List<DocImageItem>?> = _docImageItems.asStateFlow()

    /** 图片选择对话框来源（null = 未打开；控制标题 / 空态 / 来源行文案）。 */
    private val _imagePickSource = MutableStateFlow<ImagePickSource?>(null)
    val imagePickSource: StateFlow<ImagePickSource?> = _imagePickSource.asStateFlow()

    /** 文档取图装载中（空列表时区分「加载中」与「无可选图片」）。 */
    private val _docImageLoading = MutableStateFlow(false)
    val docImageLoading: StateFlow<Boolean> = _docImageLoading.asStateFlow()

    /** 导出对话框可见 + 初始选中条目（顶栏 = null 不选；操作行 = 该条 id，§8.1）。 */
    private val _exportVisible = MutableStateFlow(false)
    val exportVisible: StateFlow<Boolean> = _exportVisible.asStateFlow()
    private val _exportDefaultSelected = MutableStateFlow<String?>(null)
    val exportDefaultSelected: StateFlow<String?> = _exportDefaultSelected.asStateFlow()

    /** 导出目标文件夹选择器（null = 未打开；打开后先扫描目录再填充）。 */
    private val _exportFolderPicker = MutableStateFlow<ExportFolderPickerState?>(null)
    val exportFolderPicker: StateFlow<ExportFolderPickerState?> = _exportFolderPicker.asStateFlow()

    /** 导出目标目录绝对路径（null = 仓库根）。 */
    private var exportTargetDir: String? = null

    /** 导出目标展示名（相对仓库根的目录路径；null = 仓库根）。 */
    private val _exportTargetLabel = MutableStateFlow<String?>(null)
    val exportTargetLabel: StateFlow<String?> = _exportTargetLabel.asStateFlow()

    /** 隐私一次性告知对话框（§11.3；确认后持久化，不再弹）。 */
    private val _privacyDialog = MutableStateFlow(false)
    val privacyDialog: StateFlow<Boolean> = _privacyDialog.asStateFlow()

    /** Snackbar 一次性文案（展示后经 [consumeMessage] 置空，避免重进页面重放）。 */
    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** 导出完成数据（Screen 弹「已导出到仓库根目录」+「查看」→ 预览页）。 */
    private val _exportDone = MutableStateFlow<ExportDone?>(null)
    val exportDone: StateFlow<ExportDone?> = _exportDone.asStateFlow()

    private var streamJob: Job? = null

    /** 流式中的 AI 消息 id（onCleared 兜底收尾用）。 */
    private var streamingMessageId: String? = null

    /** 选择器全量节点（null = 首次扫描未完成；完成后为修改时间倒序）。 */
    private var docPickerAll: List<FileNode>? = null

    /** 当前视图尚未装载预览的剩余节点（滚动续载）。 */
    private var docPickerPending: List<FileNode> = emptyList()

    /** 打开选择器时的收藏集快照（相对路径集合，「只看收藏夹」过滤用）。 */
    private var docPickerFavorites: Set<String> = emptySet()

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

    /** 发送：隐私一次性告知未确认时先弹框（输入与附件不消费），确认后走 [performSend]。 */
    fun send() {
        if (_generating.value) return
        if (!settings.value.aiPrivacyAcknowledged) {
            _privacyDialog.value = true
            return
        }
        performSend()
    }

    /** 隐私告知确认：持久化后继续本次发送（直接走 [performSend]，避开写盘异步竞态）。 */
    fun confirmPrivacy() {
        _privacyDialog.value = false
        viewModelScope.launch { settingsRepository.setAiPrivacyAcknowledged() }
        performSend()
    }

    /** 隐私告知关闭（未确认：下次发送再弹）。 */
    fun dismissPrivacy() {
        _privacyDialog.value = false
    }

    /** 实际发送：追加用户消息（文本 + 待发附件 + 待发图片）→ 开启一条新的 AI 流式消息。 */
    private fun performSend() {
        if (_generating.value) return
        val provider = currentProvider() ?: return
        val model = currentModel(provider) ?: return
        val text = _input.value.trim()
        val attachments = session.consumePendingAttachments()
        val images = session.consumePendingImages()
        if (text.isEmpty() && attachments.isEmpty() && images.isEmpty()) return
        _input.value = ""
        _error.value = null
        val parts = mutableListOf<ChatPart>()
        if (text.isNotEmpty()) parts.add(ChatPart.Text(text))
        parts.addAll(attachments)
        parts.addAll(images)
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
        // 文档 / 图片附回待发区（沿用消息内的内容快照；去重）
        session.restorePendingAttachments(message.parts.filterIsInstance<ChatPart.Document>())
        session.restorePendingImages(message.parts.filterIsInstance<ChatPart.Image>())
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
     * 同批快照收藏集（「只看收藏夹」过滤为本地集合判断，零额外 IO）。
     */
    fun openDocumentPicker() {
        if (_docPickerItems.value != null) return
        _docPickerItems.value = emptyList()
        _docPickerLoading.value = true
        _docPickerQuery.value = ""
        _docPickerFavoritesOnly.value = false
        docPickerAll = null
        docPickerPending = emptyList()
        docPickerGeneration++
        val generation = docPickerGeneration
        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                val loaded = runCatching { settingsRepository.settings.first() }.getOrNull()
                docPickerFavorites = loaded?.favoritePaths.orEmpty()
                loaded?.vaultPath?.let { path ->
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

    /** 选择器「只看收藏夹」开关：本地过滤后重新装载首批预览（与搜索叠加）。 */
    fun setDocPickerFavoritesOnly(enabled: Boolean) {
        if (_docPickerFavoritesOnly.value == enabled) return
        _docPickerFavoritesOnly.value = enabled
        if (_docPickerItems.value == null) return
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
        var filtered = if (keyword.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                    it.relativePath.contains(keyword, ignoreCase = true)
            }
        }
        // 「只看收藏夹」：本地集合判断（收藏集为打开时快照，零额外 IO）
        if (_docPickerFavoritesOnly.value) {
            filtered = filtered.filter { it.relativePath in docPickerFavorites }
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
        docPickerFavorites = emptySet()
        _docPickerQuery.value = ""
        _docPickerFavoritesOnly.value = false
        _docPickerItems.value = null
        _docPickerHasMore.value = false
        _docPickerLoading.value = false
    }

    // ---------------------------------------------------------------- 图片附加（§7）

    /** 相册选图：vision 与 3 张上限守卫 → 预处理 → 成功入待发；失败按原因提示。 */
    fun attachImage(uri: Uri) {
        if (!visionEnabled.value) return
        if (session.pendingImages.value.size >= AiImagePreparer.MAX_IMAGES) {
            _message.value = UiText.of(R.string.ai_chat_image_limit)
            return
        }
        viewModelScope.launch {
            when (val result = imagePreparer.prepareFromUri(uri)) {
                is ImagePrepResult.Success -> session.addPendingImage(result.image)
                is ImagePrepResult.Failure -> _message.value = imageFailureText(result.reason)
            }
        }
    }

    /** 移除待发图片（按本地临时路径）。 */
    fun removePendingImage(localPath: String) {
        session.removePendingImage(localPath)
    }

    /** 打开「文档取图」：解析会话涉及文档（待发附件 + 已发送消息）中的本地白名单图片。 */
    fun openDocImagePicker() {
        if (_docImageItems.value != null) return
        _imagePickSource.value = ImagePickSource.DOCUMENT
        _docImageItems.value = emptyList()
        _docImageLoading.value = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { resolveDocImages() }
            _docImageItems.value = items
            _docImageLoading.value = false
        }
    }

    /** 打开「从软件图库选取」：列出当前仓库全部白名单图片（按修改时间倒序，对齐图库页）。 */
    fun openGalleryPicker() {
        if (_docImageItems.value != null) return
        _imagePickSource.value = ImagePickSource.GALLERY
        _docImageItems.value = emptyList()
        _docImageLoading.value = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { resolveGalleryImages() }
            _docImageItems.value = items
            _docImageLoading.value = false
        }
    }

    fun closeDocImagePicker() {
        _docImageItems.value = null
        _imagePickSource.value = null
        _docImageLoading.value = false
    }

    /** 文档取图确认：逐张预处理（沿用同一压缩管线；3 张上限守卫）。 */
    fun confirmDocImageSelection(paths: List<String>) {
        if (paths.isEmpty()) {
            closeDocImagePicker()
            return
        }
        viewModelScope.launch {
            val items = _docImageItems.value.orEmpty().associateBy { it.file.absolutePath }
            var limitHit = false
            var failure: ImagePrepFailure? = null
            for (path in paths) {
                if (session.pendingImages.value.size >= AiImagePreparer.MAX_IMAGES) {
                    limitHit = true
                    break
                }
                val item = items[path] ?: continue
                when (val result = imagePreparer.prepareFromVaultFile(item.file, item.name)) {
                    is ImagePrepResult.Success -> session.addPendingImage(result.image)
                    is ImagePrepResult.Failure -> if (failure == null) failure = result.reason
                }
            }
            closeDocImagePicker()
            failure?.let { _message.value = imageFailureText(it) }
            if (limitHit) _message.value = UiText.of(R.string.ai_chat_image_limit)
        }
    }

    /**
     * 列出仓库全部可用图片（IO 调用）：[AttachmentRepository.listAttachments] 全库扫描 →
     * 白名单过滤（gif 等不列出，与文档取图一致）→ 按修改时间倒序（新图在前，对齐图库页）。
     * 来源标签取仓库相对路径。
     */
    private suspend fun resolveGalleryImages(): List<DocImageItem> {
        val vaultPath = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
            ?: return emptyList()
        val root = File(vaultPath).normalize()
        val rootPath = root.absolutePath
        return attachmentRepository.listAttachments(root)
            .filter { AiImagePreparer.isSupportedExtension(it.extension) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val rel = file.absolutePath.removePrefix(rootPath).trimStart(File.separatorChar)
                DocImageItem(sourceLabel = rel, file = file, name = file.name, sizeBytes = file.length())
            }
    }

    /**
     * 解析会话涉及文档中的本地图片（IO）：待发附件 + 已发送消息 Document part（相对路径
     * 去重）→ [ImageReference.resolveExisting]（nameIndex 空，按笔记同目录 / assets/ /
     * 根路径启发）→ 白名单过滤 + 绝对路径去重。
     */
    private suspend fun resolveDocImages(): List<DocImageItem> {
        val vaultPath = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
            ?: return emptyList()
        val root = File(vaultPath).normalize()
        val docs = LinkedHashMap<String, ChatPart.Document>()
        session.pendingAttachments.value.forEach { docs.putIfAbsent(it.vaultRelPath, it) }
        session.messages.value.forEach { message ->
            message.parts.filterIsInstance<ChatPart.Document>().forEach { doc ->
                docs.putIfAbsent(doc.vaultRelPath, doc)
            }
        }
        val seen = mutableSetOf<String>()
        val items = mutableListOf<DocImageItem>()
        docs.values.forEach { doc ->
            val noteFile = File(root, doc.vaultRelPath).normalize()
            val abs = noteFile.absolutePath
            if (abs != root.absolutePath && !abs.startsWith(root.absolutePath + File.separator)) {
                return@forEach
            }
            val noteDir = noteFile.parentFile ?: root
            ImageReference.extract(doc.content).forEach { ref ->
                val file = ImageReference.resolveExisting(ref, root, noteDir) ?: return@forEach
                if (!AiImagePreparer.isSupportedExtension(file.extension)) return@forEach
                if (!seen.add(file.absolutePath)) return@forEach
                items += DocImageItem(
                    sourceLabel = doc.name,
                    file = file,
                    name = file.name,
                    sizeBytes = file.length()
                )
            }
        }
        return items
    }

    /** 图片预处理失败原因 → 提示文案。 */
    private fun imageFailureText(reason: ImagePrepFailure): UiText = when (reason) {
        ImagePrepFailure.UNSUPPORTED_FORMAT -> UiText.of(R.string.ai_chat_image_unsupported_format)
        ImagePrepFailure.TOO_LARGE -> UiText.of(R.string.ai_chat_image_too_large)
        ImagePrepFailure.READ_FAILED -> UiText.of(R.string.ai_chat_image_read_failed)
    }

    // ---------------------------------------------------------------- 压缩（§6）

    /** 手动压缩：存在可压缩区且空闲时执行摘要压缩（失败自动降级滑动窗口，§6.4）。 */
    fun compressContext() {
        if (_generating.value || _compressing.value) return
        if (compressor.compressibleCount(session.messages.value) <= 0) return
        val provider = currentProvider() ?: return
        val model = currentModel(provider) ?: return
        _compressing.value = true
        viewModelScope.launch {
            try {
                val outcome = compressor.compressIfNeeded(
                    history = session.messages.value,
                    provider = provider,
                    model = model,
                    force = true
                )
                if (outcome != null) {
                    session.replaceMessages(outcome.messages)
                    _message.value = compressDoneText(outcome.mode, outcome.removedCount)
                }
            } finally {
                _compressing.value = false
            }
        }
    }

    /**
     * 流前压缩检查（§6.1）：超预算（或 [force]）时压缩并整体写回会话
     * （进行中的流式占位消息由 compressor 原位保留）。
     * @return 是否实际压缩（ContextOverflow 重试路径据此决定是否重试）
     */
    private suspend fun compressBeforeStream(
        provider: AiProvider,
        model: AiModel,
        force: Boolean = false
    ): Boolean {
        val outcome = compressor.compressIfNeeded(
            history = session.messages.value,
            provider = provider,
            model = model,
            force = force
        ) ?: return false
        session.replaceMessages(outcome.messages)
        if (outcome.removedCount > 0) {
            _message.value = UiText.of(R.string.ai_chat_auto_compressed)
        }
        return true
    }

    private fun compressDoneText(mode: SystemNoteMode, count: Int): UiText = when (mode) {
        SystemNoteMode.SUMMARIZED -> UiText.of(R.string.ai_chat_compressed, count)
        SystemNoteMode.TRIMMED -> UiText.of(R.string.ai_chat_trimmed, count)
    }

    // ---------------------------------------------------------------- 导出（§8）

    /** 导出中（防重复导出；写盘很快，无需进度态）。 */
    private var exporting = false

    /** 打开导出对话框；[defaultSelectedId] null = 顶栏入口（默认不选），非空 = 操作行入口（默认选中该条）。 */
    fun openExport(defaultSelectedId: String? = null) {
        _exportDefaultSelected.value = defaultSelectedId
        _exportVisible.value = true
    }

    fun closeExport() {
        _exportVisible.value = false
        _exportDefaultSelected.value = null
        // 导出目标每次打开重置为仓库根（可预期）；选择器一并收起
        exportTargetDir = null
        _exportTargetLabel.value = null
        _exportFolderPicker.value = null
    }

    /** 打开「导出位置」文件夹选择器：IO 扫描当前仓库全部子目录（含根行）。 */
    fun openExportFolderPicker() {
        if (_exportFolderPicker.value != null) return
        viewModelScope.launch {
            val vaultPath = runCatching { settingsRepository.settings.first().vaultPath }
                .getOrNull() ?: return@launch
            _exportFolderPicker.value = ExportFolderPickerState(vaultPath, null)
            val dirs = withContext(Dispatchers.IO) {
                runCatching { vaultRepository.listAllDirectories(vaultPath) }.getOrDefault(emptyList())
            }
            // 防竞态：扫描期间被关闭（或重新打开）则丢弃本次结果
            if (_exportFolderPicker.value != null) {
                _exportFolderPicker.value = ExportFolderPickerState(vaultPath, dirs)
            }
        }
    }

    /** 选定导出目标目录（绝对路径；等于仓库根时按「根」处理）。 */
    fun pickExportFolder(absolutePath: String) {
        val vaultPath = _exportFolderPicker.value?.vaultPath ?: return
        val root = File(vaultPath).normalize()
        val target = File(absolutePath).normalize()
        if (target.absolutePath == root.absolutePath) {
            exportTargetDir = null
            _exportTargetLabel.value = null
        } else {
            exportTargetDir = target.absolutePath
            _exportTargetLabel.value = target.relativeTo(root).path.replace('\\', '/')
        }
        _exportFolderPicker.value = null
    }

    /** 关闭（取消）导出位置选择器。 */
    fun dismissExportFolderPicker() {
        _exportFolderPicker.value = null
    }

    /** 导出勾选消息：写入所选目录（null = 仓库根）+ 调度保存同步 + 完成后弹「查看」入口（§8.2）。 */
    fun exportSelected(selectedIds: Set<String>) {
        if (exporting || selectedIds.isEmpty()) return
        exporting = true
        viewModelScope.launch {
            try {
                val result = exporter.export(session.messages.value, selectedIds, exportTargetDir)
                if (result == null) {
                    _message.value = UiText.of(R.string.ai_chat_export_failed)
                } else {
                    syncScheduler.scheduleSaveSync()
                    val locationLabel = _exportTargetLabel.value
                    closeExport()
                    _exportDone.value = ExportDone(result.fileName, result.absolutePath, locationLabel)
                }
            } finally {
                exporting = false
            }
        }
    }

    /** 消费 Snackbar 文案（展示后置空）。 */
    fun consumeMessage() {
        _message.value = null
    }

    /** 消费导出完成数据（Snackbar 展示后置空）。 */
    fun consumeExportDone() {
        _exportDone.value = null
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
        // 仅清空对话历史：输入框文字与待发附件 / 图片保留（供新会话继续提问）
        session.clearMessages()
        _error.value = null
        // 图片临时文件清理：仅保留待发图片仍引用的文件
        val keep = session.pendingImages.value.mapTo(mutableSetOf()) { it.localPath }
        viewModelScope.launch { imagePreparer.cleanup(keep) }
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
     * 流前自动压缩（§6.1）；ContextOverflow 且零输出时强制压缩重试一次（§4.4）。
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
            // ContextOverflow 压缩后重试标记（§4.4，仅重试一次）
            var overflowRetried = false

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
                // §6.1 流前自动压缩（超预算时；进行中的流式占位消息由 compressor 原位保留）
                compressBeforeStream(provider, model)
                while (true) {
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
                    // §4.4 ContextOverflow 且本轮零输出：强制压缩后重试一次（仍失败走错误条）
                    if (error == AiError.ContextOverflow && !overflowRetried &&
                        textBuffer.isEmpty() && reasoningBuffer.isEmpty()
                    ) {
                        overflowRetried = true
                        if (compressBeforeStream(provider, model, force = true)) {
                            failure = null
                            continue
                        }
                    }
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
                    break
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
            AiError.RequestTooLarge -> UiText.of(R.string.ai_chat_error_request_too_large) to false
            AiError.Network -> UiText.of(R.string.ai_chat_error_network) to false
            is AiError.Api -> UiText.of(R.string.ai_chat_error_api, error.message) to false
            AiError.Unknown -> UiText.of(R.string.ai_chat_error_unknown) to false
        }
    }
}
