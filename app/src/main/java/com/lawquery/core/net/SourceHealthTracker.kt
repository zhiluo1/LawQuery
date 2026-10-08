package com.lawquery.core.net

import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.HealthStatus
import com.lawquery.data.source.SourceHealth
import com.lawquery.data.source.SourceId
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 源健康记录(项目文档 4.1 / 需求 3.4):
 * 记录各源最近一次请求结果;403/429 时进入 COOLDOWN(10 分钟),期间该源走降级链路。
 */
class SourceHealthTracker(private val now: () -> OffsetDateTime = OffsetDateTime::now) {

    data class Internal(
        val status: HealthStatus = HealthStatus.UNKNOWN,
        val lastSuccessAt: OffsetDateTime? = null,
        val lastFailureAt: OffsetDateTime? = null,
        val lastReason: FailureReason? = null,
        val cooldownUntil: OffsetDateTime? = null,
    )

    private val states = ConcurrentHashMap<SourceId, Internal>()
    private val _updates = MutableStateFlow<Map<SourceId, SourceHealth>>(emptyMap())

    /** 供设置页"数据源状态"观察(项目文档 6.5) */
    val snapshots: StateFlow<Map<SourceId, SourceHealth>> = _updates.asStateFlow()

    fun recordSuccess(sourceId: SourceId) = update(sourceId) {
        it.copy(status = HealthStatus.OK, lastSuccessAt = now(), lastReason = null)
    }

    fun recordFailure(sourceId: SourceId, reason: FailureReason) = update(sourceId) {
        it.copy(
            status = if (it.status == HealthStatus.COOLDOWN) it.status else HealthStatus.DEGRADED,
            lastFailureAt = now(),
            lastReason = reason,
        )
    }

    fun markCooldown(sourceId: SourceId, cooldownMs: Long) {
        val until = now().plusNanos(cooldownMs * 1_000_000)
        update(sourceId) {
            it.copy(
                status = HealthStatus.COOLDOWN,
                lastFailureAt = now(),
                lastReason = FailureReason.RATE_LIMITED,
                cooldownUntil = until,
            )
        }
    }

    fun isCoolingDown(sourceId: SourceId): Boolean {
        val s = states[sourceId] ?: return false
        val until = s.cooldownUntil ?: return false
        return now().isBefore(until)
    }

    fun snapshot(sourceId: SourceId): SourceHealth {
        val s = states[sourceId] ?: Internal()
        return SourceHealth(
            sourceId = sourceId,
            status = s.status,
            lastSuccessAt = s.lastSuccessAt,
            lastFailureAt = s.lastFailureAt,
            lastReason = s.lastReason,
            cooldownUntil = s.cooldownUntil,
        )
    }

    private fun update(sourceId: SourceId, transform: (Internal) -> Internal) {
        val next = transform(states[sourceId] ?: Internal())
        states[sourceId] = next
        _updates.value = _updates.value + (sourceId to snapshot(sourceId))
    }
}
