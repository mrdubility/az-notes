package com.az.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 承载全局偏好与 Vault 打开状态的主 ViewModel。 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    /** 选定并校验 Vault 目录后持久化路径。返回是否有效。 */
    fun openVault(path: String): Boolean {
        val normalized = path.trim().trimEnd('/')
        if (normalized.isEmpty() || !vaultRepository.isValidVault(normalized)) return false
        viewModelScope.launch { settingsRepository.setVaultPath(normalized) }
        return true
    }

    fun resetVault() {
        viewModelScope.launch { settingsRepository.setVaultPath(null) }
    }
}
