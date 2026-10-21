package com.az.notes.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TagIndex
import com.az.notes.data.storage.TagRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

data class TagsUiState(
    val loading: Boolean = true,
    /** 当前仓库展示名（页面标题下标注「仓库：xxx」） */
    val vaultName: String? = null,
    val query: String = "",
    /** 全量标签（计数降序）；过滤为内存态即席 contains，见 [visible] */
    val all: List<TagRow> = emptyList()
) {
    /** 搜索框过滤后的可见标签（大小写不敏感包含匹配）。 */
    val visible: List<TagRow>
        get() = if (query.isBlank()) all
        else all.filter { it.name.contains(query.trim(), ignoreCase = true) }
}

/**
 * 标签页 ViewModel：进入页面时经 [TagIndex.buildOrRefresh] 构建 / 增量刷新
 * 全库标签倒排索引（首次冷扫、之后指纹增量），搜索框过滤为内存态零 IO。
 */
@HiltViewModel
class TagsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tagIndex: TagIndex
) : ViewModel() {

    private val _state = MutableStateFlow(TagsUiState())
    val state: StateFlow<TagsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                val vaultName = s.vaults.firstOrNull { it.id == s.currentVaultId }?.name
                _state.update { it.copy(vaultName = vaultName) }
            }
        }
    }

    /** 构建 / 刷新索引并发布标签列表（每次进入页面调用，增量成本低）。 */
    fun refresh() {
        viewModelScope.launch {
            val vault = runCatching { settingsRepository.settings.first().vaultPath }
                .getOrNull().orEmpty()
            if (vault.isBlank()) {
                _state.update { it.copy(loading = false, all = emptyList()) }
                return@launch
            }
            _state.update { it.copy(loading = true) }
            tagIndex.buildOrRefresh(vault)
            _state.update { it.copy(loading = false, all = tagIndex.all()) }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }
}
