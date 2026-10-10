package com.az.notes.data.backup

import kotlinx.serialization.Serializable

/**
 * 配置备份文件（导出 / 导入的 JSON 根对象，§5.6 备份与恢复）。
 *
 * 容错设计（导入健壮性）：
 * - 所有字段可空 / 带默认值：缺失字段忽略，不影响其余部分导入；
 * - [app] / [version] 仅作标识与展示；未知字段由 ignoreUnknownKeys 忽略——
 *   未来版本配置增加 / 减少均不破坏导入；
 * - 枚举一律存 String name，导入时逐个解析，无法识别的值忽略。
 */
@Serializable
data class BackupPayload(
    /** 备份格式标识（固定 "az-notes-backup"） */
    val app: String? = null,
    /** 备份格式版本（当前 1） */
    val version: Int? = null,
    /** 导出时间（本地时区文本，仅展示） */
    val exportedAt: String? = null,
    val settings: BackupSettings? = null,
    val vaults: List<BackupVault>? = null,
    /** AI 供应商配置（不含 API Key：密钥由 AiKeyStore 加密独立存储，永不入备份） */
    val aiProviders: List<BackupAiProvider>? = null,
    /** 导出时的当前仓库 id（导入后若仍有效则恢复为当前仓库） */
    val currentVaultId: String? = null
)

/** 全局设置快照（每项可空：无效 / 缺失的项忽略并保持现状）。 */
@Serializable
data class BackupSettings(
    val themeMode: String? = null,
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
    val lineHeightRatio: Float? = null,
    val dynamicColor: Boolean? = null,
    val previewChars: Int? = null,
    val sortOrder: String? = null,
    val defaultNoteName: String? = null,
    val trashRetentionDays: Int? = null,
    val language: String? = null,
    val trashEnabled: Boolean? = null,
    val editorToolOrder: List<String>? = null,
    val editorToolDisabled: List<String>? = null,
    /** 打开笔记的默认落点（查看页 / 编辑页；缺失回落查看页） */
    val defaultNoteMode: String? = null,
    /** 图片相关配置（本批新增；缺失 / 无效均回落默认，逐字段容错） */
    val imageCompressEnabled: Boolean? = null,
    val attachmentPromptEnabled: Boolean? = null,
    val remoteImageMaxMb: Int? = null,
    val remoteImageTimeoutSeconds: Int? = null
)

/** 单个仓库的备份：注册表信息 + per-vault 配置（收藏 / 最近查看 / 分享目录 / 同步配置）。 */
@Serializable
data class BackupVault(
    val id: String? = null,
    val name: String? = null,
    /** 磁盘绝对路径（内置默认仓库导入时重定向为本设备私有目录） */
    val path: String? = null,
    val builtin: Boolean? = null,
    val hidden: Boolean? = null,
    /** 收藏的笔记（相对仓库根路径） */
    val favoritePaths: List<String>? = null,
    /** 最近查看记录（相对仓库根路径，最近优先） */
    val recentPaths: List<String>? = null,
    /** 分享笔记默认保存文件夹（相对仓库根） */
    val shareFolder: String? = null,
    /** WebDAV 同步配置（不含密码：密码永不导出，导入后需重新填写） */
    val sync: BackupSyncConfig? = null
)

/** 同步配置快照（枚举存 String name；不含密码）。 */
@Serializable
data class BackupSyncConfig(
    val serverUrl: String? = null,
    val username: String? = null,
    val remoteDir: String? = null,
    val mode: String? = null,
    val conflictStrategy: String? = null,
    val ignoreRules: String? = null,
    val maxFileSizeMb: Int? = null,
    val autoSyncOnStart: Boolean? = null,
    val periodicInterval: String? = null,
    val syncAfterSave: Boolean? = null
)

/** AI 供应商配置快照（不含 API Key——安全红线：密钥永不导出、永不导入）。 */
@Serializable
data class BackupAiProvider(
    val id: String? = null,
    val name: String? = null,
    /** 协议（AiProtocol.name；无法识别的值导入时跳过该条） */
    val protocol: String? = null,
    val baseUrl: String? = null,
    val headers: Map<String, String>? = null,
    val models: List<BackupAiModel>? = null,
    val lastModelId: String? = null
)

/** 供应商下的单个模型快照（逐字段容错：缺失 / 无效项忽略）。 */
@Serializable
data class BackupAiModel(
    val id: String? = null,
    val label: String? = null,
    val contextWindow: Int? = null,
    val vision: Boolean? = null
)

/** 导入结果统计（UI 提示用）。 */
data class BackupImportResult(
    /** 成功恢复（新增或覆盖）的仓库数 */
    val vaultsRestored: Int,
    /** 因路径无效 / 重复而跳过的仓库数 */
    val vaultsSkipped: Int,
    /** 是否恢复了全局设置（备份中包含 settings 且至少一项有效） */
    val settingsRestored: Boolean,
    /** 成功恢复（新增或覆盖）的 AI 供应商数 */
    val providersRestored: Int = 0
)
