package com.az.notes.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TokenEstimator 粗略估算纯逻辑单测（§6.1）：CJK 0.6 / 其余 0.3，向上取整。
 */
class TokenEstimatorTest {

    @Test
    fun `empty text estimates zero`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `pure cjk counts six tenths per char`() {
        // 2 字 × 0.6 = 1.2 → 2
        assertEquals(2, TokenEstimator.estimate("你好"))
        // 4 字 × 0.6 = 2.4 → 3
        assertEquals(3, TokenEstimator.estimate("你好世界"))
    }

    @Test
    fun `pure latin counts three tenths per char`() {
        // 5 × 0.3 = 1.5 → 2
        assertEquals(2, TokenEstimator.estimate("hello"))
        // 单字符 0.3 → 1
        assertEquals(1, TokenEstimator.estimate("a"))
    }

    @Test
    fun `mixed chinese and latin accumulates`() {
        // 0.6 + 0.6 + 0.3 + 0.3 = 1.8 → 2
        assertEquals(2, TokenEstimator.estimate("你好ab"))
        // 2 字 + 空格 + 5 字母 = 1.2 + 1.8 = 3.0 → 3
        assertEquals(3, TokenEstimator.estimate("你好 hello"))
    }

    @Test
    fun `digits and symbols count as other`() {
        // 5 × 0.3 = 1.5 → 2
        assertEquals(2, TokenEstimator.estimate("12345"))
        assertEquals(2, TokenEstimator.estimate("!@#$%"))
    }

    @Test
    fun `cjk punctuation and extensions count as cjk`() {
        // CJK 标点（0x3000-0x303F）0.6 → 1
        assertEquals(1, TokenEstimator.estimate("。"))
        // 扩展 A（0x3400-0x4DBF）0.6 → 1
        assertEquals(1, TokenEstimator.estimate("\u3400"))
        // 兼容汉字（0xF900-0xFAFF）0.6 → 1
        assertEquals(1, TokenEstimator.estimate("\uF900"))
    }
}
