package com.az.notes.domain.markdown

import com.az.notes.data.storage.VaultRepository
import java.io.File

/**
 * Wikilink 笔记目标解析：目标串（已去别名 / 锚点）→ 仓库内笔记文件。
 *
 * 解析链（对齐 Obsidian 行为）：
 *  - 裸文件名：笔记同目录 → 仓库根 → [nameIndex] 全库文件名匹配（同名多个时消歧）；
 *  - 含 `/` 或前导 `/`：从仓库根起算（Obsidian 语义），无 `.md` 后缀自动补齐。
 *
 * 同名消歧（确定性规则）：同目录优先 → 路径层级最少 → 路径字典序最小。Obsidian 的
 * 同名匹配算法未公开（实测为「先找到的」启发式），本规则为主流行为的一致近似。
 *
 * 纯 Kotlin、无 Android 依赖，可在 JVM 单元测试运行；只做存在性判定，不写盘
 * （与 [ImageReference] 同一护栏口径：结果一律限定在仓库根内）。
 */
object WikiLinkResolver {

    /**
     * 解析 [target] 为仓库内现存笔记；未命中返回 null。
     *
     * @param nameIndex 文件名（小写）→ 仓库内相对路径列表（见
     *                  [com.az.notes.data.storage.VaultNameIndex]）；不传时只做
     *                  同目录 / 仓库根两处局部解析。
     */
    fun resolve(
        target: String,
        vaultRoot: File,
        noteDir: File?,
        nameIndex: Map<String, List<String>> = emptyMap()
    ): File? {
        val cleaned = target.trim().trimStart('/').replace('\\', '/')
        if (cleaned.isEmpty()) return null
        val root = vaultRoot.normalize()
        val slashed = cleaned.contains('/')

        val ordered = LinkedHashSet<File>()
        if (!slashed && noteDir != null) {
            noteFile(noteDir, cleaned).within(root)?.let { ordered += it }
        }
        noteFile(root, cleaned).within(root)?.let { ordered += it }
        if (!slashed) {
            ordered += indexedHits(cleaned, root, nameIndex)
        }
        return ordered.firstOrNull { it.isFile }
    }

    /**
     * 「新建笔记」目标路径：[target] 的仓库内落点（前导 `/` 与子路径按仓库根相对，
     * 无 `.md` 后缀自动补齐）；越界或名称为空返回 null。仅做路径计算，不落盘。
     */
    fun createNotePath(target: String, vaultRoot: File): File? {
        val raw = target.substringBefore('#').trim().trimStart('/').replace('\\', '/')
        val name = raw.substringAfterLast('/')
        if (name.isEmpty()) return null
        val rel = if (VaultRepository.isMarkdownName(name)) raw else "$raw.md"
        val root = vaultRoot.normalize()
        val rootAbs = root.absolutePath
        val file = File(root, rel).normalize()
        if (file.absolutePath == rootAbs || !file.absolutePath.startsWith("$rootAbs${File.separator}")) {
            return null
        }
        return file
    }

    // ---------------------------------------------------------------- 内部

    /** 名称补 `.md` 后的文件（已带 Markdown 扩展则原样）。 */
    private fun noteFile(base: File, relative: String): File {
        val f = File(base, relative).normalize()
        return if (VaultRepository.isMarkdownName(f.name)) f else File(f.parentFile, f.name + ".md")
    }

    /** nameIndex 全库匹配：按键预筛 → 越界 / 存在性过滤 → 消歧排序（层少者先，同级字典序）。 */
    private fun indexedHits(
        name: String,
        root: File,
        nameIndex: Map<String, List<String>>
    ): List<File> {
        if (nameIndex.isEmpty()) return emptyList()
        val key = (if (VaultRepository.isMarkdownName(name)) name else "$name.md").lowercase()
        return nameIndex[key].orEmpty()
            .map { File(root, it).normalize() }
            .filter { it.within(root) != null && it.isFile }
            .sortedWith(compareBy({ it.absolutePath.count { c -> c == '/' } }, { it.absolutePath }))
    }

    /** 越界护栏：路径不在仓库内时返回 null（与 [ImageReference] 同一判定）。 */
    private fun File.within(root: File): File? {
        val rootAbs = root.absolutePath
        return if (absolutePath == rootAbs || absolutePath.startsWith(rootAbs + File.separator)) this else null
    }
}
