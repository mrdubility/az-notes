package com.az.notes.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.model.FileNode
import com.az.notes.ui.common.UiMessage
import com.az.notes.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class FavoritesUiState(
    val loading: Boolean = true,
    /** 收藏笔记列表（按修改时间倒序；跨文件夹、不区分层级） */
    val items: List<FileNode> = emptyList(),
    /** 一次性提示（Snackbar），消费后清空；携带撤销动作时横幅右侧显示「撤销」按钮 */
    val message: UiMessage? = null
)

/**
 * 收藏夹 ViewModel：按设置的收藏相对路径解析出实体文件（移动 / 改名后
 * 由 [NotesViewModel] 的迁移逻辑保持路径最新），失效路径静默清理写回。
 * 收藏仅本地有效，不参与云端同步。
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    /** 上次加载依据（Vault / 收藏集），设置其它字段变化时无需重扫 */
    private var lastVault: String? = null
    private var lastFavorites: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                if (s.vaultPath == lastVault && s.favoritePaths == lastFavorites) return@collect
                lastVault = s.vaultPath
                lastFavorites = s.favoritePaths
                val vault = s.vaultPath
                if (vault.isNullOrBlank()) {
                    _state.update { it.copy(loading = false, items = emptyList()) }
                    return@collect
                }
                val loaded = withContext(Dispatchers.IO) {
                    s.favoritePaths.mapNotNull { rel -> resolve(vault, rel) }
                }
                // 失效路径（被删除 / 移出 Vault）：静默清理，保持收藏集与磁盘一致
                val valid = loaded.map { it.relativePath }.toSet()
                if (valid != s.favoritePaths) {
                    runCatching { settingsRepository.setFavorites(valid) }
                }
                _state.update {
                    it.copy(
                        loading = false,
                        items = loaded.sortedByDescending { node -> node.lastModified }
                    )
                }
            }
        }
    }

    /** 取消收藏（列表中直接操作）；横幅可撤销恢复收藏，设置变化驱动列表自动收敛。 */
    fun removeFavorite(node: FileNode) {
        viewModelScope.launch {
            val rel = node.relativePath
            settingsRepository.removeFavorite(rel)
            _state.update {
                it.copy(
                    message = UiMessage(
                        text = UiText.of(R.string.msg_unfavorited, node.name),
                        undo = { viewModelScope.launch { settingsRepository.addFavorite(rel) } }
                    )
                )
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 相对路径 → 实体文件节点；文件不存在 / 不可读时返回 null（视为失效）。 */
    private fun resolve(vault: String, rel: String): FileNode? {
        val file = File(vault.trimEnd('/'), rel)
        if (!file.isFile || !vaultRepository.isValidVault(vault)) return null
        return FileNode(
            relativePath = rel,
            absolutePath = file.absolutePath,
            name = file.name,
            isDirectory = false,
            size = file.length(),
            lastModified = file.lastModified(),
            depth = rel.count { it == '/' },
            isMarkdown = VaultRepository.isMarkdownName(file.name),
            isAttachment = VaultRepository.isAttachmentName(file.name)
        )
    }
}
