package com.lawquery.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避重试单测(项目文档 4.1:5xx/超时重试至多 1 次;4xx 不重试)。
 */
class BackoffRetryInterceptorTest {

    private fun client(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(BackoffRetryInterceptor(delayMs = 1)).build()

    @Test
    fun `5xx 退避后重试一次成功`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("ok"))

        val resp = client().newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(200, resp.code)
        resp.close()
        assertEquals("5xx 应恰好重试 1 次", 2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `5xx 重试后仍失败则返回最后一次响应`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(503))

        val resp = client().newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(503, resp.code)
        resp.close()
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `4xx 不重试`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))

        val resp = client().newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(404, resp.code)
        resp.close()
        assertEquals("4xx 不应重试", 1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `限流异常原样放行不吞掉`() {
        val server = MockWebServer()
        server.start()
        val failing = OkHttpClient.Builder()
            .addInterceptor { chain ->
                throw RateLimitedException("冷却期")
                @Suppress("UNREACHABLE_CODE")
                chain.proceed(chain.request())
            }
            .addInterceptor(BackoffRetryInterceptor(delayMs = 1))
            .build()

        val result = runCatching {
            failing.newCall(Request.Builder().url(server.url("/")).build()).execute()
        }
        assertTrue(result.exceptionOrNull() is RateLimitedException)
        assertEquals("RateLimitedException 不应触发重试", 0, server.requestCount)
        server.shutdown()
    }
}
