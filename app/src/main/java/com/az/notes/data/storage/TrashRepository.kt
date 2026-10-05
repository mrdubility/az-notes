package com.az.notes.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 回收站中的一个文件条目（[file] 为 App 私有 trash 目录下的实际文件）。 */
data class TrashItem(
    /** 相对 Vault 根的原始路径（恢复时按此回位）。 */
    val relativePath: String,
    val file: File,
    val size: Long
)

/** 一次同步批次产生的回收站分组（批目录的修改时间即入站时间）。 */
data class TrashBatch(
    val id: String,
    val trashedAt: Long,
    val items: List<TrashItem>
)

/**
 * 回收站仓库（§6.5-3）：读取 / 恢复 / 删除被远端删除波及而移入
 * `filesDir/trash/<批次>/<相对路径>` 的本地文件。
 * 30 天过期清理由同步启动前的 purgeTrash 负责，这里只做用户侧操作。
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private fun root(): File = File(context.filesDir, "trash")

    /** 全部批次（新 → 旧；空批次跳过）。 */
    fun batches(): List<TrashBatch> = runCatching {
        root().listFiles()?.filter { it.isDirectory }?.mapNotNull { batch ->
            val items = batch.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    TrashItem(
                        relativePath = file.absolutePath
                            .removePrefix(batch.absolutePath)
                            .trimStart('/', '\\'),
                        file = file,
                        size = file.length()
                    )
                }
                .sortedBy { it.relativePath }
                .toList()
            if (items.isEmpty()) null else TrashBatch(batch.name, batch.lastModified(), items)
        }?.sortedByDescending { it.trashedAt } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 恢复到 Vault 原相对路径；目标已存在时自动加 ` (restored)` 后缀，绝不覆盖现有文件。
     * 恢复后若批次目录已空则一并清理。
     */
    fun restore(vaultPath: String, item: TrashItem): Boolean = runCatching {
        val target = uniqueRestoreTarget(File(vaultPath, item.relativePath))
        target.parentFile?.mkdirs()
        item.file.copyTo(target, overwrite = false)
        item.file.delete()
        cleanupEmptyBatch(item.file)
        true
    }.getOrDefault(false)

    /** 永久删除单个条目（二次确认由 UI 负责）；顺带清理空批次目录。 */
    fun delete(item: TrashItem): Boolean = runCatching {
        val ok = item.file.delete()
        cleanupEmptyBatch(item.file)
        ok
    }.getOrDefault(false)

    /** 清空整个回收站（二次确认由 UI 负责）。 */
    fun purgeAll(): Boolean = runCatching { root().deleteRecursively() }.getOrDefault(false)

    /** 目标已存在时加 ` (restored)` / ` (restored n)` 后缀，避免覆盖现有文件。 */
    private fun uniqueRestoreTarget(original: File): File {
        if (!original.exists()) return original
        val ext = original.name.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) original.name else original.name.removeSuffix(".$ext")
        var index = 1
        while (true) {
            val suffix = if (index == 1) " (restored)" else " (restored $index)"
            val name = if (ext.isEmpty()) "$base$suffix" else "$base$suffix.$ext"
            val candidate = File(original.parentFile, name)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /** 批次目录已无可恢复文件时删除之（保持回收站整洁）。 */
    private fun cleanupEmptyBatch(deleted: File) {
        val rootPath = root().absolutePath
        var dir = deleted.parentFile
        var batchDir: File? = null
        while (dir != null && dir.absolutePath.startsWith(rootPath)) {
            if (dir.parentFile?.absolutePath == rootPath) {
                batchDir = dir
                break
            }
            dir = dir.parentFile
        }
        if (batchDir != null && batchDir.walkTopDown().none { it.isFile }) {
            batchDir.deleteRecursively()
        }
    }
}
