package com.az.notes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.local.ProgressRepository
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TrashRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.data.sync.SyncEngine
import com.az.notes.data.sync.SyncRunNotifier
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FileNode
import com.az.notes.domain.model.NoteSortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * 主页列表条目：文件夹仅展示名称；笔记附带正文预览、（搜索时的）所在目录
 * 与阅读进度（仅 1–99 时显示小圆点，其它情况为 null）。
 */
data class NoteListItem(
    val node: FileNode,
    val preview: String = "",
    val subtitle: String? = null,
    val progress: Int? = null
)

data class NotesUiState(
    val vaultPath: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** 已进入的目录栈（绝对路径，根目录为空）；last 即当前目录 */
    val dirStack: List<String> = emptyList(),
    val items: List<NoteListItem> = emptyList(),
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<NoteListItem> = emptyList(),
    val sortOrder: NoteSortOrder = NoteSortOrder.MODIFIED_DESC,
    /** 右下角加号点击的默认行为（长按始终弹出全部选项） */
    val fabAction: FabAction = FabAction.NEW_NOTE,
    val error: String? = null,
    /** 一次性提示（Snackbar），消费后清空 */
    val message: String? = null
) {
    val currentDir: String? get() = dirStack.lastOrNull()
    val atRoot: Boolean get() = dirStack.isEmpty()
    val currentDirName: String?
        get() = dirStack.lastOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

/**
 * 主页笔记列表 ViewModel：单层列目录（隐藏 '.' 开头项）、
 * 排序（修改时间/名称）、递归搜索、重命名、删除、新建笔记/文件夹。
 * 所有文件操作在 `Dispatchers.IO` 执行；偏好变化（Vault / 排序 / 预览字符数）自动重载。
 * 重命名成功后额外把改名同步到云端（MOVE），避免下次同步退化为删除 + 重传。
 * 同步运行状态来自 [SyncRunNotifier]：顶栏“同步中”指示与同步完成后刷新。
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val trashRepository: TrashRepository,
    private val syncEngine: SyncEngine,
    private val progressRepository: ProgressRepository,
    syncRunNotifier: SyncRunNotifier
) : ViewModel() {

    private val _state = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    /** 是否有同步（手动/自动）正在执行：主页顶栏“同步中”指示。 */
    val syncRunning: StateFlow<Boolean> = syncRunNotifier.running

    /** 最近一次同步完成时间戳（0 = 进程内尚未完成过）：据此在同步完成后静默刷新列表。 */
    val syncCompletedAt: StateFlow<Long> = syncRunNotifier.lastCompletedAt

    private var currentSettings = AppSettings()
    private var initialized = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { applySettings(it) }
        }
    }

    private fun applySettings(s: AppSettings) {
        val prev = currentSettings
        currentSettings = s
        _state.update { it.copy(sortOrder = s.sortOrder, fabAction = s.fabAction) }

        when {
            s.vaultPath != prev.vaultPath -> {
                _state.update {
                    it.copy(
                        vaultPath = s.vaultPath,
                        dirStack = emptyList(),
                        items = emptyList(),
                        searchQuery = "",
                        searchResults = emptyList()
                    )
                }
                if (s.vaultPath.isNullOrBlank()) {
                    initialized = false
                    _state.update { it.copy(loading = false, error = "未选择 Vault 目录") }
                } else {
                    initialized = true
                    reloadItems(pullRefresh = false)
                }
            }
            // 预览字符数 / 排序方式变化：保持当前目录重新加载
            initialized && (s.previewChars != prev.previewChars || s.sortOrder != prev.sortOrder) -> {
                reloadItems(pullRefresh = false)
            }
        }
    }

    /** 进入主页（含从阅读/编辑页返回）时调用：静默重读当前目录。 */
    fun onScreenEntered() {
        if (initialized) reloadItems(pullRefresh = false)
    }

    /** 下拉刷新。 */
    fun refresh() {
        if (initialized) reloadItems(pullRefresh = true)
    }

    /** 进入子文件夹。 */
    fun enterDir(node: FileNode) {
        if (!node.isDirectory) return
        _state.update {
            it.copy(dirStack = it.dirStack + node.absolutePath, items = emptyList(), loading = true)
        }
        reloadItems(pullRefresh = false)
    }

    /** 返回上一级；根目录时无操作。 */
    fun navigateUp() {
        val stack = _state.value.dirStack
        if (stack.isEmpty()) return
        _state.update {
            it.copy(dirStack = stack.dropLast(1), items = emptyList(), loading = true)
        }
        reloadItems(pullRefresh = false)
    }

    /** 切换排序方式（持久化，偏好变化后自动重排）。 */
    fun setSortOrder(order: NoteSortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    /** 搜索输入（去抖 250ms，跨目录递归匹配文件名）。 */
    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _state.update { it.copy(searching = true) }
            val vault = currentSettings.vaultPath
            val results = if (vault.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val previewChars = currentSettings.previewChars
                        val progress = progressByPath()
                        vaultRepository.searchNotes(vault, trimmed).map { node ->
                            NoteListItem(
                                node = node,
                                preview = vaultRepository.readPreview(node.absolutePath, previewChars),
                                subtitle = node.relativePath.substringBeforeLast('/', "").ifEmpty { null },
                                progress = progress[node.absolutePath]
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            }
            _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searching = false) }
    }

    /** 重命名（笔记保留/补全 .md 扩展名，文件夹仅清洗非法字符）；成功后把改名同步到云端。 */
    fun rename(node: FileNode, input: String) {
        viewModelScope.launch {
            val newName = resolveNewName(node, input)
            if (newName == null) {
                _state.update { it.copy(message = "名称无效") }
                return@launch
            }
            if (newName == node.name) return@launch
            val parent = File(node.absolutePath).parent
            if (parent == null) {
                _state.update { it.copy(message = "重命名失败") }
                return@launch
            }
            val target = File(parent, newName).absolutePath
            val ok = withContext(Dispatchers.IO) { vaultRepository.rename(node.absolutePath, target) }
            _state.update { it.copy(message = if (ok) "已重命名" else "重命名失败（可能存在同名项）") }
            if (ok) {
                reloadItems(pullRefresh = false)
                syncRemoteRename(node.absolutePath, target)
            }
        }
    }

    /**
     * 本地重命名后把改名同步到云端（MOVE + 基线路径重映射），避免下次同步
     * 把它当成“旧路径删除 + 新路径新增”而重传整个目录。
     * 后台执行、不阻塞列表刷新；未配置同步或失败时静默跳过（同步日志可查）。
     */
    private fun syncRemoteRename(oldAbsPath: String, newAbsPath: String) {
        val vault = currentSettings.vaultPath
        if (vault.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { syncEngine.applyRemoteRename(vault, oldAbsPath, newAbsPath) }
        }
    }

    /** 删除：移入回收站（可恢复），不物理删除；目录整体入站。 */
    fun delete(node: FileNode) {
        viewModelScope.launch {
            val vault = currentSettings.vaultPath
            val ok = !vault.isNullOrBlank() &&
                withContext(Dispatchers.IO) { trashRepository.moveToTrash(vault, node.absolutePath) }
            _state.update { it.copy(message = if (ok) "已移入回收站「${node.name}」" else "删除失败") }
            if (ok) reloadItems(pullRefresh = false)
        }
    }

    /**
     * 从系统分享 / 内容传送门传入的文本新建笔记：文件名为「分享笔记 yyyy-MM-dd HHmmss」，
     * 存入当前目录（分享进入时通常位于根目录），成功后回调路径供直接进入编辑页。
     */
    fun createNoteFromShare(content: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val vault = currentSettings.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(message = "未选择 Vault 目录") }
                return@launch
            }
            val dir = _state.value.currentDir ?: vault
            val createdPath = runCatching {
                withContext(Dispatchers.IO) {
                    val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.getDefault()).format(Date())
                    val path = vaultRepository.uniqueNotePath(dir, "分享笔记 $stamp")
                    if (vaultRepository.createFile(path, content)) path else null
                }
            }.getOrNull()
            if (createdPath == null) {
                _state.update { it.copy(message = "新建分享笔记失败") }
                return@launch
            }
            reloadItems(pullRefresh = false)
            onCreated(createdPath)
        }
    }

    /** 在当前目录新建笔记并回调绝对路径（用于直接进入编辑页）。 */
    fun createNote(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val vault = currentSettings.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(message = "未选择 Vault 目录") }
                return@launch
            }
            val dir = _state.value.currentDir ?: vault
            val createdPath = runCatching {
                withContext(Dispatchers.IO) {
                    // 名称支持日期变量（如 yyyyMMdd），用当前时间解析后生成唯一路径
                    val baseName = VaultRepository.resolveDateName(currentSettings.defaultNoteName)
                    val path = vaultRepository.uniqueNotePath(dir, baseName)
                    if (vaultRepository.createFile(path, "")) path else null
                }
            }.getOrNull()
            if (createdPath == null) {
                _state.update { it.copy(message = "新建笔记失败") }
                return@launch
            }
            reloadItems(pullRefresh = false)
            onCreated(createdPath)
        }
    }

    /** 在当前目录新建文件夹并刷新列表（名称经 [VaultRepository.sanitizeEntryName] 清洗）。 */
    fun createFolder(input: String) {
        viewModelScope.launch {
            val vault = currentSettings.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(message = "未选择 Vault 目录") }
                return@launch
            }
            val name = VaultRepository.sanitizeEntryName(input)
            if (name == null) {
                _state.update { it.copy(message = "名称无效") }
                return@launch
            }
            val dir = _state.value.currentDir ?: vault
            val target = File(dir, name)
            val ok = withContext(Dispatchers.IO) {
                !target.exists() && vaultRepository.createDirectory(target.absolutePath)
            }
            _state.update {
                it.copy(message = if (ok) "已新建文件夹「$name」" else "新建文件夹失败（可能存在同名项）")
            }
            if (ok) reloadItems(pullRefresh = false)
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun resolveNewName(node: FileNode, input: String): String? {
        val cleaned = VaultRepository.sanitizeEntryName(input) ?: return null
        if (node.isDirectory) return cleaned
        return VaultRepository.ensureMarkdownName(cleaned)
    }

    private fun reloadItems(pullRefresh: Boolean) {
        val vault = currentSettings.vaultPath ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !pullRefresh && it.items.isEmpty(),
                    refreshing = pullRefresh,
                    error = null
                )
            }
            val dir = _state.value.currentDir ?: vault
            val result = runCatching {
                withContext(Dispatchers.IO) { loadListing(dir, vault) }
            }
            result.fold(
                onSuccess = { items ->
                    // 目录结构先渲染；沿用未变化文件的已加载预览，
                    // 避免刷新时预览先被清空再逐条重现（屏幕闪动）
                    _state.update { s ->
                        s.copy(
                            items = mergePreviews(s.items, items),
                            loading = false,
                            refreshing = false,
                            error = null
                        )
                    }
                    fillPreviews()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = e.message ?: "读取目录失败")
                    }
                }
            )
        }
    }

    /**
     * 用上一轮的预览填充新列表：仅当文件未变化（mtime 相同）时沿用，
     * 变化过的文件预览置空由 [fillPreviews] 重读，保证编辑后返回看到的是最新内容。
     */
    private fun mergePreviews(
        previous: List<NoteListItem>,
        next: List<NoteListItem>
    ): List<NoteListItem> {
        if (previous.isEmpty()) return next
        val byPath = previous.associateBy { it.node.absolutePath }
        return next.map { item ->
            val old = byPath[item.node.absolutePath] ?: return@map item
            if (old.preview.isNotEmpty() && old.node.lastModified == item.node.lastModified) {
                item.copy(preview = old.preview)
            } else {
                item
            }
        }
    }

    /**
     * 预览增量填充：目录先行渲染后，在 IO 上读取正文前缀并**分批**写回状态。
     * 每 [PREVIEW_BATCH] 条合并一次更新，避免逐条写入使列表反复重组（视觉闪动）；
     * 大批量文件时用户仍可立即看到列表；期间若切换目录 / 重新加载，
     * 本 Job 被取消，循环自然终止。
     */
    private suspend fun fillPreviews() {
        val previewChars = currentSettings.previewChars
        val targets = _state.value.items.filter { it.node.isMarkdown && it.preview.isEmpty() }
        if (targets.isEmpty()) return
        val pending = HashMap<String, String>()
        for (item in targets) {
            val preview = withContext(Dispatchers.IO) {
                vaultRepository.readPreview(item.node.absolutePath, previewChars)
            }
            if (preview.isNotEmpty()) pending[item.node.absolutePath] = preview
            if (pending.size >= PREVIEW_BATCH) flushPreviews(pending)
        }
        flushPreviews(pending)
    }

    /** 把累积的预览一次性写入状态并清空缓冲。 */
    private fun flushPreviews(pending: MutableMap<String, String>) {
        if (pending.isEmpty()) return
        _state.update { s ->
            s.copy(
                items = s.items.map { item ->
                    pending[item.node.absolutePath]?.let { item.copy(preview = it) } ?: item
                }
            )
        }
        pending.clear()
    }

    private suspend fun loadListing(dirPath: String, vaultPath: String): List<NoteListItem> {
        val nodes = vaultRepository.listChildren(dirPath, vaultPath)
        val folders = nodes.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val notes = sortNotes(nodes.filter { it.isMarkdown }, currentSettings.sortOrder)
        val progress = progressByPath()
        return folders.map { NoteListItem(it) } + notes.map {
            NoteListItem(it, progress = progress[it.absolutePath])
        }
    }

    /**
     * 读取全部阅读进度 → 绝对路径映射；0%（未读）与 ≥99%（已读完）不显示小圆点，
     * 取整后钳制在 1–99，保证圆点弧长可见。
     */
    private suspend fun progressByPath(): Map<String, Int> =
        runCatching {
            progressRepository.all()
                .filter { it.percent > 0.01f && it.percent < 0.99f }
                .associate { it.path to (it.percent * 100).roundToInt().coerceIn(1, 99) }
        }.getOrDefault(emptyMap())

    private fun sortNotes(notes: List<FileNode>, order: NoteSortOrder): List<FileNode> = when (order) {
        NoteSortOrder.MODIFIED_DESC -> notes.sortedByDescending { it.lastModified }
        NoteSortOrder.MODIFIED_ASC -> notes.sortedBy { it.lastModified }
        NoteSortOrder.NAME_ASC -> notes.sortedBy { it.name.lowercase() }
        NoteSortOrder.NAME_DESC -> notes.sortedByDescending { it.name.lowercase() }
    }

    private companion object {
        /** 预览批量写回的条数：约一屏，兼顾首屏速度与重组次数。 */
        const val PREVIEW_BATCH = 12
    }
}
