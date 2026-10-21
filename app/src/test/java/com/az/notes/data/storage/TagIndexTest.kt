package com.az.notes.data.storage

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 全库标签倒排索引单测（真实临时目录文件，JVM 直跑）。
 * 覆盖：构建 / 大小写合并 / 展示名 / 计数、frontmatter tags 并入、
 * 增量刷新（改动重扫 / 删除清理 / 未变跳过）、仓库切换重建、倒排查询。
 */
class TagIndexTest {

    private lateinit var root: File
    private lateinit var index: TagIndex

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tag-index-test").toFile()
        index = TagIndex(VaultRepository())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun note(rel: String, content: String): File {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `build merges case insensitively and counts docs`() = runBlocking {
        note("a.md", "#TagA #tagB")
        note("b.md", "#taga 文本")
        note("c.md", "#tagB")
        index.buildOrRefresh(root.absolutePath)

        val all = index.all()
        // 大小写合并：TagA/taga 同 key（展示首次出现的写法），计数合并
        assertEquals(2, all.first { it.name.lowercase() == "taga" }.count)
        assertEquals(2, all.first { it.name.lowercase() == "tagb" }.count)
        assertEquals(2, all.size)
    }

    @Test
    fun `frontmatter tags included`() = runBlocking {
        note("a.md", "---\ntags:\n  - recipe\n  - cooking\n---\n正文 #recipe")
        index.buildOrRefresh(root.absolutePath)

        assertEquals(1, index.all().first { it.name.lowercase() == "recipe" }.count)
        assertEquals(1, index.all().first { it.name.lowercase() == "cooking" }.count)
    }

    @Test
    fun `frontmatter tags with hash prefix and invalid values filtered`() = runBlocking {
        note("a.md", "---\ntags: [x, #y, 1984, \"has space\"]\n---\n")
        index.buildOrRefresh(root.absolutePath)

        val names = index.all().map { it.name }
        assertTrue(names.contains("x"))
        assertTrue(names.contains("y"))
        assertTrue(!names.contains("1984"))
        assertTrue(!names.contains("has space"))
    }

    @Test
    fun `docsOf returns matching docs by modification time desc`() = runBlocking {
        val a = note("a.md", "#tag")
        Thread.sleep(10)
        val b = note("b.md", "#tag")
        index.buildOrRefresh(root.absolutePath)

        val docs = index.docsOf("TAG")
        assertEquals(listOf(b.absolutePath, a.absolutePath), docs.map { it.absolutePath })
        assertTrue(index.docsOf("missing").isEmpty())
    }

    @Test
    fun `incremental refresh rescans changed and removes deleted`() = runBlocking {
        note("a.md", "#tagA")
        val b = note("b.md", "#tagB")
        index.buildOrRefresh(root.absolutePath)

        // 修改 b：加 tagA、删 tagB
        Thread.sleep(10)
        b.writeText("#tagA")
        // 删除 a
        File(root, "a.md").delete()
        index.buildOrRefresh(root.absolutePath)

        // tagA 仅剩 b；tagB 随唯一文档消失
        assertEquals(1, index.all().first { it.name.lowercase() == "taga" }.count)
        assertTrue(index.all().none { it.name.equals("tagB", ignoreCase = true) })
    }

    @Test
    fun `unchanged files keep index across refresh`() = runBlocking {
        note("a.md", "#tagA")
        val b = note("b.md", "#tagB")
        index.buildOrRefresh(root.absolutePath)

        // 无变化的刷新：结果一致且展示名保持首次写法
        index.buildOrRefresh(root.absolutePath)
        val all = index.all()
        assertEquals(setOf("tagA", "tagB"), all.map { it.name }.toSet())
        assertEquals(1, all.first { it.name.lowercase() == "taga" }.count)
        assertTrue(b.exists())
    }

    @Test
    fun `vault switch rebuilds from scratch`() = runBlocking {
        note("a.md", "#tagA")
        index.buildOrRefresh(root.absolutePath)

        val root2 = Files.createTempDirectory("tag-index-test2").toFile()
        try {
            File(root2, "x.md").writeText("#tagX")
            index.buildOrRefresh(root2.absolutePath)

            assertEquals(listOf("tagX"), index.all().map { it.name })
            assertTrue(index.docsOf("tagA").isEmpty())
        } finally {
            root2.deleteRecursively()
        }
    }

    @Test
    fun `empty vault yields empty index`() = runBlocking {
        index.buildOrRefresh(root.absolutePath)
        assertTrue(index.all().isEmpty())
    }
}
