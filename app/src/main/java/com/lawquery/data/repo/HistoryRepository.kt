package com.lawquery.data.repo

import com.lawquery.data.local.RecentDao
import com.lawquery.data.local.RecentEntity
import com.lawquery.data.local.SearchHistoryDao
import com.lawquery.data.local.SearchHistoryEntity
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * 历史记录(需求 F1/F5):
 * - 搜索历史:仅查询词,≤50 条,可单删、清空,不上传;
 * - 最近浏览:仅元数据,≤50 条滚动淘汰。
 */
class HistoryRepository(
    private val historyDao: SearchHistoryDao,
    private val recentDao: RecentDao,
) {

    fun observeHistory(): Flow<List<SearchHistoryEntity>> = historyDao.observeRecent()

    suspend fun recordHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        historyDao.upsert(SearchHistoryEntity(query = q, searchedAt = System.currentTimeMillis()))
        historyDao.evictBeyond50()
    }

    suspend fun deleteHistory(query: String) = historyDao.delete(query)

    suspend fun clearHistory() = historyDao.deleteAll()

    suspend fun suggest(keyword: String): List<String> =
        historyDao.suggest(keyword.trim()).map { it.query }

    fun observeRecents(limit: Int = 50): Flow<List<RecentEntity>> = recentDao.observeRecent(limit)

    suspend fun recordRecent(ref: LawRef) {
        recentDao.upsert(ref.toRecentEntity(System.currentTimeMillis()))
        recentDao.evictBeyond50()
    }

    suspend fun clearRecents() = recentDao.deleteAll()
}

fun LawRef.toRecentEntity(browsedAt: Long) = RecentEntity(
    refKey = key,
    sourceId = source.name,
    docId = id,
    title = title,
    url = url,
    issuingAuthority = issuingAuthority,
    docNumber = docNumber,
    publishDateEpochDay = publishDate?.toEpochDay(),
    effectiveDateEpochDay = effectiveDate?.toEpochDay(),
    status = status.name,
    browsedAt = browsedAt,
)

fun RecentEntity.toLawRef() = LawRef(
    id = docId,
    title = title,
    issuingAuthority = issuingAuthority,
    docNumber = docNumber,
    publishDate = publishDateEpochDay?.let { LocalDate.ofEpochDay(it) },
    effectiveDate = effectiveDateEpochDay?.let { LocalDate.ofEpochDay(it) },
    status = runCatching { LawStatus.valueOf(status) }.getOrDefault(LawStatus.CURRENT),
    source = com.lawquery.data.source.SourceId.valueOf(sourceId),
    url = url,
)
