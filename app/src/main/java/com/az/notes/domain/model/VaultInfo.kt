package com.az.notes.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 笔记仓库（Vault）：一个用户可见的笔记根目录。
 *
 * - [id]：本地生成的稳定标识（换路径 / 重命名不改变），per-vault 配置（收藏夹、分享目录、
 *   WebDAV、回收站、同步数据）均按 id 存储；
 * - [name]：展示名（默认取目录名，可重命名）；
 * - [path]：磁盘绝对路径（规范化后结尾不含 '/'）。
 */
@Serializable
data class VaultInfo(
    val id: String,
    val name: String,
    val path: String
) {
    companion object {
        /** 生成新的仓库 id（十六进制字符集，不会与保留合成 id "legacy" 冲突）。 */
        fun newId(): String = "v" + UUID.randomUUID().toString().take(8)
    }
}
