package com.az.notes.data.storage

import android.content.Context
import com.az.notes.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 回收站中的一个条目（[file] 为 App 私有 trash 目录下的实际文件/目录）。 */
data class TrashItem(
    /** 相对仓库根的原始路径（恢复时按此回位）。 */
    val relativePath: String,
    val file: File,
    val size: Long,
    /** 目录条目（删除的是空文件夹）：恢复时仅重建空目录。 */
    val isDirectory: Boolean = false
)

/** 一次删除 / 同步批次产生的回收站分组（批目录的修改时间即入站时间）。 */
data class TrashBatch(
    val id: String,
    val trashedAt: Long,
    val items: List<TrashItem>
)

/**
 * 回收站仓库（§6.5-3）：把 Vault 内被删除的文件 / 目录移入
 * `filesDir/trash/<仓库 id>/<批次>/<相对路径>`，供用户查看 / 恢复 / 永久删除。
 * App 内删除与同步删除共用 [moveToTrash]；过期批次清理由同步启动前的
 * [purgeExpired] 负责（保留天数可在设置调整，0 = 永不清理）。
 *
 * 多仓库：批次按仓库 id 分目录隔离（不同仓库的同名相对路径互不冲突），
 * 列表 / 清空均只作用于当前仓库；旧版一层结构（trash/<批次>）由
 * [migrateLegacyTrash] 一次性搬入当前仓库目录。
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    private fun root(): File = File(context.filesDir, "trash")

    /**
     * 把 Vault 内的文件 / 目录移入回收站（删除的唯一入口，不物理删除）。
     * [absolutePath] 必须严格位于 [vaultPath] 内；批次名取当前时刻（同秒合并），
     * 先复制后删源；同批次出现同名副本时自动加序号，不覆盖已有回收内容。
     * 返回入站后的条目（失败为 null），供调用方定位与撤销恢复。
     */
    suspend fun moveToTrash(vaultPath: String, absolutePath: String): TrashItem? {
        val vaultId = vaultIdOf(vaultPath) ?: return null
        val vault = File(vaultPath).absoluteFile
        val source = File(absolutePath).absoluteFile
        val relativePath = relativeToVault(vault, source) ?: return null
        if (!source.exists()) return null
        return runCatching {
            val batch = File(File(root(), vaultId), stampNow())
            val target = uniqueMoveTarget(batch, relativePath)
            target.parentFile?.mkdirs()
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = false)
                source.deleteRecursively()
            } else {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
            TrashItem(
                relativePath = target.absolutePath
                    .removePrefix(batch.absolutePath)
                    .trimStart('/', '\\'),
                file = target,
                size = if (target.isFile) target.length() else 0L,
                isDirectory = target.isDirectory
            )
        }.getOrNull()
    }

    /**
     * 清理入站时间早于 [retentionDays] 天的批次（同步启动前调用）。
     * [retentionDays] <= 0 表示永不清理；同时兼容旧版一层结构。
     */
    fun purgeExpired(retentionDays: Int) {
        if (retentionDays <= 0) return
        runCatching {
            val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
            root().listFiles()?.forEach { first ->
                if (!first.isDirectory) return@forEach
                if (STAMP_REGEX.matches(first.name)) {
                    // 旧版一层结构：直接子级即批次
                    if (first.lastModified() < cutoff) first.deleteRecursively()
                } else {
                    // 多仓库两层结构：<仓库 id>/<批次>
                    first.listFiles()?.forEach { batch ->
                        if (batch.isDirectory && batch.lastModified() < cutoff) batch.deleteRecursively()
                    }
                    if (first.listFiles().isNullOrEmpty()) first.delete()
                }
            }
        }
    }

    /** 指定仓库的全部批次（新 → 旧；空批次跳过）。条目 = 所有文件 + 空目录（空文件夹也可恢复）。 */
    fun batches(vaultId: String?): List<TrashBatch> {
        if (vaultId.isNullOrBlank() || vaultId == SettingsRepository.LEGACY_VAULT_ID) return emptyList()
        return runCatching {
            File(root(), vaultId).listFiles()?.filter { it.isDirectory }?.mapNotNull { batch ->
                val items = batch.walkTopDown()
                    .filter { it != batch && (it.isFile || it.isDirectory && it.listFiles().isNullOrEmpty()) }
                    .map { file ->
                        TrashItem(
                            relativePath = file.absolutePath
                                .removePrefix(batch.absolutePath)
                                .trimStart('/', '\\'),
                            file = file,
                            size = if (file.isFile) file.length() else 0L,
                            isDirectory = file.isDirectory
                        )
                    }
                    .sortedBy { it.relativePath }
                    .toList()
                if (items.isEmpty()) null else TrashBatch(batch.name, batch.lastModified(), items)
            }?.sortedByDescending { it.trashedAt } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 恢复到 Vault 原相对路径；目标已存在时自动加 ` (restored)` 后缀，绝不覆盖现有文件。
     * 目录条目仅重建空目录；恢复后清理批次内的空骨架目录与空批次。
     * 返回实际恢复到的目标（失败为 null），供调用方定位与撤销。
     */
    fun restore(vaultPath: String, item: TrashItem): File? = runCatching {
        val target = uniqueRestoreTarget(File(vaultPath, item.relativePath))
        if (item.isDirectory) {
            target.mkdirs()
            item.file.delete()
        } else {
            target.parentFile?.mkdirs()
            item.file.copyTo(target, overwrite = false)
            item.file.delete()
        }
        cleanupAfterRemove(item.file)
        target
    }.getOrNull()

    /** 永久删除单个条目（二次确认由 UI 负责）；顺带清理空骨架与空批次。 */
    fun delete(item: TrashItem): Boolean = runCatching {
        val ok = if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
        cleanupAfterRemove(item.file)
        ok
    }.getOrDefault(false)

    /** 清空指定仓库的回收站（二次确认由 UI 负责）；null / 无效仓库不动作。 */
    fun purgeAll(vaultId: String?): Boolean {
        if (vaultId.isNullOrBlank() || vaultId == SettingsRepository.LEGACY_VAULT_ID) return false
        return runCatching { File(root(), vaultId).deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 一次性迁移（幂等）：旧版一层批次目录（trash/<批次>）→ trash/<仓库 id>/ 下。
     * 同名批次（极端：迁移前同秒产生过新结构批次）追加序号，绝不合并覆盖。
     */
    fun migrateLegacyTrash(vaultId: String) {
        if (vaultId == SettingsRepository.LEGACY_VAULT_ID) return
        runCatching {
            val root = root()
            val target = File(root, vaultId).apply { mkdirs() }
            root.listFiles()?.forEach { legacy ->
                if (!legacy.isDirectory || !STAMP_REGEX.matches(legacy.name)) return@forEach
                val dest = File(target, legacy.name)
                val finalDest = if (dest.exists()) uniqueMoveTarget(target, legacy.name) else dest
                if (!legacy.renameTo(finalDest)) {
                    legacy.copyRecursively(finalDest, overwrite = false)
                    legacy.deleteRecursively()
                }
            }
            if (target.listFiles().isNullOrEmpty()) target.delete()
        }
    }

    // ---------------------------------------------------------------- 内部工具

    /** 由仓库路径反查注册表 id；迁移窗口先补建档（见 [SettingsRepository.requireCurrentVaultId]）。 */
    private suspend fun vaultIdOf(vaultPath: String): String? {
        val settings = settingsRepository.settings.first()
        val normalized = vaultPath.trimEnd('/')
        settings.vaults
            .firstOrNull { it.path == normalized && it.id != SettingsRepository.LEGACY_VAULT_ID }
            ?.let { return it.id }
        // 路径未命中注册表时兜底用当前仓库，保证删除不被阻断
        return settingsRepository.requireCurrentVaultId()
    }

    /** 目标路径必须严格位于 Vault 内（Vault 自身或外部路径 → null）。 */
    private fun relativeToVault(vault: File, target: File): String? {
        val prefix = vault.path + File.separator
        if (!target.path.startsWith(prefix)) return null
        return target.path.removePrefix(prefix)
    }

    /** 同批次内已存在同名副本（极端场景：同秒重复删除同一路径）时追加序号。 */
    private fun uniqueMoveTarget(batch: File, relativePath: String): File {
        val direct = File(batch, relativePath)
        if (!direct.exists()) return direct
        val name = direct.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = File(direct.parentFile, "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /** 批次名 = 当前时刻（yyyy-MM-dd HHmmss）；同秒内的多次删除合并进同一批次。 */
    private fun stampNow(): String =
        SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.getDefault()).format(Date())

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

    /** 条目移除后：先向上清理空骨架目录，批次内再无文件 / 空目录时删除批次（含空仓库目录）。 */
    private fun cleanupAfterRemove(removed: File) {
        val rootPath = root().absolutePath
        val batchDir = batchDirOf(removed) ?: return
        var dir = removed.parentFile
        while (dir != null && dir.absolutePath != batchDir.absolutePath &&
            dir.absolutePath.startsWith(rootPath)
        ) {
            if (dir.listFiles().isNullOrEmpty()) {
                if (!dir.delete()) break
                dir = dir.parentFile
            } else break
        }
        val empty = batchDir.walkTopDown().none {
            it.isFile || (it != batchDir && it.isDirectory && it.listFiles().isNullOrEmpty())
        }
        if (empty) {
            batchDir.deleteRecursively()
            val vaultDir = batchDir.parentFile
            if (vaultDir != null && vaultDir.absolutePath != rootPath &&
                vaultDir.listFiles().isNullOrEmpty()
            ) {
                vaultDir.delete()
            }
        }
    }

    /** [deleted] 所在批次目录（新结构 trash/<仓库 id>/<批次> 或旧结构 trash/<批次>）；找不到返回 null。 */
    private fun batchDirOf(deleted: File): File? {
        val rootPath = root().absolutePath
        var dir = deleted.parentFile
        while (dir != null && dir.absolutePath.startsWith(rootPath) && dir.absolutePath != rootPath) {
            val parent = dir.parentFile
            if (parent != null && (parent.absolutePath == rootPath ||
                    parent.parentFile?.absolutePath == rootPath)
            ) {
                return dir
            }
            dir = parent
        }
        return null
    }

    private companion object {
        /** 批次目录名格式（yyyy-MM-dd HHmmss）。 */
        val STAMP_REGEX = Regex("""^\d{4}-\d{2}-\d{2} \d{6}$""")
    }
}
