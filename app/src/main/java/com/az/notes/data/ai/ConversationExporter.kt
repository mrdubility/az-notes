package com.az.notes.data.ai

import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.ai.ChatMessage
import com.az.notes.domain.ai.ChatPart
import com.az.notes.domain.ai.ChatRole
import com.az.notes.domain.ai.SystemNoteMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 对话导出（§8）：勾选消息按会话顺序拼装为 Markdown 写入当前仓库根或选定子目录
 * （导出位置选择器只提供仓库内已存在目录）。
 *
 * - 格式（§8.2）：frontmatter（title / date / model）+ `## 用户 · HH:mm` / `## AI · HH:mm`
 *   分节；文档 part → `> 引用文档：<相对路径>`；压缩系统条目 →
 *   `> 已压缩/已丢弃 N 条早期消息`（工具轨迹与思考过程永不导出，§9.3）
 * - 图片：复制到 `<仓库根>/assets/ai-<uuid8>.<ext>` 后正文写相对链接（Obsidian 惯例），
 *   复制失败降级 `[图片: 原名]` 占位（链接相对仓库根，与导出位置无关）
 * - 命名 `AI对话 $yyyyMMdd-HHmmss$.md`（复用日期变量机制），重名自动追加序号；
 *   写盘成功后的 `scheduleSaveSync` 由调用方（VM）负责（Vault 变更纪律）
 *
 * 纯拼装逻辑抽为顶层 [buildMarkdown] / [selectForExport]（图片链接经回调注入）供单测。
 */
@Singleton
class ConversationExporter @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) {

    /** 导出结果：文件名 / 仓库内相对路径 / 绝对路径（「查看」动作跳预览页用）。 */
    data class ExportResult(
        val fileName: String,
        val relativePath: String,
        val absolutePath: String
    )

    /**
     * 导出勾选消息为 Markdown。
     *
     * @param messages 完整会话列表（勾选过滤后保持会话顺序）
     * @param selectedIds 勾选的消息 id 集合（对话框入口决定初始选中）
     * @param targetDir 目标目录绝对路径（null / 等于仓库根 = 写入仓库根；选择器只提供仓库内已存在目录）
     * @return null = 无勾选 / 无当前仓库 / 写盘失败
     */
    suspend fun export(
        messages: List<ChatMessage>,
        selectedIds: Set<String>,
        targetDir: String? = null
    ): ExportResult? = withContext(Dispatchers.IO) {
        val root = settingsRepository.settings.first().vaultPath ?: return@withContext null
        val selected = selectForExport(messages, selectedIds)
        if (selected.isEmpty()) return@withContext null

        // 图片先复制到仓库根 assets/（同一临时文件只复制一次）；失败记 null → 正文降级占位
        val links = HashMap<String, String?>()
        selected.forEach { message ->
            message.parts.filterIsInstance<ChatPart.Image>().forEach { image ->
                if (image.localPath !in links) {
                    links[image.localPath] = copyImageToAssets(root, image)
                }
            }
        }
        val markdown = buildMarkdown(
            messages = selected,
            timestamp = System.currentTimeMillis()
        ) { image -> links[image.localPath] }

        val baseName = VaultRepository.resolveDateName(EXPORT_NAME_PATTERN)
        val absolutePath = vaultRepository.uniqueNotePath(targetDir ?: root, baseName)
        runCatching { vaultRepository.writeTextAtomically(absolutePath, markdown) }
            .getOrNull() ?: return@withContext null
        val fileName = File(absolutePath).name
        // 相对路径始终相对仓库根（子目录导出 = 「目录/文件名」）
        val relativePath = runCatching {
            File(absolutePath).relativeTo(File(root)).path.replace('\\', '/')
        }.getOrDefault(fileName)
        ExportResult(fileName = fileName, relativePath = relativePath, absolutePath = absolutePath)
    }

    // ---------------------------------------------------------------- 图片复制

