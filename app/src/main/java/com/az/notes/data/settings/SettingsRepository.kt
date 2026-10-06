package com.az.notes.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.az.notes.R
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.EditorTool
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.ThemeMode
import com.az.notes.domain.model.VaultInfo
import com.az.notes.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "az_notes_settings")

/**
 * 偏好设置仓库（§5.6）。用 DataStore 持久化主题 / 字体 / 仓库注册表等。
 * 读取异常回退为默认值（[emptyPreferences]），避免崩溃。
 *
 * 多仓库模型：
 * - 全局键：[Keys.VAULTS]（仓库注册表 JSON）与 [Keys.CURRENT_VAULT_ID]（当前仓库），
 *   保证「记忆当前仓库」；分享落点 / WebDAV 等 per-vault 设置存专属键（按仓库 id）。
 * - 旧版单仓库键（vault_path / favorite_paths / share_folder）保留为升级迁移窗口的
 *   回退读取，由 [VaultMigrationRunner] 一次性迁移后清除（见 migrate* 方法）。
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

        /** 仓库注册表（JSON 序列化的 [VaultInfo] 列表） */
        val VAULTS = stringPreferencesKey("vaults")

        /** 当前使用的仓库 id（「记忆当前仓库」） */
        val CURRENT_VAULT_ID = stringPreferencesKey("current_vault_id")

        // ---- 旧版全局键：仅迁移窗口回退读取 / 一次性迁移，迁移完成后删除 ----
        val LEGACY_VAULT_PATH = stringPreferencesKey("vault_path")
        val LEGACY_FAVORITES = stringSetPreferencesKey("favorite_paths")
        val LEGACY_SHARE_FOLDER = stringPreferencesKey("share_folder")
    }

    private val appContext = context.applicationContext
    private val dataStore = context.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> settingsFrom(prefs) }

    // ---------------------------------------------------------------- 读取

    private fun settingsFrom(prefs: Preferences): AppSettings {
        val registered = parseVaults(prefs[Keys.VAULTS])
        val legacyPath = prefs[Keys.LEGACY_VAULT_PATH]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        // 迁移窗口：注册表尚未建立但存在旧版路径 → 临时合成一条仓库记录保证功能可用；
        // 全新安装（无任何旧数据）→ 合成内置默认仓库（App 私有目录），首帧即有仓库可用；
        // 两者的落盘建档均由 ensureVaultRegistry() 在启动时完成
        val vaults = registered.ifEmpty {
            legacyPath?.let {
                listOf(VaultInfo(LEGACY_VAULT_ID, File(it).name.ifBlank { it }, it))
            } ?: listOf(defaultVaultInfo())
        }
        val currentId = prefs[Keys.CURRENT_VAULT_ID]?.takeIf { id -> vaults.any { it.id == id } }
            ?: vaults.firstOrNull()?.id
        return AppSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            fontFamily = prefs[Keys.FONT]?.let { runCatching { FontFamilyPreference.valueOf(it) }.getOrNull() }
                ?: FontFamilyPreference.SANS,
            fontSizeSp = prefs[Keys.FONT_SIZE] ?: 16f,
            lineHeightRatio = prefs[Keys.LINE_HEIGHT] ?: 1.5f,
            vaults = vaults,
            currentVaultId = currentId,
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
            favoritePaths = prefs[favoritesKey(currentId)] ?: emptySet(),
            shareFolder = prefs[shareFolderKey(currentId)]?.takeIf { it.isNotBlank() }
        )
    }

    // ---------------------------------------------------- 仓库注册表管理

    /** 内置默认仓库的注册表记录（名称按创建时的系统语言取资源文案，可随时重命名）。 */
    private fun defaultVaultInfo(): VaultInfo = VaultInfo(
        id = DEFAULT_VAULT_ID,
        name = appContext.getString(R.string.vault_default_name),
        path = File(appContext.filesDir, DEFAULT_VAULT_DIR).absolutePath,
        builtin = true
    )

    /**
     * 幂等建档：保证仓库注册表与「当前仓库」处于可用状态。
     * - 注册表为空且存在旧版 vault_path → 生成 [VaultInfo] 落盘并设为当前；
     * - 注册表为空且无旧数据（全新安装）→ 创建内置默认仓库（App 私有目录）并设为当前；
     * - 注册表非空但 current 缺失 / 失效 → 修复为注册表首项。
     * @return 建档后的当前仓库 id
     */
    suspend fun ensureVaultRegistry(): String? {
        var current: String? = null
        edit { prefs ->
            val vaults = parseVaults(prefs[Keys.VAULTS]).toMutableList()
            if (vaults.isEmpty()) {
                val legacy = prefs[Keys.LEGACY_VAULT_PATH]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                if (legacy != null) {
                    vaults += VaultInfo(VaultInfo.newId(), File(legacy).name.ifBlank { legacy }, legacy)
                } else {
                    // 全新安装：启用内置默认仓库（App 私有目录），首次进入即可直接记笔记
                    val fallback = defaultVaultInfo()
                    runCatching { File(fallback.path).mkdirs() }
                    vaults += fallback
                }
            }
            if (vaults.isNotEmpty()) {
                persistVaults(prefs, vaults)
                val stored = prefs[Keys.CURRENT_VAULT_ID]
                val resolved = if (stored != null && vaults.any { it.id == stored }) stored
                else vaults.first().id
                prefs[Keys.CURRENT_VAULT_ID] = resolved
                current = resolved
            }
        }
        return current
    }

    /**
     * 解析「当前仓库 id」并保证可用（per-vault 数据写入前的统一入口）：
     * 无仓库返回 null；迁移窗口（临时合成 id）先落盘建档，避免写入丢失。
     */
    suspend fun requireCurrentVaultId(): String? {
        val stored = settings.first().currentVaultId ?: return null
        if (stored != LEGACY_VAULT_ID) return stored
        return ensureVaultRegistry()?.takeIf { it != LEGACY_VAULT_ID }
    }

    /**
     * 添加仓库并设为当前；路径已在注册表中时仅切换过去（不重复添加）。
     * @return true = 新添加；false = 已存在（仅切换）/ 路径无效
     */
    suspend fun addVault(path: String): Boolean {
        val normalized = path.trim().trimEnd('/')
        if (normalized.isEmpty()) return false
        var created = false
        edit { prefs ->
            val vaults = parseVaults(prefs[Keys.VAULTS]).toMutableList()
            val existing = vaults.firstOrNull { it.path == normalized }
            if (existing != null) {
                prefs[Keys.CURRENT_VAULT_ID] = existing.id
            } else {
                val info = VaultInfo(VaultInfo.newId(), File(normalized).name.ifBlank { normalized }, normalized)
                vaults += info
                persistVaults(prefs, vaults)
                prefs[Keys.CURRENT_VAULT_ID] = info.id
                created = true
            }
        }
        return created
    }

    /** 切换当前仓库（id 必须在注册表中才生效）。 */
    suspend fun setCurrentVault(id: String) = edit { prefs ->
        if (parseVaults(prefs[Keys.VAULTS]).any { it.id == id }) {
            prefs[Keys.CURRENT_VAULT_ID] = id
        }
    }

    /**
     * 移除仓库：从注册表删除并清理其 per-vault 键；若为当前仓库则回落到剩余首项。
     * 内置默认仓库不可移除；删到只剩默认仓库时强制取消其隐藏并选中默认仓库；
     * 删空（无内置默认仓库的迁移老用户）时重建默认仓库兜底，保证始终有仓库可用。
     * 磁盘上的笔记文件不受影响；同步配置 / 凭据 / 回收站 / 同步数据的收尾由调用方编排。
     */
    suspend fun removeVault(id: String) = edit { prefs ->
        val all = parseVaults(prefs[Keys.VAULTS])
        val target = all.firstOrNull { it.id == id } ?: return@edit
        if (target.builtin) return@edit
        var vaults = all.filterNot { it.id == id }
        // 只剩默认仓库：强行恢复显示（隐藏仅对「多仓库」有意义）
        if (vaults.size == 1 && vaults[0].builtin && vaults[0].hidden) {
            vaults = vaults.map { it.copy(hidden = false) }
        }
        // 删空兜底：重建内置默认仓库
        if (vaults.isEmpty()) {
            val fallback = defaultVaultInfo()
            runCatching { File(fallback.path).mkdirs() }
            vaults = listOf(fallback)
        }
        persistVaults(prefs, vaults)
        prefs.remove(stringSetPreferencesKey("favorite_paths_$id"))
        prefs.remove(stringPreferencesKey("share_folder_$id"))
        if (prefs[Keys.CURRENT_VAULT_ID] == id) {
            prefs[Keys.CURRENT_VAULT_ID] = vaults.first().id
        }
    }

    /** 重命名仓库（仅展示名；空名忽略，超长截断）。 */
    suspend fun renameVault(id: String, name: String) = edit { prefs ->
        val cleaned = name.trim().take(40)
        if (cleaned.isEmpty()) return@edit
        val vaults = parseVaults(prefs[Keys.VAULTS])
        if (vaults.none { it.id == id }) return@edit
        persistVaults(prefs, vaults.map { if (it.id == id) it.copy(name = cleaned) else it })
    }

    /**
     * 设置「隐藏」状态：仅内置默认仓库支持隐藏，且要求注册表存在多个仓库、
     * 目标不是当前正在使用的仓库（调用方需先切换）；取消隐藏不受限制。
     * 隐藏仅作用于顶栏切换列表，仓库管理页始终可见。
     */
    suspend fun setVaultHidden(id: String, hidden: Boolean) = edit { prefs ->
        val vaults = parseVaults(prefs[Keys.VAULTS])
        val target = vaults.firstOrNull { it.id == id } ?: return@edit
        if (!target.builtin) return@edit
        if (hidden) {
            if (vaults.size <= 1) return@edit
            if (currentVaultIdOf(prefs) == id) return@edit
        }
        persistVaults(prefs, vaults.map { if (it.id == id) it.copy(hidden = hidden) else it })
    }

    // ---------------------------------------------------------------- 迁移

    /**
     * 一次性迁移（幂等）：旧版全局收藏 / 分享目录 → 当前仓库的 per-vault 键，
     * 随后删除旧键与旧版 vault_path。仅在旧键存在时生效。
     */
    suspend fun migrateLegacyVaultScoped() = edit { prefs ->
        if (prefs[Keys.LEGACY_VAULT_PATH] == null &&
            prefs[Keys.LEGACY_FAVORITES] == null &&
            prefs[Keys.LEGACY_SHARE_FOLDER] == null
        ) {
            return@edit
        }
        val currentId = currentVaultIdOf(prefs)
        if (currentId != null) {
            val favKey = favoritesKey(currentId)
            val legacyFavs = prefs[Keys.LEGACY_FAVORITES]
            if (legacyFavs != null && legacyFavs.isNotEmpty() && prefs[favKey].isNullOrEmpty()) {
                prefs[favKey] = legacyFavs
            }
            val shareKey = shareFolderKey(currentId)
            val legacyShare = prefs[Keys.LEGACY_SHARE_FOLDER]
            if (legacyShare != null && legacyShare.isNotBlank() && prefs[shareKey] == null) {
                prefs[shareKey] = legacyShare
            }
        }
        prefs.remove(Keys.LEGACY_FAVORITES)
        prefs.remove(Keys.LEGACY_SHARE_FOLDER)
        prefs.remove(Keys.LEGACY_VAULT_PATH)
    }

    // ------------------------------------------------------- 普通设置写入

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setFontFamily(family: FontFamilyPreference) = edit { it[Keys.FONT] = family.name }
    suspend fun setFontSize(sp: Float) = edit { it[Keys.FONT_SIZE] = sp.coerceIn(12f, 24f) }
    suspend fun setLineHeight(ratio: Float) = edit { it[Keys.LINE_HEIGHT] = ratio.coerceIn(1.2f, 2.0f) }
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

    /** 分享笔记默认保存文件夹（相对当前仓库根；null = 跟随当前目录）。 */
    suspend fun setShareFolder(relativePath: String?) = edit { prefs ->
        val key = shareFolderKey(currentVaultIdOf(prefs))
        if (relativePath.isNullOrBlank()) prefs.remove(key) else prefs[key] = relativePath
    }

    // ------------------------------------------------------- 收藏夹（per-vault）

    /** 收藏一条笔记（相对当前仓库根的路径；仅本地，不参与同步）。 */
    suspend fun addFavorite(relativePath: String) = edit { prefs ->
        val key = favoritesKey(currentVaultIdOf(prefs))
        prefs[key] = (prefs[key] ?: emptySet()) + relativePath
    }

    /** 批量收藏。 */
    suspend fun addFavorites(relativePaths: Collection<String>) = edit { prefs ->
        if (relativePaths.isNotEmpty()) {
            val key = favoritesKey(currentVaultIdOf(prefs))
            prefs[key] = (prefs[key] ?: emptySet()) + relativePaths
        }
    }

    /** 取消收藏。 */
    suspend fun removeFavorite(relativePath: String) = edit { prefs ->
        val key = favoritesKey(currentVaultIdOf(prefs))
        prefs[key] = (prefs[key] ?: emptySet()) - relativePath
    }

    /** 文件被移动 / 改名后迁移收藏路径（未收藏时不做任何事）。 */
    suspend fun moveFavorite(oldRelative: String, newRelative: String) = edit { prefs ->
        val key = favoritesKey(currentVaultIdOf(prefs))
        val current = prefs[key] ?: return@edit
        if (oldRelative in current) prefs[key] = current - oldRelative + newRelative
    }

    /** 以现存集合覆盖收藏（用于清理已失效的路径）。 */
    suspend fun setFavorites(relativePaths: Set<String>) = edit { prefs ->
        prefs[favoritesKey(currentVaultIdOf(prefs))] = relativePaths
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 在编辑事务内解析「当前仓库 id」（注册表内校验后回落首项；无仓库为 null）。
     * 注：与 [settingsFrom] 的读取口回退保持一致（差异仅在迁移窗口的合成 id）。
     */
    private fun currentVaultIdOf(prefs: MutablePreferences): String? {
        val vaults = parseVaults(prefs[Keys.VAULTS])
        return prefs[Keys.CURRENT_VAULT_ID]?.takeIf { id -> vaults.any { it.id == id } }
            ?: vaults.firstOrNull()?.id
    }

    /** 收藏键：当前仓库专属；迁移窗口（合成 id）与无仓库时回退旧版全局键。 */
    private fun favoritesKey(vaultId: String?): Preferences.Key<Set<String>> =
        if (vaultId == null || vaultId == LEGACY_VAULT_ID) Keys.LEGACY_FAVORITES
        else stringSetPreferencesKey("favorite_paths_$vaultId")

    /** 分享目录键：当前仓库专属；迁移窗口与无仓库时回退旧版全局键。 */
    private fun shareFolderKey(vaultId: String?): Preferences.Key<String> =
        if (vaultId == null || vaultId == LEGACY_VAULT_ID) Keys.LEGACY_SHARE_FOLDER
        else stringPreferencesKey("share_folder_$vaultId")

    private fun parseVaults(raw: String?): List<VaultInfo> =
        raw?.let { runCatching { json.decodeFromString<List<VaultInfo>>(it) }.getOrNull() } ?: emptyList()

    private fun persistVaults(prefs: MutablePreferences, vaults: List<VaultInfo>) {
        prefs[Keys.VAULTS] = json.encodeToString(vaults)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    companion object {
        /** 迁移窗口内临时合成的仓库 id（不满足 newId 的十六进制空间，不会与真实仓库冲突）。 */
        const val LEGACY_VAULT_ID = "legacy"

        /** 内置默认仓库的固定 id（不满足 newId 的 "v"+8 位十六进制格式，不会冲突）。 */
        const val DEFAULT_VAULT_ID = "default"

        /** 内置默认仓库位于 App 私有目录下的子目录名。 */
        private const val DEFAULT_VAULT_DIR = "vault"
    }
}
