package com.lawquery.core.net

import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.HealthStatus
import com.lawquery.data.source.SourceId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 限流器单测(项目文档 M1 退出标准:并发 ≤2、同源间隔 ≥2s、403 退避逻辑)。
 */
class ThrottleInterceptorTest {

    private fun client(throttle: ThrottleInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(throttle).build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).build()

    @Test
    fun `同一主机两次请求强制间隔2秒(虚拟时钟)`() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))

        val throttle = ThrottleInterceptor(
            minIntervalMs = 2_000,
            clock = { now },
            sleeper = { now += it; waits += it },
        )
        val c = client(throttle)
        c.newCall(request(server.url("/a"))).execute().use { }
        c.newCall(request(server.url("/b"))).execute().use { }

        assertEquals(listOf(2_000L), waits)
        server.shutdown()
    }

    @Test
    fun `不同主机不等待间隔`() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val s1 = MockWebServer(); s1.start()
        val s2 = MockWebServer().also { it.start() }
        // 两台 MockWebServer 分别绑定 127.0.0.1 与 localhost 以区分主机名
        val url1 = s1.url("/a").newBuilder().host("127.0.0.1").build()
        val url2 = s2.url("/a").newBuilder().host("localhost").build()
        s1.enqueue(MockResponse().setBody("ok"))
        s2.enqueue(MockResponse().setBody("ok"))

        val throttle = ThrottleInterceptor(
            minIntervalMs = 2_000,
            clock = { now },
            sleeper = { now += it; waits += it },
        )
        val c = client(throttle)
        c.newCall(request(url1)).execute().use { }
        c.newCall(request(url2)).execute().use { }

        assertTrue("不同主机不应触发间隔等待,实际等待=$waits", waits.isEmpty())
        s1.shutdown(); s2.shutdown()
    }

    @Test
    fun `403后进入冷却且后续请求不发网络(验收E3)`() {
        var now = 0L
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(403))

        val tracker = SourceHealthTracker()
        val throttle = ThrottleInterceptor(
            clock = { now },
            sleeper = { now += it },
            health = tracker,
            sourceOfHost = { SourceId.GOV_CN }, // 测试注入:把 mock 主机映射到源
        )
        val c = client(throttle)

        val first = runCatching { c.newCall(request(server.url("/x"))).execute() }
        assertTrue(first.exceptionOrNull() is RateLimitedException)
        assertEquals(HealthStatus.COOLDOWN, tracker.snapshot(SourceId.GOV_CN).status)
        assertEquals(FailureReason.RATE_LIMITED, tracker.snapshot(SourceId.GOV_CN).lastReason)

        // 冷却期内:直接抛出,不再产生网络请求
        val second = runCatching { c.newCall(request(server.url("/x"))).execute() }
        assertTrue(second.exceptionOrNull() is RateLimitedException)
        assertEquals("冷却期内不应发出第二次网络请求", 1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `全局并发不超过2`() = runBlocking {
        val server = MockWebServer()
        val current = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cur = current.incrementAndGet()
                maxOverlap.updateAndGet { old -> maxOf(old, cur) }
                Thread.sleep(120)
                current.decrementAndGet()
                return MockResponse().setBody("ok")
            }
        }
        server.start()

        val throttle = ThrottleInterceptor(maxConcurrent = 2, minIntervalMs = 1)
        val c = client(throttle)
        val url = server.url("/p")
        (1..4).map {
            async(Dispatchers.IO) { c.newCall(request(url)).execute().use { it.code } }
        }.awaitAll()

        assertTrue("服务端观测到的并发重叠=${maxOverlap.get()}", maxOverlap.get() <= 2)
        server.shutdown()
    }
}
