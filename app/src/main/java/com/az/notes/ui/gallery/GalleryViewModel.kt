package com.az.notes.ui.gallery

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

/** 图库图片项（含展示所需的相对路径 / 体积 / 修改时间）。 */
data class GalleryImage(
    val file: File,
    val relativePath: String,
    val size: Long,
    val lastModified: Long
)

data class GalleryUiState(
    val vaultPath: String? = null,
    /** 回收站是否启用：关闭时清理为物理删除（UI 需二次确认）。 */
    val trashEnabled: Boolean = true,
    /** 全部图片（进入页面即加载，按修改时间倒序）。 */
    val images: List<GalleryImage> = emptyList(),
    val loading: Boolean = true,
    /** 过滤开关：只显示未被引用的图片。 */
    val unreferencedOnly: Boolean = false,
    /** 引用扫描进行中（开启过滤后按需扫描）。 */
    val scanning: Boolean = false,
    /** 扫描进度（已处理笔记数, 总笔记数）。 */
    val scannedNotes: Int = 0,
    val totalNotes: Int = 0,
    /** 未被任何笔记引用的图片绝对路径集合（扫描结果）。 */
    val unreferencedPaths: Set<String> = emptySet(),
    /** 超过正文扫描上限、未纳入引用解析的笔记数（页面提示，避免误判）。 */
    val unscannedNotes: Int = 0,
    /** 选择模式（长按图片或点顶栏「选择」进入）。 */
    val selectMode: Boolean = false,
    /** 多选：选中项的绝对路径集合。 */
    val selected: Set<String> = emptySet(),
    val message: UiMessage? = null
) {
    /** 展示列表实例缓存：state 实例不可变（同实例的 images 与过滤条件不会变化），避免重复重组反复建表。 */
    private var cachedDisplayed: List<GalleryImage>? = null

    /** 当前展示的图片：过滤开关开启时仅保留未被引用的。 */
    val displayed: List<GalleryImage>
        get() {
            cachedDisplayed?.let { return it }
            val result = if (unreferencedOnly) {
                images.filter { it.file.absolutePath in unreferencedPaths }
            } else {
                images
            }
            cachedDisplayed = result
            return result
        }
}

/**
 * 图库 ViewModel：跟随当前仓库列出全部图片（进入页面即加载）；
 * 「只显示未被引用的图片」按需扫描引用索引（附件依托笔记理念的反向清理）；
 * 支持多选后「移入回收站」（回收站关闭时物理删除）；文件操作全部在 IO 线程，
 * 清理后重载并调度一次防抖同步。
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val trashRepository: TrashRepository,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    /** 当前仓库根（加载时解析，供按需扫描复用）。 */
    private var vaultRoot: File? = null

    init {
        load()
    }

    /** 加载当前仓库的全部图片（进入页面 / 清理后调用）。 */
    fun load() {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val vault = settings?.vaultPath
            if (vault.isNullOrBlank()) {
                _state.update {
                    it.copy(loading = false, message = UiMessage(UiText.of(R.string.msg_vault_unset)))
                }
                return@launch
            }
            val root = File(vault).normalize()
            vaultRoot = root
            _state.update {
                it.copy(
                    vaultPath = vault,
                    trashEnabled = settings.trashEnabled,
                    loading = true,
                    selectMode = false,
                    selected = emptySet()
                )
            }
            val images = withContext(Dispatchers.IO) {
                attachmentRepository.listAttachments(root)
                    .sortedByDescending { f -> f.lastModified() }
            }
            val rootAbs = root.absolutePath
            _state.update {
                it.copy(
                    loading = false,
                    images = images.map { f -> f.toGalleryImage(rootAbs) }
                )
            }
        }
    }

    /**
     * 切换「只显示未被引用的图片」：开启时立即扫描引用索引
     * （点击进入查看即开始扫描，无需设置开关），关闭时恢复全部图片。
     */
    fun setUnreferencedOnly(enabled: Boolean) {
        if (!enabled) {
            _state.update { it.copy(unreferencedOnly = false, selectMode = false, selected = emptySet()) }
            return
        }
        if (_state.value.scanning) return
        _state.update { it.copy(unreferencedOnly = true, selectMode = false, selected = emptySet()) }
        scanReferences()
    }

    /** 扫描引用索引并更新「未被引用」集合（按需触发：开启过滤 / 清理完成）。 */
    private fun scanReferences() {
        val root = vaultRoot ?: return
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, scannedNotes = 0, totalNotes = 0) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                attachmentRepository.orphans(root) { done, total ->
                    _state.update { it.copy(scannedNotes = done, totalNotes = total) }
                }
            }
            _state.update {
                it.copy(
                    scanning = false,
                    unreferencedPaths = report.orphans.map { f -> f.absolutePath }.toSet(),
                    unscannedNotes = report.unscannedNotes
                )
            }
        }
    }

    fun enterSelectMode() {
        _state.update { it.copy(selectMode = true) }
    }

    fun exitSelectMode() {
        _state.update { it.copy(selectMode = false, selected = emptySet()) }
    }

    fun toggleSelect(absolutePath: String) {
        _state.update {
            val next = it.selected.toMutableSet()
            if (!next.remove(absolutePath)) next.add(absolutePath)
            it.copy(selected = next)
        }
    }

    /** 全选当前展示的图片（过滤开启时仅未被引用的）。 */
    fun selectAll() {
        _state.update { it.copy(selected = it.displayed.map { img -> img.file.absolutePath }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    /**
     * 把选中图片移入回收站（回收站关闭时物理删除，UI 已二次确认）。
     * 完成后重载列表（过滤开启时重扫引用）并调度同步；全程仅限仓库内路径。
     */
    fun trashSelected() {
        viewModelScope.launch {
            val current = _state.value
            val vault = current.vaultPath
            if (vault.isNullOrBlank()) return@launch
            val targets = current.images.filter { it.file.absolutePath in current.selected }
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
                        text = if (current.trashEnabled) UiText.of(R.string.gallery_moved, moved)
                        else UiText.of(R.string.gallery_deleted, moved)
                    )
                )
            }
            val keepFilter = current.unreferencedOnly
            load()
            if (keepFilter) scanReferences()
            if (moved > 0) runCatching { syncScheduler.scheduleSaveSync() }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}

/** 仓库相对路径（'/' 分隔）映射。 */
private fun File.toGalleryImage(rootAbs: String): GalleryImage = GalleryImage(
    file = this,
    relativePath = absolutePath.removePrefix(rootAbs).trimStart('/', '\\'),
    size = length(),
    lastModified = lastModified()
)
