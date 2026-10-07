package com.az.notes.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.az.notes.R
import com.az.notes.data.media.AttachmentRepository
import com.az.notes.data.media.ImageImportRepository
import com.az.notes.data.media.ImportedMedia
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.data.sync.SyncEngine
import com.az.notes.domain.model.EditorTool
import com.az.notes.domain.model.FileNode
import com.az.notes.ui.common.UiText
import com.az.notes.ui.common.toUiText
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class EditorUiState(
    val path: String = "",
    val loading: Boolean = true,
    val text: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val savedAt: Long? = null,
    val error: UiText? = null,
    /** 一次性提示（Snackbar），消费后清空 */
    val message: UiText? = null,
    /** 工具栏显示的工具（按设置排序、过滤禁用项） */
    val toolbarTools: List<EditorTool> = EditorTool.entries.toList(),
    /** 当前 Vault 根（移动对话框根目录行用） */
    val vaultPath: String? = null,
    /** 图片压缩导入开关（来自设置，默认开） */
    val imageCompressEnabled: Boolean = true,
    /** 移动对话框的目标文件夹列表（null = 尚未加载） */
    val moveTargets: List<FileNode>? = null
)

/**
 * 编辑页 ViewModel（§5.3）。源码编辑 + 停止输入 1.5s 自动保存（原子写）。
 * 重命名成功后同样把改名同步到云端（MOVE），避免下次同步退化为删除 + 重传。
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val imageImportRepository: ImageImportRepository,
    private val attachmentRepository: AttachmentRepository
) : ViewModel() {

    private var absolutePath: String =
        savedStateHandle.get<String>("path") ?: ""

    /** 是否刚从“新建笔记”进入：退出时若仍无任何内容则清理空文件（不产生空笔记）。 */
    private val fresh: Boolean = savedStateHandle.get<Boolean>("fresh") ?: false

    private val _state = MutableStateFlow(EditorUiState(path = absolutePath))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var autoSaveJob: Job? = null

    init {
        // 工具栏配置：设置页调整顺序 / 启用状态后实时生效
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.update {
                    it.copy(
                        toolbarTools = EditorTool.resolve(s.editorToolOrder, s.editorToolDisabled),
                        vaultPath = s.vaultPath,
                        imageCompressEnabled = s.imageCompressEnabled
                    )
                }
            }
        }
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { vaultRepository.readText(absolutePath) }
            }.getOrElse { e ->
                _state.update { s -> s.copy(loading = false, error = e.toUiText(R.string.msg_read_failed)) }
                return@launch
            }
            _state.update { it.copy(loading = false, text = text, dirty = false) }
        }
    }

    fun onTextChange(newText: String) {
        // 相同文本（如编辑器初始化回灌）不置脏、不重置自动保存计时
        if (newText == _state.value.text) return
        _state.update { it.copy(text = newText, dirty = true) }
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(1500)
            save()
        }
    }

    fun save() {
        val current = _state.value
        if (!current.dirty || current.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    vaultRepository.writeTextAtomically(absolutePath, current.text)
                }
            }
            result.fold(
                onSuccess = {
                    _state.update { it.copy(saving = false, dirty = false, savedAt = System.currentTimeMillis()) }
                    schedulePostSaveSync()
                },
                onFailure = { e -> _state.update { it.copy(saving = false, error = e.toUiText(R.string.msg_save_failed)) } }
            )
        }
    }

    /**
     * 退出编辑页前的收尾（返回按钮 / 系统返回共用，完成后才应导航返回）：
     * - 新建笔记且从未写入任何内容 → 删除占位空文件，不留下空笔记；
     * - 否则将未落盘内容写入磁盘（避免返回瞬间销毁 ViewModel 丢字）。
     */
    suspend fun flushOnExit() {
        val current = _state.value
        if (current.loading) return
        autoSaveJob?.cancel()
        val path = absolutePath
        val discardFresh = fresh && current.text.isBlank()
        var flushed = false
        withContext(Dispatchers.IO) {
            if (discardFresh) {
                vaultRepository.delete(path)
            } else if (current.dirty) {
                flushed = runCatching { vaultRepository.writeTextAtomically(path, current.text) }.isSuccess
            }
        }
        if (flushed) schedulePostSaveSync()
        if (discardFresh) {
            _state.update { it.copy(dirty = false) }
        }
    }

    // 工具栏的标记插入已迁移到 EditorScreen：直接操作 TextFieldState，
    // 插入标记后光标 / 选区自动跟随（旧实现只在文末拼接，体验差）。

    /**
     * 重命名当前笔记（编辑页标题入口）：
     * 清洗输入 → 先落盘未保存内容 → 同目录改名 → 更新内部路径（后续自动保存写新路径）
     * → 后台把改名同步到云端。
     */
    fun rename(input: String) {
        viewModelScope.launch {
            val current = _state.value
            if (current.loading) return@launch
            val cleaned = VaultRepository.sanitizeEntryName(input)
            if (cleaned == null) {
                _state.update { it.copy(message = UiText.of(R.string.msg_invalid_name)) }
                return@launch
            }
            val newName = VaultRepository.ensureMarkdownName(cleaned)
            val src = File(absolutePath)
            if (newName == src.name) return@launch
            val target = File(src.parentFile, newName)
            if (target.exists()) {
                _state.update { it.copy(message = UiText.of(R.string.msg_rename_failed_exists)) }
                return@launch
            }
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    // 先保存未落盘内容，避免改名后丢失编辑
                    if (current.dirty) vaultRepository.writeTextAtomically(absolutePath, current.text)
                    vaultRepository.rename(absolutePath, target.absolutePath)
                }
            }.getOrDefault(false)
            if (ok) {
                val oldPath = src.absolutePath
                absolutePath = target.absolutePath
                _state.update {
                    it.copy(
                        path = absolutePath,
                        dirty = false,
                        savedAt = System.currentTimeMillis(),
                        message = UiText.of(R.string.editor_renamed_to, newName)
                    )
                }
                syncRemoteRename(oldPath, absolutePath)
            } else {
                _state.update { it.copy(message = UiText.of(R.string.msg_rename_failed_exists)) }
            }
        }
    }

    /**
     * 把本地改名同步到云端（MOVE + 基线路径重映射）。后台执行、不阻塞编辑；
     * 未配置同步或失败时静默跳过（同步日志可查），由下次常规同步兜底。
     */
    private fun syncRemoteRename(oldAbsPath: String, newAbsPath: String) {
        viewModelScope.launch {
            val vault = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
            if (vault.isNullOrBlank()) return@launch
            runCatching { syncEngine.applyRemoteRename(vault, oldAbsPath, newAbsPath) }
        }
    }

    /** 打开移动对话框前加载目标文件夹列表（递归全部子目录）。 */
    fun loadMoveTargets() {
        if (_state.value.moveTargets != null) return
        viewModelScope.launch {
            val vault = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
            if (vault.isNullOrBlank()) return@launch
            val dirs = withContext(Dispatchers.IO) { vaultRepository.listAllDirectories(vault) }
            _state.update { it.copy(moveTargets = dirs) }
        }
    }

    /**
     * 将当前笔记移动到 [targetDir]（先落盘未保存内容）；成功后更新内部路径
     * （后续自动保存写入新位置）并把移动同步到云端；同目录 / 重名时给出提示。
     */
    fun moveTo(targetDir: String) {
        viewModelScope.launch {
            val current = _state.value
            if (current.loading) return@launch
            val src = File(absolutePath)
            val target = File(targetDir, src.name)
            if (target.parentFile?.absolutePath == src.parentFile?.absolutePath) return@launch
            if (target.exists()) {
                _state.update { it.copy(message = UiText.of(R.string.msg_move_failed_exists)) }
                return@launch
            }
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val vault = settings?.vaultPath
            // 跨仓库判定：目标目录落在“其他仓库挂载点”内（同 NotesViewModel.isCrossVaultMove 语义）
            val currentRoot = vault?.trimEnd('/')
            val targetAbs = File(targetDir).absolutePath.trimEnd('/')
            val targetVaultRoot = settings?.vaults?.firstOrNull {
                val p = it.path.trimEnd('/')
                p.isNotEmpty() && p != currentRoot && (targetAbs == p || targetAbs.startsWith("$p/"))
            }?.path
            val crossVault = targetVaultRoot != null
            // 跨仓库且开关开启时，移动前先在源仓库统计「仅本文引用」的附件，随后同名复制到目标仓库
            val exclusiveFiles: List<File> = if (crossVault && settings?.attachmentPromptEnabled == true && !vault.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        attachmentRepository.referencedByNote(File(vault!!), absolutePath).exclusiveFiles
                    }.getOrDefault(emptyList())
                }
            } else emptyList()
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    // 先保存未落盘内容，避免移动后丢失编辑
                    if (current.dirty) vaultRepository.writeTextAtomically(absolutePath, current.text)
                    vaultRepository.rename(absolutePath, target.absolutePath)
                }
            }.getOrDefault(false)
            if (ok) {
                val oldPath = absolutePath
                absolutePath = target.absolutePath
                var movedMessage = if (!vault.isNullOrBlank() && targetDir == vault) {
                    UiText.of(R.string.editor_moved_to_root)
                } else {
                    UiText.of(R.string.editor_moved_to, File(targetDir).name)
                }
                // 跨仓库：把「仅本文引用」的附件按同名复制到目标仓库对应 assets/，正文引用保持不变
                if (crossVault && exclusiveFiles.isNotEmpty() && targetVaultRoot != null) {
                    val copied = withContext(Dispatchers.IO) {
                        imageImportRepository.copyAttachmentsAcrossVaults(
                            sources = exclusiveFiles,
                            targetNoteDir = File(absolutePath).parentFile ?: File(targetDir),
                            targetVaultRoot = targetVaultRoot
                        )
                    }
                    // 复制失败不影响笔记移动，仅提示「附件未全部跟随」（无磁盘缓存 / IO 异常）
                    if (copied < exclusiveFiles.size) {
                        movedMessage = UiText.of(R.string.editor_move_attachments_failed)
                    }
                }
                // 同仓库跨目录移动单篇笔记：若笔记相邻 assets/ 仍有内容，给出「附件未跟随」提示（不静默搬运）
                if (!crossVault && settings?.attachmentPromptEnabled == true) {
                    val srcAssets = src.parentFile?.let { File(it, VaultRepository.ATTACHMENT_DIR) }
                    if (srcAssets?.isDirectory == true && srcAssets.listFiles()?.isNotEmpty() == true) {
                        movedMessage = UiText.of(R.string.editor_move_attachments_not_followed)
                    }
                }
                _state.update {
                    it.copy(
                        path = absolutePath,
                        dirty = false,
                        savedAt = System.currentTimeMillis(),
                        message = movedMessage
                    )
                }
                if (crossVault) schedulePostSaveSync() else syncRemoteRename(oldPath, absolutePath)
            } else {
                _state.update { it.copy(message = UiText.of(R.string.msg_move_failed)) }
            }
        }
    }

    /**
     * 导入一张图片到当前笔记同目录的 `assets/`，受压缩开关控制（默认开）。
     * 成功时回调 [onDone] 传入导入产物（供正文插入与体积提示），失败传 null。
     * 全程仅在仓库内落盘，任一环节失败不产生孤儿附件。
     */
    fun importImage(uri: Uri, onDone: (ImportedMedia?) -> Unit) {
        viewModelScope.launch {
            val current = _state.value
            val vault = current.vaultPath
            if (vault.isNullOrBlank()) {
                onDone(null)
                return@launch
            }
            val media = runCatching {
                imageImportRepository.import(uri, vault, File(absolutePath), current.imageCompressEnabled)
            }.getOrNull()
            onDone(media)
        }
    }

    /**
     * 保存成功后 30s 防抖触发一次自动同步（§6.4）。后台执行、不阻塞编辑；
     * 未配置同步或用户关闭该选项时由 [SyncScheduler] 自行忽略。
     */
    private fun schedulePostSaveSync() {
        viewModelScope.launch { runCatching { syncScheduler.scheduleSaveSync() } }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
