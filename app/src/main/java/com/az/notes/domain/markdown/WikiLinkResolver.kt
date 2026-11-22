package com.az.notes.domain.markdown

import com.az.notes.data.storage.VaultRepository
import java.io.File
import java.text.Normalizer

/** 别名索引条目：别名原文 + 声明该别名的笔记相对路径 + 在 aliases 列表中的声明位置。 */
data class NoteAlias(
    val name: String,
    val relativePath: String,
    /** aliases 列表内的下标（0 基）：同名别名消歧的第三级依据，避免改名后解析结果无提示翻转 */
    val declarationIndex: Int = 0
)

/** Wikilink 解析命中的跳转目标：仓库内绝对路径 + 可选标题锚点（`[[X#标题]]` 的标题段）。 */
data class WikiLinkTarget(val path: String, val anchor: String?)

/**
 * Wikilink 笔记目标解析：目标串（已去别名 / 锚点）→ 仓库内笔记文件。
 *
 * 解析链（对齐 Obsidian 行为）：
 *  - 裸文件名：笔记同目录 → 仓库根 → [nameIndex] 全库文件名匹配 → aliases
 *    frontmatter 声明的别名匹配（同名多个时消歧）；
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
     * @param aliasIndex 别名（小写）→ 声明条目的列表；仅裸名目标参与（别名不表达路径）。
     */
    fun resolve(
        target: String,
        vaultRoot: File,
        noteDir: File?,
        nameIndex: Map<String, List<String>> = emptyMap(),
        aliasIndex: Map<String, List<NoteAlias>> = emptyMap()
    ): File? {
        val cleaned = nfc(target.trim().trimStart('/').replace('\\', '/'))
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
            ordered += aliasHits(cleaned, root, noteDir, aliasIndex)
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
        val key = foldName(if (VaultRepository.isMarkdownName(name)) name else "$name.md")
        return nameIndex[key].orEmpty()
            .map { File(root, it).normalize() }
            .filter { it.within(root) != null && it.isFile }
            .sortedWith(compareBy({ it.absolutePath.count { c -> c == '/' } }, { it.absolutePath }))
    }

    /**
     * 别名全库匹配：消歧规则为 同目录优先 → 层级少者先 → 声明位置靠前 → 路径字典序。
     * 加入「声明位置」是为了同名别名冲突时解析结果稳定可预期（改名不会让它默默翻转）。
     */
    private fun aliasHits(
        name: String,
        root: File,
        noteDir: File?,
        aliasIndex: Map<String, List<NoteAlias>>
    ): List<File> {
        if (aliasIndex.isEmpty()) return emptyList()
        return aliasIndex[foldName(name)].orEmpty()
            .map { alias -> File(root, alias.relativePath).normalize() to alias.declarationIndex }
            .filter { (file, _) -> file.within(root) != null && file.isFile }
            .sortedWith(
                compareBy(
                    { (file, _) -> if (noteDir != null && file.parentFile?.absolutePath == noteDir.absolutePath) 0 else 1 },
                    { (file, _) -> file.absolutePath.count { c -> c == '/' } },
                    { (_, declaration) -> declaration },
                    { (file, _) -> file.absolutePath }
                )
            )
            .map { it.first }
    }

    /** 越界护栏：路径不在仓库内时返回 null（与 [ImageReference] 同一判定）。 */
    private fun File.within(root: File): File? {
        val rootAbs = root.absolutePath
        return if (absolutePath == rootAbs || absolutePath.startsWith(rootAbs + File.separator)) this else null
    }

    /**
     * 名称比较用的归一化：NFC（消除 macOS / iCloud 同步回来的 NFD 文件名差异）+ 小写。
     * 索引键与查询键必须走同一函数，否则"明明同名却解析不到"。
     */
    fun foldName(raw: String): String = nfc(raw).lowercase()

    /** Unicode NFC 归一化（只影响比较，不改动磁盘文件名）。 */
    private fun nfc(raw: String): String =
        runCatching { Normalizer.normalize(raw, Normalizer.Form.NFC) }.getOrDefault(raw)
}
