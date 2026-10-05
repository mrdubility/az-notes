package com.az.notes.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.storage.VaultRepository
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

data class EditorUiState(
    val path: String = "",
    val loading: Boolean = true,
    val text: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val savedAt: Long? = null,
    val error: String? = null,
    /** 一次性提示（Snackbar），消费后清空 */
    val message: String? = null
)

/**
 * 编辑页 ViewModel（§5.3）。源码编辑 + 停止输入 1.5s 自动保存（原子写）。
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private var absolutePath: String =
        savedStateHandle.get<String>("path") ?: ""

    /** 是否刚从“新建笔记”进入：退出时若仍无任何内容则清理空文件（不产生空笔记）。 */
    private val fresh: Boolean = savedStateHandle.get<Boolean>("fresh") ?: false

    private val _state = MutableStateFlow(EditorUiState(path = absolutePath))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var autoSaveJob: Job? = null

    init {
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { vaultRepository.readText(absolutePath) }
            }.getOrElse {
                _state.update { s -> s.copy(loading = false, error = it.message ?: "读取失败") }
                return@launch
            }
            _state.update { it.copy(loading = false, text = text, dirty = false) }
        }
    }

    fun onTextChange(newText: String) {
        _state.update { it.copy(text = newText, dirty = true) }
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(1500)
            save()
        }
    }

    fun save() {
        val current = _state.value
        if (!current.dirty || current.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    vaultRepository.writeTextAtomically(absolutePath, current.text)
                }
            }
            result.fold(
                onSuccess = { _state.update { it.copy(saving = false, dirty = false, savedAt = System.currentTimeMillis()) } },
                onFailure = { e -> _state.update { it.copy(saving = false, error = e.message ?: "保存失败") } }
            )
        }
    }

    /**
     * 退出编辑页前的收尾（返回按钮 / 系统返回共用，完成后才应导航返回）：
     * - 新建笔记且从未写入任何内容 → 删除占位空文件，不留下空笔记；
     * - 否则将未落盘内容写入磁盘（避免返回瞬间销毁 ViewModel 丢字）。
     */
    suspend fun flushOnExit() {
        val current = _state.value
        if (current.loading) return
        autoSaveJob?.cancel()
        val path = absolutePath
        val discardFresh = fresh && current.text.isBlank()
        withContext(Dispatchers.IO) {
            if (discardFresh) {
                vaultRepository.delete(path)
            } else if (current.dirty) {
                runCatching { vaultRepository.writeTextAtomically(path, current.text) }
            }
        }
        if (discardFresh) {
            _state.update { it.copy(dirty = false) }
        }
    }

    /** 常用标记插入（工具栏）。[wrap] 为成对标记，光标内容包裹。 */
    fun applyDecoration(before: String, after: String = before) {
        val s = _state.value.text
        val decorated = if (s.isEmpty()) before + after else s + " " + before + "文本" + after
        _state.update { it.copy(text = decorated, dirty = true) }
    }

    /** 行首标记插入（标题/列表/代码块）。 */
    fun insertLinePrefix(prefix: String) {
        val s = _state.value.text
        _state.update { it.copy(text = if (s.isEmpty()) prefix else "$s\n$prefix", dirty = true) }
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch { delay(1500); save() }
    }

    /**
     * 重命名当前笔记（编辑页标题入口）：
     * 清洗输入 → 先落盘未保存内容 → 同目录改名 → 更新内部路径（后续自动保存写新路径）。
     */
    fun rename(input: String) {
        viewModelScope.launch {
            val current = _state.value
            if (current.loading) return@launch
            val cleaned = VaultRepository.sanitizeEntryName(input)
            if (cleaned == null) {
                _state.update { it.copy(message = "名称无效") }
                return@launch
            }
            val newName = VaultRepository.ensureMarkdownName(cleaned)
            val src = File(absolutePath)
            if (newName == src.name) return@launch
            val target = File(src.parentFile, newName)
            if (target.exists()) {
                _state.update { it.copy(message = "已存在同名文件") }
                return@launch
            }
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    // 先保存未落盘内容，避免改名后丢失编辑
                    if (current.dirty) vaultRepository.writeTextAtomically(absolutePath, current.text)
                    vaultRepository.rename(absolutePath, target.absolutePath)
                }
            }.getOrDefault(false)
            if (ok) {
                absolutePath = target.absolutePath
                _state.update {
                    it.copy(
                        path = absolutePath,
                        dirty = false,
                        savedAt = System.currentTimeMillis(),
                        message = "已重命名为「$newName」"
                    )
                }
            } else {
                _state.update { it.copy(message = "重命名失败（可能存在同名项）") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
