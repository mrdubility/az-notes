package com.az.notes.ui.tags

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TagIndex
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.FileNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 标签结果条目：节点 + 正文预览 + 所在目录（相对 Vault）。 */
data class TagDocItem(
    val node: FileNode,
    val preview: String = "",
    val subtitle: String? = null
)

data class TagDocsUiState(
    val loading: Boolean = true,
    /** 查询的标签名（顶栏展示 `#tag`） */
    val tag: String = "",
    /** 含该标签的文档（按修改时间倒序） */
    val items: List<TagDocItem> = emptyList()
)

/**
 * 标签结果页 ViewModel：确保倒排索引就绪后取含 [tag] 的全部文档（O(1) 倒排查询，
 * 不跑全文搜索），预览为列表页同款头部小字节读取。
 */
@HiltViewModel
class TagDocsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
    private val tagIndex: TagIndex,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val tag = savedStateHandle.get<String>("tag").orEmpty()

    private val _state = MutableStateFlow(TagDocsUiState(tag = tag))
    val state: StateFlow<TagDocsUiState> = _state.asStateFlow()

    /** 刷新索引并取该标签的文档列表（每次进入页面调用，增量成本低）。 */
    fun refresh() {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val vault = settings?.vaultPath.orEmpty()
            if (vault.isBlank()) {
                _state.update { it.copy(loading = false, items = emptyList()) }
                return@launch
            }
            val previewChars = settings?.previewChars ?: 100
            _state.update { it.copy(loading = true) }
            tagIndex.buildOrRefresh(vault)
            val docs = tagIndex.docsOf(tag)
            val items = withContext(Dispatchers.IO) {
                docs.map { node ->
                    async {
                        TagDocItem(
                            node = node,
                            preview = vaultRepository.readPreview(node.absolutePath, previewChars),
                            subtitle = node.relativePath.substringBeforeLast('/', "")
                                .ifEmpty { null }
                        )
                    }
                }.awaitAll()
            }
            _state.update { it.copy(loading = false, items = items) }
        }
    }
}
