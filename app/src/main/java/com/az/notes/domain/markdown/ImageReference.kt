package com.az.notes.domain.markdown

import com.az.notes.data.storage.VaultRepository
import java.io.File
import java.net.URLDecoder

/**
 * 附件引用解析（图片 / PDF 等）：从 Markdown 正文里抽出全部引用，并把引用解析为
 * 仓库内的候选文件路径。设计理念是「附件必须依托笔记存在」，因此引用关系是唯一真相：
 * 孤儿检测、删除联动、跨仓库移动都走这里，渲染层（[com.az.notes.ui.reader.VaultImageTransformer]）
 * 也复用同一套解析规则，避免两处逻辑漂移。
 *
 * 支持的写法：
 * - `![alt](path "title")`、`[alt](path)`；路径含空格时支持 `<path with space>` 包裹与 `%20` 编码；
 * - `<img src="path">`；
 * - Obsidian 嵌入 `![[name]]` / `![[name|宽]]` 与双链 `[[name]]`（无扩展名时按常见图片后缀猜测）；
 * - 相对路径（相对笔记所在目录，含 `./`、`../`）与 `/` 仓库根路径。
 *
 * 百分号解码、`#`/`?` 后缀剥离与 `VaultImageTransformer` 完全一致；
 * 写入侧（生成正文链接 / 手动插入）用 [encodeTarget] 做对应的编码。
 */
object ImageReference {

    /** Markdown 图片 / 链接：取括号内的目标（去掉可选 title）。 */
    private val MD_LINK = Regex("!?\\[[^\\]]*]\\(([^)]*)\\)")

