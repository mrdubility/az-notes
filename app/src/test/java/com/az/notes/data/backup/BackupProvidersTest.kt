package com.az.notes.data.backup

import com.az.notes.data.ai.mergeProviders
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 备份扩展 AI 供应商单测：载荷序列化往返（不含 Key 字段——安全红线）、旧备份兼容
 * （无 aiProviders 字段 → null 不阻断）、导入映射容错（id / 协议无效跳过、模型逐条容错）、
 * 合并语义（同 id 覆盖、新 id 追加、空导入保持现状）。
 */
class BackupProvidersTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private fun provider(id: String, name: String) = AiProvider(
        id = id,
        name = name,
        protocol = AiProtocol.OPENAI_CHAT,
        baseUrl = "https://example.com/v1"
    )

    @Test
    fun `payload with ai providers round trips and never contains key fields`() {
        val payload = BackupPayload(
            app = "az-notes-backup",
            version = 1,
            aiProviders = listOf(
                BackupAiProvider(
                    id = "p1",
                    name = "DeepSeek",
                    protocol = "OPENAI_CHAT",
                    baseUrl = "https://api.deepseek.com/v1",
                    headers = mapOf("X-Trace" to "1"),
                    models = listOf(
                        BackupAiModel(
                            id = "deepseek-chat",
                            label = "DeepSeek Chat",
                            contextWindow = 65536,
                            vision = false
                        )
                    ),
                    lastModelId = "deepseek-chat"
                )
            )
        )
        val text = json.encodeToString(payload)
        // 安全红线：导出文本绝不出现 Key 字段（大小写不敏感）
        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("api_key", ignoreCase = true))
        val decoded = lenient.decodeFromString<BackupPayload>(text)
        assertEquals(payload.aiProviders, decoded.aiProviders)
    }

    @Test
    fun `old payload without ai providers decodes to null`() {
        val legacy = """{"app":"az-notes-backup","version":1,"settings":{"themeMode":"SYSTEM"}}"""
        val decoded = lenient.decodeFromString<BackupPayload>(legacy)
        assertNull(decoded.aiProviders)
    }

    @Test
    fun `map skips entries with missing id or unknown protocol`() {
        val valid = BackupAiProvider(id = "p", name = "N", protocol = "OPENAI_CHAT", baseUrl = "u")
        assertEquals(AiProtocol.OPENAI_CHAT, valid.toAiProviderOrNull()?.protocol)
        assertNull(valid.copy(id = " ").toAiProviderOrNull())
        assertNull(valid.copy(id = null).toAiProviderOrNull())
        assertNull(valid.copy(protocol = "NOT_A_PROTOCOL").toAiProviderOrNull())
        assertNull(valid.copy(protocol = null).toAiProviderOrNull())
    }

    @Test
    fun `map falls back name to id and tolerates invalid models`() {
        val item = BackupAiProvider(
            id = "p1",
            protocol = "ANTHROPIC_MESSAGES",
            baseUrl = " https://api.anthropic.com/v1 ",
            models = listOf(
                BackupAiModel(id = "m1"),
                BackupAiModel(id = "  "),
                BackupAiModel(id = null, label = "ghost")
            )
        )
        val mapped = item.toAiProviderOrNull()
        assertEquals("p1", mapped?.name)
        assertEquals("https://api.anthropic.com/v1", mapped?.baseUrl)
        assertEquals(listOf("m1"), mapped?.models?.map { it.id })
        assertEquals("m1", mapped?.models?.first()?.label)
    }

    @Test
    fun `merge overrides same id and appends new ids`() {
        val current = listOf(provider("a", "Old"), provider("b", "Keep"))
        val imported = listOf(provider("a", "New"), provider("c", "Added"))
        val merged = mergeProviders(current, imported)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("New", merged.first { it.id == "a" }.name)
    }

    @Test
    fun `merge keeps current for empty import and appends into empty current`() {
        val current = listOf(provider("a", "Old"))
        assertEquals(current, mergeProviders(current, emptyList()))
        val imported = listOf(provider("x", "X"))
        assertEquals(imported, mergeProviders(emptyList(), imported))
    }
}
