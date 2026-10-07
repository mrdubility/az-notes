package com.az.notes.ui.orphan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.media.AttachmentRepository
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TrashRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.ui.common.UiMessage
import com.az.notes.ui.common.UiText
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 孤儿图片列表项（含展示所需的相对路径 / 体积 / 修改时间）。 */
data class OrphanFile(
    val file: File,
    val relativePath: String,
    val size: Long,
    val lastModified: Long
)

data class OrphanUiState(
    val vaultPath: String? = null,
    /** 回收站是否启用：关闭时清理为物理删除（UI 需二次确认）。 */
    val trashEnabled: Boolean = true,
    val scanning: Boolean = true,
    /** 扫描进度（已处理笔记数, 总笔记数）。 */
    val scannedNotes: Int = 0,
    val totalNotes: Int = 0,
    val orphans: List<OrphanFile> = emptyList(),
    val totalBytes: Long = 0L,
    /** 超过正文扫描上限、未纳入引用解析的笔记数（页面提示，避免误判）。 */
    val unscannedNotes: Int = 0,
    /** 多选：选中项的绝对路径集合。 */
    val selected: Set<String> = emptySet(),
    val message: UiMessage? = null
)

/**
 * 孤儿图片 ViewModel：扫描引用索引 → 列出未被任何笔记引用的图片，
 * 支持多选后「移入回收站」（回收站关闭时物理删除）；文件操作全部在 IO 线程，
 * 清理后重扫并调度一次防抖同步。
 */
@HiltViewModel
class OrphanImageViewModel @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val trashRepository: TrashRepository,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(OrphanUiState())
    val state: StateFlow<OrphanUiState> = _state.asStateFlow()

    init {
        scan()
    }

    /** 重新扫描孤儿图片（进入页面 / 清理后调用）。 */
    fun scan() {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val vault = settings?.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update { it.copy(scanning = false, message = UiMessage(UiText.of(R.string.msg_vault_unset))) }
                return@launch
            }
            _state.update { it.copy(vaultPath = vault, trashEnabled = settings.trashEnabled, scanning = true, selected = emptySet()) }
            val report = withContext(Dispatchers.IO) {
                attachmentRepository.orphans(File(vault)) { done, total ->
                    _state.update { it.copy(scannedNotes = done, totalNotes = total) }
                }
            }
            val root = File(vault).normalize().absolutePath
            _state.update {
                it.copy(
                    scanning = false,
                    orphans = report.orphans.map { f ->
                        OrphanFile(
                            file = f,
                            relativePath = f.absolutePath.removePrefix(root).trimStart('/', '\\'),
                            size = f.length(),
                            lastModified = f.lastModified()
                        )
                    },
                    totalBytes = report.totalBytes,
                    unscannedNotes = report.unscannedNotes
                )
            }
        }
    }

    fun toggleSelect(absolutePath: String) {
        _state.update {
            val next = it.selected.toMutableSet()
            if (!next.remove(absolutePath)) next.add(absolutePath)
            it.copy(selected = next)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selected = it.orphans.map { o -> o.file.absolutePath }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    /**
     * 把选中图片移入回收站（回收站关闭时物理删除，UI 已二次确认）。
     * 完成后重扫并调度同步；全程仅限仓库内路径。
     */
    fun trashSelected() {
        viewModelScope.launch {
            val current = _state.value
            val vault = current.vaultPath
            if (vault.isNullOrBlank()) return@launch
            val targets = current.orphans.filter { it.file.absolutePath in current.selected }
            if (targets.isEmpty()) return@launch
            val moved = withContext(Dispatchers.IO) {
                var ok = 0
                targets.forEach { item ->
                    val done = if (current.trashEnabled) {
                        runCatching { trashRepository.moveToTrash(vault, item.file.absolutePath) }.getOrNull() != null
                    } else {
                        runCatching { vaultRepository.delete(item.file.absolutePath) }.getOrDefault(false)
                    }
                    if (done) ok++
                }
                ok
            }
            _state.update {
                it.copy(
                    message = UiMessage(
                        text = if (current.trashEnabled) UiText.of(R.string.orphan_images_moved, moved)
                        else UiText.of(R.string.orphan_images_deleted, moved)
                    )
                )
            }
            scan()
            if (moved > 0) runCatching { syncScheduler.scheduleSaveSync() }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
