package com.az.notes.data.sync

/**
 * Gitignore 风格过滤规则匹配器（§6.3）：最后一条匹配的规则生效。
 *
 * 语法（与 gitignore 的常用子集一致）：
 * - 空行与 `#` 开头的行忽略；
 * - `!` 前缀表示重新包含（否定）；
 * - 尾随 `/` 只匹配目录；
 * - 含 `/`（或前导 `/`）的规则锚定到 Vault 根，否则匹配任意层级；
 * - `*` 不跨 `/`，`**` 跨 `/`，`?` 匹配单个非 `/` 字符。
 *
 * 语义要点：某层目录被忽略时，其下内容同样视为被忽略（除非更深层有否定规则命中）。
 */
class IgnoreRules(pattern: String) {

    private data class Rule(val negate: Boolean, val dirOnly: Boolean, val regex: Regex)

    private val rules: List<Rule> = pattern.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { parse(it) }
        .toList()

    /** 是否存在否定规则：整树剪枝时必须保留否定规则重新包含的可能。 */
    val hasNegations: Boolean = rules.any { it.negate }

    val isEmpty: Boolean get() = rules.isEmpty()

    /** 判断相对路径（'/' 分隔，无前导斜杠）是否被忽略。 */
    fun isIgnored(path: String, isDirectory: Boolean): Boolean {
        if (rules.isEmpty()) return false
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        var ignored = false
        for (depth in 1..segments.size) {
            val prefix = segments.take(depth).joinToString("/")
            val isDir = depth < segments.size || isDirectory
            val decision = lastMatch(prefix, isDir) ?: continue
            ignored = decision
        }
        return ignored
    }

    /** 逐层匹配，返回最后一条命中的规则的判定（true = 忽略）。 */
    private fun lastMatch(path: String, isDirectory: Boolean): Boolean? {
        var decision: Boolean? = null
        for (rule in rules) {
            if (rule.dirOnly && !isDirectory) continue
            if (rule.regex.matches(path)) decision = !rule.negate
        }
        return decision
    }

    private fun parse(rawLine: String): Rule? {
        var line = rawLine
        val negate = line.startsWith("!")
        if (negate) line = line.substring(1)
        val dirOnly = line.endsWith("/")
        if (dirOnly) line = line.trimEnd('/')
        val anchored = line.startsWith("/") || line.contains('/')
        line = line.trimStart('/')
        if (line.isEmpty()) return null
        val body = globToRegex(line)
        // 命中的目录本身及其下所有内容都算命中
        val regex = if (anchored) Regex("^$body(?:/.*)?$") else Regex("^(?:.*/)?$body(?:/.*)?$")
        return Rule(negate, dirOnly, regex)
    }

    /** glob → 正则片段（`*` 不跨 `/`，`**` 跨 `/`，`?` 单个非 `/`）。 */
    private fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < glob.length && glob[i] == '/') i++
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                c.isLetterOrDigit() || c == '/' || c == '-' || c == '_' || c == ' ' -> {
                    sb.append(c)
                    i++
                }
                else -> {
                    sb.append('\\').append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    companion object {
        /** 空规则集（不做任何过滤）。 */
        val NONE = IgnoreRules("")
    }
}
