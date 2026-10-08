package com.az.notes.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * baseUrl 规范化纯逻辑单测（B1 配置底座）：去首尾空白与尾部 `/`、
 * 仅接受 http/https、拒绝空主机名；表单校验与落库统一依赖该结果（§3.2）。
 */
class BaseUrlNormalizerTest {

    @Test
    fun `keeps clean https url unchanged`() {
        val url = "https://api.openai.com/v1"
        assertEquals(url, BaseUrlNormalizer.normalize(url))
    }

    @Test
    fun `trims whitespace and trailing slashes`() {
        assertEquals("https://a.com/v1", BaseUrlNormalizer.normalize("  https://a.com/v1/  "))
        assertEquals("https://a.com/v1", BaseUrlNormalizer.normalize("https://a.com/v1///"))
        assertEquals("https://a.com", BaseUrlNormalizer.normalize("https://a.com/"))
    }

    @Test
    fun `accepts http scheme for local deployments`() {
        // 本地 Ollama 等场景允许 http://（表单另有明文传输提示）
        val url = "http://192.168.1.100:11434/v1"
        assertEquals(url, BaseUrlNormalizer.normalize(url))
    }

    @Test
    fun `rejects blank or scheme missing input`() {
        assertNull(BaseUrlNormalizer.normalize(""))
        assertNull(BaseUrlNormalizer.normalize("   "))
        assertNull(BaseUrlNormalizer.normalize("api.openai.com/v1"))
        assertNull(BaseUrlNormalizer.normalize("ftp://a.com"))
    }

    @Test
    fun `rejects empty host`() {
        assertNull(BaseUrlNormalizer.normalize("https://"))
        assertNull(BaseUrlNormalizer.normalize("http:///x"))
    }
}
