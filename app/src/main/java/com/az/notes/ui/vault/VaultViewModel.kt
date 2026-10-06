package com.az.notes.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.local.ConflictRecordDao
import com.az.notes.data.local.SyncLogDao
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TrashRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.data.sync.CredentialStore
import com.az.notes.data.sync.SyncConfigRepository
import com.az.notes.domain.model.AppSettings
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 仓库管理页 ViewModel：列出注册表，支持切换 / 添加 / 重命名 / 移除。
 * 移除时一并收尾该仓库的 per-vault 应用内数据（同步配置 / 凭据 / 回收站 / 日志 / 冲突记录），
 * 磁盘上的笔记文件不受影响；周期同步调度随「当前仓库」变化即时对齐。
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository,
    private val syncScheduler: SyncScheduler,
    private val syncConfigRepository: SyncConfigRepository,
    private val credentialStore: CredentialStore,
    private val trashRepository: TrashRepository,
    private val conflictRecordDao: ConflictRecordDao,
    private val syncLogDao: SyncLogDao
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 首次回流完成（避免“空列表”状态在数据到达前一闪而过）。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.first()
            _loaded.value = true
        }
    }

    /** 切换当前仓库（记忆；收藏 / 同步配置 / 分享目录随之切换）。 */
    fun switchTo(id: String) {
        viewModelScope.launch {
            settingsRepository.setCurrentVault(id)
            runCatching { syncScheduler.reschedulePeriodic() }
        }
    }

    /**
     * 添加仓库：先在主线程做轻量校验（目录存在可读），再入注册表并设为当前。
     * 路径已注册时内部仅切换过去（不重复添加）。
     * @return 路径是否有效（false 时 UI 提示目录不可用）
     */
    fun addVault(path: String): Boolean {
        val normalized = path.trim().trimEnd('/')
        if (normalized.isEmpty() || !vaultRepository.isValidVault(normalized)) return false
        viewModelScope.launch {
            settingsRepository.addVault(normalized)
            runCatching { syncScheduler.reschedulePeriodic() }
        }
        return true
    }

    /** 重命名仓库（仅展示名，不影响目录）。 */
    fun renameVault(id: String, name: String) =
        viewModelScope.launch { settingsRepository.renameVault(id, name) }

    /** 移除仓库：注册表 + per-vault 应用内数据收尾；磁盘笔记文件不受影响。 */
    fun removeVault(id: String) {
        viewModelScope.launch {
            runCatching { syncConfigRepository.clearVault(id) }
            runCatching { credentialStore.clearPassword(id) }
            runCatching { withContext(Dispatchers.IO) { trashRepository.purgeAll(id) } }
            runCatching { syncLogDao.clear(id) }
            runCatching { conflictRecordDao.clear(id) }
            settingsRepository.removeVault(id)
            runCatching { syncScheduler.reschedulePeriodic() }
        }
    }
}
