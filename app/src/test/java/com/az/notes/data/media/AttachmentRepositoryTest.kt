package com.az.notes.data.media

import com.az.notes.data.storage.VaultRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 附件引用索引 / 孤儿判定单测（临时目录构造仓库树，纯文件系统逻辑，JVM 直跑）：
 * 覆盖同目录 assets 引用、仅文件名嵌入、孤儿判定、独占引用计数、超大笔记跳过并计入未扫描，
 * 以及 md 互链过滤、含空格文件名的原始 / 百分号编码两种引用形态。
 */
class AttachmentRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repo = AttachmentRepository(VaultRepository())

    private fun File.write(bytes: Int): File {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes) { 'x'.code.toByte() })
        return this
    }

    @Test
    fun `referenced asset is not orphan and counts as exclusive`() {
        val vault = temp.newFolder("v1")
        val note = File(vault, "note.md")
        note.writeText("![a](assets/pic.png)")
        File(vault, "assets/pic.png").write(32)

        val orphans = repo.orphans(vault)
        assertEquals(0, orphans.orphans.size)

        val refs = repo.referencedByNote(vault, note.absolutePath)
        assertEquals(1, refs.total)
        assertEquals(1, refs.exclusiveCount)
        assertTrue(refs.hasExclusive)
        assertEquals("pic.png", refs.exclusiveFiles.single().name)
    }

    @Test
    fun `unreferenced image is reported as orphan`() {
        val vault = temp.newFolder("v2")
        File(vault, "note.md").writeText("no image here")
        val loose = File(vault, "assets/loose.png").write(50)

        val report = repo.orphans(vault)
        assertEquals(1, report.orphans.size)
        assertEquals(loose.absolutePath, report.orphans.single().absolutePath)
        assertEquals(50L, report.totalBytes)
    }

    @Test
    fun `wiki embed by bare name resolves via name index`() {
        val vault = temp.newFolder("v3")
        val note = File(vault, "sub/note.md")
        note.parentFile!!.mkdirs()
        note.writeText("![[diagram.png]]")
        val img = File(vault, "sub/assets/diagram.png").write(64)

        val refs = repo.referencedByNote(vault, note.absolutePath)
        assertEquals(1, refs.total)
        assertEquals(img.absolutePath, refs.exclusiveFiles.single().absolutePath)
        assertEquals(0, repo.orphans(vault).orphans.size)
    }

    @Test
    fun `shared asset is not exclusive to one note`() {
        val vault = temp.newFolder("v4")
        val a = File(vault, "a.md").apply { writeText("![x](assets/shared.png)") }
        val b = File(vault, "b.md").apply { writeText("![y](assets/shared.png)") }
        File(vault, "assets/shared.png").write(40)

        val refsA = repo.referencedByNote(vault, a.absolutePath)
        assertEquals(1, refsA.total)
        assertEquals(0, refsA.exclusiveCount)
        assertTrue(refsB(vault, b).total == 1)
    }

    private fun refsB(vault: File, b: File) = repo.referencedByNote(vault, b.absolutePath)

    @Test
    fun `oversized note is skipped and counted as unscanned`() {
        val vault = temp.newFolder("v5")
        // 远超 CONTENT_SEARCH_MAX_BYTES 的笔记：正文不纳入解析，其引用的图片被判孤儿并计数
        val big = StringBuilder("![z](assets/big_only.png)\n")
        while (big.length <= VaultRepository.CONTENT_SEARCH_MAX_BYTES.toInt()) big.append("padding padding padding\n")
        File(vault, "huge.md").writeText(big.toString())
        File(vault, "assets/big_only.png").write(30)

        val report = repo.orphans(vault)
        assertEquals(1, report.unscannedNotes)
        assertEquals(1, report.orphans.size)
    }

    @Test
    fun `markdown cross-link is not counted as attachment`() {
        val vault = temp.newFolder("v6")
        File(vault, "other.md").writeText("hello")
        val note = File(vault, "note.md")
        note.writeText("[跳转](other.md)\n![a](assets/pic.png)")
        File(vault, "assets/pic.png").write(32)

        val refs = repo.referencedByNote(vault, note.absolutePath)
        // 笔记互链不计入附件统计（否则删除 / 移动联动会误伤被链接的笔记）
        assertEquals(1, refs.total)
        assertEquals(1, refs.exclusiveCount)
        assertEquals("pic.png", refs.exclusiveFiles.single().name)
    }

    @Test
    fun `space file name referenced raw or percent encoded is not orphan`() {
        val vault = temp.newFolder("v7")
        File(vault, "assets/my photo.png").write(32)
        val rawNote = File(vault, "raw.md").apply { writeText("![a](my photo.png)") }
        val encNote = File(vault, "enc.md").apply { writeText("![a](my%20photo.png)") }

        assertEquals(0, repo.orphans(vault).orphans.size)
        assertEquals(1, repo.referencedByNote(vault, rawNote.absolutePath).total)
        val refsEnc = repo.referencedByNote(vault, encNote.absolutePath)
        assertEquals(1, refsEnc.total)
        // 同一附件被两篇笔记引用：都不独占
        assertEquals(0, refsEnc.exclusiveCount)
    }
}
