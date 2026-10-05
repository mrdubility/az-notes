package com.az.notes.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "az_notes_sync")

/**
 * 同步配置仓库（§6.6）：服务器地址 / 账号 / 远端目录 / 策略存 DataStore；
 * 密码（坚果云“应用密码”）单独走 [CredentialStore] 加密存储，不落明文。
 */
@Singleton
class SyncConfigRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private object Keys {
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

    private val dataStore = context.syncDataStore

    val config: Flow<SyncConfig> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            SyncConfig(
                serverUrl = prefs[Keys.SERVER_URL] ?: SyncConfig.DEFAULT_SERVER_URL,
                username = prefs[Keys.USERNAME] ?: "",
                remoteDir = prefs[Keys.REMOTE_DIR] ?: "az-notes",
                mode = prefs[Keys.MODE]
                    ?.let { runCatching { SyncMode.valueOf(it) }.getOrNull() }
                    ?: SyncMode.BIDIRECTIONAL,
                conflictStrategy = prefs[Keys.CONFLICT_STRATEGY]
                    ?.let { runCatching { ConflictStrategy.valueOf(it) }.getOrNull() }
                    ?: ConflictStrategy.CONFLICT_COPY,
                ignoreRules = prefs[Keys.IGNORE_RULES] ?: SyncConfig.DEFAULT_IGNORE_RULES,
                maxFileSizeMb = prefs[Keys.MAX_FILE_SIZE_MB] ?: 50,
                autoSyncOnStart = prefs[Keys.AUTO_SYNC_ON_START] ?: true,
                periodicInterval = prefs[Keys.PERIODIC_INTERVAL]
                    ?.let { runCatching { SyncInterval.valueOf(it) }.getOrNull() }
                    ?: SyncInterval.OFF,
                syncAfterSave = prefs[Keys.SYNC_AFTER_SAVE] ?: true
            )
        }

    suspend fun setServerUrl(url: String) = edit { it[Keys.SERVER_URL] = url.trim() }
    suspend fun setUsername(name: String) = edit { it[Keys.USERNAME] = name.trim() }
    suspend fun setRemoteDir(dir: String) = edit { it[Keys.REMOTE_DIR] = dir.trim() }
    suspend fun setMode(mode: SyncMode) = edit { it[Keys.MODE] = mode.name }
    suspend fun setConflictStrategy(strategy: ConflictStrategy) =
        edit { it[Keys.CONFLICT_STRATEGY] = strategy.name }

    /** 过滤规则：空文本回退默认模板（避免用户误清空后把所有隐藏目录纳入同步）。 */
    suspend fun setIgnoreRules(rules: String) = edit {
        it[Keys.IGNORE_RULES] = rules.take(4000).ifBlank { SyncConfig.DEFAULT_IGNORE_RULES }
    }

    suspend fun setMaxFileSizeMb(mb: Int) = edit { it[Keys.MAX_FILE_SIZE_MB] = mb.coerceIn(1, 1024) }
    suspend fun setAutoSyncOnStart(enabled: Boolean) = edit { it[Keys.AUTO_SYNC_ON_START] = enabled }
    suspend fun setPeriodicInterval(interval: SyncInterval) =
        edit { it[Keys.PERIODIC_INTERVAL] = interval.name }

    suspend fun setSyncAfterSave(enabled: Boolean) = edit { it[Keys.SYNC_AFTER_SAVE] = enabled }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }
}
