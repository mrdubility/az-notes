package com.az.notes.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.local.ProgressRepository
import com.az.notes.data.local.ReadProgressEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.markdown.Heading
import com.az.notes.domain.markdown.HeadingExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ReaderUiState(
    val path: String = "",
    val loading: Boolean = true,
    val content: String = "",
    val headings: List<Heading> = emptyList(),
    val initialProgress: ReadProgressEntity? = null,
    /** 当前笔记所属 Vault 根（图片相对路径解析用）。 */
    val vaultPath: String? = null,
    val error: String? = null
)

/**
 * 阅读器 ViewModel（§5.2 / §5.4 / §5.5）。
 * 加载正文、基于 AST 提取大纲（块序号与渲染一一对应）、读取上次进度，
 * 并对滚动进度做 500ms 去抖落库。
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val absolutePath: String =
        savedStateHandle.get<String>("path") ?: ""

    private val _state = MutableStateFlow(ReaderUiState(path = absolutePath))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 顶层块总数（与渲染块下标同口径），用于进度百分比换算。 */
    private var totalBlocks: Int = 1

    /** 首次加载是否已结束（区分“首次进入”与“从编辑页返回”，避免重复读盘）。 */
    private var loadedOnce = false

    private var saveProgressJob: Job? = null

    init {
        load(showLoading = true)
    }

    /**
     * 从编辑页返回（预览页重新进入组合）时静默重读正文，保证看到的是最新内容。
     * 不显示 loading、不重读阅读进度，避免画面闪一下或滚动位置被拉回。
     */
    fun reload() {
        if (loadedOnce) load(showLoading = false)
    }

    private fun load(showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _state.update { it.copy(loading = true, error = null) }
            try {
                val vault = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
                val text = withContext(Dispatchers.IO) { vaultRepository.readText(absolutePath) }
                val parsed = withContext(Dispatchers.IO) { HeadingExtractor.parse(text) }
                val progress = if (showLoading) {
                    withContext(Dispatchers.IO) { progressRepository.load(absolutePath) }
                } else {
                    _state.value.initialProgress
                }
                totalBlocks = parsed.blockCount.coerceAtLeast(1)
                _state.update {
                    it.copy(
                        loading = false,
                        content = text,
                        headings = parsed.headings,
                        vaultPath = vault,
                        initialProgress = progress,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取失败") }
            } finally {
                loadedOnce = true
            }
        }
    }

    /** 预览 LazyColumn 滚动位置变化回调（§5.5；块序号为真实 AST 顶层块索引）。 */
    fun onScrollPosition(firstVisibleIndex: Int, offset: Int) {
        val percent = if (totalBlocks <= 0) 0f
        else (firstVisibleIndex.toFloat() / totalBlocks).coerceIn(0f, 1f)
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            delay(500)
            runCatching {
                withContext(Dispatchers.IO) {
                    progressRepository.save(absolutePath, firstVisibleIndex, offset, percent)
                }
            }
        }
    }
}
