package com.az.notes.ui.reader

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.net.Proxy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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
 * 5. 请求头白名单：浏览器级 UA + `Accept` 限定图片类型 + `Accept-Language`，
 *    剥离 Cookie / Authorization，避免凭据外泄；
 * 6. 协议协商、连接重试等网络层行为保持 OkHttp 默认（与浏览器 / 主流图片库一致），
 *    仅不落磁盘缓存（见 AzNotesApp 的 ImageLoader 配置）——避免与中间网关的默认路径相克。
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

    /** 标准移动端浏览器 UA：贴近真实浏览器请求特征，降低 CDN / WAF / 网关误伤概率。 */
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 浏览器级语言头：与 UA 配套，进一步贴近真实浏览器请求。 */
    private const val DEFAULT_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

    /**
     * 拒绝请求的异常（[failure] 可直接映射到用户可读文案）。
     *
     * 必须继承 [IOException]：OkHttp Interceptor 契约只允许报 IOException，其他
     * Exception 会直接穿透到 Coil 的 OkHttpNetworkFetcher；Coil 对非 IO 异常会 rethrow 到 IO 协程
     * 造成未捕获崩溃。改为 IOException 后 Coil 会将其收敛为 AsyncImagePainter.State.Error，
     * UI 展示为失败横幅，不闪退。
     */
    class BlockedReason(val failure: Failure) : IOException(failure.name)

    /**
     * 服务端返回非 2xx 状态码（403/404/504 等），具体码由 [code] 携带（同属 IOException
     * 家族，理由同上）。
     *
     * [requestUrl]：实际请求的完整 URL（验证链接解析是否被截断）；
     * [responseHeaders]：**全部**响应头拼接（截 400 字符）——头全空往往意味着
     * 错误由本地/就近的拦截组件伪造而非目标站返回；
     * [bodyPreview]：响应体少量摘要（错误页常注明拦截原因，最多 240 字符）。
     */
    class HttpStatusException(
        val code: Int,
        val requestUrl: String? = null,
        val responseHeaders: String? = null,
        val bodyPreview: String? = null
    ) : IOException("HTTP $code")

    /** 体积超限异常（流式读满上限时抛出，由 Coil 收敛为加载失败；同属 IOException 家族）。 */
    class TooLargeException(val maxBytes: Long) : IOException("image exceeds $maxBytes bytes")

    /** 失败分类（UI 侧映射文案，不暴露底层异常细节）。 */
    enum class Failure {
        /** 上限设为 0：不加载网络图片。 */
        DISABLED,

        /** scheme 不允许 / 地址为空 / data: 非图片或超长 / 其它未识别失败。 */
        UNSUPPORTED,

        /** 指向私网、环回或链路本地地址。 */
        PRIVATE_ADDRESS,

        /** 服务端返回 3xx（未跟随重定向）。 */
        REDIRECT,

        /** 服务端返回非 2xx 状态码（403/404 等），具体码由 [HttpStatusException] 携带。 */
        HTTP_STATUS,

        /** 域名解析失败（DNS 无结果）。 */
        DNS_FAILED,

        /** 无法建立连接（连接被拒/重置、TLS 握手失败等）。 */
        CONNECT_FAILED,

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
    fun newClient(
        maxBytesProvider: () -> Long,
        timeoutSeconds: Int,
        /** 单次请求的连接诊断回调（DNS/代理/实际连接地址/协议/TLS）；未开启日志时静默。 */
        logEvent: (Map<String, Any?>) -> Unit = {}
    ): OkHttpClient {
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
            // 重试与协议协商均用 OkHttp 默认：多 IP / 遇协议错误时自动换路由，
            // h2 + http/1.1 按 ALPN 与服务器协商——不再强制 HTTP/1.1，以贴近浏览器/
            // 主流图片库（实测其他笔记 App 同 URL 能加载，差异在我们的配置）。
            .retryOnConnectionFailure(true)
            .addInterceptor(HeaderWhitelistInterceptor)
            .addInterceptor(SizeCapInterceptor(maxBytesProvider))
            // 网络拦截器：连接已建立且位于产生 504 的同一条代码路径上，一定能执行，
            // 据此采集实际连接信息（对端 IP/代理/协商协议/TLS）并落日志。
            // 不用 EventListener：Coil 走同步 execute()，OkHttp 在该路径不保证回调
            // callEnd/callFailed，汇总输出会落空。
            .addNetworkInterceptor(ConnectionProbe(logEvent))
            .build()
    }

    /**
     * 请求头白名单：只保留 Accept + UA + Accept-Language，凭据类头一律剥离。
     *
     * OkHttp 默认不发送 User-Agent，非浏览器特征的请求容易在部分 CDN / WAF / 网关
     * 被误伤（403 / 504 等），必须补齐浏览器级特征头，尽量贴近真实浏览器请求。
     */
    private object HeaderWhitelistInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val stripped = request.newBuilder()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Language", DEFAULT_ACCEPT_LANGUAGE)
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
            // 非 2xx（403/404 等）：不返回响应体，抛携带状态码与响应诊断信息的异常，
            // UI 呈现真实原因；诊断字段供日志辨识错误来源（如 504 由哪一层返回）。
            if (resp.code !in 200..299) throw HttpStatusException(
                code = resp.code,
                requestUrl = resp.request.url.toString(),
                // 全量响应头：若头几乎为空（如无 Server/Via），强烈指向本地/就近拦截
                // 组件伪造该错误而非目标站返回（目标站实测总带 Server 等特征头）。
                responseHeaders = runCatching {
                    resp.headers.joinToString("; ") { "${it.first}:${it.second}" }
                }.getOrNull()?.take(400)?.ifBlank { null },
                // 响应体摘要：非 2xx 常为 HTML 错误页，可能注明拦截原因/来源。
                // 读取失败或超时不阻塞主流程，异常仍按状态码抛出。
                bodyPreview = runCatching {
                    resp.peekBody(1200L).string()
                        .replace('\n', ' ').replace('\r', ' ').trim().take(240)
                }.getOrNull()?.ifBlank { null }
            )
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
        source: Source
    ) : ResponseBody() {

        // ResponseBody.source() 声明返回 BufferedSource，把计数 Source 包一层缓冲源
        private val buffered: BufferedSource = source.buffer()

        override fun contentType() = wrapped.contentType()
        override fun contentLength() = wrapped.contentLength()
        override fun source(): BufferedSource = buffered
        override fun close() = wrapped.close()
    }

    /** 从异常链判定失败分类（供 UI 映射文案）。 */
    fun classify(t: Throwable?): Failure? {
        var cur = t
        while (cur != null) {
            when (cur) {
                is TooLargeException -> return Failure.TOO_LARGE
                is BlockedReason -> return cur.failure
                is HttpStatusException -> return Failure.HTTP_STATUS
                is java.net.SocketTimeoutException -> return Failure.TIMEOUT
                is java.net.UnknownHostException -> return Failure.DNS_FAILED
                is java.net.ConnectException -> return Failure.CONNECT_FAILED
                is java.io.InterruptedIOException -> return Failure.TIMEOUT
                // 其余 IO 失败（连接被拒/重置、TLS 握手失败等）归为连接失败
                is java.io.IOException -> return Failure.CONNECT_FAILED
            }
            cur = cur.cause
        }
        return null
    }

    /** 从异常链提取 [HttpStatusException]（携带状态码与响应诊断字段）。 */
    fun httpStatusExceptionOf(t: Throwable?): HttpStatusException? {
        var cur = t
        while (cur != null) {
            if (cur is HttpStatusException) return cur
            cur = cur.cause
        }
        return null
    }

    /** 从异常链提取 HTTP 状态码（仅 [HttpStatusException] 携带）。 */
    fun httpStatusOf(t: Throwable?): Int? = httpStatusExceptionOf(t)?.code

    /**
     * 单次请求的连接诊断（网络拦截器）：在连接已建立、请求即将发往服务端的时刻，
     * 采集实际 TCP 对端地址、是否走代理、协商协议（h2 / http/1.1）、TLS 版本与 SNI，
     * 拿到响应后再记状态码，一并输出。
     *
     * 用途：远程图命中来源不明的 504/403 时，据此判断 App 到底连到了哪个 IP / 是否经本地
     * 代理 / 走了哪种协议——把「目标站 vs 中间网关 vs 本地代理」定位到具体一层。
     * 每个请求（含成功）都会输出一条，便于拿成功样本对比失败样本。
     */
    private class ConnectionProbe(
        private val emit: (Map<String, Any?>) -> Unit
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val info = HashMap<String, Any?>()
            info["url"] = chain.request().url.toString().take(180)
            chain.connection()?.let { c ->
                info["proto"] = c.protocol().toString()
                c.handshake()?.let { h -> info["tls"] = h.tlsVersion }
                runCatching {
                    val r = c.route()
                    info["peer"] =
                        "${r.socketAddress.address?.hostAddress ?: "?"}:${r.socketAddress.port}"
                    if (r.proxy.type() != Proxy.Type.DIRECT) {
                        info["proxy"] = r.proxy.toString().take(120)
                    }
                }
            }
            val resp = chain.proceed(chain.request())
            info["status"] = resp.code
            runCatching { emit(info) }
            return resp
        }
    }
}
