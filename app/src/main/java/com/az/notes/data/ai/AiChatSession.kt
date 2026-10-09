package com.az.notes.data.ai

import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** 附加文档结果：[ADDED] 已入列；[DUPLICATE] 已在待发中；[FAILED] 越界 / 不可读。 */
enum class AttachResult { ADDED, DUPLICATE, FAILED }

/**
 * 内存会话（@Singleton，§1.2 不持久化）：
 * 进程内退出对话页不丢；杀进程 / 系统回收即丢（设计使然，导出留 B4）。
 * 待发附件（B3）：预览页 / 文档选择器加入的仓库文档，发送时并入用户消息
 * （[consumePendingAttachments] 取出并清空）。
 */
@Singleton
class AiChatSession @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 待发附件（发送后 / 新会话时清空）。 */
    private val _pendingAttachments = MutableStateFlow<List<ChatPart.Document>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatPart.Document>> = _pendingAttachments.asStateFlow()

    fun append(message: ChatMessage) {
        _messages.update { it + message }
    }

    /** 就地变换指定消息（流式增量 / 状态流转共用）。 */
    fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 移除指定消息（停止生成时的空内容消息清理）。 */
    fun removeMessage(id: String) {
        _messages.update { list -> list.filterNot { it.id == id } }
    }

    /** 截断：删除 [id] 及其之后的全部消息（重新生成 / 编辑重发）。 */
    fun truncateFrom(id: String) {
        _messages.update { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) list else list.take(index)
        }
    }

    /**
     * 附加仓库文档：vaultPath 护栏（须在当前仓库内）→ 读全文 → 相对路径 → 去重追加。
     * 失败与重复由调用方静默处理（待发列表的可见性即反馈）。
     */
    suspend fun attachDocument(absolutePath: String): AttachResult {
        val vaultPath = runCatching { settingsRepository.settings.first().vaultPath }.getOrNull()
            ?: return AttachResult.FAILED
        val vaultDir = File(vaultPath).normalize()
        val target = File(absolutePath).normalize()
        val vaultAbs = vaultDir.absolutePath
        val targetAbs = target.absolutePath
        if (targetAbs != vaultAbs && !targetAbs.startsWith(vaultAbs + File.separator)) {
            return AttachResult.FAILED
        }
        if (!target.isFile) return AttachResult.FAILED
        val relPath = target.relativeTo(vaultDir).path.replace('\\', '/')
        if (_pendingAttachments.value.any { it.vaultRelPath == relPath }) return AttachResult.DUPLICATE
        val content = withContext(Dispatchers.IO) {
            runCatching { vaultRepository.readText(target.absolutePath) }.getOrNull()
        } ?: return AttachResult.FAILED
        _pendingAttachments.update {
            it + ChatPart.Document(name = target.name, vaultRelPath = relPath, content = content)
        }
        return AttachResult.ADDED
    }

    /** 移除待发附件（按仓库相对路径）。 */
    fun removePendingAttachment(vaultRelPath: String) {
        _pendingAttachments.update { list -> list.filterNot { it.vaultRelPath == vaultRelPath } }
    }

    /** 取出并清空待发附件（发送时并入用户消息）。 */
    fun consumePendingAttachments(): List<ChatPart.Document> {
        val current = _pendingAttachments.value
        _pendingAttachments.value = emptyList()
        return current
    }

    fun clear() {
        _messages.value = emptyList()
        _pendingAttachments.value = emptyList()
    }
}
