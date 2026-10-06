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
import com.az.notes.work.SyncScheduler
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
    /** 当前仓库 id（回收站按仓库隔离：只展示 / 清空当前仓库） */
    val vaultId: String? = null,
    /** 当前仓库展示名（页面标题下标注「仓库：xxx」） */
    val vaultName: String? = null,
    val loading: Boolean = true,
    val batches: List<TrashBatch> = emptyList(),
    /** 一次性提示（Snackbar），消费后清空；携带撤销动作时横幅右侧显示「撤销」按钮 */
    val message: UiMessage? = null
)

/** 回收站 ViewModel：列表 / 恢复 / 删除 / 清空；文件操作在 IO 上执行。 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** 重新读取回收站目录（仅当前仓库）。 */
    fun reload() {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val vaultId = runCatching { settingsRepository.requireCurrentVaultId() }.getOrNull()
            val vaultName = settings?.vaults?.firstOrNull { it.id == vaultId }?.name
            val batches = withContext(Dispatchers.IO) { trashRepository.batches(vaultId) }
            _state.update {
                it.copy(
                    vaultPath = settings?.vaultPath,
                    vaultId = vaultId,
                    vaultName = vaultName,
                    loading = false,
                    batches = batches
                )
            }
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
            if (restored != null) {
                refreshBatches()
                // 恢复是 Vault 内容变更：调度一次防抖同步（未配置时内部自动跳过）
                runCatching { syncScheduler.scheduleSaveSync() }
            }
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

    /** 清空回收站（UI 已二次确认）；不可撤销。仅当前仓库。 */
    fun purgeAll() {
        viewModelScope.launch {
            val vaultId = _state.value.vaultId
            val ok = withContext(Dispatchers.IO) { trashRepository.purgeAll(vaultId) }
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
        val batches = withContext(Dispatchers.IO) { trashRepository.batches(_state.value.vaultId) }
        _state.update { it.copy(batches = batches) }
    }
}
