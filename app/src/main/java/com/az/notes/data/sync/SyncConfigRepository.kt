package com.az.notes.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "az_notes_sync")

/**
 * 同步配置仓库（§6.6）：服务器地址 / 账号 / 远端目录 / 策略存 DataStore；
 * 密码（坚果云“应用密码”）单独走 [CredentialStore] 加密存储，不落明文。
 *
 * 多仓库：配置按仓库 id 序列化为 JSON 存「config_<id>」键，跟随当前仓库切换；
 * 未设置过的仓库读取为默认值。
 */
@Singleton
class SyncConfigRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val dataStore = context.syncDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 当前仓库的同步配置（切换仓库自动切换；未设置过 → 默认值）。 */
    val config: Flow<SyncConfig> = combine(
        settingsRepository.settings.map { it.currentVaultId }.distinctUntilChanged(),
        dataStore.data.catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
    ) { vaultId, prefs -> readConfig(prefs, vaultId) }
        .distinctUntilChanged()

    suspend fun setServerUrl(url: String) = update { it.copy(serverUrl = url.trim()) }
    suspend fun setUsername(name: String) = update { it.copy(username = name.trim()) }
    suspend fun setRemoteDir(dir: String) = update { it.copy(remoteDir = dir.trim()) }
    suspend fun setMode(mode: SyncMode) = update { it.copy(mode = mode) }
    suspend fun setConflictStrategy(strategy: ConflictStrategy) =
        update { it.copy(conflictStrategy = strategy) }

    /** 过滤规则：空文本回退默认模板（避免用户误清空后把所有隐藏目录纳入同步）。 */
    suspend fun setIgnoreRules(rules: String) =
        update { it.copy(ignoreRules = rules.take(4000).ifBlank { SyncConfig.DEFAULT_IGNORE_RULES }) }

    /** 大文件上限（MB）：0 = 不限制（§6.3）。 */
    suspend fun setMaxFileSizeMb(mb: Int) =
        update { it.copy(maxFileSizeMb = mb.coerceIn(0, SyncConfig.MAX_FILE_SIZE_MB_LIMIT)) }

    suspend fun setAutoSyncOnStart(enabled: Boolean) = update { it.copy(autoSyncOnStart = enabled) }
    suspend fun setPeriodicInterval(interval: SyncInterval) =
        update { it.copy(periodicInterval = interval) }

    suspend fun setSyncAfterSave(enabled: Boolean) = update { it.copy(syncAfterSave = enabled) }

    /** 清除指定仓库的同步配置（移除仓库时调用；不影响其它仓库）。 */
    suspend fun clearVault(vaultId: String) =
        dataStore.edit { prefs -> prefs.remove(configKey(vaultId)) }

    /** 读取指定仓库的同步配置（配置备份导出；未设置 / 损坏 → 默认值）。 */
    suspend fun configOf(vaultId: String): SyncConfig {
        val prefs = dataStore.data.catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }.first()
        return readConfig(prefs, vaultId)
    }

    /** 备份导入：整份覆盖指定仓库的同步配置（密码不在配置内，走 [CredentialStore] 独立管理）。 */
    suspend fun writeVaultConfig(vaultId: String, config: SyncConfig) =
        dataStore.edit { prefs -> prefs[configKey(vaultId)] = json.encodeToString(config) }

    // ---------------------------------------------------------------- 内部

    private fun configKey(vaultId: String) = stringPreferencesKey("config_$vaultId")

    /** 指定仓库的配置（未设置 / JSON 损坏 → 默认值）。 */
    private fun readConfig(prefs: Preferences, vaultId: String?): SyncConfig {
        val raw = vaultId?.let { prefs[configKey(it)] } ?: return SyncConfig()
        return runCatching { json.decodeFromString<SyncConfig>(raw) }.getOrNull() ?: SyncConfig()
    }

    /**
     * 在当前仓库的配置上应用变更；无仓库时忽略
     * （写入前先建档，见 [SettingsRepository.requireCurrentVaultId]）。
     */
    private suspend fun update(transform: (SyncConfig) -> SyncConfig) {
        val vaultId = settingsRepository.requireCurrentVaultId() ?: return
        dataStore.edit { prefs ->
            val key = configKey(vaultId)
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<SyncConfig>(it) }.getOrNull() }
                ?: SyncConfig()
            prefs[key] = json.encodeToString(transform(current))
        }
    }
}
