package com.az.notes.data.ai

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * AI 网络层共用的 OkHttp 客户端（§4）：
 * - [api]：常规请求（配置页「测试连接」等），短超时；
 * - [streaming]：SSE 流式会话，readTimeout / callTimeout 置 0（长输出不主动中断），
 *   超时由应用层「无数据 90s」检测兜底（B2 对话内核起使用）。
 * 两个实例均无外部依赖，直接在构造器内构建，不建 Hilt Module。
 */
@Singleton
class AiHttpClient @Inject constructor() {

    /** 常规请求客户端（测试连接 / 模型拉取等）。 */
    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 流式请求客户端（SSE：不设读取超时）。 */
    val streaming: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
