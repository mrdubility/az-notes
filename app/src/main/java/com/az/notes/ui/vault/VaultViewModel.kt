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
     * 添加仓库：先在 IO 线程校验目录可用（存在可读），通过后入注册表并设为当前。
     * 路径已注册时内部仅切换过去（不重复添加）。
     * @return 路径是否有效（false 时 UI 提示目录不可用）
     */
    suspend fun addVault(path: String): Boolean {
        val normalized = path.trim().trimEnd('/')
        if (normalized.isEmpty()) return false
        val valid = withContext(Dispatchers.IO) { vaultRepository.isValidVault(normalized) }
        if (!valid) return false
        // 注册表写入仍走 viewModelScope：离开页面不取消，与原有异步语义一致
        viewModelScope.launch {
            settingsRepository.addVault(normalized)
            runCatching { syncScheduler.reschedulePeriodic() }
        }
        return true
    }

    /** 重命名仓库（仅展示名，不影响目录）。 */
    fun renameVault(id: String, name: String) =
        viewModelScope.launch { settingsRepository.renameVault(id, name) }

    /** 设置仓库的隐藏状态（多仓库时可隐藏；当前仓库需先切换，数据层亦校验）。 */
    fun setVaultHidden(id: String, hidden: Boolean) =
        viewModelScope.launch { settingsRepository.setVaultHidden(id, hidden) }

    /** 移除仓库：注册表 + per-vault 应用内数据收尾；磁盘笔记文件不受影响。 */
    fun removeVault(id: String) {
        viewModelScope.launch {
            // 内置默认仓库不可移除（UI 已不提供入口，此处兜底避免误清 per-vault 数据）
            if (settingsRepository.settings.first().vaults
                    .firstOrNull { it.id == id }?.builtin == true
            ) {
                return@launch
            }
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