    /** HTML img 标签。 */
    private val HTML_IMG = Regex("<img\\s+[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)

    /** Obsidian 嵌入 / 双链：`![[target]]`、`[[target|别名]]`（先长后短，避免 `![[` 被 `[[` 抢先）。 */
    private val WIKI = Regex("!?\\[\\[([^]|]+)(?:\\|[^]]*)?]]")

    /** 无扩展名时按这些后缀猜测目标文件（Obsidian 习惯）。 */
    private val GUESS_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")

    /** 网络 / 内联图片前缀（这些不参与「本地附件」判定）。 */
    private val REMOTE_SCHEMES = listOf("http://", "https://", "data:", "content:")

    /** 是否为网络图片链接（预览页走受控网络加载）。 */
    fun isRemote(link: String): Boolean = REMOTE_SCHEMES.any { link.startsWith(it, ignoreCase = true) }

    /**
     * 抽取正文中的全部引用目标（原样字符串，未解码）。
     * 代码围栏内的内容不参与：围栏行由 [stripCodeFences] 先剔除，避免把示例代码当成引用。
     */
    fun extract(markdown: String): List<String> {
        val body = stripCodeFences(markdown)
        val out = LinkedHashSet<String>()
        MD_LINK.findAll(body).forEach { m -> out += cleanTarget(m.groupValues[1]) }
        HTML_IMG.findAll(body).forEach { m -> out += cleanTarget(m.groupValues[1]) }
        WIKI.findAll(body).forEach { m -> out += m.groupValues[1].trim() }
        return out.filter { it.isNotBlank() }
    }

    /** 正文中的网络图片链接（供引用索引跳过本地解析）。 */
    fun remoteLinks(markdown: String): List<String> = extract(markdown).filter { isRemote(it) }

    /**
     * 解析引用为仓库内的候选绝对路径（不做存在性判断，由调用方筛）。
     *
     * 顺序：笔记同目录 → 笔记旁 `assets/` → 仓库根相对路径 → 无扩展名时按常见后缀补齐
     * → [nameIndex] 提供时按文件名全库匹配（Obsidian 只写 `![[名字]]` 也能命中）。
     * 网络链接、空引用返回空列表；结果一律限定在 [vaultRoot] 内（越界护栏）。
     *
     * @param nameIndex 附件文件名（小写）→ 仓库内相对路径列表；由引用索引层预先建好，
     *                  渲染层不传则只做局部解析。
     */
    fun candidates(
        ref: String,
        vaultRoot: File,
        noteDir: File,
        nameIndex: Map<String, List<String>> = emptyMap()
    ): List<File> {
        val cleaned = cleanTarget(ref)
        if (cleaned.isBlank() || isRemote(cleaned)) return emptyList()
        val root = vaultRoot.normalize()
        val out = LinkedHashSet<File>()
        if (cleaned.startsWith("/")) {
            File(root, cleaned.trimStart('/')).normalize().within(root)?.let { out += it }
        } else {
            File(noteDir, cleaned).normalize().within(root)?.let { out += it }
            // Obsidian 习惯：![[name]] 只写文件名，附件通常在笔记旁 assets/ 下
            if (!cleaned.contains('/')) {
                File(noteDir, "${VaultRepository.ATTACHMENT_DIR}/$cleaned")
                    .normalize().within(root)?.let { out += it }
            }
        }
        // 无扩展名：按常见图片后缀补齐——对全部无扩展候选（含笔记旁 assets/ 候选）。
        // 只猜首个候选会让「![[名字]] 的图在 assets/」在渲染层（不传 nameIndex）解析不到，
        // 与索引层（有 nameIndex）结论漂移。
        out.filter { it.name.substringAfterLast('.', "").isBlank() }.forEach { base ->
            GUESS_EXTENSIONS.forEach { ext -> out += File("${base.absolutePath}.$ext") }
        }
        // 文件名全库匹配：只写名字（或名字+后缀）的引用同样能定位，避免把在用附件误判为孤儿
        if (nameIndex.isNotEmpty()) {
            val name = cleaned.substringAfterLast('/')
            if (name.isNotEmpty()) {
                val key = name.lowercase()
                val relatives = if (name.contains('.')) {
                    nameIndex[key].orEmpty()
                } else {
                    GUESS_EXTENSIONS.flatMap { ext -> nameIndex["$key.$ext"].orEmpty() }
                }
                relatives.forEach { rel ->
                    File(root, rel).normalize().within(root)?.let { out += it }
                }
            }
        }
        return out.toList()
    }

    /** 解析引用并返回真实存在的文件；越界 / 不存在时返回 null（渲染层与索引层同一判定）。 */
    fun resolveExisting(
        ref: String,
        vaultRoot: File,
        noteDir: File,
        nameIndex: Map<String, List<String>> = emptyMap()
    ): File? = candidates(ref, vaultRoot, noteDir, nameIndex).firstOrNull { it.isFile }

    /** 去掉 title、锚点、查询串并做百分号解码（与 VaultImageTransformer 一致）。
     *  title 判定遵循 CommonMark 常见形态：`<>` 包裹的路径取其内部（可含空格）；
     *  否则仅当空格后紧跟 `"` / `'` / `(` 时视为 title 起点——路径本身含空格则原样保留。 */
    private fun cleanTarget(raw: String): String {
        val trimmed = raw.trim()
        val withoutTitle = if (trimmed.startsWith("<")) {
            // `<path with space> "title"`：先剥尖括号取路径，其余（title）丢弃
            val close = trimmed.indexOf('>')
            if (close > 0) trimmed.substring(1, close) else trimmed.removePrefix("<")
        } else {
            val titleStart = findTitleStart(trimmed)
            if (titleStart > 0) trimmed.substring(0, titleStart) else trimmed
        }
        val withoutAnchor = withoutTitle.substringBefore('#').substringBefore('?')
        return decode(withoutAnchor.trim())
    }

    /** title 起点：首个「空格后紧跟引号 / 括号」的位置；不存在返回 -1。 */
    private fun findTitleStart(s: String): Int {
        for (i in 0 until s.length - 1) {
            if (s[i] == ' ' && (s[i + 1] == '"' || s[i + 1] == '\'' || s[i + 1] == '(')) return i
        }
        return -1
    }

    /**
     * 把引用目标编码为可安全写入 Markdown `(...)` 的形态：空格 → `%20`、`#` → `%23`、
     * `(` → `%28`、`)` → `%29`，并对 `%` 做转义——[escapePercent] 为 true（本 App 生成的
     * 路径，可保证解码往返）时一律 `%` → `%25`；为 false（用户手动输入）时，输入已含
     * `%XX` 编码片段则原样返回（避免双重编码），否则同样把 `%` 转义。
     */
    fun encodeTarget(raw: String, escapePercent: Boolean = false): String {
        val t = raw.trim()
        if (!escapePercent && PERCENT_ESCAPE.containsMatchIn(t)) return t
        return t.replace("%", "%25")
            .replace(" ", "%20")
            .replace("#", "%23")
            .replace("(", "%28")
            .replace(")", "%29")
    }

    /** `%XX` 编码片段（判定输入是否已按 URL 编码）。 */
    private val PERCENT_ESCAPE = Regex("%[0-9a-fA-F]{2}")

    /** 百分号解码（保留 '+' 字符本身，不做表单语义转换）。 */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    /** 剔除 ``` / ~~~ 围栏代码块，避免代码里的假链接被当成引用。 */
    private fun stripCodeFences(markdown: String): String {
        val fence = Regex("^\\s*(?:```|~~~).*$")
        val sb = StringBuilder()
        var inFence = false
        markdown.lineSequence().forEach { line ->
            if (fence.matches(line)) {
                inFence = !inFence
                sb.append('\n')
            } else if (!inFence) {
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

    /** 越界护栏：路径不在仓库内时返回 null。 */
    private fun File.within(root: File): File? {
        val rootAbs = root.normalize().absolutePath
        return if (absolutePath == rootAbs || absolutePath.startsWith(rootAbs + File.separator)) this else null
    }
}
