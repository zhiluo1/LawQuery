package com.lawquery.core.net

import android.os.Build
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/**
 * OkHttp 客户端工厂(项目文档 4.1 通用 HTTP 约定):
 * - 超时 connect 10s / read 20s / write 20s
 * - **不配置 OkHttp Cache**(需求 6.1 磁盘缓存禁用),请求头携带 Cache-Control: no-cache
 * - 拦截链:日志(Debug) → 退避重试 → 限流 → 统一 UA
 */
object HttpClientFactory {

    fun create(
        health: SourceHealthTracker,
        debugLogging: Boolean = false,
    ): OkHttpClient {
        val ua = UaProvider.browserCompatibleUa(
            androidRelease = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL ?: "Android",
        )
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // 注意:刻意不调用 cache(),确保每次进入页面对官方源均为实时请求(需求 6.1)
            // 链序:UA/禁缓存 → 退避重试(重试再次经过限流) → 限流(最贴近网络)
            .addInterceptor(HeaderInterceptor(ua))
            .addInterceptor(BackoffRetryInterceptor())
            .addInterceptor(ThrottleInterceptor(health = health))
            .apply {
                if (debugLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()
    }
}

/**
 * 统一请求头:如实标识 UA + 禁用缓存。
 *
 * **逐源 UA 例外**:调用方已显式指定 `User-Agent` 时不再覆盖。
 * 个别官方源按 UA 分流到不同站点形态,必须在请求级固定 UA(例:桌面 UA 才拿得到
 * 服务端渲染页面,移动 UA 会被重定向到另一套单页应用)。
 * 该拦截器位于请求构造之后,若无条件覆盖会把数据源侧设定的 UA 冲掉(踩过)。
 */
class HeaderInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder().header("Cache-Control", "no-cache")
        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }
        return chain.proceed(builder.build())
    }
}
