package com.az.notes.data.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 相册保存命名去重单测（API 26–28 旧媒体库模型的「 (n)」后缀兜底）：
 * 无冲突保持原名；冲突递增序号；无扩展名文件名与多点文件名保持正确切分。
 */
class GallerySaverTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `keeps original name when free`() {
        val dir = temp.newFolder()
        assertEquals("pic.png", GallerySaver.uniqueFile(dir, "pic.png").name)
    }

    @Test
    fun `appends index on conflict`() {
        val dir = temp.newFolder()
        File(dir, "pic.png").writeBytes(byteArrayOf(1))
        assertEquals("pic (1).png", GallerySaver.uniqueFile(dir, "pic.png").name)
    }

    @Test
    fun `increments index until free`() {
        val dir = temp.newFolder()
        File(dir, "pic.jpg").writeBytes(byteArrayOf(1))
        File(dir, "pic (1).jpg").writeBytes(byteArrayOf(1))
        assertEquals("pic (2).jpg", GallerySaver.uniqueFile(dir, "pic.jpg").name)
    }

    @Test
    fun `keeps name without extension intact`() {
        val dir = temp.newFolder()
        File(dir, "photo").writeBytes(byteArrayOf(1))
        assertEquals("photo (1)", GallerySaver.uniqueFile(dir, "photo").name)
    }

    @Test
    fun `keeps last extension for multi dot names`() {
        val dir = temp.newFolder()
        File(dir, "archive.tar.gz").writeBytes(byteArrayOf(1))
        assertEquals("archive.tar (1).gz", GallerySaver.uniqueFile(dir, "archive.tar.gz").name)
    }
}
