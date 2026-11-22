package com.az.notes.domain.markdown

/**
 * 属性在原文中的行区间（0 基、闭区间，相对整个文件）与原文形态标记。
 *
 * [advanced] 表示该属性的原文里含当前解析器无法无损表达的 YAML（嵌套子键 / 块标量 /
 * 行尾注释 / 多行纯量）——这类内容一旦整块重写就会被压平，故 [FrontmatterParser.patchAll]
 * 在该键被修改时直接拒绝，交回编辑器手工处理。
 */
data class AttrSpan(
    val startLine: Int,
    val endLine: Int,
    val advanced: Boolean = false
)

/** 单条属性：key + 一个或多个值（多值来自 YAML 列表写法）；[span] 为原文行区间，新建条目为 null。 */
data class FrontmatterAttribute(
    val key: String,
    val values: List<String>,
    val span: AttrSpan? = null
)

/**
 * frontmatter 拆分结果：[entries] 属性列表（保序）、[prefix] 原文前缀（含结束分隔行与换行）、
 * [body] 正文、[endLine] 闭合分隔行下标（0 基，无属性块为 -1）。
 * 无合法属性块时 [prefix] 为空串、[body] 为原文。
 */
data class FrontmatterSplit(
    val entries: List<FrontmatterAttribute>,
    val prefix: String,
    val body: String,
    val endLine: Int = -1
) {
    /** 查询属性的首个值（如 note_type）；无该属性时返回 null。 */
    fun value(key: String): String? =
        entries.firstOrNull { it.key == key }?.values?.firstOrNull()

    /**
     * aliases 属性值：兼容块列表与 `aliases: [a, b]` 行内列表写法，
     * 去除引号包裹；无声明返回空列表。
     */
    fun aliases(): List<String> {
        val attr = entries.firstOrNull { it.key.equals("aliases", ignoreCase = true) }
            ?: return emptyList()
        return attr.values.flatMap { raw ->
            val v = raw.trim()
            if (v.startsWith("[") && v.endsWith("]")) {
                v.substring(1, v.length - 1).split(',').map { it.trim() }
            } else {
                listOf(v)
            }
        }.map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    }

    /**
     * tags 属性值：兼容块列表与 `tags: [a, b]` 行内列表写法，值允许带可选
     * `#` 前缀（Obsidian 的 YAML tags 通常不带，带时同样收纳）；仅返回经
     * [TagMatcher.isValid] 校验合法的标签名，无声明返回空列表。
     */
    fun tags(): List<String> {
        val attr = entries.firstOrNull { it.key.equals("tags", ignoreCase = true) }
            ?: return emptyList()
        return attr.values.flatMap { raw ->
            val v = raw.trim()
            if (v.startsWith("[") && v.endsWith("]")) {
                v.substring(1, v.length - 1).split(',').map { it.trim() }
            } else {
                listOf(v)
            }
        }.map { it.trim().trim('"', '\'').removePrefix("#") }
            .filter { TagMatcher.isValid(it) }
    }
}

/**
 * 笔记头部属性（frontmatter）解析。
 *
 * 识别笔记开头形如：
 * ```
 * ---
 * tag: log
 * note_type: task
 * ---
 * ```
 * 的属性块：首行 `---` 起始、`---` 闭合、块内按 `key: value` 解析；
 * 键后无值时可跟缩进的 `- 值` 行组成多值（YAML 列表）：
 * ```
 * tags:
 *   - tag
 *   - 生日快乐
 * ```
 * 首行不是 `---` 或未在 [MAX_SCAN_LINES] 行内闭合时，整篇按普通正文处理。
 */
object FrontmatterParser {

    /** 结束分隔行扫描上限：防止把正文中的水平分隔线误当属性块。 */
    private const val MAX_SCAN_LINES = 60

    private const val DELIMITER = "---"

