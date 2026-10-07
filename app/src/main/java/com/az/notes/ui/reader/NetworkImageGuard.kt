package com.az.notes.ui.reader

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * 网络图片加载护栏：预览页的 `http(s)://` / `data:` 图片一律视为**不可信来源**，
 * 经本护栏产出的受控 OkHttp 客户端加载。
 *
 * 防护点（对应「防网络攻击」要求）：
 * 1. 体积上限：先看 `Content-Length` 快速失败，再以流式计数硬限——
 *    chunked / 谎报长度 / 无限流式响应都在读满上限时立即中断（防内存与流量耗尽）；
 * 2. 超时：`readTimeout` 覆盖慢速滴流，`callTimeout` 给整个请求兜硬上限（防长连接拖死）；
 * 3. 不跟随重定向：`3xx` 直接判失败，杜绝「小图链接 302 到 GB 级文件 / 内网地址」；
 * 4. 地址合规：仅允许 http/https/data 图片，IP 字面量的私网/环回/链路本地段拒绝加载
 *    （压缩内网探测面，同时不误伤域名型局域网 Wiki 图床）；
 * 5. 请求头白名单：只发 `Accept` 限定图片类型，剥离 Cookie / Authorization，避免凭据外泄；
 * 6. 不重试、强制 HTTP/1.1、不落磁盘缓存（见 AzNotesApp 的 ImageLoader 配置）。
 */
object NetworkImageGuard {

    /** 默认单张图片体积上限（MB）。 */
    const val DEFAULT_MAX_MB = 10

    /** 默认读取超时（秒）。 */
    const val DEFAULT_TIMEOUT_SECONDS = 5

    /** 连接阶段超时（秒），固定不开放配置。 */
    private const val CONNECT_TIMEOUT_SECONDS = 5L

    /** data: 内联图片的字符长度上限（超大 base64 不渲染）。 */
    const val DATA_URI_MAX_CHARS = 1_000_000

    /** 拒绝请求的异常（[failure] 可直接映射到用户可读文案）。 */
    class BlockedReason(val failure: Failure) : Exception(failure.name)

    /** 体积超限异常（流式读满上限时抛出，由 Coil 收敛为加载失败）。 */
    class TooLargeException(val maxBytes: Long) : Exception("image exceeds $maxBytes bytes")

    /** 失败分类（UI 侧映射文案，不暴露底层异常细节）。 */
    enum class Failure {
        /** 上限设为 0：不加载网络图片。 */
        DISABLED,

        /** scheme 不允许 / 地址为空 / data: 非图片或超长。 */
        UNSUPPORTED,

        /** 指向私网、环回或链路本地地址。 */
        PRIVATE_ADDRESS,

        /** 服务端返回 3xx（未跟随重定向）。 */
        REDIRECT,

        /** 超过体积上限（声明体积或实际读取字节数）。 */
        TOO_LARGE,

        /** 读取超时。 */
        TIMEOUT
    }

    /**
     * 纯校验（可 JVM 单测）：链接是否允许加载。
     *
     * @param maxBytes 体积上限（字节）；0 = 不加载网络图片
     */
    fun check(link: String, maxBytes: Long): Failure? {
        if (link.isBlank()) return Failure.UNSUPPORTED
        if (maxBytes <= 0L) return Failure.DISABLED
        val lower = link.lowercase(java.util.Locale.ROOT)
        if (lower.startsWith("data:")) {
            if (!lower.startsWith("data:image/")) return Failure.UNSUPPORTED
            if (link.length > DATA_URI_MAX_CHARS) return Failure.UNSUPPORTED
            return null
        }
        val scheme = lower.substringBefore("://", "").let { if (it.isEmpty()) null else it }
        if (scheme != "http" && scheme != "https") return Failure.UNSUPPORTED
        // 先取 authority（scheme 后、首个 `/` 前），再分离 host 与端口：
        // IPv6 字面量写在方括号内（`[::1]:8080`），必须先按方括号取内容，
        // 否则用 `:` 剥端口会把 `[::1]` 截成 `[`，令私网/环回拦截失效（SSRF 面）。
        val authority = link.substringAfter("://").substringBefore("/")
        val hostPart = if (authority.startsWith("[")) {
            authority.substringAfter("[").substringBefore("]")
        } else {
            authority.substringBefore(":")
        }
        if (hostPart.isBlank()) return Failure.UNSUPPORTED
        // 去掉 zone id 尾缀（%eth0）
        val plain = hostPart.substringBefore('%')
        if (isPrivateLiteral(plain)) return Failure.PRIVATE_ADDRESS
        return null
    }

