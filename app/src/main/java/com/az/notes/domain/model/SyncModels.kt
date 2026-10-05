package com.az.notes.domain.model

/** 同步策略（§6.3）。M3 先落三种：双向 / 仅发送 / 仅接收；
 * “仅发送（覆盖云端）”“仅接收（还原本地）”与过滤规则编辑属 M4。
 */
enum class SyncMode { BIDIRECTIONAL, UPLOAD_ONLY, DOWNLOAD_ONLY }

/**
 * 同步配置（非敏感部分，DataStore 持久化；密码见 data/sync/CredentialStore 加密存储）。
 */
data class SyncConfig(
    /** WebDAV 服务器地址（坚果云默认 https://dav.jianguoyun.com/dav/） */
    val serverUrl: String = DEFAULT_SERVER_URL,
    val username: String = "",
    /** 远端目录（相对服务器根，默认 az-notes） */
    val remoteDir: String = "az-notes",
    val mode: SyncMode = SyncMode.BIDIRECTIONAL
) {
    /** 地址与账号均已填写才可发起同步 / 连接测试。 */
    val configured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank()

    /** 拼接后的同步根 URL（供 WebDavClient 使用）。 */
    val baseUrl: String
        get() {
            val server = serverUrl.trim().trimEnd('/')
            val dir = remoteDir.trim().trim('/')
            return if (dir.isEmpty()) "$server/" else "$server/$dir/"
        }

    companion object {
        const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"
    }
}

/** 单条日志 / 计划中的操作类型。 */
enum class SyncOpType { UPLOAD, DOWNLOAD, DELETE_REMOTE, TRASH_LOCAL, CONFLICT_COPY }

/** 一次同步会话中的单个待执行操作。 */
data class SyncOp(
    val type: SyncOpType,
    /** 相对 Vault 根的路径（'/' 分隔，无前导斜杠） */
    val path: String,
    /** 附加说明：如冲突副本的保留方向（local=保留本地旧版 / remote=保留远端旧版） */
    val detail: String? = null
)

/** Scan → Diff 产出的操作计划（§6.1 Plan 阶段；execute 前经用户预览确认）。 */
data class SyncPlan(
    val ops: List<SyncOp>,
    val scannedLocal: Int,
    val scannedRemote: Int
) {
    val uploadCount: Int get() = ops.count { it.type == SyncOpType.UPLOAD }
    val downloadCount: Int get() = ops.count { it.type == SyncOpType.DOWNLOAD }
    val deleteRemoteCount: Int get() = ops.count { it.type == SyncOpType.DELETE_REMOTE }
    val trashLocalCount: Int get() = ops.count { it.type == SyncOpType.TRASH_LOCAL }
    val conflictCount: Int get() = ops.count { it.type == SyncOpType.CONFLICT_COPY }
    val isEmpty: Boolean get() = ops.isEmpty()
}

/** 执行结果汇总。 */
data class SyncSummary(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedRemote: Int = 0,
    val trashedLocal: Int = 0,
    val conflictCopies: Int = 0,
    val failed: Int = 0,
    val finishedAt: Long = System.currentTimeMillis()
)

/** 操作类型的中文名（预览列表 / 进度文案 / 日志共用；与代码库既有 VM 文案风格一致）。 */
fun SyncOpType.label(): String = when (this) {
    SyncOpType.UPLOAD -> "上传"
    SyncOpType.DOWNLOAD -> "下载"
    SyncOpType.DELETE_REMOTE -> "删除云端"
    SyncOpType.TRASH_LOCAL -> "移入回收站"
    SyncOpType.CONFLICT_COPY -> "冲突副本"
}

/** 同步策略的中文名。 */
fun SyncMode.label(): String = when (this) {
    SyncMode.BIDIRECTIONAL -> "双向同步"
    SyncMode.UPLOAD_ONLY -> "仅发送（本地 → 云端）"
    SyncMode.DOWNLOAD_ONLY -> "仅接收（云端 → 本地）"
}
