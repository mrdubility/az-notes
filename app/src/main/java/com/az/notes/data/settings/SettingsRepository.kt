package com.az.notes.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "az_notes_settings")

/**
 * 偏好设置仓库（§5.6）。用 DataStore 持久化主题 / 字体 / Vault 路径等。
 * 读取异常回退为默认值（[emptyPreferences]），避免崩溃。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val FONT = stringPreferencesKey("font_family")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val VAULT_PATH = stringPreferencesKey("vault_path")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val PREVIEW_CHARS = intPreferencesKey("preview_chars")
        val SORT_ORDER = stringPreferencesKey("note_sort_order")
    }

    private val dataStore = context.dataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                fontFamily = prefs[Keys.FONT]?.let { runCatching { FontFamilyPreference.valueOf(it) }.getOrNull() }
                    ?: FontFamilyPreference.SANS,
                fontSizeSp = prefs[Keys.FONT_SIZE] ?: 16f,
                lineHeightRatio = prefs[Keys.LINE_HEIGHT] ?: 1.5f,
                vaultPath = prefs[Keys.VAULT_PATH],
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                previewChars = prefs[Keys.PREVIEW_CHARS] ?: 100,
                sortOrder = prefs[Keys.SORT_ORDER]
                    ?.let { runCatching { NoteSortOrder.valueOf(it) }.getOrNull() }
                    ?: NoteSortOrder.MODIFIED_DESC
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setFontFamily(family: FontFamilyPreference) = edit { it[Keys.FONT] = family.name }
    suspend fun setFontSize(sp: Float) = edit { it[Keys.FONT_SIZE] = sp.coerceIn(12f, 24f) }
    suspend fun setLineHeight(ratio: Float) = edit { it[Keys.LINE_HEIGHT] = ratio.coerceIn(1.2f, 2.0f) }
    suspend fun setVaultPath(path: String?) = edit {
        if (path == null) it.remove(Keys.VAULT_PATH) else it[Keys.VAULT_PATH] = path
    }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setPreviewChars(chars: Int) = edit { it[Keys.PREVIEW_CHARS] = chars.coerceIn(20, 300) }
    suspend fun setSortOrder(order: NoteSortOrder) = edit { it[Keys.SORT_ORDER] = order.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }
}
