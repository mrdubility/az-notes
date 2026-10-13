package com.az.notes.domain.markdown

import com.az.notes.data.storage.VaultRepository
import java.net.URLDecoder

/**
 * Wikilink 渲染前转换：把 Obsidian 双链语法改写为标准 Markdown 形态，交由既有渲染链
 * 原样消费——笔记链接变 `[显示](az-note:目标)`（点击由 UI 层
 * [com.az.notes.ui.common.WikiLinkUriHandler] 拦截解析），图片嵌入变
 * `![](/仓库根相对路径)`（走既有图片加载链）。
 *
 * 存在理由：渲染库不识别 wiki 语法（`[[ ]]` 会被当普通文本），而其链接点击入口
 * 只能经标准链接形态到达；转换放在渲染前一步即两者兼得，渲染库零改动。
 *
 * 边界纪律（对齐 [MarkdownImageLifter] 的保守原则）：
 *  - 代码围栏（``` / ~~~）与行内代码（`` ` ``）内不转换；
 *  - `\[[` 转义不转换；标题行（ATX 与 Setext）不转换（大纲取渲染同源文本，转换产物会污染标题）；
 *  - 只转换已闭合的 `[[...]]`——未闭合的 `[[` 原样保留，天然免疫流式半截；
 *  - `![[` 与 `[[` 由同一正则一次匹配（先长后短），无一处分两次机会。
 *
 * 纯 Kotlin、无 Android 依赖，可在 JVM 单元测试运行；不做任何文件 IO——图片嵌入的
 * 存在性解析经 [transform] 的 resolveEmbed 回调注入，调用方持有全库 nameIndex。
 */
object WikiLinkTransformer {

    /** 笔记链接的私有 scheme：转换产物经它携带原始目标，点击时由 UI 层拦截。 */
    const val LINK_SCHEME = "az-note:"

    /** 图片嵌入尺寸在链接上的标注前缀（fragment 形式：`/path#az-size=100x245`）。 */
    const val SIZE_FRAGMENT_PREFIX = "az-size="

    /** 尺寸参数（`100` / `100x245`）。 */
    private val SIZE_SPEC = Regex("""(\d{1,4})(?:x(\d{1,4}))?""")

    /** `[[目标#锚点|别名]]` / `![[目标|参数]]`：分组依次为 嵌入标记 / 目标 / 锚点 / 别名。 */
    private val WIKI = Regex("""(!?)\[\[([^\[\]|\n#]*)(?:#([^\[\]|\n]*))?(?:\|([^\[\]\n]*))?]]""")

    /** 行内代码片段（反引号成对包裹，支持多反引号）。 */
    private val INLINE_CODE = Regex("`+[^`\n]*`+")

    /** ATX 标题行（与 CommonMark 一致：`#` 后需空白或行尾）。 */
    private val ATX_HEADING = Regex("""^\s{0,3}#{1,6}(\s|$)""")

    /** Setext 标题下划线行（`===` / `---`）。 */
    private val SETEXT_UNDERLINE = Regex("""^\s*[=-]+\s*$""")

    /**
     * 主入口：逐行转换 wikilink；行外上下文（围栏 / 标题）原样保留。
     *
     * @param resolveEmbed `![[目标]]` 的目标 → 仓库根相对路径（不存在返回 null）。
     *        传 null 表示无解析上下文（AI 回答等场景）：嵌入一律按链接处理；
     *        传入但未命中时嵌入保持原文（缺失的图片不打扰）。
     */
    fun transform(markdown: String, resolveEmbed: ((String) -> String?)? = null): String {
        if (markdown.isEmpty() || !markdown.contains("[[")) return markdown
        val lines = markdown.split('\n')
        val out = StringBuilder(markdown.length + 24)
        var inFence = false
        for (idx in lines.indices) {
            val line = lines[idx]
            if (idx > 0) out.append('\n')
            if (isFenceToggle(line)) inFence = !inFence
            if (inFence || !line.contains("[[")) {
                out.append(line)
            } else if (isHeadingLine(lines, idx)) {
                out.append(line)
            } else {
                out.append(transformLine(line, resolveEmbed))
            }
        }
        return out.toString()
    }

