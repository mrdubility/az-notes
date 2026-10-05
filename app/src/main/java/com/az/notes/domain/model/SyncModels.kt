package com.az.notes.domain.model

/**
 * 同步策略（§6.3）：五种模式全量对齐坚果云官方插件。
 * 后两种为“镜像”语义：以单侧为唯一真相，另一端对齐（多余项会被删除，删除仍需确认）。
 */
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
enum class ConflictStrategy {
    /** 冲突副本（默认）：按修改时间判定胜方，败方另存副本等人工合并。 */
    CONFLICT_COPY,
    /** 本地优先：无条件以本地版本覆盖远端（远端败方先备份）。 */
    LOCAL_FIRST,
    /** 坚果云优先：无条件以远端版本覆盖本地（本地败方先备份）。 */
    REMOTE_FIRST
}

/** 自动同步周期（§6.4）。OFF = 不启用 WorkManager 周期任务。 */
enum class SyncInterval(val minutes: Long?) {
    OFF(null), M15(15), M30(30), H1(60), H4(240), H8(480)
}

/**
 * 同步配置（非敏感部分，DataStore 持久化；密码见 data/sync/CredentialStore 加密存储）。
 */
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
    /** 大文件上限（MB），超过则跳过该文件的同步（§6.3） */
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

    /** 大文件上限（字节）。 */
    val maxFileSizeBytes: Long
        get() = maxFileSizeMb.toLong() * 1024 * 1024

    /** 拼接后的同步根 URL（供 WebDavClient 使用）。 */
    val baseUrl: String
        get() {
            val server = serverUrl.trim().trimEnd('/')
            val dir = remoteDir.trim().trim('/')
            return if (dir.isEmpty()) "$server/" else "$server/$dir/"
        }

    companion object {
        const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"

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
    /** 附加说明：如冲突副本的保留方向（local=保留本地旧版 / remote=保留远端旧版） */
    val detail: String? = null,
    /** MOVE_LOCAL / MOVE_REMOTE：源相对路径（[path] 为目标路径） */
    val moveFrom: String? = null,
    /** CONFLICT_COPY：败方备份的目标相对路径（计划阶段确定，执行阶段直接写入） */
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

/** 操作类型的中文名（预览列表 / 进度文案 / 日志共用；与代码库既有 VM 文案风格一致）。 */
fun SyncOpType.label(): String = when (this) {
    SyncOpType.UPLOAD -> "上传"
    SyncOpType.DOWNLOAD -> "下载"
    SyncOpType.DELETE_REMOTE -> "删除云端"
    SyncOpType.TRASH_LOCAL -> "移入回收站"
    SyncOpType.CONFLICT_COPY -> "冲突副本"
    SyncOpType.MOVE_LOCAL -> "本地改名"
    SyncOpType.MOVE_REMOTE -> "云端改名"
}

/** 同步策略的中文名。 */
fun SyncMode.label(): String = when (this) {
    SyncMode.BIDIRECTIONAL -> "双向同步"
    SyncMode.UPLOAD_ONLY -> "仅发送（本地 → 云端）"
    SyncMode.UPLOAD_OVERWRITE -> "仅发送（覆盖云端）"
    SyncMode.DOWNLOAD_ONLY -> "仅接收（云端 → 本地）"
    SyncMode.DOWNLOAD_RESTORE -> "仅接收（还原本地）"
}

/** 冲突策略的中文名。 */
fun ConflictStrategy.label(): String = when (this) {
    ConflictStrategy.CONFLICT_COPY -> "冲突副本（默认）"
    ConflictStrategy.LOCAL_FIRST -> "本地优先"
    ConflictStrategy.REMOTE_FIRST -> "坚果云优先"
}

/** 自动同步周期的中文名。 */
fun SyncInterval.label(): String = when (this) {
    SyncInterval.OFF -> "关闭"
    SyncInterval.M15 -> "每 15 分钟"
    SyncInterval.M30 -> "每 30 分钟"
    SyncInterval.H1 -> "每 1 小时"
    SyncInterval.H4 -> "每 4 小时"
    SyncInterval.H8 -> "每 8 小时"
}
