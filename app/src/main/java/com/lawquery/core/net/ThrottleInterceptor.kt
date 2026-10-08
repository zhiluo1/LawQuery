package com.lawquery.core.net

import com.lawquery.data.source.SourceId
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 限流拦截器(项目文档 4.1 / 需求 7.2.3):
 * - 全局并发 ≤ 2
 * - 同一源两次请求间隔 ≥ 2s
 * - 收到 403/429:立即放弃并标记该源冷却 10 分钟,冷却期内请求直接抛 [RateLimitedException],
 *   不发网络请求
 *
 * 拦截器运行在 OkHttp 线程池上,采用阻塞等待(可注入 clock/sleeper 便于单测虚拟时钟)。
 */
class ThrottleInterceptor(
    private val maxConcurrent: Int = 2,
    private val minIntervalMs: Long = 2_000,
    private val cooldownMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val health: SourceHealthTracker? = null,
    private val sourceOfHost: (String) -> SourceId? = HostSourceMapper::sourceIdOf,
) : Interceptor {

    private val inFlight = AtomicInteger(0)
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val hostLocks = ConcurrentHashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val sourceId = sourceOfHost(host)

        // 冷却期检查:期间直接走降级,不发网络请求
        val until = cooldownUntil[host]
        if (until != null) {
            if (clock() < until) throw RateLimitedException("源处于限流冷却期:$host")
            cooldownUntil.remove(host, until)
        }

        try {
            acquireSlot()
            try {
                waitForInterval(host)
                lastRequestAt[host] = clock()
                val response = chain.proceed(chain.request())
                if (response.code == 403 || response.code == 429) {
                    response.close()
                    markCooldown(host, sourceId)
                    throw RateLimitedException("HTTP ${response.code},源已冷却:$host")
                }
                if (sourceId != null) health?.recordSuccess(sourceId)
                return response
            } finally {
                releaseSlot()
            }
        } catch (e: RateLimitedException) {
            throw e
        } catch (e: IOException) {
            if (sourceId != null) health?.recordFailure(sourceId, reasonOf(e))
            throw e
        }
    }

    private fun acquireSlot() {
        // CAS 循环:并发下保证同时在途请求数不超过 maxConcurrent
        while (true) {
            val current = inFlight.get()
            if (current >= maxConcurrent) {
                sleeper(50)
                continue
            }
            if (inFlight.compareAndSet(current, current + 1)) return
        }
    }

    private fun releaseSlot() {
        inFlight.decrementAndGet()
    }

    private fun waitForInterval(host: String) {
        val lock = hostLocks.computeIfAbsent(host) { Any() }
        synchronized(lock) {
            val last = lastRequestAt[host]
            if (last != null) {
                val elapsed = clock() - last
                if (elapsed < minIntervalMs) sleeper(minIntervalMs - elapsed)
            }
        }
    }

    private fun markCooldown(host: String, sourceId: SourceId?) {
        cooldownUntil[host] = clock() + cooldownMs
        if (sourceId != null) health?.markCooldown(sourceId, cooldownMs)
    }

    private fun reasonOf(e: IOException) = when (e) {
        is java.net.SocketTimeoutException -> com.lawquery.data.source.FailureReason.TIMEOUT
        else -> com.lawquery.data.source.FailureReason.NETWORK
    }
}

/** 官方域名 → 源标识 映射(白名单依据需求 7.4) */
object HostSourceMapper {
    fun sourceIdOf(host: String): SourceId? = when {
        host == "www.gov.cn" || host.endsWith(".www.gov.cn") -> SourceId.GOV_CN
        // 案例库单列一源,避免其检索请求计入最高人民法院源的健康/限流统计
        host == "rmfyalk.court.gov.cn" -> SourceId.CASE_LIBRARY
        host == "www.court.gov.cn" || host.endsWith(".court.gov.cn") -> SourceId.COURT
        /*
         * ⚠️ 此处曾是 `null`,注释写着「flk 仅 WebView 直通,原生通道不访问」——
         * 那是 flk 原生化之前的旧事实。现在六个法规分类全部由 FlkSource 原生供数,
         * 其请求走的就是本拦截器:映射成 null 会让 flk 的请求**不计健康、不计冷却**
         * (间隔与并发仍按域名生效),表现为「数据源状态」里 flk 常年 UNKNOWN,
         * 且官方 WAF 返回 403 时不会进入 10 分钟冷却、被连续重试。
         */
        host == "flk.npc.gov.cn" -> SourceId.FLK
        // 规章库前置创宇盾风控,务必让它走与国务院的逻辑一致的限流/冷却
        host == "app.mps.gov.cn" -> SourceId.MPS_REG
        else -> null
    }
}
