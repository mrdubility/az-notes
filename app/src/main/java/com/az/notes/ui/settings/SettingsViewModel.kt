package com.az.notes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FileNode
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 设置页 ViewModel（§5.6）。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 分享文件夹选择对话框的目标目录列表（null = 尚未加载） */
    private val _moveTargets = MutableStateFlow<List<FileNode>?>(null)
    val moveTargets: StateFlow<List<FileNode>?> = _moveTargets.asStateFlow()

    /** 加载文件夹选择列表（缓存式，首次打开对话框时调用）。 */
    fun loadMoveTargets() {
        if (_moveTargets.value != null) return
        viewModelScope.launch {
            val vault = settings.value.vaultPath ?: return@launch
            val dirs = withContext(Dispatchers.IO) { vaultRepository.listAllDirectories(vault) }
            _moveTargets.value = dirs
        }
    }

    /** 分享新建笔记的默认进入文件夹：选择目标目录（绝对路径），Vault 根 → null。 */
    fun setShareFolderAbsolute(targetDir: String) {
        viewModelScope.launch {
            val vault = settings.value.vaultPath ?: return@launch
            val root = vault.trimEnd('/')
            val rel = when {
                targetDir == root -> null
                targetDir.startsWith("$root/") -> targetDir.removePrefix("$root/")
                else -> return@launch
            }
            settingsRepository.setShareFolder(rel)
        }
    }

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

    /** 默认新建笔记名。 */
    fun setDefaultNoteName(name: String) =
        viewModelScope.launch { settingsRepository.setDefaultNoteName(name) }

    /** 回收站自动清理天数（0 = 永不清理）。 */
    fun setTrashRetentionDays(days: Int) =
        viewModelScope.launch { settingsRepository.setTrashRetentionDays(days) }

    /** 右下角加号点击的默认行为。 */
    fun setFabAction(action: FabAction) =
        viewModelScope.launch { settingsRepository.setFabAction(action) }

    /** 应用语言（写偏好 + 镜像；重建 Activity 由 UI 层触发）。 */
    fun setLanguage(language: AppLanguage) =
        viewModelScope.launch { settingsRepository.setLanguage(language) }

    /** 是否启用回收站。 */
    fun setTrashEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setTrashEnabled(enabled) }

    /** 编辑器工具栏顺序。 */
    fun setEditorToolOrder(order: List<String>) =
        viewModelScope.launch { settingsRepository.setEditorToolOrder(order) }

    /** 编辑器工具栏禁用集合。 */
    fun setEditorToolDisabled(disabled: Set<String>) =
        viewModelScope.launch { settingsRepository.setEditorToolDisabled(disabled) }

    fun resetVault() = viewModelScope.launch { settingsRepository.setVaultPath(null) }
}
