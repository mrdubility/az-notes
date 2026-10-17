package com.az.notes.ui.common

import com.az.notes.R
import com.az.notes.data.storage.VaultRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 目录选择器「新建文件夹」共享逻辑单测（临时目录构造仓库树，纯文件系统逻辑，JVM 直跑）：
 * 覆盖名称清洗（路径分隔符过滤后落盘）、同名已存在拒绝（区分重名与写盘失败）、
 * 空名与 '.' 开头拒绝。四个选择器（移动 / 导出 / 分享）共用同一 helper，测试一份即覆盖。
 */
class FolderCreationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repo = VaultRepository()

    @Test
    fun `creates folder with sanitized name`() = runBlocking {
        val root = temp.newFolder("vault")

        val result = createVaultSubfolder(repo, root.absolutePath, "a/b")
        assertTrue(result is CreateFolderResult.Created)

        val created = File((result as CreateFolderResult.Created).absolutePath)
        assertEquals("ab", created.name)
        assertTrue(created.isDirectory)
    }

    @Test
    fun `rejects duplicate name as already exists`() = runBlocking {
        val root = temp.newFolder("vault")
        File(root, "docs").mkdirs()

        val result = createVaultSubfolder(repo, root.absolutePath, "docs")
        assertTrue(result is CreateFolderResult.Failed)
        assertEquals(
            R.string.msg_folder_exists,
            (result as CreateFolderResult.Failed).message.resId
        )
    }

    @Test
    fun `rejects blank and dot prefixed names`() = runBlocking {
        val root = temp.newFolder("vault")

        for (input in listOf("", "   ", ".hidden", "./x")) {
            val result = createVaultSubfolder(repo, root.absolutePath, input)
            assertTrue("input=$input", result is CreateFolderResult.Failed)
            assertEquals(
                "input=$input",
                R.string.msg_invalid_name,
                (result as CreateFolderResult.Failed).message.resId
            )
        }
        // 全部被拒绝：父目录下没有产生任何子项
        assertEquals(0, root.listFiles()?.size ?: 0)
    }
}
