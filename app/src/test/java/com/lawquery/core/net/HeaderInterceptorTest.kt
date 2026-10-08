package com.lawquery.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统一请求头拦截器单测。
 *
 * 回归背景:该拦截器位于请求构造之后,若**无条件**写 `User-Agent`,会把数据源侧
 * 显式设定的 UA 冲掉(曾导致按 UA 分流的官方源被重定向到移动版页面、正文获取失败)。
 */
class HeaderInterceptorTest {

    private val unifiedUa = "LawQuery/1.0.0 (Android 14; test)"

    private fun server(): MockWebServer = MockWebServer().apply {
        start()
        enqueue(MockResponse().setBody("ok"))
        enqueue(MockResponse().setBody("ok"))
        enqueue(MockResponse().setBody("ok"))
    }

    private fun client(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(HeaderInterceptor(unifiedUa)).build()

    @Test
    fun `未指定 UA 时补上统一 UA 并禁用缓存`() {
        val s = server()
        client().newCall(Request.Builder().url(s.url("/a")).build()).execute().use { }
        val recorded = s.takeRequest()
        assertEquals(unifiedUa, recorded.getHeader("User-Agent"))
        assertEquals("no-cache", recorded.getHeader("Cache-Control"))
        s.shutdown()
    }

    @Test
    fun `请求级显式指定的 UA 不被覆盖`() {
        val s = server()
        val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36"
        client().newCall(
            Request.Builder().url(s.url("/b")).header("User-Agent", desktopUa).build()
        ).execute().use { }
        assertEquals(desktopUa, s.takeRequest().getHeader("User-Agent"))
        s.shutdown()
    }

    @Test
    fun `覆盖 UA 的请求同样带上禁缓存头`() {
        val s = server()
        client().newCall(
            Request.Builder().url(s.url("/c")).header("User-Agent", "custom-ua").build()
        ).execute().use { }
        val recorded = s.takeRequest()
        assertEquals("custom-ua", recorded.getHeader("User-Agent"))
        assertEquals("no-cache", recorded.getHeader("Cache-Control"))
        s.shutdown()
    }
}
