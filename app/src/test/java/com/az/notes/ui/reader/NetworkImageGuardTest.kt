package com.az.notes.ui.reader

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 网络图片护栏单测：
 * - 纯函数 [NetworkImageGuard.check]/[NetworkImageGuard.isPrivateLiteral]/[NetworkImageGuard.classify] 走 JVM；
 * - 受控客户端（体积上限 / 快速失败 / 重定向 / 慢速滴流超时）用 OkHttp `MockWebServer` 集成验证。
 * - host 判定补充：userinfo 剥离、query/fragment 无路径形态、localhost 别名、短格式数字 IP。
 */
class NetworkImageGuardTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- check：scheme / 私网 / data: 判定（纯逻辑） ----------

    @Test
    fun `check rejects blank and disabled limits`() {
        assertEquals(NetworkImageGuard.Failure.UNSUPPORTED, NetworkImageGuard.check("", 1_000))
        assertEquals(NetworkImageGuard.Failure.DISABLED, NetworkImageGuard.check("https://a/b.png", 0))
        assertEquals(NetworkImageGuard.Failure.DISABLED, NetworkImageGuard.check("https://a/b.png", -1))
    }

    @Test
    fun `check allows only image data uri within length`() {
        assertNull(NetworkImageGuard.check("data:image/png;base64,AAAA", 1_000_000))
        assertEquals(NetworkImageGuard.Failure.UNSUPPORTED, NetworkImageGuard.check("data:text/html,hi", 1_000_000))
        val huge = "data:image/png;base64," + "A".repeat(NetworkImageGuard.DATA_URI_MAX_CHARS)
        assertEquals(NetworkImageGuard.Failure.UNSUPPORTED, NetworkImageGuard.check(huge, 1_000_000))
    }

    @Test
    fun `check rejects non-http schemes and empty host`() {
        assertEquals(NetworkImageGuard.Failure.UNSUPPORTED, NetworkImageGuard.check("ftp://host/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.UNSUPPORTED, NetworkImageGuard.check("file:///etc/passwd", 1_000))
        assertEquals(NetworkImageGuard.Failure.UNSUPPORTED, NetworkImageGuard.check("http://", 1_000))
    }

    @Test
    fun `check rejects private ip literals but keeps public and domain hosts`() {
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://127.0.0.1/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://10.1.2.3/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://192.168.1.1/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://[::1]/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://[fe80::1]/a.png", 1_000))
        assertNull(NetworkImageGuard.check("https://8.8.8.8/a.png", 1_000))
        // 域名型主机不拦截（避免误伤局域网 Wiki 图床）
        assertNull(NetworkImageGuard.check("https://intranet.example.com/a.png", 1_000))
    }

    @Test
    fun `check strips userinfo and delimiter suffixes before host judgement`() {
        // userinfo（user@ / user:pass@）不能遮蔽私网主机：真实请求目标是 @ 之后
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://user@127.0.0.1/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://user:pw@127.0.0.1/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://user@[::1]/a.png", 1_000))
        // 无路径形态：query / fragment 直接跟随 authority
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://127.0.0.1?x=1", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://127.0.0.1#f", 1_000))
        // 剥离 userinfo 后是公网主机的不误伤
        assertNull(NetworkImageGuard.check("http://user@8.8.8.8/a.png", 1_000))
        assertNull(NetworkImageGuard.check("http://user@intranet.example.com/a.png", 1_000))
    }

    @Test
    fun `check rejects shorthand numeric hosts resolved to loopback`() {
        // 传统 1-4 段 IPv4 形态与十进制整数形态都会被平台解析为数字地址
        // （实测 JVM：127.1 / 2130706433 → 127.0.0.1），必须拦截
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://127.1/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://2130706433/a.png", 1_000))
        // 短格式公网地址按实际解析结果放行（8.8 → 8.0.0.8，非私网），不因形态一刀切
        assertNull(NetworkImageGuard.check("http://8.8/a.png", 1_000))
    }

    @Test
    fun `check rejects localhost alias and trailing dot`() {
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://localhost/a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://localhost./a.png", 1_000))
        assertEquals(NetworkImageGuard.Failure.PRIVATE_ADDRESS, NetworkImageGuard.check("http://LOCALHOST/a.png", 1_000))
    }

    // ---------- isPrivateLiteral ----------

    @Test
    fun `isPrivateLiteral classes addresses`() {
        assertFalse(NetworkImageGuard.isPrivateLiteral(""))
        assertFalse(NetworkImageGuard.isPrivateLiteral("example.com"))
        assertFalse(NetworkImageGuard.isPrivateLiteral("8.8.8.8"))
        assertTrue(NetworkImageGuard.isPrivateLiteral("127.0.0.1"))
        assertTrue(NetworkImageGuard.isPrivateLiteral("172.16.5.4"))
        assertTrue(NetworkImageGuard.isPrivateLiteral("169.254.1.1"))
        assertTrue(NetworkImageGuard.isPrivateLiteral("::1"))
    }

    // ---------- classify ----------

    @Test
    fun `classify maps exceptions to failures`() {
        assertEquals(NetworkImageGuard.Failure.TOO_LARGE, NetworkImageGuard.classify(NetworkImageGuard.TooLargeException(10)))
        assertEquals(NetworkImageGuard.Failure.REDIRECT, NetworkImageGuard.classify(NetworkImageGuard.BlockedReason(NetworkImageGuard.Failure.REDIRECT)))
        assertEquals(NetworkImageGuard.Failure.TIMEOUT, NetworkImageGuard.classify(java.net.SocketTimeoutException("read timeout")))
        assertEquals(NetworkImageGuard.Failure.TIMEOUT, NetworkImageGuard.classify(java.io.InterruptedIOException("cancelled")))
        assertNull(NetworkImageGuard.classify(RuntimeException("other")))
        // 嵌套 cause 也能识别
        val wrapped = RuntimeException("outer", NetworkImageGuard.TooLargeException(10))
        assertEquals(NetworkImageGuard.Failure.TOO_LARGE, NetworkImageGuard.classify(wrapped))
    }

    @Test
    fun `classify maps network errors to precise failures`() {
        assertEquals(
            NetworkImageGuard.Failure.HTTP_STATUS,
            NetworkImageGuard.classify(NetworkImageGuard.HttpStatusException(404))
        )
        assertEquals(
            NetworkImageGuard.Failure.DNS_FAILED,
            NetworkImageGuard.classify(java.net.UnknownHostException("no such host"))
        )
        assertEquals(
            NetworkImageGuard.Failure.CONNECT_FAILED,
            NetworkImageGuard.classify(java.net.ConnectException("connection refused"))
        )
        assertEquals(
            NetworkImageGuard.Failure.CONNECT_FAILED,
            NetworkImageGuard.classify(java.io.IOException("tls handshake failed"))
        )
        // 嵌套 cause 也能提取状态码
        val wrapped = RuntimeException("outer", NetworkImageGuard.HttpStatusException(403))
        assertEquals(NetworkImageGuard.Failure.HTTP_STATUS, NetworkImageGuard.classify(wrapped))
        assertEquals(403, NetworkImageGuard.httpStatusOf(wrapped))
        assertNull(NetworkImageGuard.httpStatusOf(java.io.IOException("no status")))
    }

    // ---------- 受控客户端 + MockWebServer ----------

    @Test
    fun `declared content-length over limit fails fast`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "5000").setBody("x".repeat(5000)))
        val client = NetworkImageGuard.newClient(maxBytesProvider = { 100L }, timeoutSeconds = 5)
        val failure = runAndGetFailure(client, server.url("/big.png").toString(), readBody = false)
        assertEquals(NetworkImageGuard.Failure.TOO_LARGE, failure)
    }

    @Test
    fun `chunked oversized body is cut at streaming cap`() {
        // chunked：无 Content-Length，谎报无从判断，只能靠流式计数在超限时中断
        val payload = ByteArray(4096) { 'x'.code.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(String(payload), 512))
        val client = NetworkImageGuard.newClient(maxBytesProvider = { 100L }, timeoutSeconds = 5)
        val failure = runAndGetFailure(client, server.url("/stream.png").toString(), readBody = true)
        assertEquals(NetworkImageGuard.Failure.TOO_LARGE, failure)
    }

    @Test
    fun `redirect is not followed and reported`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/final.png").toString()))
        val client = NetworkImageGuard.newClient(maxBytesProvider = { 1_000_000L }, timeoutSeconds = 5)
        val failure = runAndGetFailure(client, server.url("/start.png").toString(), readBody = false)
        assertEquals(NetworkImageGuard.Failure.REDIRECT, failure)
    }

    @Test
    fun `non-2xx status is reported with http status failure`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = NetworkImageGuard.newClient(maxBytesProvider = { 1_000_000L }, timeoutSeconds = 5)
        val failure = runAndGetFailure(client, server.url("/missing.png").toString(), readBody = false)
        assertEquals(NetworkImageGuard.Failure.HTTP_STATUS, failure)
    }

    @Test
    fun `small compliant image loads without error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("png-bytes"))
        val client = NetworkImageGuard.newClient(maxBytesProvider = { 1_000_000L }, timeoutSeconds = 5)
        val failure = runAndGetFailure(client, server.url("/ok.png").toString(), readBody = true)
        assertNull(failure)
    }

    @Test
    fun `slow drip exceeds read timeout`() {
        // 体积上限足够大（不误触发 TOO_LARGE），靠节流让读取拖过 readTimeout
        // 节流间隔（2s）> readTimeout（1s）：单次 socket 读取即超时，无需等 callTimeout 兜底
        val payload = ByteArray(4096)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(String(payload))
                .throttleBody(1, 2, TimeUnit.SECONDS)
        )
        val client = NetworkImageGuard.newClient(maxBytesProvider = { 10_000_000L }, timeoutSeconds = 1)
        val failure = runAndGetFailure(client, server.url("/slow.png").toString(), readBody = true)
        assertEquals(NetworkImageGuard.Failure.TIMEOUT, failure)
    }

    /** 执行请求并按需读取正文，返回 [NetworkImageGuard.classify] 归类结果；成功返回 null。 */
    private fun runAndGetFailure(client: OkHttpClient, url: String, readBody: Boolean): NetworkImageGuard.Failure? {
        return try {
            val call = client.newCall(Request.Builder().url(url).build())
            val resp = call.execute()
            if (readBody) resp.use { it.body?.bytes() } else resp.close()
            null
        } catch (t: Throwable) {
            NetworkImageGuard.classify(t)
        }
    }
}
