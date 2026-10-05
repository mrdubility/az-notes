package com.az.notes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页 ViewModel（§5.6）。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setFontFamily(family: FontFamilyPreference) =
        viewModelScope.launch { settingsRepository.setFontFamily(family) }
    fun setFontSize(sp: Float) = viewModelScope.launch { settingsRepository.setFontSize(sp) }
    fun setLineHeight(ratio: Float) = viewModelScope.launch { settingsRepository.setLineHeight(ratio) }
    fun setDynamicColor(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }

    /** 列表正文预览字符数（20～300，越界由仓库收敛）。 */
    fun setPreviewChars(chars: Int) =
        viewModelScope.launch { settingsRepository.setPreviewChars(chars) }

    /** 列表默认排序方式。 */
    fun setSortOrder(order: NoteSortOrder) =
        viewModelScope.launch { settingsRepository.setSortOrder(order) }

    fun resetVault() = viewModelScope.launch { settingsRepository.setVaultPath(null) }
}
