package com.az.notes.data.backup

import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.settings.VaultPerVault
import com.az.notes.data.sync.SyncConfigRepository
import com.az.notes.domain.model.AppLanguage
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.FabAction
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.NoteSortOrder
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import com.az.notes.domain.model.ThemeMode
import com.az.notes.domain.model.VaultInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 配置备份仓库（§5.6 备份与恢复）：把当前全部配置（全局设置 + 仓库注册表 +
 * per-vault 收藏 / 分享目录 / 同步配置）导出为单个 JSON 文件，并支持导入恢复。
 *
 * 导入健壮性：
 * - 宽松解析（ignoreUnknownKeys + coerceInputValues + isLenient）：未来配置
 *   增加 / 减少、未知字段、类型不匹配的单项均不阻断整体导入；
 * - 逐字段容错：枚举 / 数值逐项解析，无效项忽略（保持现状），有效项正常生效，
 *   数值经既有 setter 钳制到合法区间；
 * - 内置默认仓库路径重定向为本设备私有目录（不信任备份中的绝对路径）；
 * - 外部仓库目录不存在 / 不可读时跳过该仓库并计数；
 * - 仓库按 id 合并恢复（同 id 覆盖、新 id 追加），不做删除；
 * - 安全红线：WebDAV 密码永不导出、也永不导入。
 */
