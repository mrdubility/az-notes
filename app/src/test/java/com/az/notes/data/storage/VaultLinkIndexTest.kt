package com.az.notes.data.storage

import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.RawLinkRef
import com.az.notes.domain.markdown.WikiLinkResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 全库双链索引单测（真实临时目录文件，JVM 直跑）。
 * 覆盖：正向 / 反向解析、别名回退、同名消歧、嵌入过滤、自锚点与自链、
 * 增量刷新（改 / 删 / 新增）、改名后重解析、未解析、大文件索引预算与显式失效。
 */
class VaultLinkIndexTest {

    private lateinit var root: File
    private lateinit var store: MemoryLinkCacheStore
    private lateinit var index: VaultLinkIndex

    @Before
    fun setUp() {
        root = Files.createTempDirectory("link-index-test").toFile()
        store = MemoryLinkCacheStore()
        index = newIndex()
    }

    /** 新建索引实例：模拟冷启动（内存快照为空，只能靠持久化缓存恢复）。 */
    private fun newIndex(): VaultLinkIndex =
        VaultLinkIndex(VaultRepository(), VaultNameIndex(), store)

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

    private fun norm(path: String): String = path.replace('\\', '/')

    @Test
    fun `build resolves both directions`() = runBlocking {
        val a = note("a.md", "[[b]]")
        val b = note("b.md", "[[a]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        assertEquals(listOf(b.absolutePath), index.backlinksOf(a.absolutePath).map { it.sourcePath })
        assertEquals(listOf(a.absolutePath), index.backlinksOf(b.absolutePath).map { it.sourcePath })
        assertEquals("b", index.outLinksOf(a.absolutePath).single().display)
    }

    @Test
    fun `backlink carries readable context`() = runBlocking {
        val a = note("a.md", "")
        note("b.md", "- 见 [[a|别名]] 的说明")
        index.buildOrRefresh(root.absolutePath, force = true)

        val hit = index.backlinksOf(a.absolutePath).single()
        assertEquals("- 见 别名 的说明", hit.snippet)
        assertEquals(0, hit.line)
    }

    @Test
    fun `alias declaration resolves`() = runBlocking {
        val target = note("target.md", "---\naliases:\n  - 别名\n---\n")
        val src = note("src.md", "见 [[别名]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        assertEquals(listOf(src.absolutePath), index.backlinksOf(target.absolutePath).map { it.sourcePath })
        assertEquals(target.absolutePath, index.outLinksOf(src.absolutePath).single().targetPath)
    }

    @Test
    fun `same name resolves to same directory first`() = runBlocking {
        note("a/Note.md", "")
        note("b/Note.md", "")
        val src = note("a/Src.md", "[[Note]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        val target = index.outLinksOf(src.absolutePath).single()
        assertEquals("a/Note.md", norm(target.relativePath!!))
    }

    @Test
    fun `image embeds are not links but note embeds are`() = runBlocking {
        note("img.png", "")
        val target = note("note2.md", "")
        val a = note("a.md", "![[img.png]] 与 ![[note2]] 与 [[img.png]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        val outs = index.outLinksOf(a.absolutePath)
        assertEquals(listOf("note2", "img.png"), outs.map { it.display })
        assertEquals(target.absolutePath, outs[0].targetPath)
        // 图片嵌入不计入；普通链接指向图片名仍是"未解析"（走新建笔记兜底）
        assertNull(outs[1].targetPath)
    }

    @Test
    fun `self anchors are skipped and self links never become backlinks`() = runBlocking {
        val a = note("a.md", "[[#标题]] [[a]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        val outs = index.outLinksOf(a.absolutePath)
        assertEquals(1, outs.size)
        assertEquals(a.absolutePath, outs[0].targetPath)
        assertTrue(index.backlinksOf(a.absolutePath).isEmpty())
    }

    @Test
    fun `incremental refresh picks up changed and deleted notes`() = runBlocking {
        val a = note("a.md", "[[b]]")
        val b = note("b.md", "")
        index.buildOrRefresh(root.absolutePath, force = true)
        // a 指向 b，所以 b 的反向链接里有 a
        assertEquals(listOf(a.absolutePath), index.backlinksOf(b.absolutePath).map { it.sourcePath })

        // a 改指 c（内容变长，不依赖 mtime 精度），b 被删除
        a.writeText("[[c]] 说明")
        val c = note("c.md", "")
        assertTrue(b.delete())
        index.buildOrRefresh(root.absolutePath, force = true)

        assertTrue(index.backlinksOf(b.absolutePath).isEmpty())
        assertEquals(listOf(a.absolutePath), index.backlinksOf(c.absolutePath).map { it.sourcePath })
        assertEquals(c.absolutePath, index.outLinksOf(a.absolutePath).single().targetPath)
    }

    @Test
    fun `rename re-resolves every reference`() = runBlocking {
        val x = note("x.md", "[[y]]")
        val y = note("y.md", "")
        index.buildOrRefresh(root.absolutePath, force = true)
        val target = index.outLinksOf(x.absolutePath).single().targetPath!!
        assertEquals(1, index.backlinksOf(target).size)

        val z = File(root, "z.md")
        assertTrue(y.renameTo(z))
        index.buildOrRefresh(root.absolutePath, force = true)

        assertTrue(index.backlinksOf(z.absolutePath).isEmpty())
        assertNull(index.outLinksOf(x.absolutePath).single().targetPath)
    }

    @Test
    fun `unresolved targets stay in outlinks but not in backlinks`() = runBlocking {
        val a = note("a.md", "[[nope]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        val out = index.outLinksOf(a.absolutePath).single()
        assertEquals("nope", out.display)
        assertNull(out.targetPath)
        assertNull(out.relativePath)
    }

    @Test
    fun `notes above search limit but inside index budget are indexed`() = runBlocking {
        // 300KB：超过搜索上限（256KB）但远小于索引预算（4MB）——必须参与索引
        val big = "[[target]]\n" + "x".repeat(300_000)
        val src = note("big.md", big)
        val target = note("target.md", "")
        index.buildOrRefresh(root.absolutePath, force = true)
        assertEquals(listOf(src.absolutePath), index.backlinksOf(target.absolutePath).map { it.sourcePath })

        // 一致性：dirty（markChanged 强制重读）与非 dirty 两条路径的可见范围必须相同
        index.markChanged(src.absolutePath)
        index.buildOrRefresh(root.absolutePath, force = true)
        assertEquals(1, index.backlinksOf(target.absolutePath).size)
    }

    @Test
    fun `notes above index budget are always skipped`() = runBlocking {
        val huge = "[[target]]\n" + "x".repeat((VaultRepository.INDEX_MAX_BYTES + 1024).toInt())
        val src = note("huge.md", huge)
        note("target.md", "")
        index.buildOrRefresh(root.absolutePath, force = true)

        assertTrue(index.outLinksOf(src.absolutePath).isEmpty())
        // 路径稳定：标脏后仍不参与索引（不会"刚编辑时跑进来、刷新后又消失"）
        index.markChanged(src.absolutePath)
        index.buildOrRefresh(root.absolutePath, force = true)
        assertTrue(index.outLinksOf(src.absolutePath).isEmpty())
    }

    @Test
    fun `markChanged forces reread of current note`() = runBlocking {
        val a = note("a.md", "")
        val c = note("c.md", "")
        val b = note("b.md", "[[a]]")
        index.buildOrRefresh(root.absolutePath, force = true)
        assertEquals(1, index.backlinksOf(a.absolutePath).size)

        // 等长替换：仅靠 size 指纹无法识别，必须由 markChanged 兜住
        b.writeText("[[c]]")
        index.markChanged(b.absolutePath)
        index.buildOrRefresh(root.absolutePath, force = true)

        assertTrue(index.backlinksOf(a.absolutePath).isEmpty())
        assertEquals(listOf(b.absolutePath), index.backlinksOf(c.absolutePath).map { it.sourcePath })
    }

    @Test
    fun `empty vault yields empty index`() = runBlocking {
        index.buildOrRefresh(root.absolutePath, force = true)
        assertTrue(index.outLinksOf(File(root, "a.md").absolutePath).isEmpty())
        assertTrue(index.backlinksOf(File(root, "a.md").absolutePath).isEmpty())
    }

    @Test
    fun `cold start restores index from persisted cache`() = runBlocking {
        val a = note("a.md", "[[b]]")
        val b = note("b.md", "[[a]]")
        index.buildOrRefresh(root.absolutePath, force = true)

        // 新实例（冷启动）：不读正文，仅靠缓存恢复出同样的双向链接
        val cold = newIndex()
        cold.buildOrRefresh(root.absolutePath, force = true)

        assertTrue(cold.isReadyFor(root.absolutePath))
        assertEquals(listOf(b.absolutePath), cold.backlinksOf(a.absolutePath).map { it.sourcePath })
        assertEquals(listOf(a.absolutePath), cold.backlinksOf(b.absolutePath).map { it.sourcePath })
        assertEquals("b", cold.outLinksOf(a.absolutePath).single().display)
    }

    @Test
    fun `changed file is re-extracted after cold start`() = runBlocking {
        note("a.md", "")
        val b = note("b.md", "无链接内容")
        index.buildOrRefresh(root.absolutePath, force = true)
        assertTrue(index.backlinksOf(b.absolutePath).isEmpty())

        // 内容变长（不依赖 mtime 精度）：缓存应失效，只重读这一篇
        note("a.md", "[[b]] 新加的一段说明文字")
        val cold = newIndex()
        cold.buildOrRefresh(root.absolutePath, force = true)

        assertEquals(listOf(File(root, "a.md").absolutePath), cold.backlinksOf(b.absolutePath).map { it.sourcePath })
    }

    @Test
    fun `aliases survive cold start`() = runBlocking {
        val target = note("目标.md", "---\naliases: [别名甲]\n---\n\n正文")
        val src = note("src.md", "[[别名甲]]")
        index.buildOrRefresh(root.absolutePath, force = true)
        assertEquals(listOf(target.absolutePath), index.outLinksOf(src.absolutePath).mapNotNull { it.targetPath })

        val cold = newIndex()
        cold.buildOrRefresh(root.absolutePath, force = true)

        assertEquals(listOf(target.absolutePath), cold.outLinksOf(src.absolutePath).mapNotNull { it.targetPath })
    }

    /** 内存版缓存：跨索引实例共享，用来模拟“杀进程后再进来”。 */
    private class MemoryLinkCacheStore : LinkCacheStore {

        private data class Entry(
            val fingerprint: CachedFingerprint,
            val refs: List<RawLinkRef>,
            val aliases: List<String>
        )

        private val scopes = HashMap<String, MutableMap<String, Entry>>()

        override suspend fun load(scope: String): LinkCacheSnapshot? {
            val entries = scopes[scope]?.takeIf { it.isNotEmpty() } ?: return null
            val fingerprints = HashMap<String, CachedFingerprint>(entries.size)
            val refs = HashMap<String, List<RawLinkRef>>(entries.size)
            val aliases = HashMap<String, MutableList<NoteAlias>>()
            for ((path, entry) in entries) {
                fingerprints[path] = entry.fingerprint
                refs[path] = entry.refs
                val relative = path.removePrefix(scope).trimStart('/', '\\')
                entry.aliases.forEachIndexed { index, name ->
                    aliases.getOrPut(WikiLinkResolver.foldName(name)) { ArrayList() }
                        .add(NoteAlias(name, relative, index))
                }
            }
            return LinkCacheSnapshot(fingerprints, refs, aliases)
        }

        override suspend fun saveFiles(scope: String, files: List<CachedFile>) {
            val entries = scopes.getOrPut(scope) { HashMap() }
            files.forEach { entries[it.path] = Entry(it.fingerprint, it.refs, it.aliases) }
        }

        override suspend fun removeFiles(scope: String, removed: List<RemovedFile>) {
            val entries = scopes[scope] ?: return
            removed.forEach { entries.remove(it.absolutePath) }
        }

        override suspend fun clear(scope: String) {
            scopes.remove(scope)
        }
    }
}