    /** 复制图片到 `<仓库根>/assets/`；返回正文相对链接（`assets/ai-xxx.jpg`），失败 null。 */
    private fun copyImageToAssets(root: String, image: ChatPart.Image): String? {
        val source = File(image.localPath)
        if (!source.isFile) return null
        val dir = File(root, VaultRepository.ATTACHMENT_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val ext = source.extension.lowercase().ifBlank { extensionOf(image.mime) }
        val target = uniqueImageFile(dir, ext)
        return runCatching {
            source.copyTo(target, overwrite = false)
            "${VaultRepository.ATTACHMENT_DIR}/${target.name}"
        }.getOrNull()
    }

    /** `ai-<uuid前8位>.<ext>` 唯一名（已存在则重新随机，规避碰撞）。 */
    private fun uniqueImageFile(dir: File, ext: String): File {
        while (true) {
            val candidate = File(dir, "ai-${UUID.randomUUID().toString().take(8)}.$ext")
            if (!candidate.exists()) return candidate
        }
    }

    /** mime 兜底扩展名（源文件无扩展时；白名单格式已由 AiImagePreparer 保证）。 */
    private fun extensionOf(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private companion object {
        /** 文件名模式（§8.2，复用 `$日期变量$` 机制；重名由 uniqueNotePath 追加序号）。 */
        const val EXPORT_NAME_PATTERN = "AI对话 \$yyyyMMdd-HHmmss\$"
    }
}

// ---------------------------------------------------------------- 纯拼装（单测）

/** 导出正文中的用户角色徽标（§8.2）。 */
private const val ROLE_USER = "用户"

/** 导出正文中的 AI 角色徽标（§8.2）。 */
private const val ROLE_AI = "AI"

/** frontmatter 标题（§8.2）。 */
private const val EXPORT_TITLE = "AI 对话"

/**
 * 勾选过滤：保持会话顺序输出勾选消息；TOOL 为传输态角色，不导出。
 * （「保持会话顺序」由本函数基于原列表顺序 filter 保证，而非勾选顺序。）
 */
internal fun selectForExport(
    messages: List<ChatMessage>,
    selectedIds: Set<String>
): List<ChatMessage> = messages.filter { it.id in selectedIds && it.role != ChatRole.TOOL }

/**
 * 纯拼装（internal，供单测）：frontmatter + 勾选消息分节，`\n\n` 分隔、末尾单换行。
 *
 * - USER 分节：文档引用行 → 正文 → 图片行；ASSISTANT 分节：正文（工具轨迹永不导出）
 * - SYSTEM 条目输出为单行引用（`> 已压缩/已丢弃 N 条…`，摘要正文不导出）
 * - reasoning 与工具轨迹不参与拼装（永不导出）；空正文分节整体跳过
 * - model 取首个携带 [ChatMessage.modelLabel] 的 ASSISTANT，无则省略该行
 *
 * @param timestamp 导出时刻（frontmatter date 行）
 * @param imageLink 图片相对链接解析（成功 = `assets/...`；null → `[图片: 名称]` 占位）
 */
internal fun buildMarkdown(
    messages: List<ChatMessage>,
    timestamp: Long,
    imageLink: (ChatPart.Image) -> String?
): String {
    val sections = messages.mapNotNull { message ->
        when (message.role) {
            ChatRole.USER -> userSection(message, imageLink)
            ChatRole.ASSISTANT -> assistantSection(message)
            ChatRole.SYSTEM -> systemSection(message)
            ChatRole.TOOL -> null
        }
    }
    val model = messages
        .firstOrNull { it.role == ChatRole.ASSISTANT && !it.modelLabel.isNullOrBlank() }
        ?.modelLabel
    val frontmatter = buildString {
        append("---\n")
        append("title: ").append(EXPORT_TITLE).append('\n')
        append("date: ").append(formatTime(timestamp, "yyyy-MM-dd HH:mm")).append('\n')
        if (model != null) append("model: ").append(model).append('\n')
        append("---")
    }
    return (listOf(frontmatter) + sections).joinToString("\n\n") + "\n"
}

/** USER 分节：`## 用户 · HH:mm` + 文档引用 / 正文 / 图片（无任何内容时跳过）。 */
private fun userSection(message: ChatMessage, imageLink: (ChatPart.Image) -> String?): String? {
    val body = buildList {
        message.parts.filterIsInstance<ChatPart.Document>().forEach { document ->
            add("> 引用文档：${document.vaultRelPath}")
        }
        if (message.text.isNotEmpty()) add(message.text)
        message.parts.filterIsInstance<ChatPart.Image>().forEach { image ->
            val link = imageLink(image)
            add(if (link != null) "![图片]($link)" else "[图片: ${image.name}]")
        }
    }
    if (body.isEmpty()) return null
    return sectionOf(ROLE_USER, message.timestamp, body)
}

/** ASSISTANT 分节：`## AI · HH:mm` + 正文（工具轨迹永不导出；正文为空时跳过）。 */
private fun assistantSection(message: ChatMessage): String? {
    if (message.text.isEmpty()) return null
    return sectionOf(ROLE_AI, message.timestamp, listOf(message.text))
}

/** SYSTEM 条目（压缩提示）：单行引用；摘要正文与 reasoning 均不导出。 */
private fun systemSection(message: ChatMessage): String? {
    val note = message.systemNote ?: return null
    val text = when (note.mode) {
        SystemNoteMode.SUMMARIZED -> "已压缩 ${note.count} 条早期消息"
        SystemNoteMode.TRIMMED -> "已丢弃 ${note.count} 条早期消息"
    }
    return "> $text"
}

/** 分节标题 + 正文块（块间空行）。 */
private fun sectionOf(role: String, timestamp: Long, body: List<String>): String =
    "## $role · ${formatTime(timestamp, "HH:mm")}\n\n" + body.joinToString("\n\n")

/** 时间格式化（导出一次性使用，逐条新建 SimpleDateFormat 无性能顾虑）。 */
private fun formatTime(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
