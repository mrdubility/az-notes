package com.az.notes.util

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * 系统文件夹选择器（SAF）路径转换。
 *
 * 门禁页用系统自带选择器（OpenDocumentTree）让用户点选 Vault 文件夹，
 * 返回的树 Uri 在外部存储 DocumentsProvider 下的 docId 形如
 * `primary:Documents/Obsidian`（或 `raw:/storage/...`），
 * 可直接换算为文件系统绝对路径——与 VaultRepository 的 java.io.File 存取配套。
 */
object SafPathUtils {

    /** 将 SAF 树 Uri 转为绝对路径；非外部存储提供方等无法识别时返回 null。 */
    fun treeUriToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null

        // 部分文件管理器（如 MT 管理器）返回 raw: 前缀的绝对路径
        if (docId.startsWith("raw:")) {
            return docId.removePrefix("raw:").trim().ifBlank { null }
        }

        val parts = docId.split(':', limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return null
        val volume = parts[0]
        val relative = parts[1].trim('/')
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            // SD 卡 / U 盘等：卷标即 /storage/<volumeId>
            "/storage/$volume"
        }
        return if (relative.isBlank()) base else "$base/$relative"
    }
}
