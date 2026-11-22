package com.az.notes.domain.notes

import com.az.notes.domain.model.FileNode

/**
 * 主页列表装配的纯函数（不读写文件系统，便于 JVM 单测）：
 * 置顶笔记切分 与 「启动时进入文件夹」的目录解析。
 */

/**
 * 把已排好序（含保序快照）的笔记切分为「置顶区」与「普通区」。
 *
 * 置顶记录是相对仓库根的路径，而一篇笔记只会出现在其父目录的列表里，
 * 因此按成员判定即天然满足「置顶仅在所在文件夹生效」；
 * 切分（而非复制）后置顶笔记不再出现在普通区，列表内不会重复显示。
 *
 * @return 置顶区与普通区，两者各自保持 [notes] 中的相对顺序
 */
fun splitPinnedNotes(
    notes: List<FileNode>,
    pinnedPaths: Set<String>
): Pair<List<FileNode>, List<FileNode>> {
    if (pinnedPaths.isEmpty()) return emptyList<FileNode>() to notes
    val pinned = ArrayList<FileNode>()
    val rest = ArrayList<FileNode>(notes.size)
    for (note in notes) {
        if (note.relativePath in pinnedPaths) pinned += note else rest += note
    }
    return pinned to rest
}

/**
 * 启动目录解析：相对仓库根的路径 → 绝对路径。
 * 越界（绝对路径 / `..` / `.` / 空段）或目录不存在时返回 null，调用方回落根目录
 * （不清除偏好：目录可能因未挂载、同步中而临时不可用）。
 *
 * @param isDirectory 目录存在性判定（由调用方注入，IO 实现留在外部）
 */
fun resolveStartFolder(
    vaultPath: String,
    relative: String?,
    isDirectory: (String) -> Boolean
): String? {
    val raw = relative?.trim()?.trimEnd('/') ?: return null
    val root = vaultPath.trimEnd('/')
    if (raw.isEmpty() || raw.startsWith("/") || root.isEmpty()) return null
    val segments = raw.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
    val absolute = "$root/" + segments.joinToString("/")
    return if (isDirectory(absolute)) absolute else null
}