    /** 解码转换产物的链接目标（与 [ImageReference.encodeTarget] 编码互为逆操作）。 */
    fun decodeTarget(encoded: String): String =
        runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(encoded)

    /** `|100` / `|100x245` 形式的嵌入参数 → (宽, 高?)；非尺寸形式返回 null。 */
    fun parseSizeSpec(param: String?): Pair<Int, Int?>? {
        val match = SIZE_SPEC.matchEntire(param?.trim().orEmpty()) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull()
        if (width <= 0 || (height != null && height <= 0)) return null
        return width to height
    }

    /** 读取图片嵌入产物 link 上的尺寸标注（见 [SIZE_FRAGMENT_PREFIX]）；无标注返回 null。 */
    fun sizeSpecOf(link: String): Pair<Int, Int?>? {
        val fragment = link.substringAfter('#', "")
        if (!fragment.startsWith(SIZE_FRAGMENT_PREFIX)) return null
        return parseSizeSpec(fragment.removePrefix(SIZE_FRAGMENT_PREFIX))
    }

    // ---------------------------------------------------------------- 内部

    private fun transformLine(line: String, resolveEmbed: ((String) -> String?)?): String {
        if (!line.contains("]]")) return line
        val codeSpans = INLINE_CODE.findAll(line).map { it.range }.toList()
        var cursor = 0
        var out: StringBuilder? = null
        for (m in WIKI.findAll(line)) {
            val start = m.range.first
            if (start > 0 && line[start - 1] == '\\') continue
            if (codeSpans.any { start in it }) continue
            val replacement = replacement(m, resolveEmbed) ?: continue
            val sb = out ?: StringBuilder(line.length + 16).also { out = it }
            sb.append(line, cursor, start).append(replacement)
            cursor = m.range.last + 1
        }
        return (out ?: return line).append(line, cursor, line.length).toString()
    }

    /** 单个匹配的转换产物；返回 null 表示保持原文。 */
    private fun replacement(m: MatchResult, resolveEmbed: ((String) -> String?)?): String? {
        val embed = m.groupValues[1] == "!"
        val target = m.groupValues[2].trim()
        val anchor = m.groupValues[3].trim().ifEmpty { null }
        val alias = m.groupValues[4].trim().ifEmpty { null }

        if (target.isEmpty()) {
            // [[#标题]]：本文锚点，目标仅锚点（点击回跳当前笔记）
            return if (anchor == null) null
            else "[${alias ?: anchor}]($LINK_SCHEME${encode("#$anchor")})"
        }

        if (embed) {
            val resolved = resolveEmbed?.invoke(target)
            if (resolved == null) {
                // 无解析上下文：嵌入按链接处理；有上下文但未命中：保持原文
                return if (resolveEmbed == null) link(alias ?: target, full(target, anchor)) else null
            }
            if (VaultRepository.isImageName(resolved)) {
                // 尺寸参数（`|宽` / `|宽x高`）以 fragment 随链接传递，由独立图片块解析
                val size = parseSizeSpec(alias)
                val suffix = size?.let { (w, h) ->
                    "#" + SIZE_FRAGMENT_PREFIX + w + (h?.let { "x$it" } ?: "")
                } ?: ""
                return "![](${encode("/$resolved")}$suffix)"
            }
            // 解析到笔记等非图片：嵌入降级为链接
            return link(alias ?: target, full(target, anchor))
        }

        val display = alias ?: anchor?.let { "$target > $it" } ?: target
        return link(display, full(target, anchor))
    }

    private fun link(display: String, target: String): String =
        "[$display]($LINK_SCHEME${encode(target)})"

    private fun full(target: String, anchor: String?): String =
        if (anchor == null) target else "$target#$anchor"

    private fun encode(raw: String): String =
        ImageReference.encodeTarget(raw, escapePercent = true)

    private fun isFenceToggle(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    /** 标题行判定：ATX 前缀，或下一行是 Setext 下划线（本行即标题内容）。 */
    private fun isHeadingLine(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (ATX_HEADING.containsMatchIn(line)) return true
        val next = lines.getOrNull(index + 1) ?: return false
        return line.isNotBlank() && SETEXT_UNDERLINE.matches(next)
    }
}
