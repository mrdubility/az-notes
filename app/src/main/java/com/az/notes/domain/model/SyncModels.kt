package com.az.notes.domain.model

import kotlinx.serialization.Serializable

/**
 * 同步策略（§6.3）：五种模式全量对齐坚果云官方插件。
 * 后两种为“镜像”语义：以单侧为唯一真相，另一端对齐（多余项会被删除，删除仍需确认）。
 */
@Serializable
enum class SyncMode {
    BIDIRECTIONAL,
    UPLOAD_ONLY,
    /** 仅发送（覆盖云端）：本地为唯一真相，忽略远端修改，远端多余项删除。 */
    UPLOAD_OVERWRITE,
    DOWNLOAD_ONLY,
    /** 仅接收（还原本地）：远端为唯一真相，忽略本地修改，本地多余项移入回收站。 */
    DOWNLOAD_RESTORE
}

/** 冲突处理策略（§6.2）。三种策略均在覆盖前把败方另存为冲突副本。 */
@Serializable
enum class ConflictStrategy {
    /** 冲突副本（默认）：按修改时间判定胜方，败方另存副本等人工合并。 */
    CONFLICT_COPY,
    /** 本地优先：无条件以本地版本覆盖远端（远端败方先备份）。 */
    LOCAL_FIRST,
    /** 坚果云优先：无条件以远端版本覆盖本地（本地败方先备份）。 */
    REMOTE_FIRST
}

/**
 * 冲突处理结果状态（写入 conflict_record.merge_state，供同步历史页展示与待处理计数）。
 *
 * 在原有"按策略择一覆盖 + 旧版另存"的基础上，逐步引入同文件内合并：
 * 合并成功时为 [MERGED_CLEAN]，仍有重叠冲突块时为 [MERGED_MARKED]，
 * 没有祖先可做三方合并时退化为 [NO_BASE]。
 */
enum class ConflictMergeState {
    /** 文本冲突：旧版已备份到应用私有目录（合并能力上线前的阶段状态） */
    BACKED_UP,

    /** 二进制 / 超大文件：按最新版直接覆盖，不保留旧版 */
    BINARY_KEEP,

    /** 同文件内合并完成，无残留冲突块 */
    MERGED_CLEAN,

    /** 合并完成但仍留有待人工处理的冲突块（正文含标记） */
    MERGED_MARKED,

    /** 无祖先内容可用，仅按段落取较新一侧合并 */
    NO_BASE,

    /** 合并或备份失败（保留记录供排查） */
    FAILED
}

/** 自动同步周期（§6.4）。OFF = 不启用 WorkManager 周期任务。 */
@Serializable
enum class SyncInterval(val minutes: Long?) {
    OFF(null), M15(15), M30(30), H1(60), H4(240), H8(480)
}

/**
 * 同步配置（非敏感部分，DataStore 持久化，按仓库序列化为 JSON；密码见 data/sync/CredentialStore 加密存储）。
 */
