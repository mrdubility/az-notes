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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.EditorTool
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import com.az.notes.util.LocaleHelper
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
        val DEFAULT_NOTE_NAME = stringPreferencesKey("default_note_name")
        val TRASH_RETENTION = intPreferencesKey("trash_retention_days")
        val FAB_ACTION = stringPreferencesKey("fab_action")
        val LANGUAGE = stringPreferencesKey("language")
        val TRASH_ENABLED = booleanPreferencesKey("trash_enabled")
        val TOOL_ORDER = stringPreferencesKey("editor_tool_order")
        val TOOL_DISABLED = stringPreferencesKey("editor_tool_disabled")
        val FAVORITES = stringSetPreferencesKey("favorite_paths")
        val SHARE_FOLDER = stringPreferencesKey("share_folder")
    }

    private val appContext = context.applicationContext
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
                    ?: NoteSortOrder.MODIFIED_DESC,
                defaultNoteName = prefs[Keys.DEFAULT_NOTE_NAME]?.takeIf { it.isNotBlank() }
                    ?: "新建笔记",
                trashRetentionDays = prefs[Keys.TRASH_RETENTION] ?: 30,
                fabAction = prefs[Keys.FAB_ACTION]
                    ?.let { runCatching { FabAction.valueOf(it) }.getOrNull() }
                    ?: FabAction.NEW_NOTE,
                language = prefs[Keys.LANGUAGE]
                    ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                    ?: AppLanguage.SYSTEM,
                trashEnabled = prefs[Keys.TRASH_ENABLED] ?: true,
                editorToolOrder = prefs[Keys.TOOL_ORDER]
                    ?.split(',')
                    ?.filter { EditorTool.fromId(it) != null }
                    ?.takeIf { it.isNotEmpty() }
                    ?: EditorTool.defaultOrder,
                editorToolDisabled = prefs[Keys.TOOL_DISABLED]
                    ?.split(',')
                    ?.filter { EditorTool.fromId(it) != null }
                    ?.toSet()
                    ?: emptySet(),
                favoritePaths = prefs[Keys.FAVORITES] ?: emptySet(),
                shareFolder = prefs[Keys.SHARE_FOLDER]?.takeIf { it.isNotBlank() }
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

    /** 默认新建笔记名（清洗非法字符；空则回退默认值）。 */
    suspend fun setDefaultNoteName(name: String) = edit {
        val cleaned = name.trim().take(60)
        it[Keys.DEFAULT_NOTE_NAME] = cleaned.ifBlank { "新建笔记" }
    }

    /** 回收站自动清理天数（0 = 永不清理；上限 180 天）。 */
    suspend fun setTrashRetentionDays(days: Int) = edit {
        it[Keys.TRASH_RETENTION] = days.coerceIn(0, 180)
    }

    /** 右下角加号点击的默认行为。 */
    suspend fun setFabAction(action: FabAction) = edit { it[Keys.FAB_ACTION] = action.name }

    /** 应用语言：同时写 SharedPreferences 镜像，供 attachBaseContext 同步读取。 */
    suspend fun setLanguage(language: AppLanguage) {
        LocaleHelper.persist(appContext, language.tag)
        edit { it[Keys.LANGUAGE] = language.name }
    }

    /** 是否启用回收站。 */
    suspend fun setTrashEnabled(enabled: Boolean) = edit { it[Keys.TRASH_ENABLED] = enabled }

    /** 编辑器工具栏顺序（存工具 id，逗号分隔）。 */
    suspend fun setEditorToolOrder(order: List<String>) = edit {
        it[Keys.TOOL_ORDER] = order.filter { id -> EditorTool.fromId(id) != null }.joinToString(",")
    }

    /** 编辑器工具栏禁用集合（存工具 id，逗号分隔）。 */
    suspend fun setEditorToolDisabled(disabled: Set<String>) = edit {
        it[Keys.TOOL_DISABLED] = disabled.filter { id -> EditorTool.fromId(id) != null }.joinToString(",")
    }

    /** 分享笔记默认保存文件夹（相对 Vault 根；null = 跟随当前目录）。 */
    suspend fun setShareFolder(relativePath: String?) = edit {
        if (relativePath.isNullOrBlank()) it.remove(Keys.SHARE_FOLDER)
        else it[Keys.SHARE_FOLDER] = relativePath
    }

    /** 收藏一条笔记（相对 Vault 根的路径；仅本地，不参与同步）。 */
    suspend fun addFavorite(relativePath: String) = edit {
        it[Keys.FAVORITES] = (it[Keys.FAVORITES] ?: emptySet()) + relativePath
    }

    /** 批量收藏。 */
    suspend fun addFavorites(relativePaths: Collection<String>) = edit {
        if (relativePaths.isNotEmpty()) {
            it[Keys.FAVORITES] = (it[Keys.FAVORITES] ?: emptySet()) + relativePaths
        }
    }

    /** 取消收藏。 */
    suspend fun removeFavorite(relativePath: String) = edit {
        it[Keys.FAVORITES] = (it[Keys.FAVORITES] ?: emptySet()) - relativePath
    }

    /** 文件被移动 / 改名后迁移收藏路径（未收藏时不做任何事）。 */
    suspend fun moveFavorite(oldRelative: String, newRelative: String) = edit {
        val current = it[Keys.FAVORITES] ?: return@edit
        if (oldRelative in current) it[Keys.FAVORITES] = current - oldRelative + newRelative
    }

    /** 以现存集合覆盖收藏（用于清理已失效的路径）。 */
    suspend fun setFavorites(relativePaths: Set<String>) = edit {
        it[Keys.FAVORITES] = relativePaths
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }
}
