package com.az.notes.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TrashBatch
import com.az.notes.data.storage.TrashItem
import com.az.notes.data.storage.TrashRepository
import com.az.notes.ui.common.UiMessage
import com.az.notes.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TrashUiState(
    val vaultPath: String? = null,
    val loading: Boolean = true,
    val batches: List<TrashBatch> = emptyList(),
    /** 一次性提示（Snackbar），消费后清空；携带撤销动作时横幅右侧显示「撤销」按钮 */
    val message: UiMessage? = null
)

/** 回收站 ViewModel：列表 / 恢复 / 删除 / 清空；文件操作在 IO 上执行。 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** 重新读取回收站目录。 */
    fun reload() {
        viewModelScope.launch {
            val vault = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
            val batches = withContext(Dispatchers.IO) { trashRepository.batches() }
            _state.update { it.copy(vaultPath = vault, loading = false, batches = batches) }
        }
    }

    /** 恢复到 Vault 原路径（目标同名时由仓库自动加 (restored) 后缀）；横幅可撤销移回回收站。 */
    fun restore(item: TrashItem) {
        viewModelScope.launch {
            val vault = _state.value.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(message = UiMessage(UiText.of(R.string.msg_vault_unset))) }
                return@launch
            }
            val restored = withContext(Dispatchers.IO) { trashRepository.restore(vault, item) }
            _state.update {
                it.copy(
                    message = if (restored != null) {
                        UiMessage(
                            text = UiText.of(R.string.trash_restored, item.relativePath),
                            // 撤销恢复：把实体移回回收站（新批次），随后刷新列表收敛
                            undo = {
                                viewModelScope.launch {
                                    withContext(Dispatchers.IO) {
                                        trashRepository.moveToTrash(vault, restored.absolutePath)
                                    }
                                    refreshBatches()
                                }
                            }
                        )
                    } else UiMessage(UiText.of(R.string.trash_restore_failed))
                )
            }
            if (restored != null) refreshBatches()
        }
    }

    /** 永久删除单条（UI 已二次确认）；不可撤销。 */
    fun delete(item: TrashItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { trashRepository.delete(item) }
            _state.update {
                it.copy(
                    message = if (ok) UiMessage(UiText.of(R.string.msg_deleted, item.relativePath))
                    else UiMessage(UiText.of(R.string.msg_delete_failed))
                )
            }
            if (ok) refreshBatches()
        }
    }

    /** 清空回收站（UI 已二次确认）；不可撤销。 */
    fun purgeAll() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { trashRepository.purgeAll() }
            _state.update {
                it.copy(
                    message = if (ok) UiMessage(UiText.of(R.string.trash_purged))
                    else UiMessage(UiText.of(R.string.trash_purge_failed))
                )
            }
            if (ok) refreshBatches()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun refreshBatches() {
        val batches = withContext(Dispatchers.IO) { trashRepository.batches() }
        _state.update { it.copy(batches = batches) }
    }
}
