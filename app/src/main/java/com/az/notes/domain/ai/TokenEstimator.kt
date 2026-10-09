package com.az.notes.domain.ai

import kotlin.math.ceil

/**
 * 粗略 token 估算（§6.1）：不引入分词依赖，按字符类别加权近似——
 * CJK（含扩展区 / 兼容区 / 标点）约 0.6 token/字，其余约 0.3 token/字符，向上取整。
 */
object TokenEstimator {

    /** 估算文本 token 数（空串为 0）。 */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0.0
        text.forEach { ch ->
            tokens += if (isCjk(ch)) CJK_PER_CHAR else OTHER_PER_CHAR
        }
        return ceil(tokens).toInt()
    }

    /** CJK 判定：基本汉字 / 扩展 A / 兼容汉字 / CJK 标点。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x3000..0x303F
    }

    private const val CJK_PER_CHAR = 0.6
    private const val OTHER_PER_CHAR = 0.3
}
