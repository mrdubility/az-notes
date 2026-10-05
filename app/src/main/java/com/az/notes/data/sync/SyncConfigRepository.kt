package com.az.notes.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.domain.model.SyncConfig
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
                    ?: SyncMode.BIDIRECTIONAL
            )
        }

    suspend fun setServerUrl(url: String) = edit { it[Keys.SERVER_URL] = url.trim() }
    suspend fun setUsername(name: String) = edit { it[Keys.USERNAME] = name.trim() }
    suspend fun setRemoteDir(dir: String) = edit { it[Keys.REMOTE_DIR] = dir.trim() }
    suspend fun setMode(mode: SyncMode) = edit { it[Keys.MODE] = mode.name }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }
}
