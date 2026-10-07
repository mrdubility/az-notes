package com.az.notes.domain.markdown

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 引用解析纯逻辑单测（§4.1：不依赖 Android，JVM 直跑）。
 * 覆盖：title/锚点/查询剥离、百分号解码、Obsidian 嵌入、`<img>`、代码围栏排除、
 * 网络链接判定、相对/根路径解析与 `../` 越界护栏；
 * 写入侧 encodeTarget 的转义 / 往返、空格路径与 `<>` 包裹解析。
 */
class ImageReferenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `extract strips title anchor and query`() {
        assertEquals(listOf("x.png"), ImageReference.extract("""![a](x.png "title")"""))
        assertEquals(listOf("pic.png"), ImageReference.extract("![a](pic.png#frag)"))
        assertEquals(listOf("pic.png"), ImageReference.extract("![a](pic.png?w=100)"))
    }

    @Test
    fun `extract decodes percent escapes`() {
        assertEquals(
            listOf("assets/pic 01.png"),
            ImageReference.extract("![a](assets/pic%2001.png)")
        )
    }

    @Test
    fun `extract reads obsidian embed and html img`() {
        assertEquals(listOf("IMG"), ImageReference.extract("![[IMG]]"))
        assertEquals(listOf("IMG"), ImageReference.extract("![[IMG|200]]"))
        assertEquals(listOf("photo.jpg"), ImageReference.extract("<img src=\"photo.jpg\">"))
    }

    @Test
    fun `extract ignores links inside code fence`() {
        val md = """
            ![real](ok.png)
            ```
            ![fake](inside.png)
            ```
        """.trimIndent()
        val refs = ImageReference.extract(md)
        assertTrue(refs.contains("ok.png"))
        assertFalse(refs.contains("inside.png"))
    }

    @Test
    fun `isRemote only for network or inline schemes`() {
        assertTrue(ImageReference.isRemote("http://a/b.png"))
        assertTrue(ImageReference.isRemote("https://a/b.png"))
        assertTrue(ImageReference.isRemote("data:image/png;base64,AAAA"))
        assertTrue(ImageReference.isRemote("content://x/y"))
        assertFalse(ImageReference.isRemote("assets/b.png"))
        assertFalse(ImageReference.isRemote("/root/b.png"))
    }

    @Test
    fun `candidates resolve relative and root path within vault`() {
        val vault = temp.root
        val pic = File(vault, "assets/pic.png").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        val rootPic = File(vault, "root.png").apply { writeText("x") }

        val rel = ImageReference.resolveExisting("assets/pic.png", vault, vault)
        assertEquals(pic.absolutePath, rel?.absolutePath)

        val root = ImageReference.resolveExisting("/root.png", vault, vault)
        assertEquals(rootPic.absolutePath, root?.absolutePath)
    }

    @Test
    fun `candidates reject escaping the vault`() {
        val vault = temp.newFolder("vault")
        val empty = ImageReference.candidates("../../outside.png", vault, vault)
        assertTrue(empty.isEmpty())
        assertNull(ImageReference.resolveExisting("assets/missing.png", vault, vault))
    }

    @Test
    fun `extract keeps path spaces and strips angle bracket wrapper`() {
        assertEquals(listOf("my photo.png"), ImageReference.extract("![a](my photo.png)"))
        assertEquals(listOf("assets/my photo.png"), ImageReference.extract("![a](<assets/my photo.png>)"))
        // 尖括号 + title：剥尖括号取路径，title 丢弃
        assertEquals(listOf("my photo.png"), ImageReference.extract("![a](<my photo.png> \"t\")"))
    }

    @Test
    fun `encodeTarget round-trips through extraction`() {
        // 本 App 生成路径（escapePercent=true）：空格 / 井号 / 括号 / 百分号全部可逆
        val raw = "assets/我的 图 #1(2).png"
        val encoded = ImageReference.encodeTarget(raw, escapePercent = true)
        assertEquals("assets/我的%20图%20%231%282%29.png", encoded)
        assertEquals(raw, ImageReference.extract("![a]($encoded)").single())
    }

    @Test
    fun `encodeTarget keeps pre-escaped manual input untouched`() {
        // 已含 %XX 片段的手动输入原样保留：解析端只做一次解码，防双重编码
        assertEquals("assets/pic%20a.png", ImageReference.encodeTarget("assets/pic%20a.png"))
        // 不含 %XX 的手动输入：空格 / 井号 / 括号转义
        assertEquals(
            "assets/pic%20a%231%282%29.png",
            ImageReference.encodeTarget("assets/pic a#1(2).png")
        )
    }

    @Test
    fun `extract ignores links inside tilde fence too`() {
        val md = """
            ![real](ok.png)
            ~~~
            ![fake](inside.png)
            ~~~
            ![tail](ok2.png)
        """.trimIndent()
        assertEquals(listOf("ok.png", "ok2.png"), ImageReference.extract(md))
    }

    @Test
    fun `extract reads wiki link and alias variants`() {
        assertEquals(listOf("note.md"), ImageReference.extract("[[note.md]]"))
        assertEquals(listOf("d.png"), ImageReference.extract("![[d.png|仅宽度]]"))
    }

    @Test
    fun `extensionless wiki name resolves via assets guess without name index`() {
        val vault = temp.newFolder("v-wiki")
        val img = File(vault, "assets/diagram.png").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        // 渲染层形态：不传 nameIndex、只写文件名，附件在笔记旁 assets/ 下
        val hit = ImageReference.resolveExisting("diagram", vault, vault)
        assertEquals(img.absolutePath, hit?.absolutePath)
    }
}
