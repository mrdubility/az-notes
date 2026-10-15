package com.az.notes.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络图片保存命名推断单测：URL 末段优先（百分号解码、天然剥 query/fragment）、
 * 末段无扩展名时按内容类型补齐、均无时回退兜底主体名，以及消毒（控制字符 / 限长）。
 */
class RemoteImageDownloaderTest {

    /** 兜底主体名（真实调用方传 R.string.image_default_base_name 的中文文案）。 */
    private val defaultBase = "图片"

    @Test
    fun `keeps extension from url segment`() {
        assertEquals(
            "pic.jpg",
            RemoteImageDownloader.inferName("https://host.com/a/pic.jpg", "image/jpeg", defaultBase)
        )
    }

    @Test
    fun `decodes percent-encoded segment and drops query`() {
        assertEquals(
            "图片.png",
            RemoteImageDownloader.inferName(
                "https://host.com/dir/%E5%9B%BE%E7%89%87.png?raw=1#frag",
                "image/png",
                defaultBase
            )
        )
    }

    @Test
    fun `appends extension from content type when segment has none`() {
        assertEquals(
            "photo.webp",
            RemoteImageDownloader.inferName(
                "https://host.com/photo",
                "image/webp; charset=binary",
                defaultBase
            )
        )
    }

    @Test
    fun `falls back to default base when no segment`() {
        assertEquals(
            "图片.gif",
            RemoteImageDownloader.inferName("https://host.com/", "image/gif", defaultBase)
        )
    }

    @Test
    fun `keeps default base when content type unknown`() {
        assertEquals(
            "图片",
            RemoteImageDownloader.inferName("https://host.com/", null, defaultBase)
        )
    }

    @Test
    fun `filters control characters`() {
        assertEquals(
            "ab.jpg",
            RemoteImageDownloader.inferName("https://host.com/a%00b.jpg", "image/jpeg", defaultBase)
        )
    }

    @Test
    fun `truncates overlong segment and appends extension`() {
        val name = RemoteImageDownloader.inferName(
            "https://host.com/" + "a".repeat(100) + ".jpg",
            "image/jpeg",
            defaultBase
        )
        // 截断到 64 字符主体（扩展名被截掉）后按内容类型重新补 .jpg
        assertEquals(68, name.length)
        assertTrue(name.endsWith(".jpg"))
    }
}
