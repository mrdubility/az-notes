package com.az.notes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.AppSettings
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
import javax.inject.Inject

/** 主页列表条目：文件夹仅展示名称；笔记附带正文预览与（搜索时的）所在目录。 */
data class NoteListItem(
    val node: FileNode,
    val preview: String = "",
    val subtitle: String? = null
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
 * 排序（修改时间/名称）、递归搜索、重命名、删除、新建笔记。
 * 所有文件操作在 `Dispatchers.IO` 执行；偏好变化（Vault / 排序 / 预览字符数）自动重载。
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

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
        _state.update { it.copy(sortOrder = s.sortOrder) }

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
                        vaultRepository.searchNotes(vault, trimmed).map { node ->
                            NoteListItem(
                                node = node,
                                preview = vaultRepository.readPreview(node.absolutePath, previewChars),
                                subtitle = node.relativePath.substringBeforeLast('/', "").ifEmpty { null }
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

    /** 重命名（保留/补全 .md 扩展名；非法字符过滤）。 */
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
            if (ok) reloadItems(pullRefresh = false)
        }
    }

    /** 删除（二次确认由 UI 负责）。 */
    fun delete(node: FileNode) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { vaultRepository.delete(node.absolutePath) }
            _state.update { it.copy(message = if (ok) "已删除「${node.name}」" else "删除失败") }
            if (ok) reloadItems(pullRefresh = false)
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
                    val path = vaultRepository.uniqueNotePath(dir)
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

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun resolveNewName(node: FileNode, input: String): String? {
        val cleaned = input.trim()
            .filterNot { it in "\\/:*?\"<>|" }
            .trim()
        if (cleaned.isEmpty() || cleaned.startsWith(".")) return null
        if (node.isDirectory) return cleaned
        return if (VaultRepository.isMarkdownName(cleaned)) cleaned else "$cleaned.md"
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
                    _state.update { it.copy(items = items, loading = false, refreshing = false, error = null) }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = e.message ?: "读取目录失败")
                    }
                }
            )
        }
    }

    private fun loadListing(dirPath: String, vaultPath: String): List<NoteListItem> {
        val nodes = vaultRepository.listChildren(dirPath, vaultPath)
        val folders = nodes.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val notes = sortNotes(nodes.filter { it.isMarkdown }, currentSettings.sortOrder)
        val previewChars = currentSettings.previewChars
        return folders.map { NoteListItem(it) } +
            notes.map { NoteListItem(it, preview = vaultRepository.readPreview(it.absolutePath, previewChars)) }
    }

    private fun sortNotes(notes: List<FileNode>, order: NoteSortOrder): List<FileNode> = when (order) {
        NoteSortOrder.MODIFIED_DESC -> notes.sortedByDescending { it.lastModified }
        NoteSortOrder.MODIFIED_ASC -> notes.sortedBy { it.lastModified }
        NoteSortOrder.NAME_ASC -> notes.sortedBy { it.name.lowercase() }
        NoteSortOrder.NAME_DESC -> notes.sortedByDescending { it.name.lowercase() }
    }
}
