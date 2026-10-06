package com.az.notes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
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
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    /** 是否启用回收站（关闭时删除直接物理删除、抽屉隐藏入口） */
    val trashEnabled: Boolean = true,
    /** 是否还有后续批次未装载（滚动到底自动加载） */
    val hasMore: Boolean = false,
    /** 多选模式（长按进入）：批量移动笔记 */
    val selectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    /** 批量移动对话框的目标文件夹列表（null = 尚未加载） */
    val moveTargets: List<FileNode>? = null,
    val error: UiText? = null,
    /** 一次性提示（Snackbar），消费后清空 */
    val message: UiText? = null
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
    private var loadMoreJob: Job? = null
    private var searchJob: Job? = null

    /** 列表装载世代号：reload 后旧批次（loadMore）的写回作废 */
    private var listingGeneration = 0

    /** 当前目录尚未装载预览的后续笔记（滚动到底逐批加载） */
    private var pendingNotes: List<FileNode> = emptyList()

    /** 正文预览内存缓存（绝对路径 → 文件 mtime + 预览）：跨目录往返与重复刷新零重读。 */
    private val previewCache = ConcurrentHashMap<String, CachedPreview>()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { applySettings(it) }
        }
    }

    private fun applySettings(s: AppSettings) {
        val prev = currentSettings
        currentSettings = s
        _state.update {
            it.copy(sortOrder = s.sortOrder, fabAction = s.fabAction, trashEnabled = s.trashEnabled)
        }

        when {
            s.vaultPath != prev.vaultPath -> {
                _state.update {
                    it.copy(
                        vaultPath = s.vaultPath,
                        dirStack = emptyList(),
                        items = emptyList(),
                        searchQuery = "",
                        searchResults = emptyList(),
                        selectMode = false,
                        selectedPaths = emptySet(),
                        moveTargets = null
                    )
                }
                if (s.vaultPath.isNullOrBlank()) {
                    initialized = false
                    _state.update { it.copy(loading = false, error = UiText.of(R.string.home_no_vault)) }
                } else {
                    initialized = true
                    reloadItems(pullRefresh = false)
                }
            }
            // 预览字符数 / 排序方式变化：保持当前目录重新加载
            initialized && (s.previewChars != prev.previewChars || s.sortOrder != prev.sortOrder) -> {
                if (s.previewChars != prev.previewChars) previewCache.clear() // 预览长度变化：缓存失效
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

    /** 进入子文件夹：保留当前列表直到新数据就绪，避免白屏 / 转圈闪动。 */
    fun enterDir(node: FileNode) {
        if (!node.isDirectory) return
        _state.update { it.copy(dirStack = it.dirStack + node.absolutePath) }
        reloadItems(pullRefresh = false)
    }

    /** 返回上一级；根目录时无操作。列表保留到新数据就绪，避免闪动。 */
    fun navigateUp() {
        val stack = _state.value.dirStack
        if (stack.isEmpty()) return
        _state.update { it.copy(dirStack = stack.dropLast(1)) }
        reloadItems(pullRefresh = false)
    }

    /** 切换排序方式（持久化，偏好变化后自动重排）。 */
    fun setSortOrder(order: NoteSortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    /** 搜索输入（去抖 250ms，跨目录全局搜索：文件名 + 正文内容）。 */
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
                        vaultRepository.searchNotes(vault, trimmed).map { hit ->
                            NoteListItem(
                                node = hit.node,
                                // 正文命中展示上下文片段，文件名命中展示正文预览
                                preview = hit.snippet ?: previewFor(hit.node, previewChars),
                                subtitle = hit.node.relativePath.substringBeforeLast('/', "").ifEmpty { null },
                                progress = progress[hit.node.absolutePath]
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
                _state.update { it.copy(message = UiText.of(R.string.msg_invalid_name)) }
                return@launch
            }
            if (newName == node.name) return@launch
            val parent = File(node.absolutePath).parent
            if (parent == null) {
                _state.update { it.copy(message = UiText.of(R.string.msg_rename_failed)) }
                return@launch
            }
            val target = File(parent, newName).absolutePath
            val ok = withContext(Dispatchers.IO) { vaultRepository.rename(node.absolutePath, target) }
            _state.update {
                it.copy(
                    message = UiText.of(
                        if (ok) R.string.msg_renamed else R.string.msg_rename_failed_exists
                    )
                )
            }
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

    /** 删除：启用回收站时移入回收站（可恢复）；关闭时直接物理删除（设置页有警示确认）。 */
    fun delete(node: FileNode) {
        viewModelScope.launch {
            val vault = currentSettings.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(message = UiText.of(R.string.home_no_vault)) }
                return@launch
            }
            val toTrash = currentSettings.trashEnabled
            val ok = withContext(Dispatchers.IO) {
                if (toTrash) trashRepository.moveToTrash(vault, node.absolutePath)
                else vaultRepository.delete(node.absolutePath)
            }
            _state.update {
                it.copy(
                    message = when {
                        !ok -> UiText.of(R.string.msg_delete_failed)
                        toTrash -> UiText.of(R.string.msg_moved_to_trash, node.name)
                        else -> UiText.of(R.string.msg_deleted, node.name)
                    }
                )
            }
            if (ok) reloadItems(pullRefresh = false)
        }
    }

    /**
     * 从系统分享 / 内容传送门传入的文本新建笔记：文件名为默认新建笔记名
     * （与新建笔记同规则，支持 `$日期变量$`），存入当前目录，成功后回调路径供直接进入编辑页。
     */
    fun createNoteFromShare(content: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            // 冷启动分享时设置可能尚未回流到 currentSettings：直接读一次最新快照，
            // 避免误报“未选择 Vault 目录”并丢失分享内容
            val snapshot = runCatching { settingsRepository.settings.first() }.getOrNull()
            val vault = snapshot?.vaultPath ?: currentSettings.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(message = UiText.of(R.string.home_no_vault)) }
                return@launch
            }
            val dir = _state.value.currentDir ?: vault
            val createdPath = runCatching {
                withContext(Dispatchers.IO) {
                    val baseName = VaultRepository.resolveDateName(
                        snapshot?.defaultNoteName ?: currentSettings.defaultNoteName
                    )
                    val path = vaultRepository.uniqueNotePath(dir, baseName)
                    if (vaultRepository.createFile(path, content)) path else null
                }
            }.getOrNull()
            if (createdPath == null) {
                _state.update { it.copy(message = UiText.of(R.string.msg_share_note_failed)) }
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
                _state.update { it.copy(message = UiText.of(R.string.home_no_vault)) }
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
                _state.update { it.copy(message = UiText.of(R.string.msg_note_create_failed)) }
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
                _state.update { it.copy(message = UiText.of(R.string.home_no_vault)) }
                return@launch
            }
            val name = VaultRepository.sanitizeEntryName(input)
            if (name == null) {
                _state.update { it.copy(message = UiText.of(R.string.msg_invalid_name)) }
                return@launch
            }
            val dir = _state.value.currentDir ?: vault
            val target = File(dir, name)
            val ok = withContext(Dispatchers.IO) {
                !target.exists() && vaultRepository.createDirectory(target.absolutePath)
            }
            _state.update {
                it.copy(
                    message = if (ok) UiText.of(R.string.msg_folder_created, name)
                    else UiText.of(R.string.msg_folder_create_failed)
                )
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

    // ——————————————— 多选批量移动（整理笔记） ———————————————

    /** 长按条目：进入多选模式并选中该项。 */
    fun enterSelectMode(path: String) {
        _state.update { it.copy(selectMode = true, selectedPaths = setOf(path)) }
    }

    /** 多选模式下点击条目：切换选中；已无选中项时退出多选模式。 */
    fun toggleSelected(path: String) {
        _state.update { s ->
            if (!s.selectMode) return@update s
            val selected = if (path in s.selectedPaths) s.selectedPaths - path else s.selectedPaths + path
            s.copy(selectedPaths = selected, selectMode = selected.isNotEmpty())
        }
    }

    /** 退出多选模式。 */
    fun exitSelectMode() {
        _state.update { it.copy(selectMode = false, selectedPaths = emptySet()) }
    }

    /** 全选 / 取消全选当前列表。 */
    fun toggleSelectAll() {
        _state.update { s ->
            val all = s.items.map { it.node.absolutePath }.toSet()
            if (all.isNotEmpty() && s.selectedPaths.size >= all.size) {
                s.copy(selectedPaths = emptySet(), selectMode = false)
            } else {
                s.copy(selectMode = true, selectedPaths = all)
            }
        }
    }

    /** 打开移动对话框前加载目标文件夹列表（递归全部子目录）。 */
    fun loadMoveTargets() {
        val vault = currentSettings.vaultPath ?: return
        if (_state.value.moveTargets != null) return
        viewModelScope.launch {
            val dirs = withContext(Dispatchers.IO) { vaultRepository.listAllDirectories(vault) }
            _state.update { it.copy(moveTargets = dirs) }
        }
    }

    /** 将选中的条目批量移动到 [targetDir]（逐个移动，重名跳过）；完成后退出多选并刷新。 */
    fun moveSelectedTo(targetDir: String) {
        viewModelScope.launch {
            val paths = _state.value.selectedPaths.toList()
            if (paths.isEmpty()) return@launch
            var skipped = 0
            val moved = withContext(Dispatchers.IO) {
                var ok = 0
                for (src in paths) {
                    val name = File(src).name
                    val target = File(targetDir, name).absolutePath
                    if (target == src) {
                        skipped++ // 已在该目录：跳过且不计失败
                        continue
                    }
                    if (vaultRepository.rename(src, target)) {
                        ok++
                        syncRemoteRename(src, target)
                    }
                }
                ok
            }
            val failed = paths.size - moved - skipped
            _state.update {
                it.copy(
                    message = if (failed == 0) UiText.of(R.string.home_moved_count, moved)
                    else UiText.of(R.string.home_move_partial, moved, failed)
                )
            }
            exitSelectMode()
            reloadItems(pullRefresh = false)
        }
    }

    private fun reloadItems(pullRefresh: Boolean) {
        val vault = currentSettings.vaultPath ?: return
        loadJob?.cancel()
        loadMoreJob?.cancel()
        listingGeneration++
        val generation = listingGeneration
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !pullRefresh && it.items.isEmpty(),
                    refreshing = pullRefresh,
                    error = null,
                    // 目录 / 偏好变化：选择状态可能指向已失效条目，一并重置
                    selectMode = false,
                    selectedPaths = emptySet(),
                    moveTargets = null
                )
            }
            val dir = _state.value.currentDir ?: vault
            val result = runCatching {
                withContext(Dispatchers.IO) { loadListing(dir, vault) }
            }
            if (generation != listingGeneration) return@launch
            result.fold(
                onSuccess = { page ->
                    pendingNotes = page.pending
                    _state.update { s ->
                        s.copy(
                            items = page.items,
                            loading = false,
                            refreshing = false,
                            error = null,
                            hasMore = page.pending.isNotEmpty()
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = e.toUiText(R.string.msg_list_load_failed)
                        )
                    }
                }
            )
        }
    }

    /**
     * 滚动接近列表末尾时加载下一批：为后续笔记读取预览后追加到列表。
     * 仅追加不改动已显示条目，避免已渲染内容闪动与滚动位置跳动。
     */
    fun loadMore() {
        val remaining = pendingNotes
        if (remaining.isEmpty() || loadMoreJob?.isActive == true) return
        val generation = listingGeneration
        loadMoreJob = viewModelScope.launch {
            val batch = remaining.take(PAGE_SIZE)
            val progress = progressByPath()
            val previewChars = currentSettings.previewChars
            val newItems = withContext(Dispatchers.IO) {
                batch.map { node ->
                    NoteListItem(
                        node = node,
                        preview = previewFor(node, previewChars),
                        progress = progress[node.absolutePath]
                    )
                }
            }
            if (generation != listingGeneration) return@launch
            pendingNotes = remaining.drop(PAGE_SIZE)
            _state.update { s ->
                s.copy(items = s.items + newItems, hasMore = pendingNotes.isNotEmpty())
            }
        }
    }

    /** 一页列表数据：已就绪条目（文件夹 + 首批笔记）+ 尚未装载预览的后续笔记。 */
    private data class ListingPage(val items: List<NoteListItem>, val pending: List<FileNode>)

    /**
     * 列表分批装载：文件夹无需预览，全部立即包含；笔记按 [PAGE_SIZE] 逐批读取预览，
     * 首批就绪即提交 UI（首屏等待只与首批相关），其余由 [loadMore] 追加。
     * 列目录 / 阅读进度 / 首批预览三路并行读取，缩短首屏等待；
     * 预览读取走 [previewFor] 内存缓存，未变化文件不重读、往返目录零等待。
     */
    private suspend fun loadListing(dirPath: String, vaultPath: String): ListingPage = coroutineScope {
        val nodesAsync = async(Dispatchers.IO) { vaultRepository.listChildren(dirPath, vaultPath) }
        val progressAsync = async(Dispatchers.IO) { progressByPath() }
        val nodes = nodesAsync.await()
        val folders = nodes.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val notes = sortNotes(nodes.filter { it.isMarkdown }, currentSettings.sortOrder)
        val progress = progressAsync.await()
        val previewChars = currentSettings.previewChars
        val firstBatch = notes.take(PAGE_SIZE)
        // 首批预览并行读取（单文件为小字节读，并行显著缩短首屏等待；awaitAll 保序）
        val firstItems = firstBatch
            .map { node ->
                async(Dispatchers.IO) {
                    NoteListItem(
                        node = node,
                        preview = previewFor(node, previewChars),
                        progress = progress[node.absolutePath]
                    )
                }
            }
            .awaitAll()
        val items = folders.map { NoteListItem(it) } + firstItems
        ListingPage(items, notes.drop(PAGE_SIZE))
    }

    /** 预览缓存条目：文件 mtime 未变即可复用。 */
    private data class CachedPreview(val lastModified: Long, val preview: String)

    /**
     * 预览读取：命中缓存（mtime 未变）直接复用，否则读盘并写回缓存；
     * 读取为空（无正文 / 失败）时不缓存，下次仍会重试。
     */
    private fun previewFor(node: FileNode, previewChars: Int): String {
        val cached = previewCache[node.absolutePath]
        if (cached != null && cached.lastModified == node.lastModified) return cached.preview
        val preview = vaultRepository.readPreview(node.absolutePath, previewChars)
        if (preview.isNotEmpty()) {
            if (previewCache.size >= PREVIEW_CACHE_LIMIT) previewCache.clear()
            previewCache[node.absolutePath] = CachedPreview(node.lastModified, preview)
        }
        return preview
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
        /** 预览缓存上限：超过后整体清空，控制内存占用。 */
        const val PREVIEW_CACHE_LIMIT = 2000

        /** 列表每批装载的笔记数：首屏只读首批预览，其余滚动到底自动加载。 */
        const val PAGE_SIZE = 60
    }
}
