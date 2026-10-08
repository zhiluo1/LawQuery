package com.lawquery.data.repo

import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.data.local.SourceHealthDao
import com.lawquery.data.local.SourceHealthEntity
import com.lawquery.data.source.HealthStatus
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SourceHealth
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceRegistry
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * 数据源状态(项目文档 6.5:设置页"数据源状态"):
 * - 实时健康快照(core/net Tracker)+ Room 持久化(最近一次结果跨进程可见);
 * - 「立即检测」为用户主动触发的轻量请求(需求 4.1 仅用户主动操作)。
 */
class SourceStatusRepository(
    private val tracker: SourceHealthTracker,
    private val dao: SourceHealthDao,
    private val registry: SourceRegistry,
    appScope: CoroutineScope,
) {

    /** 实时快照 + 历史持久化合并(实时优先) */
    val statuses: Flow<Map<SourceId, SourceHealth>> =
        combine(tracker.snapshots, dao.observeAll()) { live, stored ->
            val persisted = stored.mapNotNull { it.toHealth() }.associateBy { it.sourceId }
            live + persisted.filterKeys { it !in live }
        }

    init {
        // 健康变化落库(仅元数据)
        tracker.snapshots
            .onEach { map ->
                map.forEach { (id, health) -> dao.upsert(health.toEntity()) }
            }
            .launchIn(appScope)
    }

    /** 用户主动检测某源:1 条轻量请求;WEB_ONLY 源无程序化通道,直接返回 */
    suspend fun check(sourceId: SourceId): Boolean {
        val source = registry.source(sourceId) ?: return false
        if (source.capability == com.lawquery.data.source.SourceCapability.WEB_ONLY) return false
        return when (source.search(SearchQuery(keyword = "法", page = 1, pageSize = 1))) {
            is com.lawquery.data.source.SourceResult.Success -> true
            else -> false
        }
    }

    companion object {
        fun SourceHealth.toEntity() = SourceHealthEntity(
            sourceId = sourceId.name,
            status = status.name,
            lastSuccessAt = lastSuccessAt?.toEpochSecond()?.let { it * 1000 },
            lastFailureAt = lastFailureAt?.toEpochSecond()?.let { it * 1000 },
            lastReason = lastReason?.name,
            cooldownUntil = cooldownUntil?.toEpochSecond()?.let { it * 1000 },
        )

        fun SourceHealthEntity.toHealth(): SourceHealth? {
            val id = runCatching { SourceId.valueOf(sourceId) }.getOrNull() ?: return null
            return SourceHealth(
                sourceId = id,
                status = runCatching { HealthStatus.valueOf(status) }.getOrDefault(HealthStatus.UNKNOWN),
                lastSuccessAt = lastSuccessAt?.let { OffsetDateTime.now().with(java.time.Instant.ofEpochMilli(it)) },
                lastFailureAt = lastFailureAt?.let { OffsetDateTime.now().with(java.time.Instant.ofEpochMilli(it)) },
                lastReason = lastReason?.let { runCatching { com.lawquery.data.source.FailureReason.valueOf(it) }.getOrNull() },
                cooldownUntil = cooldownUntil?.let { OffsetDateTime.now().with(java.time.Instant.ofEpochMilli(it)) },
            )
        }
    }
}
