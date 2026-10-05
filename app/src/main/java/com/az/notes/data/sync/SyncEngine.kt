package com.az.notes.data.sync

import android.content.Context
import com.az.notes.data.local.SyncBaselineDao
import com.az.notes.data.local.SyncBaselineEntity
import com.az.notes.data.local.SyncLogDao
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.webdav.WebDavClient
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncMode
import com.az.notes.domain.model.SyncOp
import com.az.notes.domain.model.SyncOpType
import com.az.notes.domain.model.SyncPlan
import com.az.notes.domain.model.SyncSummary
import com.az.notes.domain.model.label
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
 * M3 范围：双向 + 仅发送 + 仅接收三种策略、手动同步、操作预览确认、日志与基线；
 * 差异判定采用宽松模式（本地 size+mtime、远端 size+etag 短路）。
 * 安全阀（§6.5）：远端为空而基线存在中止；本地被删文件进 App 私有 trash/ 不物理删除；
 * 失败路径不写基线，下一轮重新决策。
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val credentialStore: CredentialStore,
    private val baselineDao: SyncBaselineDao,
    private val syncLogDao: SyncLogDao
) {

    private data class LocalStat(val size: Long, val mtime: Long)

    // ---------------------------------------------------------------- 连接测试

    suspend fun testConnection(config: SyncConfig): Result<Unit> {
        val client = runCatching { buildClient(config) }.getOrElse { return Result.failure(it) }
        return client.testConnection()
    }

    // ---------------------------------------------------------------- Plan

    /** 扫描两端并与基线三方对比，产出待执行操作计划。 */
    suspend fun plan(config: SyncConfig, onStatus: (String) -> Unit): SyncPlan {
        val vault = requireVault()
        val client = buildClient(config) { attempt ->
            onStatus("触发服务端限流，自动等待重试（第 $attempt 次）…")
        }

        onStatus("正在扫描本地文件…")
        val local = withContext(Dispatchers.IO) { scanLocal(vault) }

        onStatus("正在扫描远端目录…")
        val remote = client.listAll { scanned, discoveredFiles ->
            onStatus("正在扫描远端目录（已扫描 $scanned 个目录，发现 $discoveredFiles 个文件）…")
        }.filter { !it.isDirectory && !isIgnoredPath(it.path) }.associateBy { it.path }

        val baseline = withContext(Dispatchers.IO) { baselineDao.getAll() }.associateBy { it.path }

        // 安全阀（§6.5-2）：远端为空而本地有基线 → 疑似凭据 / 目录配置错误，中止删除传播
        if (remote.isEmpty() && baseline.isNotEmpty() && local.isNotEmpty()) {
            throw IOException("远端目录为空而本地已有同步基线，疑似凭据或远端目录配置错误，已中止本次同步")
        }

        val allowUpload = config.mode != SyncMode.DOWNLOAD_ONLY
        val allowDownload = config.mode != SyncMode.UPLOAD_ONLY
        // 冲突副本需要读写双向能力（保留败方旧版本），仅双向模式下启用
        val keepConflictCopies = config.mode == SyncMode.BIDIRECTIONAL

        val ops = ArrayList<SyncOp>()
        val paths = (local.keys + remote.keys + baseline.keys).toSortedSet()
        for (path in paths) {
            val l = local[path]
            val r = remote[path]
            val b = baseline[path]

            if (b == null) {
                // —— 无基线：新增 / 首次共存 ——
                when {
                    l != null && r == null -> if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
                    l == null && r != null -> if (allowDownload) ops += SyncOp(SyncOpType.DOWNLOAD, path)
                    l != null && r != null -> {
                        if (l.size != r.size) {
                            if (l.mtime >= r.lastModified) {
                                // 本地较新：上传；远端旧版本保留为本地冲突副本
                                if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
                                if (keepConflictCopies) {
                                    ops += SyncOp(SyncOpType.CONFLICT_COPY, path, detail = "remote")
                                }
                            } else {
                                // 远端较新：本地旧版本先存冲突副本，再下载覆盖
                                if (allowDownload) {
                                    if (keepConflictCopies) {
                                        ops += SyncOp(SyncOpType.CONFLICT_COPY, path, detail = "local")
                                    }
                                    ops += SyncOp(SyncOpType.DOWNLOAD, path)
                                }
                            }
                        }
                        // size 相同视为一致（宽松模式），跳过
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
                            ops += SyncOp(SyncOpType.UPLOAD, path, detail = "远端已删除，本地修改已保留")
                        }
                    } else if (allowDownload) {
                        // 远端删除传播到本地：移入 App 私有回收站（§6.5-3），不物理删除
                        ops += SyncOp(SyncOpType.TRASH_LOCAL, path)
                    }
                }
                l != null && r != null -> when {
                    lChanged && !rChanged -> if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
                    !lChanged && rChanged -> if (allowDownload) ops += SyncOp(SyncOpType.DOWNLOAD, path)
                    lChanged && rChanged -> {
                        if (l.size != r.size) {
                            if (l.mtime >= r.lastModified) {
                                if (allowUpload) ops += SyncOp(SyncOpType.UPLOAD, path)
                                if (keepConflictCopies) {
                                    ops += SyncOp(SyncOpType.CONFLICT_COPY, path, detail = "remote")
                                }
                            } else {
                                if (allowDownload) {
                                    if (keepConflictCopies) {
                                        ops += SyncOp(SyncOpType.CONFLICT_COPY, path, detail = "local")
                                    }
                                    ops += SyncOp(SyncOpType.DOWNLOAD, path)
                                }
                            }
                        }
                        // size 相同视为等价（宽松模式），跳过
                    }
                    else -> Unit // 两端均未变化
                }
            }
        }

        // 执行顺序：写入类在前、删除类在后；冲突副本（保留旧版）必须先于同路径的下载覆盖
        val ordered = ops.sortedBy { opPriority(it.type) }
        return SyncPlan(ops = ordered, scannedLocal = local.size, scannedRemote = remote.size)
    }

    // ---------------------------------------------------------------- Execute

    /** 依计划执行；[onProgress] 回调 (已完成数, 总数, 当前操作描述)。 */
    suspend fun execute(
        config: SyncConfig,
        plan: SyncPlan,
        onProgress: (done: Int, total: Int, label: String) -> Unit
    ): SyncSummary {
        val vault = requireVault()

        var uploaded = 0
        var downloaded = 0
        var deletedRemote = 0
        var trashedLocal = 0
        var conflictCopies = 0
        var failed = 0
        val failedPaths = HashSet<String>()
        val logs = ArrayList<SyncLogEntity>()
        val total = plan.ops.size
        var progressIndex = 0
        val client = buildClient(config) { attempt ->
            onProgress(progressIndex, total, "触发服务端限流，自动等待重试（第 $attempt 次）…")
        }

        plan.ops.forEachIndexed { index, op ->
            progressIndex = index
            onProgress(index, total, "${op.type.label()} ${op.path}")
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
                    SyncOpType.UPLOAD -> uploaded++
                    SyncOpType.DOWNLOAD -> downloaded++
                    SyncOpType.DELETE_REMOTE -> deletedRemote++
                    SyncOpType.TRASH_LOCAL -> trashedLocal++
                    SyncOpType.CONFLICT_COPY -> conflictCopies++
                }
            } else {
                failed++
                failedPaths += op.path
            }
            logs += SyncLogEntity(
                ts = System.currentTimeMillis(),
                op = op.type.name,
                path = op.path,
                result = if (ok) "OK" else "FAIL",
                detail = error ?: op.detail
            )
        }

        onProgress(total, total, "正在提交同步基线…")
        withContext(Dispatchers.IO) {
            syncLogDao.insertAll(logs)
            syncLogDao.trimTo(500)
        }
        // CommitBaseline：重新快照两端状态；失败路径不写基线，下一轮重新决策
        var baselineError: String? = null
        try {
            withContext(Dispatchers.IO) { commitBaseline(vault, client, failedPaths) }
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

        return SyncSummary(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedRemote = deletedRemote,
            trashedLocal = trashedLocal,
            conflictCopies = conflictCopies,
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
            SyncOpType.TRASH_LOCAL -> moveToTrash(vaultRoot, op.path)
            SyncOpType.CONFLICT_COPY -> {
                val target = conflictTarget(vaultRoot, op.path)
                if (op.detail == "local") {
                    val source = File(vaultRoot, op.path)
                    source.isFile && runCatching {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = false)
                        true
                    }.getOrDefault(false)
                } else {
                    client.download(op.path, target)
                }
            }
        }

    // ---------------------------------------------------------------- 基线

    private suspend fun commitBaseline(
        vaultRoot: String,
        client: WebDavClient,
        excludePaths: Set<String>
    ) {
        val local = scanLocal(vaultRoot)
        val remote = client.listAll()
            .filter { !it.isDirectory && !isIgnoredPath(it.path) }
            .associateBy { it.path }
        val now = System.currentTimeMillis()
        val entries = ArrayList<SyncBaselineEntity>()
        for ((path, l) in local) {
            if (path in excludePaths) continue
            val r = remote[path] ?: continue
            entries += SyncBaselineEntity(
                path = path,
                localSize = l.size,
                localMtime = l.mtime,
                remoteSize = r.size,
                remoteMtime = r.lastModified,
                remoteEtag = r.etag,
                syncedAt = now
            )
        }
        baselineDao.replaceAll(entries)
    }

    // ---------------------------------------------------------------- 文件系统辅助

    private fun scanLocal(vaultRoot: String): Map<String, LocalStat> {
        val root = File(vaultRoot)
        if (!root.isDirectory) throw IOException("Vault 目录不存在或不可读")
        val out = HashMap<String, LocalStat>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack += root to ""
        while (stack.isNotEmpty()) {
            val (dir, prefix) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (isIgnoredName(f.name)) continue
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (f.isDirectory) {
                    stack += f to rel
                } else if (f.isFile) {
                    out[rel] = LocalStat(f.length(), f.lastModified())
                }
            }
        }
        return out
    }

    /** 同步过滤（§6.3 默认模板）：'.' 开头（.obsidian/.trash/.git 等）与 *.tmp 不参与同步。 */
    private fun isIgnoredName(name: String): Boolean =
        name.startsWith(".") || name.endsWith(".tmp", ignoreCase = true)

    private fun isIgnoredPath(path: String): Boolean =
        path.split('/').any { isIgnoredName(it) }

    /** 被远端删除波及的本地文件移入 App 私有 trash/（§6.5-3），不物理删除。 */
    private fun moveToTrash(vaultRoot: String, relativePath: String): Boolean {
        val source = File(vaultRoot, relativePath)
        if (!source.isFile) return false
        val target = File(context.filesDir, "trash/${conflictStamp()}/$relativePath")
        return runCatching {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
            source.delete()
        }.getOrDefault(false)
    }

    /** 冲突副本路径：`名称 (conflict yyyy-MM-dd HHmmss).md`，重名自动加序号。 */
    private fun conflictTarget(vaultRoot: String, relativePath: String): File {
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
        return candidate
    }

    private fun conflictStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.getDefault()).format(Date())

    private fun opPriority(type: SyncOpType): Int = when (type) {
        SyncOpType.UPLOAD -> 0
        SyncOpType.CONFLICT_COPY -> 1
        SyncOpType.DOWNLOAD -> 2
        SyncOpType.TRASH_LOCAL -> 3
        SyncOpType.DELETE_REMOTE -> 4
    }

    // ---------------------------------------------------------------- 基础依赖

    private suspend fun requireVault(): String {
        val vault = settingsRepository.settings.first().vaultPath
        if (vault.isNullOrBlank()) throw IOException("未选择 Vault 目录")
        return vault
    }

    private fun buildClient(
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
}
