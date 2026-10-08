package com.az.notes.data.sync

import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogType
import com.az.notes.data.local.ConflictRecordDao
import com.az.notes.data.local.ConflictRecordEntity
import com.az.notes.data.local.SyncBaselineDao
import com.az.notes.data.local.SyncBaselineEntity
import com.az.notes.data.local.SyncLogDao
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.storage.TrashRepository
import com.az.notes.data.webdav.WebDavClient
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncMode
import com.az.notes.domain.model.SyncOp
import com.az.notes.domain.model.SyncOpType
import com.az.notes.domain.model.SyncPlan
import com.az.notes.domain.model.SyncSummary
import com.az.notes.domain.model.displayPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步引擎（§6.1，沿用坚果云插件算法思想，Kotlin 重写）。
 *
 * 单次会话流程：Scan → LoadBaseline → Diff/Decide（三方对比）→ Plan（给 UI 预览确认）
 * → Execute → CommitBaseline（重新快照两端一致状态）。
 *
 * 能力：五种同步模式（含两种镜像）、冲突副本/优先策略、重命名启发式（MOVE）、
 * Gitignore 风格过滤、大文件上限、回收站（保留天数可在设置调整）、操作预览确认、日志与基线。
 * 差异判定采用宽松模式（本地 size+mtime、远端 size+etag 短路）。
 * 安全阀（§6.5）：远端为空而基线存在中止；本地被删文件进 App 私有 trash/ 不物理删除；
 * 删除量达阈值时自动同步转人工确认；失败路径不写基线，下一轮重新决策。
 */
