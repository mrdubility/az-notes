package com.az.notes.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 笔记仓库（Vault）：一个用户可见的笔记根目录。
 *
 * - [id]：本地生成的稳定标识（换路径 / 重命名不改变），per-vault 配置（收藏夹、分享目录、
 *   WebDAV、回收站、同步数据）均按 id 存储；
 * - [name]：展示名（默认取目录名，可重命名）；
 * - [path]：磁盘绝对路径（规范化后结尾不含 '/'）；
 * - [builtin]：内置默认仓库（App 私有目录）——不可删除，可重命名；
 * - [hidden]：从顶栏切换列表隐藏（仓库管理页始终可见）；仅多仓库且非当前仓库时允许，
 *   切换为当前仓库时自动取消隐藏。
 */
@Serializable
data class VaultInfo(
    val id: String,
    val name: String,
    val path: String,
    val builtin: Boolean = false,
    val hidden: Boolean = false
) {
    companion object {
        /** 生成新的仓库 id（十六进制字符集，不会与内置默认仓库 id "default" 冲突）。 */
        fun newId(): String = "v" + UUID.randomUUID().toString().take(8)
    }
}
