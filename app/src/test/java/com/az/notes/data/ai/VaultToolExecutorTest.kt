package com.az.notes.data.ai

import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.ai.RawToolCall
import com.az.notes.domain.ai.ToolSpecs
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 工具执行器单测（§12.2）：路径护栏（`../` / 绝对路径逃逸返回错误文本不抛异常）、
 * read_note 正常 / offset+limit / 32K 截断 / 不存在、list_notes 层级与隐藏过滤、
 * search_notes 命中与未命中、未知工具与非法 JSON 参数（均不抛异常）。
 */
class VaultToolExecutorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val executor = VaultToolExecutor(VaultRepository())

    private fun vault(): File = temp.newFolder("vault")

    private fun args(vararg pairs: Pair<String, JsonPrimitive>): JsonObject =
        JsonObject(pairs.toMap())

    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())): RawToolCall =
        RawToolCall(id = "call-1", name = name, argumentsJson = args.toString())

    private fun JsonArray.paths(): List<String> =
        map { ((it as JsonObject)["path"] as JsonPrimitive).content }

    // ---------- 路径护栏 ----------

    @Test
    fun `read note rejects escaping paths with error text instead of throwing`() {
        val vault = vault()
        File(vault, "ok.md").writeText("hi")
        val escaping = listOf(
            "../outside.md",
            "a/../../outside.md",
            "..%2Foutside.md",
            "C:\\Windows\\win.ini"
        )
        escaping.forEach { path ->
            val result = executor.execute(
                vault.absolutePath,
                call(ToolSpecs.READ_NOTE, args("path" to JsonPrimitive(path)))
            )
            assertTrue("path=$path → ${result.resultText}", result.resultText.startsWith("错误："))
        }
    }

    @Test
    fun `resolve rejects traversal and keeps inside paths`() {
        val vault = vault()
        val note = File(vault, "folder/note.md")
        note.parentFile!!.mkdirs()
        note.writeText("x")

        assertNull(executor.resolve(vault.absolutePath, "../x.md"))
        assertNull(executor.resolve(vault.absolutePath, "folder/../../x.md"))
        assertEquals(
            note.absolutePath,
            executor.resolve(vault.absolutePath, "folder/note.md")?.absolutePath
        )
        // 根目录自身可解析（list_notes 空参从根开始）
        assertEquals(
            vault.absolutePath,
            executor.resolve(vault.absolutePath, "")?.absolutePath
        )
    }

    // ---------- read_note ----------

    @Test
    fun `read note returns content and applies offset limit`() {
        val vault = vault()
        val text = (1..10).joinToString("\n") { "line$it" }
        File(vault, "note.md").writeText(text)

        val full = executor.execute(
            vault.absolutePath,
            call(ToolSpecs.READ_NOTE, args("path" to JsonPrimitive("note.md")))
        )
        assertEquals(text, full.resultText)
        assertEquals(ToolSpecs.READ_NOTE, full.record.tool)
        assertEquals("note.md", full.record.argsSummary)

        val slice = executor.execute(
            vault.absolutePath,
            call(
                ToolSpecs.READ_NOTE,
                args(
                    "path" to JsonPrimitive("note.md"),
                    "offset" to JsonPrimitive(3),
                    "limit" to JsonPrimitive(2)
                )
            )
        )
        assertEquals("line3\nline4", slice.resultText)
    }

    @Test
    fun `read note reports missing file and out of range offset`() {
        val vault = vault()
        File(vault, "note.md").writeText("a\nb")

        val missing = executor.execute(
            vault.absolutePath,
            call(ToolSpecs.READ_NOTE, args("path" to JsonPrimitive("missing.md")))
        )
        assertTrue(missing.resultText.startsWith("错误：文件不存在"))

        val outOfRange = executor.execute(
            vault.absolutePath,
            call(
                ToolSpecs.READ_NOTE,
                args("path" to JsonPrimitive("note.md"), "offset" to JsonPrimitive(99))
            )
        )
        assertTrue(outOfRange.resultText.startsWith("错误：起始行"))
    }

    @Test
    fun `read note clips oversized content and hints follow-up offset`() {
        val vault = vault()
        val content = (1..600).joinToString("\n") { "line-$it-" + "x".repeat(80) }
        File(vault, "big.md").writeText(content)

        val result = executor.execute(
            vault.absolutePath,
            call(ToolSpecs.READ_NOTE, args("path" to JsonPrimitive("big.md")))
        )
        assertTrue(result.resultText.contains("已达单次读取上限"))
        assertTrue(result.resultText.contains("offset="))
        // 截断标注之前的正文不超过单次上限
        val body = result.resultText.substringBefore("\n\n[…")
        assertTrue(body.length <= 32_768)
    }

    // ---------- list_notes ----------

    @Test
    fun `list notes respects depth hidden filters and markdown only`() {
        val vault = vault()
        File(vault, "a.md").writeText("x")
        File(vault, "pic.png").writeText("x")

        val b = File(vault, "sub/b.md")
        b.parentFile!!.mkdirs()
        b.writeText("x")
        val c = File(vault, "sub/deeper/c.md")
        c.parentFile!!.mkdirs()
        c.writeText("x")
        val leaf = File(vault, "sub/deeper/deepest/leaf.md")
        leaf.parentFile!!.mkdirs()
        leaf.writeText("x")
        val hidden = File(vault, ".obsidian/app.json")
        hidden.parentFile!!.mkdirs()
        hidden.writeText("{}")
        val asset = File(vault, "assets/pic2.png")
        asset.parentFile!!.mkdirs()
        asset.writeText("x")

        val result = executor.execute(vault.absolutePath, call(ToolSpecs.LIST_NOTES))
        val paths = (json.parseToJsonElement(result.resultText) as JsonArray).paths()

        assertTrue("sub" in paths)
        assertTrue("a.md" in paths)
        assertTrue("sub/b.md" in paths)
        assertTrue("sub/deeper" in paths)
        assertTrue("sub/deeper/c.md" in paths)
        assertTrue("sub/deeper/deepest" in paths)
        // 第 4 层内容不展开；隐藏目录 / 附件夹 / 非 Markdown 文件被过滤
        assertFalse(paths.any { it.endsWith("leaf.md") })
        assertFalse(paths.any { it.contains("app.json") })
        assertFalse(paths.any { it.contains("assets") })
        assertFalse(paths.any { it.endsWith("pic.png") })
        assertFalse(paths.any { it.endsWith("pic2.png") })
    }

    @Test
    fun `list notes from subdir scopes to that dir and reports empty vault`() {
        val vault = vault()
        File(vault, "a.md").writeText("x")
        val b = File(vault, "sub/b.md")
        b.parentFile!!.mkdirs()
        b.writeText("x")

        val scoped = executor.execute(
            vault.absolutePath,
            call(ToolSpecs.LIST_NOTES, args("dir" to JsonPrimitive("sub")))
        )
        assertEquals(listOf("sub/b.md"), (json.parseToJsonElement(scoped.resultText) as JsonArray).paths())

        val emptyDir = temp.newFolder("empty")
        val empty = executor.execute(emptyDir.absolutePath, call(ToolSpecs.LIST_NOTES))
        assertEquals("（该目录下没有笔记或子目录）", empty.resultText)
    }

    // ---------- search_notes ----------

    @Test
    fun `search notes hits content and reports miss`() {
        val vault = vault()
        File(vault, "alpha.md").writeText("关于 Kotlin 协程的笔记")
        File(vault, "beta.md").writeText("另一篇不太相关")

        val hit = executor.execute(
            vault.absolutePath,
            call(ToolSpecs.SEARCH_NOTES, args("query" to JsonPrimitive("Kotlin")))
        )
        assertTrue(hit.resultText.contains("alpha.md"))
        assertFalse(hit.resultText.contains("beta.md"))

        val miss = executor.execute(
            vault.absolutePath,
            call(ToolSpecs.SEARCH_NOTES, args("query" to JsonPrimitive("绝不匹配的关键词xyz")))
        )
        assertTrue(miss.resultText.startsWith("未找到"))
    }

    // ---------- 容错 ----------

    @Test
    fun `unknown tool invalid json and blank root return error text`() {
        val vault = vault()
        val unknown = executor.execute(vault.absolutePath, RawToolCall("c1", "no_such_tool", "{}"))
        assertTrue(unknown.resultText.startsWith("错误：未知工具"))

        val invalid = executor.execute(
            vault.absolutePath,
            RawToolCall("c2", ToolSpecs.READ_NOTE, "not-a-json")
        )
        assertTrue(invalid.resultText.contains("缺少 path 参数"))

        // 空 vaultRoot：所有工具返回错误文本（不抛异常）
        val noRoot = executor.execute("", call(ToolSpecs.LIST_NOTES))
        assertTrue(noRoot.resultText.startsWith("错误："))
    }
}
