package com.az.notes.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 重命名 / 移动路径广播单测（不依赖 Android，JVM 直跑）。
 * 覆盖：取后即清、未知路径、链式跟进、容量淘汰。
 */
class PathUpdateBusTest {

    @Test
    fun `consume returns new path once then clears the entry`() {
        val bus = PathUpdateBus()
        bus.post("/vault/a.md", "/vault/b.md")

        assertEquals("/vault/b.md", bus.consume("/vault/a.md"))
        assertNull(bus.consume("/vault/a.md"))
    }

    @Test
    fun `consume unknown path returns null`() {
        val bus = PathUpdateBus()
        assertNull(bus.consume("/vault/missing.md"))
    }

    @Test
    fun `repeated post for same old path keeps the latest target`() {
        val bus = PathUpdateBus()
        bus.post("/vault/a.md", "/vault/b.md")
        bus.post("/vault/a.md", "/vault/c.md")

        assertEquals("/vault/c.md", bus.consume("/vault/a.md"))
    }

    @Test
    fun `chained updates can be consumed hop by hop`() {
        val bus = PathUpdateBus()
        bus.post("/vault/a.md", "/vault/b.md")
        bus.post("/vault/b.md", "/vault/c.md")

        val first = bus.consume("/vault/a.md")
        assertEquals("/vault/b.md", first)
        assertEquals("/vault/c.md", bus.consume(first!!))
        assertNull(bus.consume("/vault/c.md"))
    }

    @Test
    fun `oldest entry is evicted beyond capacity`() {
        val bus = PathUpdateBus()
        repeat(9) { i -> bus.post("/vault/$i.md", "/vault/${i + 100}.md") }

        // 容量 8：最早的一条被淘汰，最近的仍在
        assertNull(bus.consume("/vault/0.md"))
        assertEquals("/vault/108.md", bus.consume("/vault/8.md"))
    }
}