    /** IP 字面量的私网/环回/链路本地判定；非字面量（域名）返回 false，不误伤局域网域名图床。 */
    fun isPrivateLiteral(host: String): Boolean {
        if (host.isBlank()) return false
        val literal = runCatching {
            if (host.indexOf(':') >= 0) {
                InetAddress.getByName(host)
            } else if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                InetAddress.getByName(host)
            } else null
        }.getOrNull() ?: return false
        return literal.isLoopbackAddress || literal.isAnyLocalAddress ||
            literal.isLinkLocalAddress || literal.isSiteLocalAddress
    }

    /**
     * 构造受控客户端。
     *
     * @param maxBytesProvider 体积上限提供者（设置页改动即时生效，无需重建客户端）
     * @param timeoutSeconds   读取超时（秒）；改动时由 [com.az.notes.di.CoilHolder] 重建客户端，
     *                         渲染层以超时值为 key 重建 painter
     */
    fun newClient(maxBytesProvider: () -> Long, timeoutSeconds: Int): OkHttpClient {
        val timeout = timeoutSeconds.coerceIn(1, 30).toLong()
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 整请求硬上限（含 DNS / 连接 / 读满）：比读取超时再宽 5 秒，
            // 因此 DNS 挂起同样落在超时窗口内，无需额外的 EventListener 计时
            .callTimeout(timeout + CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(HeaderWhitelistInterceptor)
            .addInterceptor(SizeCapInterceptor(maxBytesProvider))
            .build()
    }

    /** 请求头白名单：只保留 Accept，凭据类头一律剥离，不随重定向/第三方主机外泄。 */
    private object HeaderWhitelistInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val stripped = request.newBuilder()
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .removeHeader("Authorization")
                .removeHeader("Cookie")
                .removeHeader("Proxy-Authorization")
                .build()
            return chain.proceed(stripped)
        }
    }

    /**
     * 体积上限拦截器：声明体积快速失败 + 实际字节流式硬限 + 状态码/重定向判定。
     */
    private class SizeCapInterceptor(private val maxBytesProvider: () -> Long) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val maxBytes = maxBytesProvider()
            if (maxBytes <= 0L) throw BlockedReason(Failure.DISABLED)
            val resp = chain.proceed(chain.request())
            if (resp.code in 300..399) throw BlockedReason(Failure.REDIRECT)
            if (resp.code !in 200..299) return resp
            val declared = resp.headers["Content-Length"]?.toLongOrNull()
            if (declared != null && declared > maxBytes) throw TooLargeException(maxBytes)
            val body = resp.body ?: return resp
            // Content-Length 缺失或谎报时靠这里兜住：读满上限立刻中断
            val counting = LimitedSource(body.source(), maxBytes)
            return resp.newBuilder().body(CapBody(body, counting)).build()
        }
    }

    /** 读满 [maxBytes] 即抛 [TooLargeException] 的计数 Source。 */
    private class LimitedSource(
        delegate: Source,
        private val maxBytes: Long
    ) : ForwardingSource(delegate) {

        private var readTotal = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                readTotal += n
                if (readTotal > maxBytes) throw TooLargeException(maxBytes)
            }
            return n
        }
    }

    /** 换用计数 Source 的响应体包装（保留 contentType / contentLength 等元信息）。 */
    private class CapBody(
        private val wrapped: ResponseBody,
        private val source: Source
    ) : ResponseBody() {

        override fun contentType() = wrapped.contentType()
        override fun contentLength() = wrapped.contentLength()
        override fun source(): Source = source
        override fun close() = wrapped.close()
    }

    /** 从异常链判定失败分类（供 UI 映射文案）。 */
    fun classify(t: Throwable?): Failure? {
        var cur = t
        while (cur != null) {
            when (cur) {
                is TooLargeException -> return Failure.TOO_LARGE
                is BlockedReason -> return cur.failure
                is java.net.SocketTimeoutException -> return Failure.TIMEOUT
                is java.io.InterruptedIOException -> return Failure.TIMEOUT
            }
            cur = cur.cause
        }
        return null
    }
}