    /** 拆分 [content]；写回时用 `prefix + body` 可无损还原原文。 */
    fun split(content: String): FrontmatterSplit {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val end = endDelimiterIndex(lines)
            ?: return FrontmatterSplit(emptyList(), "", normalized)
        val entries = ArrayList<FrontmatterAttribute>()
        var i = 1
        while (i < end) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }
            val key = line.substring(0, colon).trim()
            if (key.isEmpty()) {
                i++
                continue
            }
            val inline = line.substring(colon + 1).trim()
            if (inline.isNotEmpty()) {
                val advanced = advancedInline(inline, hasIndentedChild(lines, i, end))
                entries += FrontmatterAttribute(key, listOf(inline), AttrSpan(i, i, advanced))
                i++
            } else {
                // 键后换行的 YAML 列表：收集后续缩进的 "- 值" 行
                val values = ArrayList<String>()
                var j = i + 1
                while (j < end) {
                    val item = lines[j].trim()
                    if (item == "-") {
                        values += ""
                        j++
                    } else if (item.startsWith("- ")) {
                        values += item.substring(2).trim()
                        j++
                    } else {
                        break
                    }
                }
                val lastLine = if (values.isEmpty()) i else j - 1
                val advanced = advancedInline("", hasIndentedChild(lines, lastLine, end))
                entries += FrontmatterAttribute(key, values, AttrSpan(i, lastLine, advanced))
                i = if (values.isEmpty()) i + 1 else j
            }
        }
        val prefix = lines.take(end + 1).joinToString("\n") + "\n"
        val body = lines.drop(end + 1).joinToString("\n")
        return FrontmatterSplit(entries, prefix, body, end)
    }

    /** 剥离 frontmatter 返回正文；无属性块时返回原文（换行已规范化为 LF）。 */
    fun strip(content: String): String = split(content).body

    /**
     * 用 [entries] 生成属性块并拼回 [body]（属性为空时直接返回正文）：
     * 单值写 `key: value`，多值写 YAML 列表，空值保留 `key:` 占位。
     */
    fun build(entries: List<FrontmatterAttribute>, body: String): String {
        val valid = entries.filter { it.key.isNotBlank() }
        if (valid.isEmpty()) return body
        val sb = StringBuilder()
        sb.append(DELIMITER).append('\n')
        valid.forEach { attr ->
            val key = attr.key.trim()
            val values = attr.values.map { it.trim() }.filter { it.isNotEmpty() }
            when {
                values.isEmpty() -> sb.append(key).append(':').append('\n')
                values.size == 1 -> sb.append(key).append(": ").append(values[0]).append('\n')
                else -> {
                    sb.append(key).append(':').append('\n')
                    values.forEach { value -> sb.append("  - ").append(value).append('\n') }
                }
            }
        }
        sb.append(DELIMITER).append('\n').append(body)
        return sb.toString()
    }

    /** 用 [entries] 替换现有属性块（保留正文）；原无属性块时在文首生成。 */
    fun update(content: String, entries: List<FrontmatterAttribute>): String =
        build(entries, split(content).body)

    /**
     * 属性写回的「外科手术」入口：只改动与 [entries] 不一致的键，其余原文行（含注释、嵌套
     * 子键、块标量、行内列表写法、键序）逐行原样保留。
     *
     * **注意 [entries] 是完整的属性列表，不是"仅改动的键"**：不在列表里的键视为删除，
     * 列表为空则删掉整个属性块。只传变动项会误删其它属性。
     *
     *  - 值未变的键 → 原样输出其行区间，一个字符都不碰；
     *  - 值变化的键 → 只重写该键的行区间；
     *  - 新增键 → 追加在闭合分隔行之前（保持调用方给定顺序）；
     *  - 删除键 → 删除其行区间；
     *  - 该键原文含编辑器无法表达的形态（[AttrSpan.advanced]）且被修改 → 返回
     *    [FrontmatterPatchResult.Refused]，绝不静默压平。
     */
    fun patchAll(content: String, entries: List<FrontmatterAttribute>): FrontmatterPatchResult {
        val split = split(content)
        val incoming = entries.filter { it.key.isNotBlank() }
        if (incoming.isEmpty()) return FrontmatterPatchResult.Ok(split.body)
        if (split.endLine < 0) return FrontmatterPatchResult.Ok(build(incoming, split.body))

        val lines = normalizedLines(content)
        val originals = split.entries
        val consumed = BooleanArray(incoming.size)
        val actions = HashMap<Int, AttrAction>(originals.size)
        val refused = ArrayList<String>()
        originals.forEachIndexed { oi, old ->
            val ni = incoming.indices.firstOrNull {
                !consumed[it] && incoming[it].key.trim() == old.key.trim()
            } ?: -1
            if (ni < 0) {
                actions[oi] = AttrAction.Delete
                return@forEachIndexed
            }
            consumed[ni] = true
            val neu = incoming[ni]
            actions[oi] = when {
                sameValues(old, neu) -> AttrAction.Keep
                old.span?.advanced == true -> {
                    refused += old.key
                    AttrAction.Keep
                }
                else -> AttrAction.Replace(render(neu.key.trim(), neu.values))
            }
        }
        if (refused.isNotEmpty()) return FrontmatterPatchResult.Refused(refused)

        val owner = HashMap<Int, Int>(originals.size * 2)
        originals.forEachIndexed { oi, attr ->
            attr.span?.let { span -> for (l in span.startLine..span.endLine) owner[l] = oi }
        }
        val out = ArrayList<String>(lines.size + 8)
        // 闭合分隔行之前的逐行重建：有归属行按键的处置取舍，无归属行（注释 / 空行）原样保留
        out += lines[0]
        for (i in 1 until split.endLine) {
            val oi = owner[i]
            if (oi == null) {
                out += lines[i]
                continue
            }
            when (val action = actions[oi]) {
                null, AttrAction.Delete -> Unit
                AttrAction.Keep -> out += lines[i]
                is AttrAction.Replace -> if (i == originals[oi].span?.startLine) out += action.lines
            }
        }
        incoming.forEachIndexed { ni, attr ->
            if (!consumed[ni]) out += render(attr.key.trim(), attr.values)
        }
        out += lines[split.endLine]
        out += lines.drop(split.endLine + 1)
        return FrontmatterPatchResult.Ok(out.joinToString("\n"))
    }

    /** 定位结束分隔行下标（0 基）；首行不是 `---` 或未闭合时返回 null。 */
    private fun endDelimiterIndex(lines: List<String>): Int? {
        if (lines.isEmpty() || lines[0].trimEnd() != DELIMITER) return null
        val limit = minOf(lines.size, MAX_SCAN_LINES + 1)
        for (i in 1 until limit) {
            if (lines[i].trimEnd() == DELIMITER) return i
        }
        return null
    }

    /** 属性值是否等价（忽略首尾空白与空值占位）。 */
    private fun sameValues(a: FrontmatterAttribute, b: FrontmatterAttribute): Boolean =
        cleanValues(a.values) == cleanValues(b.values)

    private fun cleanValues(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }

    /** 单条属性的文本形态：单值一行 / 多值块列表 / 空值占位。 */
    private fun render(key: String, values: List<String>): List<String> {
        val v = cleanValues(values)
        return when {
            v.isEmpty() -> listOf("$key:")
            v.size == 1 -> listOf("$key: ${v[0]}")
            else -> listOf("$key:") + v.map { "  - $it" }
        }
    }

    private fun normalizedLines(content: String): List<String> =
        content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** 块标量起始（`|` / `>` 及带 chomping 指示符的写法）。 */
    private val BLOCK_SCALAR = Regex("""[|>][+-]?$""")

    /** 行尾注释（`#` 前有空白、后为空白或行尾）。 */
    private val TRAILING_COMMENT = Regex("""\s#(\s|$)""")

    /** 键行本身是否含编辑器无法无损表达的形态。 */
    private fun advancedInline(inline: String, hasNested: Boolean): Boolean =
        BLOCK_SCALAR.matches(inline) ||
            TRAILING_COMMENT.containsMatchIn(inline) ||
            (inline.isEmpty() && hasNested)

    /**
     * 键行之后是否紧跟「缩进的非列表行」（嵌套 map 或多行纯量）：当前解析器会把它当成独立条目，
     * 整块重写时父键会被压平成空值 key，故标为 advanced。
     */
    private fun hasIndentedChild(lines: List<String>, keyLine: Int, end: Int): Boolean {
        val next = keyLine + 1
        if (next >= end) return false
        val line = lines[next]
        if (line.isEmpty() || line[0] != ' ' && line[0] != '\t') return false
        val item = line.trim()
        return item.isNotEmpty() && item != "-" && !item.startsWith("- ")
    }

    /** 单条原属性的处置方式（内部）。 */
    private sealed interface AttrAction {
        data object Keep : AttrAction

        data object Delete : AttrAction

        data class Replace(val lines: List<String>) : AttrAction
    }
}

/** [FrontmatterParser.patchAll] 的结果：成功文本 / 因高级 YAML 被拒绝的键列表。 */
sealed interface FrontmatterPatchResult {
    data class Ok(val text: String) : FrontmatterPatchResult

    data class Refused(val keys: List<String>) : FrontmatterPatchResult
}
