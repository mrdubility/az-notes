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
import javax.inject.Inject

data class EditorUiState(
    val path: String = "",
    val loading: Boolean = true,
    val text: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val savedAt: Long? = null,
    val error: String? = null
)

/**
 * 编辑页 ViewModel（§5.3）。源码编辑 + 停止输入 1.5s 自动保存（原子写）。
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val absolutePath: String =
        savedStateHandle.get<String>("path") ?: ""

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
}
