package com.az.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 承载全局偏好与 Vault 打开状态的主 ViewModel。 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    /**
     * 设置是否已从 DataStore 首次回流：启动画面据此延迟退场、导航图据此决定起点，
     * 避免已配置 Vault 时先闪一帧引导页。
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .onEach { _ready.value = true }
        .catch { _ready.value = true } // 读取失败也放行，避免卡在启动画面
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings()
        )

    /** 选定并校验 Vault 目录后持久化路径。返回是否有效。 */
    fun openVault(path: String): Boolean {
        val normalized = path.trim().trimEnd('/')
        if (normalized.isEmpty() || !vaultRepository.isValidVault(normalized)) return false
        viewModelScope.launch { settingsRepository.setVaultPath(normalized) }
        return true
    }

    /**
     * 系统分享（ACTION_SEND）与文本处理（ACTION_PROCESS_TEXT）传入的待写入文本。
     * 由主页消费后清空；未选 Vault 时暂存，完成门禁后仍会送达。
     */
    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText.asStateFlow()

    /** 接收系统分享的文本；空白内容忽略。 */
    fun setSharedText(text: String) {
        if (text.isBlank()) return
        _sharedText.value = text
    }

    /** 分享文本已交给主页建笔记，清空避免重复处理。 */
    fun consumeSharedText() {
        _sharedText.value = null
    }

    fun resetVault() {
        viewModelScope.launch { settingsRepository.setVaultPath(null) }
    }
}
