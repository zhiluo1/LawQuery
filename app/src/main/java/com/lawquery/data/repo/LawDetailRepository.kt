package com.lawquery.data.repo

import com.lawquery.core.sessioncache.SessionCache
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceRegistry
import com.lawquery.data.source.SourceResult
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef

/**
 * 详情仓库(需求 5.4 数据流 / 6.1 缓存策略):
 * 1. 命中内存会话缓存(TTL 5min)→ 直接返回(带原 fetchedAt);
 * 2. 未命中 → LawSource.fetchDocument 实时拉取 → 写入会话缓存;
 * 3. 任何路径都不读磁盘正文——磁盘里没有正文表。
 */
class LawDetailRepository(
    private val registry: SourceRegistry,
    private val cache: SessionCache,
    private val history: HistoryRepository,
) {

    sealed interface Outcome {
        data class Ready(val document: LawDocument, val fromCache: Boolean) : Outcome
        data class Unavailable(val source: SourceId, val reason: FailureReason) : Outcome
    }

    suspend fun getLaw(ref: LawRef): Outcome {
        cache.get(ref.key)?.let { return Outcome.Ready(it, fromCache = true) }

        val source = registry.source(ref.source)
            ?: return Outcome.Unavailable(ref.source, FailureReason.PARSE_FAILED)

        return when (val result = source.fetchDocument(ref)) {
            is SourceResult.Success -> {
                val doc = result.data
                cache.put(ref.key, doc)
                history.recordRecent(doc.ref)
                Outcome.Ready(doc, fromCache = false)
            }
            is SourceResult.SourceUnavailable -> Outcome.Unavailable(result.source, result.reason)
        }
    }
}