@Singleton
class SyncEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val credentialStore: CredentialStore,
    private val trashRepository: TrashRepository,
    private val baselineDao: SyncBaselineDao,
    private val syncLogDao: SyncLogDao,
    private val conflictRecordDao: ConflictRecordDao,
    private val syncRunNotifier: SyncRunNotifier,
    private val debugLogRepository: DebugLogRepository
) {

    private data class LocalStat(val size: Long, val mtime: Long)

    /** 当前仓库引用：同步数据（基线 / 日志 / 冲突记录）按 [id] 隔离，文件操作走 [path]。 */
    private data class VaultRef(val id: String, val path: String)

    /** plan 阶段的扫描结果，供同一次同步的 commitBaseline 复用。 */
    private class ScanSnapshot(
        val remote: Map<String, WebDavClient.RemoteEntry>,
        /** 远端列表可能被分页截断、需原样保留的基线条目（否则会被误判为未同步而反复重传）。 */
        val carryOver: List<SyncBaselineEntity> = emptyList(),
        /** plan 阶段的本地扫描快照：提交基线时作为本轮未被触碰文件的“未变更”参照。 */
        val local: Map<String, LocalStat> = emptyMap()
    )

    @Volatile
    private var lastSnapshot: ScanSnapshot? = null

    /** 同步会话互斥：手动与自动同步不能并发（并发会双写基线、重复传输）。 */
    private val sessionMutex = Mutex()

    /** 调试日志快捷入口（type=SYNC）：未开启收集时仅一次 volatile 读，零开销短路。 */
    private fun syncLog(
        level: DebugLogLevel,
        msg: String,
        extra: Map<String, Any?> = emptyMap()
    ) = debugLogRepository.log(level, DebugLogType.SYNC, msg, extra)

    /**
     * 独占发起一次同步会话；已有会话（手动 / 自动）进行中时返回 false。
     * 调用方在 [block] 内自行调用 plan / execute。
     */
    suspend fun runExclusive(block: suspend () -> Unit): Boolean {
        if (!sessionMutex.tryLock()) return false
        try {
            block()
        } finally {
            sessionMutex.unlock()
        }
        return true
    }

    // ---------------------------------------------------------------- 连接测试

    /** 连接测试；遇到服务端限流（503/429 自动等待重试）时经 [onStatus] 反馈进度。 */
    suspend fun testConnection(
        config: SyncConfig,
        onStatus: (String) -> Unit = {}
    ): Result<Unit> {
        val client = runCatching {
            buildClient(config) { attempt ->
                onStatus("触发服务端限流，自动等待重试（第 $attempt 次）…")
            }
        }.getOrElse { return Result.failure(it) }
        return client.testConnection()
    }

    // ---------------------------------------------------------------- Plan

    /** 扫描两端并与基线三方对比，产出待执行操作计划。 */
    suspend fun plan(config: SyncConfig, onStatus: (String) -> Unit): SyncPlan {
        val ref = requireVaultRef()
        val vault = ref.path
        val ignore = IgnoreRules(config.ignoreRules)
        val client = buildClient(config) { attempt ->
            onStatus("触发服务端限流，自动等待重试（第 $attempt 次）…")
            debugLogRepository.log(
                DebugLogLevel.WARN,
                DebugLogType.NET,
                "服务端限流，自动等待重试",
                mapOf("attempt" to attempt)
            )
        }

        // 回收站过期批次清理（保留天数可在设置调整，0 = 永不清理）：同步前顺手执行
        purgeTrash()

        // 上一次同步若异常中止，快照可能残留；本轮重新扫描后才有可信快照
        lastSnapshot = null

        // 本地与远端扫描并行：远端逐目录 PROPFIND 是长耗时环节，本地扫描（毫秒级）不必串行等待
        onStatus("正在扫描本地与远端文件…")
        val (local, scan) = coroutineScope {
            val localDeferred = async(Dispatchers.IO) { scanLocal(vault, ignore) }
            val remoteDeferred = async {
                client.listAll { scanned, discoveredFiles ->
                    onStatus("正在扫描远端目录（已扫描 $scanned 个目录，发现 $discoveredFiles 个文件）…")
                }
            }
            localDeferred.await() to remoteDeferred.await()
        }
        val remote = scan.entries
            .filter { !it.isDirectory && !ignore.isIgnored(it.path, false) }
            .associateBy { it.path }
        // 条目数达到服务端单次返回上限的目录，其远端列表可能被分页截断而不完整
        val untrustedDirs = scan.truncatedDirs

        val baseline = withContext(Dispatchers.IO) { baselineDao.getAll(ref.id) }.associateBy { it.path }

        // 调试埋点：本轮扫描规模与配置（排查“假冲突”等问题时的现场基线）
        syncLog(
            DebugLogLevel.INFO,
            "plan 开始",
            mapOf(
                "mode" to config.mode.name,
                "strategy" to config.conflictStrategy.name,
                "maxMb" to config.maxFileSizeMb,
                "localFiles" to local.size,
                "remoteFiles" to remote.size,
                "baseline" to baseline.size
            )
        )

        // 安全阀（§6.5-2）：远端为空而本地有基线 → 疑似凭据 / 目录配置错误，中止删除传播
        if (remote.isEmpty() && baseline.isNotEmpty() && local.isNotEmpty()) {
            syncLog(DebugLogLevel.ERROR, "远端为空而基线存在，已中止同步（疑似配置错误）")
            throw IOException("远端目录为空而本地已有同步基线，疑似凭据或远端目录配置错误，已中止本次同步")
        }

        val allowUpload = config.mode != SyncMode.DOWNLOAD_ONLY && config.mode != SyncMode.DOWNLOAD_RESTORE
        val allowDownload = config.mode != SyncMode.UPLOAD_ONLY && config.mode != SyncMode.UPLOAD_OVERWRITE
        // 镜像模式（§6.3）：以单侧为唯一真相，不参与三方对比
        val mirrorToRemote = config.mode == SyncMode.UPLOAD_OVERWRITE
        val mirrorToLocal = config.mode == SyncMode.DOWNLOAD_RESTORE
        // 冲突处理（副本 / 优先策略）只在双向模式下有意义：单侧模式不存在“双改冲突”
        val handleConflicts = config.mode == SyncMode.BIDIRECTIONAL
        val strategy = config.conflictStrategy
        val maxBytes = config.maxFileSizeBytes

        val ops = ArrayList<SyncOp>()
        val carryOver = ArrayList<SyncBaselineEntity>()
        var unsafeSkipped = 0
        var skippedLarge = 0
        // 不可信判定：路径落在被截断的目录内（根目录 "" 被截断时全库不可信）
        fun untrusted(path: String): Boolean =
            untrustedDirs.any { it.isEmpty() || path.startsWith("$it/") }
        fun tooLarge(size: Long): Boolean = size > maxBytes

        /**
         * 冲突处理（§6.2）：按策略决定胜方，再以胜方覆盖另一端。
         * 文本类文件先把败方另存冲突副本；二进制/图片不生成副本、直接覆盖（与插件一致）。
         * 两者都在执行阶段写入 conflict_record（§4.2）供同步页查看。
         */
        fun addConflict(path: String) {
            val localWins = when (strategy) {
                ConflictStrategy.LOCAL_FIRST -> true
                ConflictStrategy.REMOTE_FIRST -> false
                ConflictStrategy.CONFLICT_COPY ->
                    (local[path]?.mtime ?: 0L) >= (remote[path]?.lastModified ?: 0L)
            }
            val winner = if (localWins) "local" else "remote"
            // 调试埋点：冲突判定现场（hasBase=false 的“首次共存”是“假冲突”高发形态）
            val base = baseline[path]
            syncLog(
                DebugLogLevel.WARN,
                "冲突判定",
                mapOf(
                    "path" to path,
                    "hasBase" to (base != null),
                    "localSize" to local[path]?.size,
                    "localMtime" to local[path]?.mtime,
                    "remoteSize" to remote[path]?.size,
                    "remoteMtime" to remote[path]?.lastModified,
                    "remoteEtag" to remote[path]?.etag,
                    "baseLocalSize" to base?.localSize,
                    "baseLocalMtime" to base?.localMtime,
                    "baseRemoteSize" to base?.remoteSize,
                    "winner" to winner
                )
            )
            val backup = if (isTextLike(path)) conflictRelativePath(vault, path) else null
            ops += SyncOp(SyncOpType.CONFLICT_COPY, path, detail = winner, backupPath = backup)
            // 副本与主文件同轮上传（CONFLICT_COPY 优先级更高，先落盘再上传）：
            // 否则下一轮同步会把副本当作“本地新增”再次请求上传
            if (backup != null && allowUpload) ops += SyncOp(SyncOpType.UPLOAD, backup)
            if (localWins) {
                if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
            } else {
                if (allowDownload) ops += SyncOp(SyncOpType.DOWNLOAD, path)
            }
        }

        val paths = (local.keys + remote.keys + baseline.keys).toSortedSet()
        for (path in paths) {
            val l = local[path]
            val r = remote[path]
            val b = baseline[path]

            // —— 镜像模式（§6.3）：以单侧为唯一真相，不参与三方对比 ——
            if (mirrorToRemote) {
                when {
                    l != null -> if (tooLarge(l.size)) skippedLarge++ else ops += SyncOp(SyncOpType.UPLOAD, path)
                    r != null -> if (!untrusted(path)) ops += SyncOp(SyncOpType.DELETE_REMOTE, path)
                }
                continue
            }
            if (mirrorToLocal) {
                when {
                    r != null -> if (tooLarge(r.size)) skippedLarge++ else ops += SyncOp(SyncOpType.DOWNLOAD, path)
                    l != null -> if (untrusted(path)) unsafeSkipped++ else ops += SyncOp(SyncOpType.TRASH_LOCAL, path)
                }
                continue
            }

            if (b == null) {
                // —— 无基线：新增 / 首次共存 ——
                when {
                    l != null && r == null -> if (allowUpload) {
                        if (tooLarge(l.size)) skippedLarge++ else ops += SyncOp(SyncOpType.UPLOAD, path)
                    }
                    l == null && r != null -> if (allowDownload) {
                        if (tooLarge(r.size)) skippedLarge++ else ops += SyncOp(SyncOpType.DOWNLOAD, path)
                    }
                    l != null && r != null -> {
                        // size 相同视为一致（宽松模式），跳过；否则按冲突处理
                        if (l.size != r.size) {
                            if (handleConflicts) {
                                addConflict(path)
                            } else if (l.mtime >= r.lastModified) {
                                if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
                            } else {
                                if (allowDownload) ops += SyncOp(SyncOpType.DOWNLOAD, path)
                            }
                        }
                    }
                }
                continue
            }

            // —— 有基线：三方对比 ——
            val lChanged = l != null && (l.size != b.localSize || l.mtime != b.localMtime)
            val rChanged = r != null && (
                r.size != b.remoteSize ||
                    (r.etag != null && b.remoteEtag != null && r.etag != b.remoteEtag)
                )
            when {
                l == null && r == null -> Unit // 两端都已删除，清理即可
                l == null && r != null -> {
                    if (rChanged) {
                        // 远端修改 + 本地已删除 → 远端为准恢复本地
                        if (allowDownload) {
                            ops += SyncOp(SyncOpType.DOWNLOAD, path, detail = "远端已修改，本地删除未生效")
                        }
                    } else if (allowUpload) {
                        // 本地删除传播到云端（删除在预览中可见，执行前需用户确认）
                        ops += SyncOp(SyncOpType.DELETE_REMOTE, path)
                    }
                }
                l != null && r == null -> {
                    if (lChanged) {
                        // 本地修改 + 远端已删除 → 重新上传保留本地版本
                        if (allowUpload) {
                            if (tooLarge(l.size)) skippedLarge++
                            else ops += SyncOp(SyncOpType.UPLOAD, path, detail = "远端已删除，本地修改已保留")
                        }
                    } else if (allowDownload) {
                        if (untrusted(path)) {
                            // “没列出”不等于“已删除”（可能只是分页截断），跳过以免误删本地文件；
                            // 同时保留其基线条目，避免下一轮把它当成新文件重新上传
                            unsafeSkipped++
                            carryOver += b
                        } else {
                            // 远端删除传播到本地：移入 App 私有回收站（§6.5-3），不物理删除
                            ops += SyncOp(SyncOpType.TRASH_LOCAL, path)
                        }
                    }
                }
                l != null && r != null -> when {
                    lChanged && !rChanged -> if (allowUpload) {
                        if (tooLarge(l.size)) skippedLarge++ else ops += SyncOp(SyncOpType.UPLOAD, path)
                    }
                    !lChanged && rChanged -> if (allowDownload) {
                        if (tooLarge(r.size)) skippedLarge++ else ops += SyncOp(SyncOpType.DOWNLOAD, path)
                    }
                    lChanged && rChanged -> {
                        // size 相同视为等价（宽松模式），跳过；否则按冲突处理
                        if (l.size != r.size) {
                            if (handleConflicts) {
                                addConflict(path)
                            } else if (l.mtime >= r.lastModified) {
                                if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
                            } else {
                                if (allowDownload) ops += SyncOp(SyncOpType.DOWNLOAD, path)
                            }
                        }
                    }
                    else -> Unit // 两端均未变化
                }
            }
        }

        // —— 重命名识别（§6.1）：把“一侧改名”还原为 MOVE 指令，避免“删除 + 重传” ——
        // 判据：一侧的旧路径消失且新路径出现、两者内容大小一致且各自唯一（歧义时保守放弃）。
        if (config.mode == SyncMode.BIDIRECTIONAL) {
            // ① 远端改名（桌面端 Obsidian 改名）：本地仍是旧路径 → 本地改名对齐
            val goneRemote = ops.filter { it.type == SyncOpType.TRASH_LOCAL }
                .mapNotNull { op -> local[op.path]?.let { op.path to it.size } }
            val addedRemote = ops.filter {
                it.type == SyncOpType.DOWNLOAD && baseline[it.path] == null && local[it.path] == null
            }.mapNotNull { op -> remote[op.path]?.let { op.path to it.size } }
            for ((oldPath, newPath) in pairBySize(goneRemote, addedRemote)) {
                ops.removeAll {
                    (it.type == SyncOpType.TRASH_LOCAL && it.path == oldPath) ||
                        (it.type == SyncOpType.DOWNLOAD && it.path == newPath)
                }
                ops += SyncOp(SyncOpType.MOVE_LOCAL, newPath, detail = oldPath, moveFrom = oldPath)
                syncLog(
                    DebugLogLevel.INFO,
                    "识别重命名（远端改名对齐本地）",
                    mapOf("from" to oldPath, "to" to newPath)
                )
            }
            // ② 本地改名（在 App 之外改的名）：远端仍是旧路径 → 远端 MOVE 对齐
            val goneLocal = ops.filter { it.type == SyncOpType.DELETE_REMOTE }
                .mapNotNull { op -> remote[op.path]?.let { op.path to it.size } }
            val addedLocal = ops.filter {
                it.type == SyncOpType.UPLOAD && baseline[it.path] == null && remote[it.path] == null
            }.mapNotNull { op -> local[op.path]?.let { op.path to it.size } }
            for ((oldPath, newPath) in pairBySize(goneLocal, addedLocal)) {
                ops.removeAll {
                    (it.type == SyncOpType.DELETE_REMOTE && it.path == oldPath) ||
                        (it.type == SyncOpType.UPLOAD && it.path == newPath)
                }
                ops += SyncOp(SyncOpType.MOVE_REMOTE, newPath, detail = oldPath, moveFrom = oldPath)
                syncLog(
                    DebugLogLevel.INFO,
                    "识别重命名（本地改名同步远端）",
                    mapOf("from" to oldPath, "to" to newPath)
                )
            }
        }

        // 执行顺序：改名先于其他操作（后续操作以新路径为准）；冲突副本先于同路径的覆盖
        val ordered = ops.sortedBy { opPriority(it.type) }
        // 两端已完全一致（空计划）时清除历史冲突记录：角标数字源自记录条数，
        // 残留会导致此后无差异时仍显示角标
        if (ordered.isEmpty()) {
            withContext(Dispatchers.IO) { conflictRecordDao.clear(ref.id) }
        }
        // 快照留给 commitBaseline 复用：同一次同步不再把远端全树扫第二遍
        lastSnapshot = ScanSnapshot(remote, carryOver, local)
        val warning = buildWarning(untrustedDirs, unsafeSkipped, skippedLarge)
        val plan = SyncPlan(
            ops = ordered,
            scannedLocal = local.size,
            scannedRemote = remote.size,
            warning = warning,
            skippedLarge = skippedLarge
        )
        syncLog(
            DebugLogLevel.INFO,
            "plan 完成",
            mapOf(
                "ops" to plan.ops.size,
                "uploads" to plan.uploadCount,
                "downloads" to plan.downloadCount,
                "destructive" to plan.destructiveCount,
                "conflicts" to plan.conflictCount,
                "moves" to plan.moveCount,
                "skippedLarge" to skippedLarge,
                "unsafeSkipped" to unsafeSkipped
            )
        )
        return plan
    }

    // ---------------------------------------------------------------- Execute

    /**
     * 依计划执行；[onProgress] 回调 (已完成数, 总数, 当前操作描述)。
     * 执行期间置位 [SyncRunNotifier]（供顶栏“同步中”指示），结束（含取消）后复位。
     */
    suspend fun execute(
        config: SyncConfig,
        plan: SyncPlan,
        onProgress: (done: Int, total: Int, label: String) -> Unit
    ): SyncSummary {
        syncRunNotifier.setRunning(true)
        try {
            return executeInternal(config, plan, onProgress)
        } finally {
            syncRunNotifier.markCompleted()
        }
    }

    private suspend fun executeInternal(
        config: SyncConfig,
        plan: SyncPlan,
        onProgress: (done: Int, total: Int, label: String) -> Unit
    ): SyncSummary {
        val ref = requireVaultRef()
        val vault = ref.path

        var uploaded = 0
        var downloaded = 0
        var deletedRemote = 0
        var trashedLocal = 0
        var conflictCopies = 0
        var moved = 0
        var failed = 0
        val runStartedAt = System.currentTimeMillis()
        val failedPaths = HashSet<String>()
        // 上传 / 远端改名成功的路径 → 操作前捕获的本地属性：远端属性已变（后者不在扫描
        // 快照中），提交基线时需逐个 Depth:0 刷新；stat 失败时以捕获值兜底合成条目
        val uploadedStats = HashMap<String, LocalStat>()
        // 下载成功的路径 → 落盘后捕获的本地属性（用户随后编辑不影响该值）
        val downloadedStats = HashMap<String, LocalStat>()
        // 执行成功的冲突副本：全部操作完成后统一计算指纹写入 conflict_record（§4.2）
        val conflictOps = ArrayList<SyncOp>()
        // 冲突副本（败方备份）失败的路径：同路径的覆盖操作必须跳过，避免丢失版本
        val unprotectedConflict = HashSet<String>()
        val logs = ArrayList<SyncLogEntity>()
        val total = plan.ops.size
        var progressIndex = 0
        val client = buildClient(config) { attempt ->
            onProgress(progressIndex, total, "触发服务端限流，自动等待重试（第 $attempt 次）…")
            debugLogRepository.log(
                DebugLogLevel.WARN,
                DebugLogType.NET,
                "服务端限流，自动等待重试（执行阶段）",
                mapOf("attempt" to attempt)
            )
        }

        plan.ops.forEachIndexed { index, op ->
            progressIndex = index
            onProgress(index, total, "${op.type.engineLabel()} ${op.displayPath}")
            // 前置冲突副本失败时跳过同路径覆盖（§6.2）：宁可整条留到下轮，也不在未备份败方时丢失版本
            if ((op.type == SyncOpType.UPLOAD || op.type == SyncOpType.DOWNLOAD) && op.path in unprotectedConflict) {
                failed++
                failedPaths += op.path
                logs += SyncLogEntity(
                    vaultId = ref.id,
                    ts = System.currentTimeMillis(),
                    op = op.type.name,
                    path = op.displayPath,
                    result = "SKIP",
                    detail = "冲突副本未成功，已跳过覆盖以防丢失版本"
                )
                return@forEachIndexed
            }
            // 上传 / 远端改名：操作前捕获本地属性（上传内容即当前磁盘状态，需先于操作取）
            val capturedStat =
                if (op.type == SyncOpType.UPLOAD || op.type == SyncOpType.MOVE_REMOTE) {
                    localStatOf(vault, op.path)
                } else {
                    null
                }
            var ok = false
            var error: String? = null
            try {
                ok = executeOp(vault, client, op)
                if (!ok) error = "操作未完成"
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                error = t.message ?: t::class.java.simpleName
            }
            if (ok) {
                when (op.type) {
                    SyncOpType.UPLOAD -> {
                        uploaded++
                        capturedStat?.let {
                            uploadedStats[op.path] = it
                            // 写透：远端内容此刻已与上传时刻的本地一致
                            persistBaselineEntry(ref.id, op.path, it)
                        }
                    }
                    SyncOpType.DOWNLOAD -> {
                        downloaded++
                        localStatOf(vault, op.path)?.let {
                            downloadedStats[op.path] = it
                            // 写透：远端值用扫描快照（精确 size / mtime / etag）
                            persistBaselineEntry(ref.id, op.path, it, lastSnapshot?.remote?.get(op.path))
                        }
                    }
                    SyncOpType.DELETE_REMOTE -> deletedRemote++
                    SyncOpType.TRASH_LOCAL -> trashedLocal++
                    SyncOpType.CONFLICT_COPY -> {
                        conflictCopies++
                        conflictOps += op
                        syncLog(
                            DebugLogLevel.INFO,
                            "冲突副本已创建",
                            mapOf("path" to op.displayPath, "winner" to op.detail, "backup" to op.backupPath)
                        )
                    }
                    SyncOpType.MOVE_LOCAL -> {
                        moved++
                        // 写透：本地新路径建档（远端值取快照），源路径条目移除
                        localStatOf(vault, op.path)?.let {
                            persistBaselineEntry(
                                ref.id, op.path, it, lastSnapshot?.remote?.get(op.path),
                                removePath = op.moveFrom
                            )
                        }
                    }
                    SyncOpType.MOVE_REMOTE -> {
                        moved++
                        // 远端路径已变且不在 plan 扫描快照中：提交基线时重新 stat 新路径
                        capturedStat?.let {
                            uploadedStats[op.path] = it
                            // 写透：远端新路径内容 = 本地改名后内容；源路径条目移除
                            persistBaselineEntry(ref.id, op.path, it, removePath = op.moveFrom)
                        }
                    }
                }
            } else {
                failed++
                failedPaths += op.path
                // 调试埋点：失败现场；失败路径保留旧基线，下一轮重新决策
                syncLog(
                    DebugLogLevel.WARN,
                    "操作失败",
                    mapOf("op" to op.type.name, "path" to op.displayPath, "error" to (error ?: "未知错误"))
                )
                // 败方备份未成功：同路径的覆盖操作在后续循环中一并跳过
                if (op.type == SyncOpType.CONFLICT_COPY && op.backupPath != null) {
                    unprotectedConflict += op.path
                }
            }
            logs += SyncLogEntity(
                vaultId = ref.id,
                ts = System.currentTimeMillis(),
                op = op.type.name,
                path = op.displayPath,
                result = if (ok) "OK" else "FAIL",
                detail = error ?: op.detail
            )
        }

        onProgress(total, total, "正在提交同步基线…")
        withContext(Dispatchers.IO) {
            syncLogDao.insertAll(logs)
            syncLogDao.trimTo(ref.id, 500)
        }
        // 冲突记录（§6.2）：记录冲突双方指纹与解决方式，供同步页人工合并追踪
        if (conflictOps.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                for (op in conflictOps) {
                    // 胜方内容在 path（覆盖成功后），败方内容在副本（二进制冲突无副本，记 null）
                    val winHash = if (op.path in failedPaths) null else sha1(File(vault, op.path))
                    val loseHash = sha1(op.backupPath?.takeIf { it.isNotEmpty() }?.let { File(vault, it) })
                    conflictRecordDao.insert(
                        ConflictRecordEntity(
                            vaultId = ref.id,
                            path = op.path,
                            baseSha1 = null,
                            localSha1 = if (op.detail == "local") winHash else loseHash,
                            remoteSha1 = if (op.detail == "local") loseHash else winHash,
                            resolvedBy = "${config.conflictStrategy.name}:${op.detail}",
                            backupPath = op.backupPath.orEmpty(),
                            createdAt = now
                        )
                    )
                }
                conflictRecordDao.trimTo(ref.id, 200)
            }
        }
        // 已解决的冲突记录清理：副本已被合并 / 删除（本轮之前创建的）→ 记录移除，角标随之消失
        withContext(Dispatchers.IO) {
            val stale = conflictRecordDao.getAll(ref.id).filter {
                it.createdAt < runStartedAt &&
                    it.backupPath.isNotEmpty() && !File(vault, it.backupPath).exists()
            }
            if (stale.isNotEmpty()) conflictRecordDao.deleteByIds(stale.map { it.id })
        }
        // CommitBaseline：重新快照两端状态；失败路径不写基线，下一轮重新决策
        var baselineError: String? = null
        try {
            val snapshot = lastSnapshot
            lastSnapshot = null // 用完即弃，避免跨会话复用陈旧快照
            withContext(Dispatchers.IO) {
                commitBaseline(
                    vaultId = ref.id,
                    vaultRoot = vault,
                    client = client,
                    ignore = IgnoreRules(config.ignoreRules),
                    excludePaths = failedPaths,
                    uploaded = uploadedStats,
                    downloaded = downloadedStats,
                    planLocal = snapshot?.local.orEmpty(),
                    snapshot = snapshot
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Exception) {
            baselineError = t.message ?: "基线提交失败"
        }
        val err = baselineError
        if (err != null) {
            withContext(Dispatchers.IO) {
                syncLogDao.insertAll(
                    listOf(
                        SyncLogEntity(
                            vaultId = ref.id,
                            ts = System.currentTimeMillis(),
                            op = "BASELINE",
                            path = "",
                            result = "FAIL",
                            detail = err
                        )
                    )
                )
            }
        }

        syncLog(
            DebugLogLevel.INFO,
            "执行完成",
            mapOf(
                "uploaded" to uploaded,
                "downloaded" to downloaded,
                "deletedRemote" to deletedRemote,
                "trashedLocal" to trashedLocal,
                "conflictCopies" to conflictCopies,
                "moved" to moved,
                "failed" to failed,
                "skippedLarge" to plan.skippedLarge
            )
        )
        return SyncSummary(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedRemote = deletedRemote,
            trashedLocal = trashedLocal,
            conflictCopies = conflictCopies,
            moved = moved,
            skippedLarge = plan.skippedLarge,
            failed = failed
        )
    }

    private suspend fun executeOp(vaultRoot: String, client: WebDavClient, op: SyncOp): Boolean =
        when (op.type) {
            SyncOpType.UPLOAD -> {
                val local = File(vaultRoot, op.path)
                if (local.isFile) client.upload(local, op.path) else false
            }
            SyncOpType.DOWNLOAD -> client.download(op.path, File(vaultRoot, op.path))
            SyncOpType.DELETE_REMOTE -> client.delete(op.path)
            SyncOpType.TRASH_LOCAL ->
                trashRepository.moveToTrash(vaultRoot, File(vaultRoot, op.path).absolutePath) != null
            SyncOpType.CONFLICT_COPY -> {
                val backupRel = op.backupPath
                if (backupRel.isNullOrEmpty()) {
                    // 二进制/图片冲突：不落盘副本，仅由 conflict_record 记录（§6.2）
                    true
                } else {
                    val backup = File(vaultRoot, backupRel)
                    if (op.detail == "local") {
                        // 本地胜：远端是败方，先把远端旧版下载为副本
                        client.download(op.path, backup)
                    } else {
                        // 远端胜：本地是败方，先把本地旧版复制为副本
                        val source = File(vaultRoot, op.path)
                        source.isFile && runCatching {
                            backup.parentFile?.mkdirs()
                            source.copyTo(backup, overwrite = false)
                            true
                        }.getOrDefault(false)
                    }
                }
            }
            SyncOpType.MOVE_LOCAL -> {
                // 远端已改名：本地旧路径 → 新路径（远端无需操作）
                val source = op.moveFrom?.let { File(vaultRoot, it) }
                val target = File(vaultRoot, op.path)
                source != null && source.isFile && runCatching {
                    target.parentFile?.mkdirs()
                    source.renameTo(target)
                }.getOrDefault(false)
            }
            SyncOpType.MOVE_REMOTE -> {
                // 本地已改名：远端旧路径 → 新路径（MOVE，仅文件）
                val from = op.moveFrom
                from != null && client.move(from, op.path, isDirectory = false)
            }
        }

    // ---------------------------------------------------------------- 基线

    /**
     * 提交基线：重新快照两端一致状态。
     *
     * 远端优先复用 plan 阶段的扫描快照 [snapshot]，仅对本次上传 / 远端改名的路径
     * （[uploaded]）逐个 Depth:0 取最新属性——避免每次同步都把远端全树扫两遍
     * （请求数约减半，无变更时零额外请求）。stat 瞬时失败时用操作前捕获的本地属性
     * 合成条目兜底（etag 置空），避免该条目掉出基线、下一轮被误判为首次共存冲突。
     * 失败路径（[excludePaths]）不更新条目但【保留旧值】（Fix A）：整条删除会使下一轮
     * 因无基线而把“双侧共存且内容不同”误判为首次冲突，生成“冲突副本 = 修改前版本”的假冲突。
     * 无快照时回退全量扫描。
     *
     * 本地侧取值（避免把执行期间的编辑误记为“已同步”而静默丢失该编辑）：
     * 本轮下载的用落盘捕获值，本轮上传的用操作前捕获值，其余用 plan 阶段扫描值。
     */
    private suspend fun commitBaseline(
        vaultId: String,
        vaultRoot: String,
        client: WebDavClient,
        ignore: IgnoreRules,
        excludePaths: Set<String>,
        uploaded: Map<String, LocalStat>,
        downloaded: Map<String, LocalStat>,
        planLocal: Map<String, LocalStat>,
        snapshot: ScanSnapshot?
    ) {
        val local = scanLocal(vaultRoot, ignore)
        val carryOver = snapshot?.carryOver.orEmpty()
        // 失败路径的旧基线条目（Fix A）：本轮不更新但必须原样保留，留待下一轮重新决策
        val previous = baselineDao.getAll(vaultId).associateBy { it.path }
        val remote: Map<String, WebDavClient.RemoteEntry> = if (snapshot != null) {
            val merged = snapshot.remote.toMutableMap()
            for ((path, captured) in uploaded) {
                val fresh = runCatching { client.stat(path) }.getOrNull()
                merged[path] = fresh ?: WebDavClient.RemoteEntry(
                    path = path,
                    isDirectory = false,
                    size = captured.size,
                    lastModified = captured.mtime,
                    etag = null
                )
            }
            merged
        } else {
            client.listAll().entries
                .filter { !it.isDirectory && !ignore.isIgnored(it.path, false) }
                .associateBy { it.path }
        }
        val now = System.currentTimeMillis()
        val entries = ArrayList<SyncBaselineEntity>()
        var preserved = 0
        for ((path, l) in local) {
            if (path in excludePaths) {
                previous[path]?.let {
                    entries += it
                    preserved++
                }
                continue
            }
            val r = remote[path]
            if (r == null) {
                // 远端未列出：可能是分页截断造成的假象，沿用原基线避免反复重传
                carryOver.firstOrNull { it.path == path }?.let { entries += it }
                continue
            }
            val localStat = when {
                downloaded.containsKey(path) -> downloaded.getValue(path)
                uploaded.containsKey(path) -> uploaded.getValue(path)
                else -> planLocal[path] ?: l
            }
            entries += SyncBaselineEntity(
                vaultId = vaultId,
                path = path,
                localSize = localStat.size,
                localMtime = localStat.mtime,
                remoteSize = r.size,
                remoteMtime = r.lastModified,
                remoteEtag = r.etag,
                syncedAt = now
            )
        }
        // 失败路径若已不在本地扫描结果中（如上传失败后本地又被删除）：同样保留旧条目
        for (path in excludePaths) {
            if (path !in local) {
                previous[path]?.let {
                    entries += it
                    preserved++
                }
            }
        }
        baselineDao.replaceAll(vaultId, entries)
        syncLog(
            DebugLogLevel.DEBUG,
            "基线提交完成",
            mapOf("entries" to entries.size, "preservedFailed" to preserved)
        )
    }

    /** 读取本地文件当前属性（size / mtime）；不存在返回 null。 */
    private suspend fun localStatOf(vaultRoot: String, relativePath: String): LocalStat? =
        withContext(Dispatchers.IO) {
            File(vaultRoot, relativePath).takeIf { it.isFile }
                ?.let { LocalStat(it.length(), it.lastModified()) }
        }

    /**
     * 成功操作后立即写透单条基线（Fix B）：会话被取消 / 进程被杀时 commitBaseline
     * 可能未执行，及时落盘可防止本轮回执丢失（下轮把已同步文件再判成差异，重演假冲突）。
     * 零额外网络请求：远端值优先用扫描快照，缺失时按“远端已与本地一致”合成（etag 置空，
     * 宽松模式下不影响判定）。[removePath] 用于 MOVE：移除源路径的旧基线条目。
     */
    private suspend fun persistBaselineEntry(
        vaultId: String,
        path: String,
        localStat: LocalStat,
        remote: WebDavClient.RemoteEntry? = null,
        removePath: String? = null
    ) {
        val entry = SyncBaselineEntity(
            vaultId = vaultId,
            path = path,
            localSize = localStat.size,
            localMtime = localStat.mtime,
            remoteSize = remote?.size ?: localStat.size,
            remoteMtime = remote?.lastModified ?: localStat.mtime,
            remoteEtag = remote?.etag,
            syncedAt = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            removePath?.let { baselineDao.deleteByPath(vaultId, it) }
            baselineDao.upsertAll(listOf(entry))
        }
        syncLog(DebugLogLevel.DEBUG, "基线写透", mapOf("path" to path, "remove" to removePath))
    }

    // ---------------------------------------------------------------- 重命名同步

    /**
     * 本地重命名（文件或文件夹）后，把改名同步到云端（MOVE）并重映射基线路径。
     *
     * 不做这一步，下次同步会把它当成“旧路径删除 + 新路径新增”，
     * 导致整个目录重新上传（慢且耗流量）。未配置同步、路径不在 Vault 内、
     * 远端不存在或 MOVE 失败时静默跳过（仅记一条日志），由下次常规同步兜底。
     * 与同步会话（手动 / 自动）互斥：会话进行中时跳过本次即时改名，
     * 避免 MOVE 与扫描 / 执行交错产生重复文件或假冲突副本。
     */
    suspend fun applyRemoteRename(vaultRoot: String, oldAbsPath: String, newAbsPath: String) {
        val vaultId = settingsRepository.requireCurrentVaultId() ?: return
        val config = runCatching { syncConfigRepository.config.first() }.getOrNull() ?: return
        if (!config.configured) return
        val root = vaultRoot.trimEnd('/') + "/"
        if (!oldAbsPath.startsWith(root) || !newAbsPath.startsWith(root)) return
        val oldRel = oldAbsPath.removePrefix(root).trim('/')
        val newRel = newAbsPath.removePrefix(root).trim('/')
        if (oldRel.isEmpty() || newRel.isEmpty()) return
        // 抢会话锁（非阻塞）：同步进行中则本次跳过，交由下轮常规同步处理
        if (!sessionMutex.tryLock()) {
            withContext(Dispatchers.IO) {
                syncLogDao.insertAll(
                    listOf(
                        SyncLogEntity(
                            vaultId = vaultId,
                            ts = System.currentTimeMillis(),
                            op = LOG_OP_REMOTE_MOVE,
                            path = "$oldRel → $newRel",
                            result = "SKIP",
                            detail = "同步进行中，改名交由下次同步处理"
                        )
                    )
                )
            }
            return
        }
        try {
            val client = runCatching { buildClient(config) }.getOrNull() ?: return
            // 本地已改名完成，旧路径不再存在；以新路径判定是否为文件夹
            val isDirectory = withContext(Dispatchers.IO) { File(newAbsPath).isDirectory }
            val ok = runCatching { client.move(oldRel, newRel, isDirectory) }.getOrDefault(false)
            withContext(Dispatchers.IO) {
                if (ok) remapBaseline(vaultId, oldRel, newRel)
                syncLogDao.insertAll(
                    listOf(
                        SyncLogEntity(
                            vaultId = vaultId,
                            ts = System.currentTimeMillis(),
                            op = LOG_OP_REMOTE_MOVE,
                            path = "$oldRel → $newRel",
                            result = if (ok) "OK" else "SKIP",
                            detail = if (ok) "重命名已同步到云端"
                            else "云端未同步本次重命名，将在下次同步时处理"
                        )
                    )
                )
            }
        } finally {
            sessionMutex.unlock()
        }
    }

    /** 基线路径重映射：单文件或整个目录前缀（远端 MOVE 成功后两端仍一致）。 */
    private suspend fun remapBaseline(vaultId: String, oldRel: String, newRel: String) {
        val all = baselineDao.getAll(vaultId)
        val prefix = "$oldRel/"
        var changed = false
        val remapped = all.map { entry ->
            when {
                entry.path == oldRel -> {
                    changed = true
                    entry.copy(path = newRel)
                }
                entry.path.startsWith(prefix) -> {
                    changed = true
                    entry.copy(path = newRel + entry.path.removePrefix(oldRel))
                }
                else -> entry
            }
        }
        if (changed) baselineDao.replaceAll(vaultId, remapped)
    }

    // ---------------------------------------------------------------- 文件系统辅助

    private fun scanLocal(vaultRoot: String, ignore: IgnoreRules): Map<String, LocalStat> {
        val root = File(vaultRoot)
        if (!root.isDirectory) throw IOException("Vault 目录不存在或不可读")
        val out = HashMap<String, LocalStat>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack += root to ""
        while (stack.isNotEmpty()) {
            val (dir, prefix) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (f.isDirectory) {
                    // 命中忽略规则的目录整树剪枝；存在否定规则时继续下探，避免漏掉重新包含项
                    if (ignore.isIgnored(rel, true) && !ignore.hasNegations) continue
                    stack += f to rel
                } else if (f.isFile) {
                    if (ignore.isIgnored(rel, false)) continue
                    out[rel] = LocalStat(f.length(), f.lastModified())
                }
            }
        }
        return out
    }

    /** 回收站过期清理（§6.5-3）：保留天数来自设置，0 表示永不清理。 */
    private suspend fun purgeTrash() {
        val days = settingsRepository.settings.first().trashRetentionDays
        withContext(Dispatchers.IO) { trashRepository.purgeExpired(days) }
    }

    /** 计算文件 SHA-1（冲突记录指纹，尽力而为；读取失败返回 null）。 */
    private fun sha1(file: File?): String? {
        if (file == null || !file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /** 文本类文件（可人工合并）：冲突时生成冲突副本；图片/二进制按优先策略直接覆盖（§6.2）。 */
    private fun isTextLike(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in TEXT_EXTENSIONS

    /** 冲突副本相对路径：`名称 (conflict yyyy-MM-dd HHmmss).md`，重名自动加序号。 */
    private fun conflictRelativePath(vaultRoot: String, relativePath: String): String {
        val parent = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val dir = if (parent.isEmpty()) File(vaultRoot) else File(vaultRoot, parent)
        var candidate = File(dir, "$base (conflict ${conflictStamp()})$ext")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base (conflict ${conflictStamp()} $index)$ext")
            index++
        }
        return if (parent.isEmpty()) candidate.name else "$parent/${candidate.name}"
    }

    /**
     * 重命名启发式配对（§6.1）：把“一侧消失的旧路径”与“另一侧出现的新路径”按大小配对。
     * 远端拿不到内容 hash（需额外下载），改用 size 相等 + 两侧各自唯一做保守推断；
     * 任何一侧存在同大小歧义（多个候选）时放弃该条，避免误配出错误 MOVE。
     */
    private fun pairBySize(
        gone: List<Pair<String, Long>>,
        added: List<Pair<String, Long>>
    ): List<Pair<String, String>> {
        // 0 字节文件（新建占位）不参与配对，避免高概率误配
        val goneBySize = gone.filter { it.second > 0 }.groupBy { it.second }
        val addedBySize = added.filter { it.second > 0 }.groupBy { it.second }
        val pairs = ArrayList<Pair<String, String>>()
        for ((size, olds) in goneBySize) {
            if (olds.size != 1) continue
            val news = addedBySize[size] ?: continue
            if (news.size != 1) continue
            pairs += olds.first().first to news.first().first
        }
        return pairs
    }

    /** 汇总扫描限制提醒：分页截断导致的跳过项 / 大文件跳过（§6.3 / §6.5）。 */
    private fun buildWarning(
        untrustedDirs: Set<String>,
        unsafeSkipped: Int,
        skippedLarge: Int
    ): String? {
        val parts = ArrayList<String>()
        if (untrustedDirs.isNotEmpty() && unsafeSkipped > 0) {
            parts += "有 $unsafeSkipped 个文件位于云端目录列表被截断的位置，已跳过对它们的删除判断，将在下次同步重新核对"
        }
        if (skippedLarge > 0) {
            parts += "有 $skippedLarge 个文件超过大文件上限，本次已跳过"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun conflictStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.getDefault()).format(Date())

    /** 执行顺序：先改名，再备冲突副本（覆盖前完成败方备份），随后上传/下载，最后删除类操作。 */
    private fun opPriority(type: SyncOpType): Int = when (type) {
        SyncOpType.MOVE_LOCAL, SyncOpType.MOVE_REMOTE -> 0
        SyncOpType.CONFLICT_COPY -> 1
        SyncOpType.UPLOAD -> 2
        SyncOpType.DOWNLOAD -> 3
        SyncOpType.TRASH_LOCAL -> 4
        SyncOpType.DELETE_REMOTE -> 5
    }

    // ---------------------------------------------------------------- 基础依赖

    /** 解析当前仓库（id + 路径）；未选择仓库时抛出，同步不可用。 */
    private suspend fun requireVaultRef(): VaultRef {
        val settings = settingsRepository.settings.first()
        val id = settings.currentVaultId
        val path = settings.vaultPath
        if (id == null || path.isNullOrBlank()) {
            throw IOException("未选择仓库目录")
        }
        return VaultRef(id, path)
    }

    /** 构建 WebDAV 客户端（密码按当前仓库读取，凭据存储为 suspend）。 */
    private suspend fun buildClient(
        config: SyncConfig,
        onRateLimited: (attempt: Int) -> Unit = {}
    ): WebDavClient {
        if (!config.configured) throw IOException("请先填写服务器地址与账号")
        return WebDavClient(
            config.baseUrl,
            config.username,
            credentialStore.getPassword(),
            onRateLimited
        )
    }

    private companion object {
        /** 日志中的“云端重命名”操作名（不属于 [SyncOpType]，仅用于展示）。 */
        const val LOG_OP_REMOTE_MOVE = "REMOTE_MOVE"

        /** 冲突时按“文本可人工合并”处理的扩展名（其余走优先策略直接覆盖，§6.2）。 */
        val TEXT_EXTENSIONS =
            setOf("md", "markdown", "txt", "text", "json", "yaml", "yml", "csv", "canvas", "html")
    }
}

/** 引擎进度文案中的操作名（动态技术文本：不随界面语言切换，与落库日志文案一致）。 */
private fun SyncOpType.engineLabel(): String = when (this) {
    SyncOpType.UPLOAD -> "上传"
    SyncOpType.DOWNLOAD -> "下载"
    SyncOpType.DELETE_REMOTE -> "删除云端"
    SyncOpType.TRASH_LOCAL -> "移入回收站"
    SyncOpType.CONFLICT_COPY -> "冲突副本"
    SyncOpType.MOVE_LOCAL -> "本地改名"
    SyncOpType.MOVE_REMOTE -> "云端改名"
}
