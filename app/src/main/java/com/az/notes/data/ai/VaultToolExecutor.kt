package com.az.notes.data.ai

import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.ToolCallRecord
import com.az.notes.domain.ai.ToolSpecs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 单次工具执行结果：[resultText] 回填给模型；[record] 为 UI 展示 / 导出用轨迹摘要。 */
data class ToolExecutionResult(val resultText: String, val record: ToolCallRecord)

/**
 * 内置只读工具的本地执行器（B3）：限定当前仓库根，所有路径经 [resolve] 护栏（containment）。
 * 安全约定：任何越界 / 不存在的输入都返回「错误文本」交给模型自纠，绝不抛异常。
 */
@Singleton
class VaultToolExecutor @Inject constructor(
    private val vaultRepository: VaultRepository
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 按工具名分发执行（未知工具 / 非法参数按错误文本返回）。 */
    fun execute(vaultRoot: String, call: RawToolCall): ToolExecutionResult {
        val args = parseArgs(call.argumentsJson)
        val resultText = when (call.name) {
            ToolSpecs.LIST_NOTES -> listNotes(vaultRoot, args)
            ToolSpecs.READ_NOTE -> readNote(vaultRoot, args)
            ToolSpecs.SEARCH_NOTES -> searchNotes(vaultRoot, args)
            else -> "错误：未知工具「${call.name}」"
        }
        return ToolExecutionResult(
            resultText = resultText,
            record = ToolCallRecord(
                tool = call.name,
                argsSummary = argsSummary(call.name, args),
                resultSummary = resultSummary(resultText)
            )
        )
    }

    /** 执行前预览轨迹（[ToolCallRecord.argsSummary] 已提取、resultSummary 空串占位）：
     *  ChatEngine 在执行开始前即发出状态行（UI「正在读取…」），避免执行完成后才可见。 */
    internal fun preview(call: RawToolCall): ToolCallRecord = ToolCallRecord(
        tool = call.name,
        argsSummary = argsSummary(call.name, parseArgs(call.argumentsJson)),
        resultSummary = ""
    )

    /**
     * 仓库相对路径 → 绝对 File 护栏：normalize 后必须仍在仓库根内（对齐 ImageReference.within），
     * `..` 段直接拒绝；越界 / 根不存在返回 null。
     */
    internal fun resolve(root: String, relative: String): File? {
        val rootDir = File(root).normalize()
        if (!rootDir.isDirectory) return null
        val cleaned = relative.trim().replace('\\', '/').trimStart('/')
        if (cleaned.split('/').any { it == ".." }) return null
        val target = File(rootDir, cleaned).normalize()
        val rootAbs = rootDir.absolutePath
        val targetAbs = target.absolutePath
        return if (targetAbs == rootAbs || targetAbs.startsWith(rootAbs + File.separator)) {
            target
        } else {
            null
        }
    }

    /** 目录 BFS（≤[MAX_LIST_DEPTH] 层、≤[MAX_LIST_ENTRIES] 条），输出 JSON 数组（过滤隐藏项）。 */
    private fun listNotes(root: String, args: JsonObject): String {
        val dirArg = stringArg(args, "dir").orEmpty()
        val start = resolve(root, dirArg)
            ?: return "错误：路径超出仓库范围：${dirArg.ifBlank { "." }}"
        if (!start.isDirectory) return "错误：目录不存在：${dirArg.ifBlank { "." }}"
        val rootDir = File(root).normalize()
        val entries = mutableListOf<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += start to 1
        while (queue.isNotEmpty() && entries.size < MAX_LIST_ENTRIES) {
            val (dir, level) = queue.removeFirst()
            val children = (dir.listFiles() ?: continue).sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            for (child in children) {
                if (child.name.startsWith(".") || child.name in HIDDEN_DIRS) continue
                if (child.isDirectory) {
                    entries += child
                    if (level < MAX_LIST_DEPTH) queue += child to level + 1
                } else if (VaultRepository.isMarkdownName(child.name)) {
                    entries += child
                }
                if (entries.size >= MAX_LIST_ENTRIES) break
            }
        }
        if (entries.isEmpty()) return "（该目录下没有笔记或子目录）"
        return buildJsonArray {
            entries.forEach { file ->
                addJsonObject {
                    put("path", relativePath(rootDir, file))
                    put("name", file.name)
                    put("isDir", file.isDirectory)
                    if (!file.isDirectory) put("size", file.length())
                    put("modified", file.lastModified())
                }
            }
        }.toString()
    }

    /** 读取正文：offset（1 基行号）/limit 行切片；单次 ≤[MAX_NOTE_CHARS] 字符并附续读提示。 */
    private fun readNote(root: String, args: JsonObject): String {
        val path = stringArg(args, "path") ?: return "错误：缺少 path 参数"
        val file = resolve(root, path) ?: return "错误：路径超出仓库范围：$path"
        if (!file.isFile) return "错误：文件不存在：$path"
        val text = runCatching { vaultRepository.readText(file.absolutePath) }
            .getOrElse { return "错误：读取失败：$path" }
        val lines = text.split('\n')
        val offset = (intArg(args, "offset") ?: 1).coerceAtLeast(1)
        if (offset > lines.size) {
            return "错误：起始行 $offset 超出文件总行数（共 ${lines.size} 行）"
        }
        val limit = intArg(args, "limit")?.takeIf { it > 0 }
        val endExclusive = limit?.let { (offset - 1 + it).coerceAtMost(lines.size) } ?: lines.size
        val sliceText = lines.subList(offset - 1, endExclusive).joinToString("\n")
        if (sliceText.length <= MAX_NOTE_CHARS) return sliceText
        val clipped = sliceText.take(MAX_NOTE_CHARS)
        val lastBreak = clipped.lastIndexOf('\n')
        val content = if (lastBreak > 0) clipped.substring(0, lastBreak) else clipped
        val consumedLines = content.count { it == '\n' } + 1
        return content +
            "\n\n[…已达单次读取上限（$MAX_NOTE_CHARS 字符）；如需继续，请用 offset=${offset + consumedLines} 重新调用 read_note…]"
    }

    /** 全文搜索（文件名 + 正文，上限 [SEARCH_LIMIT] 条），输出 JSON（path/snippet）。 */
    private fun searchNotes(root: String, args: JsonObject): String {
        val query = stringArg(args, "query") ?: return "错误：缺少 query 参数"
        val hits = vaultRepository.searchNotes(root, query, limit = SEARCH_LIMIT)
        if (hits.isEmpty()) return "未找到与「$query」相关的笔记"
        return buildJsonArray {
            hits.forEach { hit ->
                addJsonObject {
                    put("path", hit.node.relativePath)
                    hit.snippet?.let { put("snippet", it) }
                }
            }
        }.toString()
    }

    /** 轨迹摘要：[ToolCallRecord.argsSummary] 取主参数（UI 状态行展示用）。 */
    private fun argsSummary(tool: String, args: JsonObject): String = when (tool) {
        ToolSpecs.LIST_NOTES -> stringArg(args, "dir").orEmpty().ifBlank { "." }
        ToolSpecs.READ_NOTE -> stringArg(args, "path").orEmpty()
        ToolSpecs.SEARCH_NOTES -> stringArg(args, "query").orEmpty()
        else -> ""
    }

    /** 轨迹摘要：[ToolCallRecord.resultSummary] 错误类给文案片段，正常给返回体量。 */
    private fun resultSummary(resultText: String): String =
        if (resultText.startsWith("错误：")) resultText.take(80) else "已返回 ${resultText.length} 字符"

    /** argumentsJson 容错解析：非法 JSON / 非对象 → 空参（由各工具报缺失参数错误）。 */
    private fun parseArgs(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private fun stringArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun intArg(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.intOrNull

    private fun relativePath(rootDir: File, file: File): String =
        file.relativeTo(rootDir).path.replace('\\', '/')

    private companion object {
        /** 目录列表：最大展开层数（相对 dir 起点）。 */
        const val MAX_LIST_DEPTH = 3

        /** 目录列表：最大条目数。 */
        const val MAX_LIST_ENTRIES = 200

        /** read_note 单次返回上限（字符）。 */
        const val MAX_NOTE_CHARS = 32_768

        /** search_notes 命中上限。 */
        const val SEARCH_LIMIT = 20

        /** 与展示层一致的隐藏目录过滤（附件夹经图库浏览，不进列表）。 */
        val HIDDEN_DIRS = setOf(".obsidian", ".trash", ".git", VaultRepository.ATTACHMENT_DIR)
    }
}
