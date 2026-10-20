package com.az.notes.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.ai.AiChatSession
import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogType
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.PathUpdateBus
import com.az.notes.data.storage.VaultNameIndex
import com.az.notes.data.storage.VaultPropertyIndex
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.markdown.FrontmatterAttribute
import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.Heading
import com.az.notes.domain.markdown.HeadingExtractor
import com.az.notes.domain.markdown.ImageReference
import com.az.notes.domain.markdown.MarkdownImageLifter
import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.TaskExtractor
import com.az.notes.domain.markdown.TaskItem
import com.az.notes.domain.markdown.WikiLinkResolver
import com.az.notes.domain.markdown.WikiLinkTarget
import com.az.notes.domain.markdown.WikiLinkTransformer
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.toUiText
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 文档信息（预览页“文档信息”对话框数据）：文件属性 + 正文统计。
 */
data class DocInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 文件系统创建时间（btime）；不可得时为 null（界面隐藏该行）。 */
    val createdAt: Long?,
    /** 非空白字符数 */
    val charCount: Int,
    val lineCount: Int,
    val headingCount: Int
)

data class ReaderUiState(
    val path: String = "",
    val loading: Boolean = true,
    val content: String = "",
    val headings: List<Heading> = emptyList(),
    /** 当前笔记所属 Vault 根（图片相对路径解析用）。 */
    val vaultPath: String? = null,
    /** 网络图片体积上限（字节）；0 = 不加载网络图片（预览页护栏用）。 */
    val remoteImageMaxBytes: Long = 0L,
    /** “文档信息”对话框数据（null = 对话框未打开）。 */
    val docInfo: DocInfo? = null,
    /** frontmatter `note_type` 值（小写）；null = 未声明。 */
    val noteType: String? = null,
    /** frontmatter 全部属性（保序，多值已解析）；用于预览页属性面板。 */
    val attributes: List<FrontmatterAttribute> = emptyList(),
    /** 待办条目（仅 task 模式非空）。 */
    val tasks: List<TaskItem> = emptyList(),
    /** 一次性提示（Snackbar），展示后由 [consumeMessage] 清除。 */
    val message: UiText? = null,
    val error: UiText? = null
)

