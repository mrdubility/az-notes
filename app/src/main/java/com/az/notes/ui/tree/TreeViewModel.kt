package com.az.notes.ui.tree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.local.FileIndexDao
import com.az.notes.data.local.FileIndexEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.FileNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TreeUiState(
    val loading: Boolean = false,
    val vaultPath: String? = null,
    val rows: List<FileNode> = emptyList(),          // 当前可见行（已按展开状态过滤）
    val totalCount: Int = 0,
    val searchQuery: String = "",
    val searchResults: List<FileNode> = emptyList(),
    val error: String? = null
)

/**
 * 目录树 ViewModel（§5.1）。
 * 打开 Vault 时后台 `Dispatchers.IO` 递归扫描并持久化 [FileIndexEntity] 索引，
 * UI 侧以扁平列表 + depth 缩进渲染，展开/收起状态存于内存。
 */
@HiltViewModel
class TreeViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val fileIndexDao: FileIndexDao
) : ViewModel() {

    private val _state = MutableStateFlow(TreeUiState())
    val state: StateFlow<TreeUiState> = _state.asStateFlow()

    /** 已折叠的目录相对路径集合（默认全部展开，集合内为“收起”）。 */
    private val collapsedDirs = MutableStateFlow<Set<String>>(emptySet())

    private var allNodes: List<FileNode> = emptyList()

    init {
        viewModelScope.launch {
            collapsedDirs.collect { rebuildVisibleRows() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val vaultPath = settingsRepository.settings.first().vaultPath
            if (vaultPath.isNullOrBlank()) {
                _state.value = _state.value.copy(error = "未选择 Vault 目录")
                return@launch
            }
            _state.value = _state.value.copy(loading = true, vaultPath = vaultPath, error = null)
            try {
                val nodes = withContext(Dispatchers.IO) { vaultRepository.scanTree(vaultPath) }
                allNodes = nodes
                persistIndex(vaultPath, nodes)
                _state.value = _state.value.copy(loading = false, totalCount = nodes.size)
                rebuildVisibleRows()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "扫描失败")
            }
        }
    }

    fun toggle(dirPath: String) {
        collapsedDirs.value = collapsedDirs.value.toMutableSet().apply {
            if (!remove(dirPath)) add(dirPath)
        }
    }

    fun onSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(searchQuery = "", searchResults = emptyList())
            return
        }
        val results = allNodes.filter {
            !it.isDirectory && it.name.contains(trimmed, ignoreCase = true)
        }.sortedBy { it.name.lowercase() }
        _state.value = _state.value.copy(searchQuery = trimmed, searchResults = results)
    }

    private suspend fun persistIndex(vaultPath: String, nodes: List<FileNode>) {
        withContext(Dispatchers.IO) {
            val entities = nodes.map {
                val parentRel = it.relativePath.substringBeforeLast('/', ".")
                FileIndexEntity(
                    path = it.relativePath,
                    name = it.name,
                    isDir = it.isDirectory,
                    size = it.size,
                    mtime = it.lastModified,
                    parentPath = parentRel,
                    depth = it.depth
                )
            }
            fileIndexDao.rebuild(entities)
        }
    }

    private suspend fun rebuildVisibleRows() {
        val collapsed = collapsedDirs.value
        val visible = ArrayList<FileNode>(allNodes.size)
        var skipDepth = -1
        for (node in allNodes) {
            if (skipDepth >= 0) {
                if (node.depth > skipDepth) continue else skipDepth = -1
            }
            visible += node
            if (node.isDirectory && node.relativePath in collapsed) {
                skipDepth = node.depth
            }
        }
        _state.value = _state.value.copy(rows = visible)
    }
}
