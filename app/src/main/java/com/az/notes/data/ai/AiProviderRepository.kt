package com.az.notes.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.domain.ai.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "az_notes_ai")

/**
 * AI 供应商配置仓库（B1 配置底座）：全部供应商序列化为 JSON 数组存 DataStore「providers」键，
 * 保持插入顺序；API Key 不在此处，按供应商 id 走 [AiKeyStore] 加密存储（§11）。
 * 宽松解析：未知字段容忍、损坏回退空列表（对齐 SyncConfigRepository 容错范式）。
 */
@Singleton
class AiProviderRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val keyStore: AiKeyStore
) {
    private val dataStore = context.aiDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 供应商列表（插入顺序）。 */
    val providers: Flow<List<AiProvider>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> ProviderCodec.decode(json, prefs[PROVIDERS_KEY]) }
        .distinctUntilChanged()

    /** 新增供应商（[AiProvider.id] 为空时自动生成），返回最终落库的 id。 */
    suspend fun add(provider: AiProvider): String {
        val withId = if (provider.id.isBlank()) {
            provider.copy(id = UUID.randomUUID().toString())
        } else {
            provider
        }
        dataStore.edit { prefs ->
            val current = ProviderCodec.decode(json, prefs[PROVIDERS_KEY])
            prefs[PROVIDERS_KEY] = ProviderCodec.encode(json, current + withId)
        }
        return withId.id
    }

    /**
     * 更新供应商（按 id 变换；不存在时忽略）。
     * 闭包内直接基于落库旧值 copy：可保留表单未编辑的字段（headers / lastModelId），
     * 也避免「读取快照 → 写入」之间的并发覆盖。
     */
    suspend fun update(id: String, transform: (AiProvider) -> AiProvider) {
        dataStore.edit { prefs ->
            val current = ProviderCodec.decode(json, prefs[PROVIDERS_KEY])
            prefs[PROVIDERS_KEY] = ProviderCodec.encode(
                json,
                current.map { if (it.id == id) transform(it) else it }
            )
        }
    }

    /** 删除供应商，并清除其加密存储的 API Key。 */
    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = ProviderCodec.decode(json, prefs[PROVIDERS_KEY])
            prefs[PROVIDERS_KEY] = ProviderCodec.encode(json, current.filterNot { it.id == id })
        }
        keyStore.clearKey(id)
    }

    /** 读取指定供应商当前落库值（未找到 → null）。 */
    suspend fun getById(id: String): AiProvider? =
        providers.first().firstOrNull { it.id == id }

    private companion object {
        val PROVIDERS_KEY = stringPreferencesKey("providers")
    }
}

/**
 * 供应商列表 JSON 编解码（纯函数，供单测直接覆盖）：
 * 编码失败回退空串；解析失败（损坏 / 结构不符）回退空列表。
 */
internal object ProviderCodec {
    fun encode(json: Json, providers: List<AiProvider>): String =
        runCatching { json.encodeToString(providers) }.getOrDefault("")

    fun decode(json: Json, raw: String?): List<AiProvider> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<AiProvider>>(raw) }
            .getOrDefault(emptyList())
    }
}
