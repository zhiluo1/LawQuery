package com.lawquery.core.net

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 退避重试拦截器(项目文档 4.1):
 * - 5xx 与网络超时:退避 2s 后重试至多 1 次
 * - 4xx 不重试(403/429 由限流拦截器直接抛 [RateLimitedException],此处原样放行)
 */
class BackoffRetryInterceptor(
    private val delayMs: Long = 2_000,
    private val maxRetries: Int = 1,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastError: IOException? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            if (attempt > 0) sleeper(delayMs)
            try {
                response?.close()
                response = chain.proceed(request)
                if (response.code in 500..599 && attempt < maxRetries) {
                    attempt++
                    continue
                }
                return response
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                lastError = e
                attempt++
            }
        }
        return response ?: throw (lastError ?: IOException("重试后仍失败"))
    }
}
