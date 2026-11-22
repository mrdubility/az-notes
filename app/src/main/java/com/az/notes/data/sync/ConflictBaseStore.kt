package com.az.notes.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步祖先内容缓存（"上一次同步成功时"的文本内容）。
 *
 * 目录布局：`filesDir/sync-base/<vaultId>/<相对路径>`，与 Vault 完全隔离。
 *
 * 存在理由：冲突合并需要**三方**（祖先 / 本地 / 远端）才能判断"哪一段是谁改的"。
 * WebDAV 没有版本历史，同步基线只存 size + mtime + etag，因此祖先内容必须由本应用
 * 自己留一份。没有祖先时只能退回"择一覆盖 + 备份"，无法做同文件内合并。
 *
 * 容量：单篇 ≤ [MAX_FILE_BYTES]，仓库总量 ≤ [MAX_TOTAL_BYTES]，
 * 超限按最近写入时间淘汰最旧的祖先。
 */
@Singleton
class ConflictBaseStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 是否有该笔记的祖先内容。 */
    fun has(vaultId: String, relativePath: String): Boolean = pathOf(vaultId, relativePath).isFile

    /** 读取祖先内容；不存在或读取失败返回 null（调用方据此退回无祖先分支）。 */
    fun read(vaultId: String, relativePath: String): String? = runCatching {
        val file = pathOf(vaultId, relativePath)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return@runCatching null
        file.readText()
    }.getOrNull()

    /** 记录当前内容为新的祖先（文本类、且不超单篇上限）。 */
    fun save(vaultId: String, relativePath: String, text: String) {
        if (text.length > MAX_FILE_BYTES) return
        runCatching {
            val target = pathOf(vaultId, relativePath)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
        purge(vaultId)
    }

    /** 删除单篇祖先（笔记被删除 / 移入回收站）。 */
    fun remove(vaultId: String, relativePath: String) {
        runCatching { pathOf(vaultId, relativePath).delete() }
    }

    /**
     * 删除某个目录前缀下的全部祖先（目录改名 / 目录删除）。
     * [relativeDir] 为仓库根相对目录路径，空串表示整个仓库。
     */
    fun removeTree(vaultId: String, relativeDir: String) {
        runCatching {
            val root = File(rootDir(), vaultId)
            if (relativeDir.isBlank()) {
                root.deleteRecursively()
            } else {
                File(root, relativeDir).deleteRecursively()
            }
        }
    }

    /** 总量超限时按最久未写入淘汰。 */
    fun purge(vaultId: String, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        runCatching {
            val root = File(rootDir(), vaultId)
            val files = root.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= maxTotalBytes) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= maxTotalBytes) return@forEach
                total -= file.length()
                file.delete()
            }
        }
    }

    /** 祖先文件落点（`..` 与空段剔除，保证不越出私有目录）。 */
    private fun pathOf(vaultId: String, relativePath: String): File {
        val segments = relativePath.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val root = File(rootDir(), vaultId)
        return if (segments.isEmpty()) File(root, FALLBACK_NAME) else File(root, segments.joinToString("/"))
    }

    private fun rootDir(): File = File(context.filesDir, DIR_NAME)

    private companion object {
        const val DIR_NAME = "sync-base"
        const val FALLBACK_NAME = "base.txt"

        /** 单篇祖先上限：超过不缓存（合并时退回"择一覆盖 + 备份"）。 */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024

        /** 单仓库祖先总量上限。 */
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    }
}