@Singleton
class BackupRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncConfigRepository: SyncConfigRepository
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** 宽容解析器：忽略未知 / 无效字段，未来配置增减均不影响导入。 */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** 组装当前全部配置的备份载荷。 */
    suspend fun buildPayload(): BackupPayload {
        val settings = settingsRepository.settings.first()
        val vaults = settings.vaults.map { vault ->
            val perVault = settingsRepository.perVaultSnapshot(vault.id)
            BackupVault(
                id = vault.id,
                name = vault.name,
                path = vault.path,
                builtin = vault.builtin,
                hidden = vault.hidden,
                favoritePaths = perVault.favorites.sorted(),
                shareFolder = perVault.shareFolder,
                sync = runCatching { syncConfigRepository.configOf(vault.id) }.getOrNull()?.toBackup()
            )
        }
        return BackupPayload(
            app = BACKUP_APP,
            version = BACKUP_VERSION,
            exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            settings = settings.toBackup(),
            vaults = vaults,
            currentVaultId = settings.currentVaultId
        )
    }

    /** 序列化为 JSON 文本（导出文件内容）。 */
    fun encode(payload: BackupPayload): String = json.encodeToString(payload)

    /** 建议的导出文件名（系统「另存为」默认名）。 */
    fun suggestedFileName(): String =
        "az-notes-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"

    /** 当前语言（导入前后对比，判断是否需要重建界面）。 */
    suspend fun currentLanguage(): AppLanguage = settingsRepository.settings.first().language

    /**
     * 导入备份文本并恢复配置。JSON 结构无法解析时抛出异常（调用方提示文件无效），
     * 其余情况尽力恢复有效部分并给出统计。
     */
    suspend fun import(text: String): BackupImportResult {
        val payload = runCatching { lenientJson.decodeFromString<BackupPayload>(text) }.getOrNull()
            ?: throw IllegalArgumentException("invalid backup file")
        val builtinPath = settingsRepository.builtinVaultPath()
        val existing = settingsRepository.settings.first()
        val takenPaths = existing.vaults.map { it.path.trimEnd('/') }.toMutableSet()
        val takenIds = existing.vaults.map { it.id }.toMutableSet()

        val restored = mutableListOf<VaultInfo>()
        val perVault = mutableMapOf<String, VaultPerVault>()
        val syncConfigs = mutableMapOf<String, SyncConfig>()
        var skipped = 0

        for (item in payload.vaults.orEmpty()) {
            val id = item.id?.trim().orEmpty()
            if (id.isEmpty()) {
                skipped++ // 无 id 无法关联 per-vault 数据
                continue
            }
            val builtin = item.builtin == true || id == SettingsRepository.DEFAULT_VAULT_ID
            val path = if (builtin) builtinPath else item.path?.trim()?.trimEnd('/').orEmpty()
            if (!builtin) {
                // 外部仓库：本设备目录须存在且可读；路径被其他 id 占用时跳过
                val dir = File(path)
                if (path.isEmpty() || !dir.isDirectory || !dir.canRead()) {
                    skipped++
                    continue
                }
                if (path in takenPaths && id !in takenIds) {
                    skipped++
                    continue
                }
            }
            val name = item.name?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
                ?: File(path).name.ifBlank { path }
            restored += VaultInfo(
                id = id,
                name = name,
                path = path,
                builtin = builtin,
                hidden = item.hidden == true
            )
            takenPaths += path
            takenIds += id
            perVault[id] = VaultPerVault(
                favorites = item.favoritePaths.orEmpty().filter { it.isNotBlank() }.toSet(),
                shareFolder = item.shareFolder?.trim()?.takeIf { it.isNotBlank() }
            )
            item.sync?.toSyncConfig()?.let { syncConfigs[id] = it }
        }

        val settingsRestored = payload.settings?.let { applyGlobalSettings(it) } ?: false

        if (restored.isNotEmpty()) {
            settingsRepository.restoreVaultRegistry(
                imported = restored,
                currentVaultId = payload.currentVaultId?.trim()?.takeIf { it.isNotEmpty() }
            )
            restored.forEach { vault ->
                perVault[vault.id]?.let { per ->
                    runCatching { settingsRepository.restorePerVault(vault.id, per.favorites, per.shareFolder) }
                }
                syncConfigs[vault.id]?.let { config ->
                    runCatching { syncConfigRepository.writeVaultConfig(vault.id, config) }
                }
            }
        }
        return BackupImportResult(restored.size, skipped, settingsRestored)
    }

    // ---------------------------------------------------------------- 映射

    private fun AppSettings.toBackup(): BackupSettings = BackupSettings(
        themeMode = themeMode.name,
        fontFamily = fontFamily.name,
        fontSizeSp = fontSizeSp,
        lineHeightRatio = lineHeightRatio,
        dynamicColor = dynamicColor,
        previewChars = previewChars,
        sortOrder = sortOrder.name,
        defaultNoteName = defaultNoteName,
        trashRetentionDays = trashRetentionDays,
        fabAction = fabAction.name,
        language = language.name,
        trashEnabled = trashEnabled,
        editorToolOrder = editorToolOrder,
        editorToolDisabled = editorToolDisabled.sorted()
    )

    private fun SyncConfig.toBackup(): BackupSyncConfig = BackupSyncConfig(
        serverUrl = serverUrl,
        username = username,
        remoteDir = remoteDir,
        mode = mode.name,
        conflictStrategy = conflictStrategy.name,
        ignoreRules = ignoreRules,
        maxFileSizeMb = maxFileSizeMb,
        autoSyncOnStart = autoSyncOnStart,
        periodicInterval = periodicInterval.name,
        syncAfterSave = syncAfterSave
    )

    /** 同步配置：备份字段逐项回落默认值（数值钳制到合法区间）。 */
    private fun BackupSyncConfig.toSyncConfig(): SyncConfig {
        val defaults = SyncConfig()
        return SyncConfig(
            serverUrl = serverUrl ?: defaults.serverUrl,
            username = username ?: defaults.username,
            remoteDir = remoteDir ?: defaults.remoteDir,
            mode = enumOf<SyncMode>(mode) ?: defaults.mode,
            conflictStrategy = enumOf<ConflictStrategy>(conflictStrategy) ?: defaults.conflictStrategy,
            ignoreRules = ignoreRules?.take(4000)?.takeIf { it.isNotBlank() } ?: defaults.ignoreRules,
            maxFileSizeMb = (maxFileSizeMb ?: defaults.maxFileSizeMb)
                .coerceIn(0, SyncConfig.MAX_FILE_SIZE_MB_LIMIT),
            autoSyncOnStart = autoSyncOnStart ?: defaults.autoSyncOnStart,
            periodicInterval = enumOf<SyncInterval>(periodicInterval) ?: defaults.periodicInterval,
            syncAfterSave = syncAfterSave ?: defaults.syncAfterSave
        )
    }

    /**
     * 全局设置逐字段恢复：仅应用成功解析的项（无效 / 缺失项保持现状）；
     * 数值经 SettingsRepository 现有 setter 钳制到合法区间。
     * @return 是否至少应用了一项
     */
    private suspend fun applyGlobalSettings(s: BackupSettings): Boolean {
        var applied = false
        enumOf<ThemeMode>(s.themeMode)?.let { settingsRepository.setThemeMode(it); applied = true }
        enumOf<FontFamilyPreference>(s.fontFamily)?.let { settingsRepository.setFontFamily(it); applied = true }
        s.fontSizeSp?.let { settingsRepository.setFontSize(it); applied = true }
        s.lineHeightRatio?.let { settingsRepository.setLineHeight(it); applied = true }
        s.dynamicColor?.let { settingsRepository.setDynamicColor(it); applied = true }
        s.previewChars?.let { settingsRepository.setPreviewChars(it); applied = true }
        enumOf<NoteSortOrder>(s.sortOrder)?.let { settingsRepository.setSortOrder(it); applied = true }
        s.defaultNoteName?.takeIf { it.isNotBlank() }?.let {
            settingsRepository.setDefaultNoteName(it); applied = true
        }
        s.trashRetentionDays?.let { settingsRepository.setTrashRetentionDays(it); applied = true }
        enumOf<FabAction>(s.fabAction)?.let { settingsRepository.setFabAction(it); applied = true }
        enumOf<AppLanguage>(s.language)?.let { settingsRepository.setLanguage(it); applied = true }
        s.trashEnabled?.let { settingsRepository.setTrashEnabled(it); applied = true }
        s.editorToolOrder?.let { settingsRepository.setEditorToolOrder(it); applied = true }
        s.editorToolDisabled?.let { settingsRepository.setEditorToolDisabled(it.toSet()); applied = true }
        return applied
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    companion object {
        /** 备份格式标识。 */
        const val BACKUP_APP = "az-notes-backup"

        /** 当前备份格式版本。 */
        const val BACKUP_VERSION = 1
    }
}
