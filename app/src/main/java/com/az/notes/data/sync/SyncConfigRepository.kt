package com.az.notes.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
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
 * 旧版全局键组（server_url 等）仅作升级迁移窗口的回退读取，
 * 由 [VaultMigrationRunner] 一次性迁入 per-vault JSON 后删除。
 */
@Singleton
class SyncConfigRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository
) {
    private object Keys {
        // ---- 旧版全局键组：仅迁移读取 / 一次性迁移，勿在正常流程写入 ----
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val REMOTE_DIR = stringPreferencesKey("remote_dir")
        val MODE = stringPreferencesKey("mode")
        val CONFLICT_STRATEGY = stringPreferencesKey("conflict_strategy")
        val IGNORE_RULES = stringPreferencesKey("ignore_rules")
        val MAX_FILE_SIZE_MB = intPreferencesKey("max_file_size_mb")
        val AUTO_SYNC_ON_START = booleanPreferencesKey("auto_sync_on_start")
        val PERIODIC_INTERVAL = stringPreferencesKey("periodic_interval")
        val SYNC_AFTER_SAVE = booleanPreferencesKey("sync_after_save")
    }

    private val legacyKeys = listOf(
        Keys.SERVER_URL, Keys.USERNAME, Keys.REMOTE_DIR, Keys.MODE, Keys.CONFLICT_STRATEGY,
        Keys.IGNORE_RULES, Keys.MAX_FILE_SIZE_MB, Keys.AUTO_SYNC_ON_START,
        Keys.PERIODIC_INTERVAL, Keys.SYNC_AFTER_SAVE
    )

    private val dataStore = context.syncDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 当前仓库的同步配置（切换仓库自动切换；无仓库时回退迁移数据 / 默认值）。 */
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

    // ---------------------------------------------------------------- 迁移

    /**
     * 一次性迁移（幂等）：旧版全局键组 → 当前仓库的「config_<id>」JSON，随后删除旧键。
     * 若当前仓库已存在 JSON（升级后用户已先改过设置），保留 JSON、仅删旧键。
     */
    suspend fun migrateLegacyConfig() {
        val vaultId = settingsRepository.settings.first().currentVaultId ?: return
        if (vaultId == SettingsRepository.LEGACY_VAULT_ID) return
        dataStore.edit { prefs ->
            if (legacyKeys.none { prefs[it] != null }) return@edit
            val key = configKey(vaultId)
            if (prefs[key] == null) prefs[key] = json.encodeToString(readLegacyConfig(prefs))
            legacyKeys.forEach { prefs.remove(it) }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun configKey(vaultId: String) = stringPreferencesKey("config_$vaultId")

    private fun readConfig(prefs: Preferences, vaultId: String?): SyncConfig {
        val raw = vaultId
            ?.takeIf { it != SettingsRepository.LEGACY_VAULT_ID }
            ?.let { prefs[configKey(it)] }
        if (raw != null) {
            runCatching { json.decodeFromString<SyncConfig>(raw) }.getOrNull()?.let { return it }
        }
        // 迁移窗口（合成 id）/ 无仓库 / JSON 损坏：回退旧版全局键（无则全默认）
        return readLegacyConfig(prefs)
    }

    /** 旧版全局键组 → [SyncConfig]（缺项取默认；仅供迁移窗口与迁移方法使用）。 */
    private fun readLegacyConfig(prefs: Preferences): SyncConfig = SyncConfig(
        serverUrl = prefs[Keys.SERVER_URL]?.takeIf { it.isNotBlank() } ?: SyncConfig.DEFAULT_SERVER_URL,
        username = prefs[Keys.USERNAME].orEmpty(),
        remoteDir = prefs[Keys.REMOTE_DIR]?.takeIf { it.isNotBlank() } ?: "az-notes",
        mode = prefs[Keys.MODE]
            ?.let { runCatching { SyncMode.valueOf(it) }.getOrNull() }
            ?: SyncMode.BIDIRECTIONAL,
        conflictStrategy = prefs[Keys.CONFLICT_STRATEGY]
            ?.let { runCatching { ConflictStrategy.valueOf(it) }.getOrNull() }
            ?: ConflictStrategy.CONFLICT_COPY,
        ignoreRules = prefs[Keys.IGNORE_RULES] ?: SyncConfig.DEFAULT_IGNORE_RULES,
        maxFileSizeMb = (prefs[Keys.MAX_FILE_SIZE_MB] ?: 50)
            .coerceIn(0, SyncConfig.MAX_FILE_SIZE_MB_LIMIT),
        autoSyncOnStart = prefs[Keys.AUTO_SYNC_ON_START] ?: true,
        periodicInterval = prefs[Keys.PERIODIC_INTERVAL]
            ?.let { runCatching { SyncInterval.valueOf(it) }.getOrNull() }
            ?: SyncInterval.OFF,
        syncAfterSave = prefs[Keys.SYNC_AFTER_SAVE] ?: true
    )

    /**
     * 在当前仓库的配置上应用变更；无仓库时忽略。
     * 迁移窗口内先补建档，避免写入丢失（见 [SettingsRepository.requireCurrentVaultId]）。
     */
    private suspend fun update(transform: (SyncConfig) -> SyncConfig) {
        val vaultId = settingsRepository.requireCurrentVaultId() ?: return
        dataStore.edit { prefs ->
            val key = configKey(vaultId)
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<SyncConfig>(it) }.getOrNull() }
                ?: readLegacyConfig(prefs)
            prefs[key] = json.encodeToString(transform(current))
        }
    }
}
