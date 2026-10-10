package com.az.notes.ui.recent

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 最近查看条目：节点 + 正文预览（头部小字节读取，列表页同款 [VaultRepository.readPreview]）。 */
data class RecentItem(val node: FileNode, val preview: String = "")

data class RecentUiState(
    val loading: Boolean = true,
    /** 当前仓库展示名（页面标题下标注「仓库：xxx」） */
    val vaultName: String? = null,
    /** 最近查看列表（最近优先，保持记录顺序；跨文件夹、不区分层级；含正文预览） */
    val items: List<RecentItem> = emptyList(),
    /** 一次性提示（Snackbar）：移除 / 清空记录，携带撤销动作 */
    val message: UiMessage? = null
)

/**
 * 最近查看 ViewModel：按设置的最近记录顺序（最近优先）解析出实体文件（查看 / 编辑
 * 笔记时由阅读器与编辑页记录，移动 / 改名后由 [com.az.notes.ui.notes.NotesViewModel]
 * 的迁移逻辑保持路径最新），失效路径静默清理写回。记录仅本地有效、按仓库隔离，
 * 不参与云端同步；上限 50 条滚动存储。
 */
@HiltViewModel
class RecentViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RecentUiState())
    val state: StateFlow<RecentUiState> = _state.asStateFlow()

    /** 上次加载依据（Vault / 记录集 / 预览长度），设置其它字段变化时无需重扫 */
    private var lastVault: String? = null
    private var lastRecents: List<String> = emptyList()
    private var lastPreviewChars: Int = -1

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                val vaultName = s.vaults.firstOrNull { it.id == s.currentVaultId }?.name
                if (s.vaultPath == lastVault && s.recentPaths == lastRecents &&
                    s.previewChars == lastPreviewChars
                ) {
                    // 其它设置（如仓库重命名）变化：仅同步仓库标注，无需重扫
                    _state.update { it.copy(vaultName = vaultName) }
                    return@collect
                }
                lastVault = s.vaultPath
                lastRecents = s.recentPaths
                lastPreviewChars = s.previewChars
                val vault = s.vaultPath
                if (vault.isNullOrBlank()) {
                    _state.update { it.copy(vaultName = vaultName, loading = false, items = emptyList()) }
                    return@collect
                }
                // 按记录顺序（最近优先）解析 + 预览并行读取；失效路径丢弃
                val loaded = withContext(Dispatchers.IO) {
                    s.recentPaths
                        .mapNotNull { rel -> resolve(vault, rel) }
                        .map { node ->
                            async {
                                RecentItem(
                                    node = node,
                                    preview = vaultRepository.readPreview(node.absolutePath, s.previewChars)
                                )
                            }
                        }
                        .awaitAll()
                }
                // 失效路径（被删除 / 移出 Vault）：静默清理，保持记录与磁盘一致
                val valid = loaded.map { it.node.relativePath }
                if (valid != s.recentPaths) {
                    runCatching { settingsRepository.setRecentPaths(valid) }
                }
                _state.update {
                    it.copy(vaultName = vaultName, loading = false, items = loaded)
                }
            }
        }
    }

    /** 移除一条记录（列表中直接操作）；横幅可撤销（恢复移除前的完整记录列表）。 */
    fun remove(item: RecentItem) {
        viewModelScope.launch {
            val snapshot = runCatching { settingsRepository.settings.first().recentPaths }
                .getOrDefault(emptyList())
            settingsRepository.removeRecent(item.node.relativePath)
            _state.update {
                it.copy(
                    message = UiMessage(
                        text = UiText.of(R.string.msg_recent_removed, item.node.name),
                        undo = { viewModelScope.launch { settingsRepository.setRecentPaths(snapshot) } }
                    )
                )
            }
        }
    }

    /** 清空当前仓库的全部记录；横幅可撤销（恢复清空前的完整记录列表）。 */
    fun clearAll() {
        viewModelScope.launch {
            val snapshot = runCatching { settingsRepository.settings.first().recentPaths }
                .getOrDefault(emptyList())
            if (snapshot.isEmpty()) return@launch
            settingsRepository.clearRecent()
            _state.update {
                it.copy(
                    message = UiMessage(
                        text = UiText.of(R.string.msg_recents_cleared),
                        undo = { viewModelScope.launch { settingsRepository.setRecentPaths(snapshot) } }
                    )
                )
            }
        }
    }

    /** 清除已展示的一次性提示。 */
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