@Serializable
data class SyncConfig(
    /** WebDAV 服务器地址（坚果云默认 https://dav.jianguoyun.com/dav/） */
    val serverUrl: String = DEFAULT_SERVER_URL,
    val username: String = "",
    /** 远端目录（相对服务器根，默认 az-notes） */
    val remoteDir: String = "az-notes",
    val mode: SyncMode = SyncMode.BIDIRECTIONAL,
    /** 冲突处理策略（§6.2） */
    val conflictStrategy: ConflictStrategy = ConflictStrategy.CONFLICT_COPY,
    /** Gitignore 风格过滤规则（多行文本，最后一条匹配规则生效，§6.3） */
    val ignoreRules: String = DEFAULT_IGNORE_RULES,
    /** 大文件上限（MB），0 = 不限制；超过则跳过该文件的同步（§6.3） */
    val maxFileSizeMb: Int = 50,
    /** 启动后延迟 10s 自动拉取一次（§6.4，可关） */
    val autoSyncOnStart: Boolean = true,
    /** WorkManager 周期同步（§6.4） */
    val periodicInterval: SyncInterval = SyncInterval.OFF,
    /** 编辑保存后 30s 防抖触发一次性同步（§6.4，可关） */
    val syncAfterSave: Boolean = true
) {
    /** 地址与账号均已填写才可发起同步 / 连接测试。 */
    val configured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank()

    /** 大文件上限（字节）；0 = 不限制（等价 Long.MAX_VALUE，永不跳过）。 */
    val maxFileSizeBytes: Long
        get() = if (maxFileSizeMb <= 0) Long.MAX_VALUE else maxFileSizeMb.toLong() * 1024 * 1024

    /** 拼接后的同步根 URL（供 WebDavClient 使用）。 */
    val baseUrl: String
        get() {
            val server = serverUrl.trim().trimEnd('/')
            val dir = remoteDir.trim().trim('/')
            return if (dir.isEmpty()) "$server/" else "$server/$dir/"
        }

    companion object {
        const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"

        /** 大文件上限（MB）输入最大值：102400 MB（100 GB），防误输入天文数字；0 = 不限制。 */
        const val MAX_FILE_SIZE_MB_LIMIT = 102400

        /**
         * 默认过滤模板（§6.3）：Obsidian 配置目录与同步插件工作目录、
         * 其他隐藏项（.trash/.git/临时状态）、临时与半成品文件。
         */
        val DEFAULT_IGNORE_RULES = """
            # Obsidian 配置目录（含坚果云同步插件的工作目录）
            .obsidian/
            # 其他隐藏项：.trash / .git / 临时状态文件等
            .*
            # 临时与半成品文件
            *.tmp
            *.part
        """.trimIndent()
    }
}

/** 单条日志 / 计划中的操作类型。 */
enum class SyncOpType { UPLOAD, DOWNLOAD, DELETE_REMOTE, TRASH_LOCAL, CONFLICT_COPY, MOVE_LOCAL, MOVE_REMOTE }

/** 一次同步会话中的单个待执行操作。 */
data class SyncOp(
    val type: SyncOpType,
    /** 目标相对路径（'/' 分隔，无前导斜杠）；MOVE 类为目标路径 */
    val path: String,
    /** 附加说明：如冲突副本的胜方（local=保留本地版本 / remote=保留云端版本） */
    val detail: String? = null,
    /** MOVE_LOCAL / MOVE_REMOTE：源相对路径（[path] 为目标路径） */
    val moveFrom: String? = null,
    /** CONFLICT_COPY：败方私有备份的**绝对路径**（应用私有目录，不进 Vault）；null = 不备份（二进制 / 超大） */
    val backupPath: String? = null
)

/** Scan → Diff 产出的操作计划（§6.1 Plan 阶段；execute 前经用户预览确认）。 */
data class SyncPlan(
    val ops: List<SyncOp>,
    val scannedLocal: Int,
    val scannedRemote: Int,
    /** 需要用户知晓的扫描限制提醒（如服务端分页截断），无则为 null。 */
    val warning: String? = null,
    /** 因超过大文件上限而跳过的条目数（§6.3） */
    val skippedLarge: Int = 0
) {
    val uploadCount: Int get() = ops.count { it.type == SyncOpType.UPLOAD }
    val downloadCount: Int get() = ops.count { it.type == SyncOpType.DOWNLOAD }
    val deleteRemoteCount: Int get() = ops.count { it.type == SyncOpType.DELETE_REMOTE }
    val trashLocalCount: Int get() = ops.count { it.type == SyncOpType.TRASH_LOCAL }
    val conflictCount: Int get() = ops.count { it.type == SyncOpType.CONFLICT_COPY }
    val moveCount: Int get() = ops.count { it.type == SyncOpType.MOVE_LOCAL || it.type == SyncOpType.MOVE_REMOTE }

    /** 删除类操作数（§6.5-1 安全阀：≥ 20 或多于 30% 条目时自动同步需转人工确认）。 */
    val destructiveCount: Int get() = deleteRemoteCount + trashLocalCount
    val isEmpty: Boolean get() = ops.isEmpty()
}

/** 执行结果汇总。 */
data class SyncSummary(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedRemote: Int = 0,
    val trashedLocal: Int = 0,
    val conflictCopies: Int = 0,
    val moved: Int = 0,
    val skippedLarge: Int = 0,
    val failed: Int = 0,
    val finishedAt: Long = System.currentTimeMillis()
)

/** 预览列表 / 日志中的展示路径：MOVE 显示「源 → 目标」。 */
val SyncOp.displayPath: String get() = moveFrom?.let { "$it → $path" } ?: path

