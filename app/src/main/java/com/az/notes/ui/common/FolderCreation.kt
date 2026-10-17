package com.az.notes.ui.common

import com.az.notes.R
import com.az.notes.data.storage.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 目录选择对话框内「新建文件夹」的结果：
 * - [Created] 携带实际落盘绝对路径（名称可能经清洗而与输入不同），调用方据此选中；
 * - [Failed] 携带本地化失败原因，在输入框下方就地展示（对话框不关闭）。
 */
sealed interface CreateFolderResult {
    data class Created(val absolutePath: String) : CreateFolderResult
    data class Failed(val message: UiText) : CreateFolderResult
}

/**
 * 在 [parentPath] 下按用户输入新建文件夹（移动 / 导出 / 分享目录等选择器共用）：
 * 名称经 [VaultRepository.sanitizeEntryName] 清洗（过滤路径分隔符与非法字符、
 * 拒绝空名与 '.' 开头），同名文件或目录已存在时按失败处理（mkdirs 对已存在目录
 * 返回 false，故需先判定以区分“重名”与“写盘失败”）。
 */
suspend fun createVaultSubfolder(
    repository: VaultRepository,
    parentPath: String,
    input: String
): CreateFolderResult = withContext(Dispatchers.IO) {
    val name = VaultRepository.sanitizeEntryName(input)
        ?: return@withContext CreateFolderResult.Failed(UiText.of(R.string.msg_invalid_name))
    val target = File(parentPath, name)
    if (target.exists()) {
        return@withContext CreateFolderResult.Failed(UiText.of(R.string.msg_folder_exists))
    }
    if (!repository.createDirectory(target.absolutePath)) {
        return@withContext CreateFolderResult.Failed(UiText.of(R.string.msg_folder_create_failed))
    }
    CreateFolderResult.Created(target.absolutePath)
}
