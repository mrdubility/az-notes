package com.az.notes.data.ai

import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商列表 JSON 编解码单测（B1）：与生产同一配置（ignoreUnknownKeys + encodeDefaults）；
 * 往返保持全字段，损坏 / 结构不符回退空列表不抛异常（DataStore 容错范式）。
 */
class ProviderCodecTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val sample = AiProvider(
        id = "p1",
        name = "阿里云百炼",
        protocol = AiProtocol.OPENAI_CHAT,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        headers = mapOf("X-Trace" to "1"),
        models = listOf(
            AiModel(id = "qwen-plus"),
            AiModel(id = "qwen-vl-max", vision = true, contextWindow = 131072)
        ),
        lastModelId = "qwen-plus"
    )

    @Test
    fun `round trip preserves every field`() {
        val decoded = ProviderCodec.decode(json, ProviderCodec.encode(json, listOf(sample)))
        assertEquals(listOf(sample), decoded)
    }

    @Test
    fun `decode returns empty for null or blank raw`() {
        assertTrue(ProviderCodec.decode(json, null).isEmpty())
        assertTrue(ProviderCodec.decode(json, "").isEmpty())
        assertTrue(ProviderCodec.decode(json, "   ").isEmpty())
    }

    @Test
    fun `decode returns empty for corrupted or mismatched json`() {
        assertTrue(ProviderCodec.decode(json, "{not-json").isEmpty())
        assertTrue(ProviderCodec.decode(json, """{"a":1}""").isEmpty())
    }

    @Test
    fun `decode tolerates unknown fields from future versions`() {
        // 未来版本新增字段时，旧版 App 应能继续解析（忽略未知键）
        val raw = """
            [{"id":"a","name":"n","protocol":"OPENAI_RESPONSES",
              "baseUrl":"https://a.com/v1","futureField":123}]
        """.trimIndent()
        val decoded = ProviderCodec.decode(json, raw)
        assertEquals(1, decoded.size)
        assertEquals("a", decoded.first().id)
        assertEquals(AiProtocol.OPENAI_RESPONSES, decoded.first().protocol)
    }

    @Test
    fun `encode of empty list round trips to empty`() {
        val encoded = ProviderCodec.encode(json, emptyList())
        assertEquals("[]", encoded)
        assertTrue(ProviderCodec.decode(json, encoded).isEmpty())
    }
}
