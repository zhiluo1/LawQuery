package com.lawquery.core.sessioncache

import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.SourceId
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话缓存单测(需求 6.1:TTL 5 分钟、容量 20、携带 fetchedAt、进程内存活)。
 */
class SessionCacheTest {

    private fun ref(id: String) = LawRef(
        id = id,
        title = "测试法律$id",
        issuingAuthority = "全国人大",
        docNumber = null,
        publishDate = LocalDate.of(2024, 1, 1),
        effectiveDate = null,
        status = LawStatus.CURRENT,
        source = SourceId.GOV_CN,
        url = "https://www.gov.cn/$id",
    )

    private fun doc(id: String, fetchedAt: OffsetDateTime) = LawDocument(
        ref = ref(id),
        fetchedAt = fetchedAt,
        preamble = emptyList(),
        chapters = emptyList(),
    )

    @Test
    fun `TTL过期后不可读取`() {
        var now = 0L
        val cache = SessionCache(ttlMs = 5 * 60_000, clock = { now })
        val t = OffsetDateTime.now()
        cache.put("k", doc("k", t), fetchedAtMs = now)

        now = 4 * 60_000
        assertEquals(t, cache.get("k")?.fetchedAt)

        now = 6 * 60_000
        assertNull("超过 TTL 5 分钟应失效", cache.get("k"))
    }

    @Test
    fun `LRU容量淘汰最旧条目`() {
        var now = 0L
        val cache = SessionCache(maxSize = 2, ttlMs = Long.MAX_VALUE, clock = { now })
        val t = OffsetDateTime.now()
        cache.put("a", doc("a", t), now)
        now += 1; cache.put("b", doc("b", t), now)
        now += 1; cache.put("c", doc("c", t), now)

        assertEquals(2, cache.size())
        assertNull("最早写入且未访问的 a 应被淘汰", cache.get("a"))
        assertEquals("b", cache.get("b")?.ref?.id)
        assertEquals("c", cache.get("c")?.ref?.id)
    }

    @Test
    fun `访问可续命LRU顺序且fetchedAt保持原值`() {
        var now = 0L
        val cache = SessionCache(maxSize = 2, ttlMs = Long.MAX_VALUE, clock = { now })
        val tA = OffsetDateTime.parse("2026-10-01T08:00+08:00")
        val tB = OffsetDateTime.parse("2026-10-01T08:01+08:00")
        cache.put("a", doc("a", tA), now)
        now += 1; cache.put("b", doc("b", tB), now)

        // 访问 a,使 b 成为最旧
        now += 1
        assertEquals(tA, cache.get("a")?.fetchedAt)
        now += 1; cache.put("c", doc("c", tB), now)

        assertNull("b 应被淘汰", cache.get("b"))
        assertEquals("fetchedAt 不因缓存续命而改变(需求 D1)", tA, cache.get("a")?.fetchedAt)
    }
}
