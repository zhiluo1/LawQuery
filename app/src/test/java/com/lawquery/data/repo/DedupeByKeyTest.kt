package com.lawquery.data.repo

import com.lawquery.data.source.SearchSort
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表 key 去重(崩溃修复)。
 *
 * 2026-10-06 实测崩溃:
 * ```
 * FATAL EXCEPTION: main
 * java.lang.IllegalArgumentException: Key "COURT:188631" was already used.
 *   If you are using LazyColumn/Row please make sure you provide a unique key for each item.
 * ```
 *
 * 各列表把 `LawRef.key`(源:源内 id)直接当 LazyColumn 的 item key,
 * 一旦出现两条同 key 的项,Compose 在 measure 阶段就抛异常**让进程崩掉**。
 *
 * 重复的两个来源:
 * 1. [com.lawquery.data.repo.SearchRepository.search] 合并多个源的结果;
 * 2. 各页「加载更多」跨页追加 —— 分页边界常把上一页末条再返回一次,
 *    实测 `COURT`(最高法官网列表抓取)在边界上重复返回同一条。
 */
class DedupeByKeyTest {

    private fun ref(id: String, source: SourceId = SourceId.COURT, day: Int = 1) = LawRef(
        id = id,
        title = "法规$id",
        issuingAuthority = "最高人民法院",
        docNumber = null,
        publishDate = LocalDate.of(2026, 1, day.coerceIn(1, 28)),
        effectiveDate = null,
        status = LawStatus.CURRENT,
        source = source,
        url = "",
    )

    @Test
    fun `同 key 只保留首条`() {
        val a = ref("188631")
        val dup = ref("188631")
        val b = ref("188632")
        val out = SearchRepository.dedupeByKey(listOf(a, dup, b))
        assertEquals(2, out.size)
        assertEquals("188631", out[0].id)
        assertEquals("188632", out[1].id)
    }

    /** 不同源的同 id 不算重复:key 带源前缀,两条都要保留 */
    @Test
    fun `不同源的相同 id 视为不同条目`() {
        val out = SearchRepository.dedupeByKey(
            listOf(
                ref("1001", SourceId.COURT),
                ref("1001", SourceId.FLK),
                ref("1001", SourceId.GOV_CN),
            )
        )
        assertEquals(3, out.size)
    }

    @Test
    fun `无重复时保持原顺序`() {
        val input = (1..5).map { ref("id$it") }
        assertEquals(input, SearchRepository.dedupeByKey(input))
    }

    @Test
    fun `空表与单条原样返回`() {
        assertEquals(0, SearchRepository.dedupeByKey(emptyList()).size)
        val one = listOf(ref("x"))
        assertEquals(one, SearchRepository.dedupeByKey(one))
    }

    /** 跨页追加:新页里的旧条目(分页边界重复)要被挡掉 */
    @Test
    fun `追加时挡掉与已有重复的条目`() {
        val existing = listOf(ref("1"), ref("2"), ref("3"))
        val more = listOf(ref("3"), ref("4"), ref("4"), ref("5"))
        val out = SearchRepository.appendUnique(existing, more)
        assertEquals(listOf("1", "2", "3", "4", "5"), out.map { it.id })
    }

    @Test
    fun `追加空页时原样返回`() {
        val existing = listOf(ref("1"))
        assertEquals(existing, SearchRepository.appendUnique(existing, emptyList()))
    }

    @Test
    fun `追加到空列表时也去重`() {
        val out = SearchRepository.appendUnique(emptyList(), listOf(ref("1"), ref("1")))
        assertEquals(1, out.size)
    }

    /**
     * 日期倒序浏览的跨页追加必须**整体重排**。
     *
     * 各源每页容量不同(政府网/最高法 15、规章库 50、案例库 20),页码是各源独立的
     * 窗口;只做拼接会出现「第 1 页末尾是 2008 年,第 2 页开头又回到 2026 年」的
     * 日期倒跳,用户会以为列表坏了。
     */
    @Test
    fun `日期倒序浏览时跨页追加后整体按日期重排`() {
        val existing = listOf(
            ref("a", day = 20), ref("b", day = 10), ref("c", day = 5),
        )
        // 第 2 页里混着比第 1 页更旧的条和更"新"的条(各源窗口不同导致)
        val more = listOf(ref("d", day = 15), ref("e", day = 1))
        val out = SearchRepository.appendForBrowse(existing, more, SearchSort.PUBLISH_DATE_DESC)
        assertEquals(listOf("a", "d", "b", "c", "e"), out.map { it.id })
    }

    /** 相关度序不能跨页重排:那是各页独立算的「名称优先」顺序 */
    @Test
    fun `相关度序跨页追加只去重不重排`() {
        val existing = listOf(ref("a", day = 20), ref("b", day = 10), ref("c", day = 5))
        val more = listOf(ref("d", day = 15))
        val out = SearchRepository.appendForBrowse(existing, more, SearchSort.RELEVANCE)
        assertEquals(listOf("a", "b", "c", "d"), out.map { it.id })
    }

    /** 发布日期为空的条目排在最后,不能因为 null 崩掉比较 */
    @Test
    fun `日期为空的条目排在末尾`() {
        val dated = ref("a", day = 3)
        val undated = LawRef(
            id = "z", title = "无日期", issuingAuthority = "最高人民法院",
            docNumber = null, publishDate = null, effectiveDate = null,
            status = LawStatus.CURRENT, source = SourceId.COURT, url = "",
        )
        val out = SearchRepository.appendForBrowse(
            listOf(undated), listOf(dated), SearchSort.PUBLISH_DATE_DESC
        )
        assertEquals(listOf("a", "z"), out.map { it.id })
    }

    /**
     * 不变式:任何路径产出的列表都**不得含重复 key**。
     * LazyColumn 的硬性要求,违反即崩。
     */
    @Test
    fun `产出列表的 key 唯一性不变式`() {
        val sources = listOf(
            listOf(ref("1"), ref("1"), ref("2")),            // 单源内重复
            listOf(ref("2"), ref("3"), ref("3")),            // 跨源重复
            listOf(ref("9")),// 新增
        )
        val merged = sources.flatten()
        val deduped = SearchRepository.dedupeByKey(merged)
        assertEquals(
            "去重后不应有重复 key",
            deduped.size,
            deduped.map { it.key }.distinct().size,
        )
    }
}