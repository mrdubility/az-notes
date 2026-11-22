package com.az.notes.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 冲突败方的私有备份存储。
 *
 * 目录布局：`filesDir/sync-conflicts/<vaultId>/<yyyyMMdd-HHmmss>/<相对路径>`。
 *
 * 为什么不放回 Vault：Vault 里多一份副本会进链接索引 / 搜索 / 未链接提及，
 * 还会产生"指向副本"的反链；改为私有目录后，即可保留可恢复性，又与用户视线、
 * 索引、同步范围完全隔离（Vault 里始终只有一份笔记）。
 */
@Singleton
class ConflictBackupStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 本次冲突的备份落点（不创建文件）。[relativePath] 中的空段与 `..`
     * 一律剔除，保证不会写出私有目录之外。
     */
    fun targetPath(vaultId: String, relativePath: String): File {
        val segments = relativePath.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val dir = File(File(rootDir(), vaultId), stamp())
        return if (segments.isEmpty()) {
            File(dir, FALLBACK_NAME)
        } else {
            File(dir, segments.joinToString("/"))
        }
    }

    /** 把 [source] 复制为败方备份；源文件不存在或复制失败返回 null。 */
    fun backupFile(vaultId: String, relativePath: String, source: File): File? = runCatching {
        if (!source.isFile) return@runCatching null
        val target = targetPath(vaultId, relativePath)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        target.takeIf { it.isFile }
    }.getOrNull()

    /** 把备份复制回笔记（人工恢复用）；成功返回 true。 */
    fun restore(backup: File, targetPath: String): Boolean = runCatching {
        if (!backup.isFile) return@runCatching false
        val target = File(targetPath)
        target.parentFile?.mkdirs()
        backup.copyTo(target, overwrite = true)
        true
    }.getOrDefault(false)

    /** 清理某仓库超过 [days] 天的备份批次（0 = 不清理）。 */
    fun purgeExpired(vaultId: String, days: Int = KEEP_DAYS) {
        if (days <= 0) return
        runCatching {
            val cutoff = System.currentTimeMillis() - days * DAY_MS
            File(rootDir(), vaultId).listFiles()?.forEach { batch ->
                if (batch.isDirectory && batch.lastModified() < cutoff) batch.deleteRecursively()
            }
        }
    }

    private fun rootDir(): File = File(context.filesDir, DIR_NAME)

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    private companion object {
        const val DIR_NAME = "sync-conflicts"
        const val FALLBACK_NAME = "conflict.bak"
        const val KEEP_DAYS = 7
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
