package com.az.notes.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.local.ProgressRepository
import com.az.notes.data.local.ReadProgressEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.markdown.FrontmatterAttribute
import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.Heading
import com.az.notes.domain.markdown.HeadingExtractor
import com.az.notes.domain.markdown.TaskExtractor
import com.az.notes.domain.markdown.TaskItem
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.toUiText
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
    val initialProgress: ReadProgressEntity? = null,
    /** 当前笔记所属 Vault 根（图片相对路径解析用）。 */
    val vaultPath: String? = null,
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
                val split = withContext(Dispatchers.IO) { FrontmatterParser.split(text) }
                val noteType = split.value("note_type")?.trim()?.lowercase()
                val parsed = withContext(Dispatchers.IO) { HeadingExtractor.parse(split.body) }
                val tasks = if (noteType == "task") {
                    withContext(Dispatchers.IO) { TaskExtractor.extract(split.body) }
                } else emptyList()
                val progress = if (showLoading) {
                    withContext(Dispatchers.IO) { progressRepository.load(absolutePath) }
                } else {
                    _state.value.initialProgress
                }
                totalBlocks = parsed.blockCount.coerceAtLeast(1)
                _state.update {
                    it.copy(
                        loading = false,
                        content = split.body,
                        headings = parsed.headings,
                        vaultPath = vault,
                        initialProgress = progress,
                        noteType = noteType,
                        attributes = split.entries,
                        tasks = tasks,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUiText(R.string.msg_read_failed)) }
            } finally {
                loadedOnce = true
            }
        }
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
                    charCount = content.count { !it.isWhitespace() },
                    lineCount = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1,
                    headingCount = snapshot.headings.size
                )
            }
            _state.update { it.copy(docInfo = info) }
        }
    }

    /** 关闭“文档信息”对话框。 */
    fun clearDocInfo() {
        _state.update { it.copy(docInfo = null) }
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

    /** 清除已展示的一次性提示。 */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
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
                load(showLoading = false)
            } else {
                _state.update { it.copy(message = UiText.of(R.string.msg_task_save_failed)) }
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
