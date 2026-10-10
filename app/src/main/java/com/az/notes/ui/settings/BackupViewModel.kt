package com.az.notes.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.backup.BackupRepository
import com.az.notes.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    /** 导出 / 导入进行中（禁用入口 + 顶部进度条） */
    val busy: Boolean = false,
    /** 一次性提示（导出结果 / 导入统计 / 错误） */
    val message: UiText? = null,
    /** 导出待写入内容（非 null 时 UI 拉起系统「另存为」） */
    val pendingExport: String? = null,
    /** 系统「另存为」的建议文件名 */
    val exportFileName: String? = null,
    /** 导入后语言发生变化：UI 重启界面使其生效 */
    val languageChanged: Boolean = false
)

/**
 * 备份与恢复页 ViewModel：导出当前全部配置为 JSON 文件 / 从备份文件恢复。
 * 文件读写经 SAF（系统文件选择器）完成，无需存储权限；WebDAV 密码永不导出 / 导入。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** 导出准备：组装 JSON 与建议文件名；完成后由 UI 拉起系统「另存为」。 */
    fun prepareExport() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val json = runCatching {
                withContext(Dispatchers.IO) {
                    backupRepository.encode(backupRepository.buildPayload())
                }
            }.getOrNull()
            if (json == null) {
                _state.update {
                    it.copy(busy = false, message = UiText.of(R.string.backup_export_failed))
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        pendingExport = json,
                        exportFileName = backupRepository.suggestedFileName()
                    )
                }
            }
        }
    }

    /** 系统「另存为」返回后写入导出内容（uri 为 null = 用户取消，静默忽略）。 */
    fun writeExport(uri: Uri?) {
        val content = _state.value.pendingExport
        _state.update { it.copy(pendingExport = null, exportFileName = null) }
        if (uri == null || content == null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                        out.flush()
                        true
                    } ?: false
                }
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    busy = false,
                    message = UiText.of(
                        if (ok) R.string.backup_export_done else R.string.backup_export_failed
                    )
                )
            }
        }
    }

    /** 导入：读取所选文件并恢复配置；语言变化时由 UI 重启界面生效。 */
    fun import(uri: Uri?) {
        if (uri == null || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val before = runCatching { backupRepository.currentLanguage() }.getOrNull()
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }
                } ?: throw IOException("empty backup input")
                withContext(Dispatchers.IO) { backupRepository.import(text) }
            }
            result.fold(
                onSuccess = { r ->
                    val after = runCatching { backupRepository.currentLanguage() }.getOrNull()
                    _state.update {
                        it.copy(
                            busy = false,
                            languageChanged = before != null && after != null && before != after,
                            message = if (r.vaultsSkipped > 0) {
                                UiText.of(
                                    R.string.backup_import_done_partial,
                                    r.vaultsRestored, r.providersRestored, r.vaultsSkipped
                                )
                            } else {
                                UiText.of(
                                    R.string.backup_import_done,
                                    r.vaultsRestored, r.providersRestored
                                )
                            }
                        )
                    }
                },
                onFailure = {
                    _state.update {
                        it.copy(busy = false, message = UiText.of(R.string.backup_import_invalid))
                    }
                }
            )
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeLanguageChanged() {
        _state.update { it.copy(languageChanged = false) }
    }
}