/**
 * 阅读器 ViewModel（§5.2 / §5.4 / §5.5）。
 * 加载正文、基于 AST 提取大纲（块序号与渲染一一对应）。
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val debugLogRepository: DebugLogRepository,
    private val vaultNameIndex: VaultNameIndex,
    private val vaultPropertyIndex: VaultPropertyIndex,
    private val pathUpdateBus: PathUpdateBus,
    private val aiSession: AiChatSession,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private var absolutePath: String =
        savedStateHandle.get<String>("path") ?: ""

    /** 属性写回串行化：防止并发的读-改-写互相覆盖（如键改名 + 新建同时提交）。 */
    private val attrMutex = Mutex()

    private val _state = MutableStateFlow(ReaderUiState(path = absolutePath))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 首次加载是否已结束（区分“首次进入”与“从编辑页返回”，避免重复读盘）。 */
    private var loadedOnce = false

    /** 退出清理防重入（读盘 / 删除为异步，双击返回时避免重复执行）。 */
    private var exiting = false

    /** Wikilink 解析上下文：打开笔记时建好，点击解析零 IO 等待。 */
    @Volatile
    private var wikiRoot: File? = null

    @Volatile
    private var wikiIndex: Map<String, List<String>> = emptyMap()

    @Volatile
    private var wikiAliases: Map<String, List<NoteAlias>> = emptyMap()

    /** 待滚动锚点：路由入参（`[[笔记#标题]]`）或页内点击（`[[#标题]]`）设置，滚动后消费。 */
    private val _pendingAnchor = MutableStateFlow(
        savedStateHandle.get<String>("anchor")?.ifEmpty { null }
    )
    val pendingAnchor: StateFlow<String?> = _pendingAnchor.asStateFlow()

    /** 属性键值输入历史（null = 尚未加载；值为该键历史输入过的值）。 */
    private val _propertyHistory = MutableStateFlow<Map<String, List<String>>?>(null)
    val propertyHistory: StateFlow<Map<String, List<String>>?> = _propertyHistory.asStateFlow()

    init {
        load(showLoading = true)
    }

    /**
     * 从编辑页返回（预览页重新进入组合）时静默重读正文，保证看到的是最新内容。
     * 不显示 loading，避免画面闪一下。
     */
    fun reload() {
        applyPathUpdate()
        if (loadedOnce) load(showLoading = false)
    }

    /**
     * 应用编辑页重命名 / 移动广播的新路径（PathUpdateBus）：在本轮重读前生效，
     * 避免拿已不存在的旧路径报「找不到文件」。连续改名 / 移动会形成链式映射
     * （A→B→C），逐跳跟进（跳数上限仅防御异常数据）。
     */
    private fun applyPathUpdate() {
        var target = pathUpdateBus.consume(absolutePath) ?: return
        var hops = 0
        while (hops < 8) {
            val next = pathUpdateBus.consume(target) ?: break
            target = next
            hops++
        }
        absolutePath = target
        _state.update { it.copy(path = target) }
    }

    private fun load(showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _state.update { it.copy(loading = true, error = null) }
            try {
                val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
                val vault = settings?.vaultPath
                val remoteMaxBytes = settings?.remoteImageMaxBytes ?: 0L
                // 读盘与解析合并为单个 IO 块（中间结果不来回切线程）；settings 只读一次复用。
                // 预览专用预处理：把段落内图抽出成独立块（规避库的 inline Placeholder 不撑高段落
                // 导致“图只显上半部分 + 下方文字不可见 + 无法下滑”的问题）。
                // 与 HeadingExtractor 共用同一份预处理后字符串，保证大纲 blockIndex 与
                // LazyColumn 下标仍严格一一对应；写盘（mutateBody / TaskExtractor）路径仍用原始 body。
                withContext(Dispatchers.IO) {
                    val text = vaultRepository.readText(absolutePath)
                    val split = FrontmatterParser.split(text)
                    val previewBody = preparePreviewBody(split.body, vault)
                    val noteType = split.value("note_type")?.trim()?.lowercase()
                    val parsed = HeadingExtractor.parse(previewBody)
                    val tasks = if (noteType == "task") TaskExtractor.extract(split.body) else emptyList()
                    _state.update {
                        it.copy(
                            loading = false,
                            content = previewBody,
                            headings = parsed.headings,
                            vaultPath = vault,
                            remoteImageMaxBytes = remoteMaxBytes,
                            noteType = noteType,
                            attributes = split.entries,
                            tasks = tasks,
                            error = null
                        )
                    }
                }
                // 记录最近查看（打开即算；重复打开幂等置顶）：读取抛错时不记录
                vault?.let { v ->
                    vaultRelative(v, absolutePath)?.let { rel ->
                        runCatching { settingsRepository.addRecent(rel) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUiText(R.string.msg_read_failed)) }
            } finally {
                loadedOnce = true
            }
        }
    }

    /** 绝对路径 → Vault 根相对路径（不在 Vault 内返回 null）。 */
    private fun vaultRelative(vault: String, absPath: String): String? {
        val prefix = vault.trimEnd('/') + "/"
        if (!absPath.startsWith(prefix)) return null
        return absPath.removePrefix(prefix)
    }

    /**
     * 预览正文预处理：Wikilink 转标准语法 → 段落内图片抽离。转换在抽离之前——
     * `![[图]]` 需先产出标准 `![](...)` 才能被 [MarkdownImageLifter] 识别；与
     * [HeadingExtractor] 共用同一份产物，保证大纲 blockIndex 与列表下标不漂移。
     */
    private fun preparePreviewBody(body: String, vault: String?): String {
        if (!body.contains("[[") || vault.isNullOrBlank()) return MarkdownImageLifter.lift(body)
        val root = File(vault).normalize()
        val index = vaultNameIndex.names(root)
        val aliases = vaultNameIndex.aliases(root)
        val noteDir = File(absolutePath).parentFile
        wikiRoot = root
        wikiIndex = index
        wikiAliases = aliases
        val transformed = WikiLinkTransformer.transform(body) { target ->
            val file = ImageReference.resolveExisting(target, root, noteDir, index)
                ?: WikiLinkResolver.resolve(target, root, noteDir, index, aliases)
            file?.let { vaultRelativeOf(root, it) }
        }
        return MarkdownImageLifter.lift(transformed)
    }

    /** 绝对路径 → 相对 [root] 的仓库内路径（正斜杠分隔）。 */
    private fun vaultRelativeOf(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')

    /**
     * Wikilink 点击解析：目标原文（可含 `#锚点`）→ 跳转目标（路径 + 标题锚点）；
     * 本文锚点（`#标题`）回到当前笔记；未命中返回 null（UI 层提示并提供新建入口）。
     */
    fun resolveWikiTarget(target: String): WikiLinkTarget? {
        val anchor = target.substringAfter('#', "").trim().ifEmpty { null }
        val filePart = target.substringBefore('#').trim()
        if (filePart.isEmpty()) {
            return if (anchor != null) WikiLinkTarget(absolutePath, anchor) else null
        }
        val root = wikiRoot ?: return null
        val noteDir = File(absolutePath).parentFile
        val file = WikiLinkResolver.resolve(filePart, root, noteDir, wikiIndex, wikiAliases)
            ?: return null
        return WikiLinkTarget(file.absolutePath, anchor)
    }

    /** 请求滚动到本文指定标题（`[[#标题]]` 自锚点点击）。 */
    fun requestAnchorScroll(anchor: String) {
        _pendingAnchor.value = anchor
    }

    /** 锚点已消费（滚动执行或判定无命中后调用，避免重入页面时重放）。 */
    fun consumeAnchor() {
        _pendingAnchor.value = null
    }

    /** 未解析链接的「新建笔记」：按目标相对路径落盘空文件；越界 / 失败返回 null。 */
    suspend fun createWikiNote(target: String): String? = withContext(Dispatchers.IO) {
        val root = wikiRoot ?: return@withContext null
        val file = WikiLinkResolver.createNotePath(target, root) ?: return@withContext null
        val ok = file.isFile || vaultRepository.createFile(file.absolutePath, "")
        if (!ok) return@withContext null
        vaultNameIndex.invalidate()
        file.absolutePath
    }

    /** 打开“文档信息”对话框：读取文件属性并统计正文字数（一次读取，关闭后清除）。 */
    fun loadDocInfo() {
        viewModelScope.launch {
            val snapshot = _state.value
            val info = withContext(Dispatchers.IO) {
                val file = File(absolutePath)
                val content = snapshot.content
                DocInfo(
                    name = file.name,
                    path = absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    createdAt = creationTimeOf(file),
                    charCount = content.count { !it.isWhitespace() },
                    lineCount = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1,
                    headingCount = snapshot.headings.size
                )
            }
            _state.update { it.copy(docInfo = info) }
        }
    }

    /**
     * 文件创建时间（btime，epoch 毫秒）。Android 的 Java NIO 拿不到真实 btime：
     * libcore 仅在 Darwin 置位 SUPPORTS_BIRTHTIME（AOSP UnixNativeDispatcher.c），
     * Linux 上 BasicFileAttributes.creationTime() 恒等于修改时间。故改用系统
     * toybox `stat -c %W`（epoch 秒）读取；设备 / 文件系统不支持 btime 时返回
     * null（界面隐藏该行），不以修改时间冒充。
     */
    private fun creationTimeOf(file: File): Long? = runCatching {
        val process = ProcessBuilder("toybox", "stat", "-c", "%W", file.absolutePath).start()
        val seconds = process.inputStream.bufferedReader().use { it.readText() }.trim().toLongOrNull()
        process.waitFor()
        seconds?.takeIf { it > 0 }?.times(1000L)
    }.getOrNull()

    /** 关闭“文档信息”对话框。 */
    fun clearDocInfo() {
        _state.update { it.copy(docInfo = null) }
    }

    /** 图片加载链路日志（写入调试日志 IMAGE 类型；未开启收集时静默丢弃）。 */
    fun imageLog(level: DebugLogLevel, msg: String, extra: Map<String, Any?>) {
        debugLogRepository.log(level, DebugLogType.IMAGE, msg, extra)
    }

    /**
     * 「加入 AI 对话」：当前文档作为待发附件加入会话（vaultPath 护栏 / 重复静默跳过
     * 由 [AiChatSession.attachDocument] 内部处理）；调用方随后导航到聊天页。
     */
    fun addToAiChat() {
        viewModelScope.launch { aiSession.attachDocument(absolutePath) }
    }

    /** 切换待办勾选状态（写回磁盘后静默刷新列表）。 */
    fun toggleTask(lineIndex: Int, checked: Boolean) {
        mutateBody { body -> TaskExtractor.toggle(body, lineIndex, checked) }
    }

    /** 追加一条待办（写回磁盘后静默刷新列表）。 */
    fun addTask(text: String) {
        if (text.isBlank()) return
        mutateBody { body -> TaskExtractor.append(body, text) }
    }

    /** 重排待办（拖动排序）：[lineOrder] 为任务行号的期望顺序，仅调整这些行之间的相对顺序。 */
    fun reorderTasks(lineOrder: List<Int>) {
        mutateBody { body -> TaskExtractor.reorder(body, lineOrder) }
    }

    /** 编辑待办文本（写回磁盘后静默刷新列表）。 */
    fun editTask(lineIndex: Int, text: String) {
        if (text.isBlank()) return
        mutateBody { body -> TaskExtractor.update(body, lineIndex, text) }
    }

    /** 删除待办行（写回磁盘后静默刷新列表）。 */
    fun deleteTask(lineIndex: Int) {
        mutateBody { body -> TaskExtractor.delete(body, lineIndex) }
    }

    /** 加载全库属性历史（键 → 值），供属性编辑器做键值联想；TTL 内复用索引缓存。 */
    fun loadPropertyHistory() {
        if (_propertyHistory.value != null) return
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) {
                val vault = _state.value.vaultPath
                    ?: runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
                if (vault.isNullOrBlank()) {
                    emptyMap<String, List<String>>()
                } else {
                    runCatching { vaultPropertyIndex.history(File(vault)) }
                        .getOrDefault(emptyMap<String, List<String>>())
                }
            }
            _propertyHistory.value = history
        }
    }

    /** 写入属性值（不存在则新增、已存在则覆盖；空值保留为占位属性）。 */
    fun setAttributeValues(key: String, values: List<String>) {
        val name = key.trim()
        if (name.isEmpty()) return
        mutateAttributes { entries ->
            val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
            val attr = FrontmatterAttribute(name, cleaned)
            val index = entries.indexOfFirst { it.key == name }
            if (index >= 0) {
                entries.toMutableList().apply { set(index, attr) }
            } else {
                entries + attr
            }
        }
    }

    /** 属性键改名：一次写盘内删旧键 + 建新键（避免两次写回出现中间态）。 */
    fun replaceAttribute(oldKey: String, newKey: String, values: List<String>) {
        val name = newKey.trim()
        if (name.isEmpty()) return
        mutateAttributes { entries ->
            val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
            val attr = FrontmatterAttribute(name, cleaned)
            val without = entries.filterNot { it.key == oldKey }
            val index = without.indexOfFirst { it.key == name }
            if (index >= 0) {
                without.toMutableList().apply { set(index, attr) }
            } else {
                without + attr
            }
        }
    }

    /** 删除整条属性（不存在时忽略）。 */
    fun removeAttribute(key: String) {
        mutateAttributes { entries ->
            entries.filterNot { it.key == key }.takeIf { it.size != entries.size }
        }
    }

    /**
     * 新建待办（fresh）退出清理：正文为空（无条目）时删除占位文件（与空笔记 fresh 清理一致）。
     * 读盘判断不依赖内存状态；随后执行 [onDone]（导航返回）。
     */
    fun exitWithTaskCleanup(fresh: Boolean, onDone: () -> Unit) {
        if (!fresh) {
            onDone()
            return
        }
        if (exiting) return
        exiting = true
        viewModelScope.launch {
            val empty = withContext(Dispatchers.IO) {
                runCatching {
                    val split = FrontmatterParser.split(vaultRepository.readText(absolutePath))
                    split.value("note_type")?.trim()?.lowercase() == "task" && split.body.isBlank()
                }.getOrDefault(false)
            }
            if (empty) {
                withContext(Dispatchers.IO) { runCatching { vaultRepository.delete(absolutePath) } }
            }
            onDone()
        }
    }

    /**
     * 清除已展示的一次性提示。传入 [current] 时仅当仍是同一条才清除：
     * 横幅协程因离开页面被取消时于 finally 兜底消费本条，且不误清被替换的新提示。
     */
    fun consumeMessage(current: UiText? = null) {
        _state.update { s -> if (current == null || s.message == current) s.copy(message = null) else s }
    }

    /**
     * 待办写回：重读磁盘最新内容 → 仅变换正文 → 保留 frontmatter 原样 → 原子写盘 → 静默刷新。
     * 读 / 写失败时通过 [ReaderUiState.message] 提示。
     */
    private fun mutateBody(transform: (String) -> String?) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val current = vaultRepository.readText(absolutePath)
                    val split = FrontmatterParser.split(current)
                    val updated = transform(split.body) ?: return@runCatching true
                    vaultRepository.writeTextAtomically(absolutePath, split.prefix + updated)
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                runCatching { syncScheduler.scheduleSaveSync() }
                load(showLoading = false)
            } else {
                _state.update { it.copy(message = UiText.of(R.string.msg_task_save_failed)) }
            }
        }
    }

    /**
     * 属性写回：重读磁盘 → 仅重建 frontmatter 属性块 → 原子写盘 → 失效名字 / 属性索引 →
     * 调度保存同步；成功后用新属性就地更新状态（仅属性区变化，不做整篇重载，
     * 复选框等操作立即反馈）。读 / 写失败时通过 [ReaderUiState.message] 提示。
     */
    private fun mutateAttributes(transform: (List<FrontmatterAttribute>) -> List<FrontmatterAttribute>?) {
        viewModelScope.launch {
            val result = attrMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val current = vaultRepository.readText(absolutePath)
                        val split = FrontmatterParser.split(current)
                        val entries = transform(split.entries)
                        if (entries == null) {
                            Result.success<List<FrontmatterAttribute>?>(null)
                        } else {
                            vaultRepository.writeTextAtomically(
                                absolutePath,
                                FrontmatterParser.build(entries, split.body)
                            )
                            Result.success<List<FrontmatterAttribute>?>(entries)
                        }
                    }.getOrElse { Result.failure(it) }
                }
            }
            val newEntries = result.getOrNull()
            if (result.isSuccess) {
                if (newEntries != null) {
                    _state.update { it.copy(attributes = newEntries) }
                    vaultNameIndex.invalidate()
                    vaultPropertyIndex.invalidate()
                    _propertyHistory.value = null
                    runCatching { syncScheduler.scheduleSaveSync() }
                }
            } else {
                _state.update { it.copy(message = UiText.of(R.string.msg_attr_save_failed)) }
            }
        }
    }
}
