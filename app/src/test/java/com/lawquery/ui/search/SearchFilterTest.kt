package com.lawquery.ui.search

import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.data.source.SearchFilters
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索筛选的兜底过滤规则。
 *
 * 筛选条件已**下推到官方接口**(flk 支持时效性/年份/地区三个维度),
 * 客户端过滤只作补漏。但它有个隐性约束 —— 翻页追加的条目也会经过这里,
 * 若实现不一致,用户会在同一份筛选下看到「前 10 条符合 3 条、后 10 条符合 0 条」
 * 这种自相矛盾的结果,也就是「筛选时有时无」。
 *
 * 这些规则用**复刻实现**做对照(不直接调 ViewModel:它依赖 5 个协程仓库,
 * 为几条断言搭一套 mock 不划算)。价值在于把语义钉住 —— 重构时若规则变了,
 * 这些断言会先失败,提醒同步更新真实实现。
 */
class SearchFilterTest {

    private fun ref(
        id: String,
        status: LawStatus = LawStatus.CURRENT,
        year: Int? = 2024,
        authority: String = "国务院",
        source: com.lawquery.data.source.SourceId = com.lawquery.data.source.SourceId.FLK,
    ) = LawRef(
        id = id,
        title = "测试法规$id",
        issuingAuthority = authority,
        docNumber = null,
        publishDate = year?.let { LocalDate.of(it, 6, 1) },
        effectiveDate = null,
        status = status,
        source = source,
        url = "",
    )

    /** 复刻 SearchViewModel.visibleItems 的规则(含人民法院案例库的年份豁免) */
    private fun filter(items: List<LawRef>, f: SearchFilters) =
        items.filter { ref ->
            (f.status == null || ref.status == f.status) &&
                (f.year == null || ref.source == com.lawquery.data.source.SourceId.CASE_LIBRARY ||
                    ref.publishDate?.year == f.year) &&
                (f.authority.isNullOrBlank() || ref.issuingAuthority.contains(f.authority!!)) &&
                (f.dateFrom == null || (ref.publishDate != null && !ref.publishDate.isBefore(f.dateFrom))) &&
                (f.dateTo == null || (ref.publishDate != null && !ref.publishDate.isAfter(f.dateTo)))
        }

    private val sample = listOf(
        ref("a", LawStatus.CURRENT, 2024),
        ref("b", LawStatus.REVISED, 2024),
        ref("c", LawStatus.CURRENT, 2020),
        ref("d", LawStatus.PENDING, 2020),
    )

    @Test
    fun `空筛选返回全部`() {
        assertEquals(4, filter(sample, SearchFilters()).size)
    }

    @Test
    fun `时效性筛选只保留严格相等的条目`() {
        val r = filter(sample, SearchFilters(status = LawStatus.CURRENT))
        assertEquals(2, r.size)
        assertTrue(r.all { it.status == LawStatus.CURRENT })
    }

    @Test
    fun `年份筛选只保留该年公布的条目`() {
        val r = filter(sample, SearchFilters(year = 2020))
        assertEquals(2, r.size)
        assertTrue(r.all { it.publishDate?.year == 2020 })
    }

    /** 多维度取交集:2020 + 现行有效 → 只剩 c */
    @Test
    fun `多维度同时筛选取交集`() {
        val r = filter(sample, SearchFilters(status = LawStatus.CURRENT, year = 2020))
        assertEquals(1, r.size)
        assertEquals("c", r.first().id)
    }

    @Test
    fun `发文机关按名称包含匹配`() {
        val items = listOf(
            ref("a", authority = "国务院"),
            ref("b", authority = "国务院办公厅"),
            ref("c", authority = "最高人民法院"),
        )
        assertEquals(2, filter(items, SearchFilters(authority = "国务院")).size)
        assertEquals(1, filter(items, SearchFilters(authority = "最高人民法院")).size)
    }

    /**
     * 地区筛选只在地方性法规分类下开放。
     *
     * 其余位阶由中央机关制定,没有省级维度 —— 若在这些分类下也显示地区选择器,
     * 选了必然查不到东西(实测下推 `zdjgCodeId` 会把结果清空)。
     */
    @Test
    fun `地区筛选仅地方性法规可用`() {
        val supports = { c: LawCategory? -> c == LawCategory.LOCAL }
        assertTrue(supports(LawCategory.LOCAL))
        listOf(
            LawCategory.CONSTITUTION, LawCategory.LAW, LawCategory.ADMIN_REG,
            LawCategory.SUPERVISION, LawCategory.JUDICIAL, LawCategory.STATE_COUNCIL,
            LawCategory.CASE_LIBRARY, null,
        ).forEach { c ->
            assertTrue("分类 $c 不应支持地区", !supports(c))
        }
    }

    /**
     * 人民法院案例库的年份豁免(v5 修复的纰漏)。
     *
     * 案例库的年份已由官方 `year_cpwsAl` **服务端分面**过滤,客户端不再二次判定。
     * 官方属性串偶尔取不到裁判日期(此时 `publishDate == null`),若本地再按年份过滤,
     * 会把服务端明明返回了的有效案例整片误杀 —— 表现为「一按年份筛选,案例库全没了」。
     * 分类浏览页早已有同一豁免,搜索页此前漏了,两处行为不一致。
     */
    @Test
    fun `案例库条目缺裁判日期时不被年份筛选误杀`() {
        val alk = ref("x", year = null, source = com.lawquery.data.source.SourceId.CASE_LIBRARY)
        val out = filter(listOf(alk), SearchFilters(year = 2024))
        assertEquals("案例库不应因缺日期被整片筛掉", 1, out.size)
    }

    /** 反面对照:豁免**只**给案例库,其他来源缺日期仍按年份筛掉(防止豁免扩大化) */
    @Test
    fun `非案例库来源缺日期时仍被年份筛掉`() {
        val flk = ref("y", year = null, source = com.lawquery.data.source.SourceId.FLK)
        assertEquals(0, filter(listOf(flk), SearchFilters(year = 2024)).size)
    }

    /** 豁免仅限年份维度:时效性 / 发文机关仍要正常过滤 */
    @Test
    fun `案例库豁免仅限年份维度`() {
        val alk = ref("k", status = LawStatus.REPEALED, year = null,
            source = com.lawquery.data.source.SourceId.CASE_LIBRARY)
        // 年份维度:被豁免,条目保留
        assertEquals(1, filter(listOf(alk), SearchFilters(year = 2024)).size)
        // 时效性维度:仍然生效(REPEALED 被 CURRENT 筛掉)
        assertEquals(0, filter(listOf(alk), SearchFilters(status = LawStatus.CURRENT, year = 2024)).size)
        // 发文机关维度:仍然生效("国务院" 不含 "最高人民法院")
        assertEquals(0, filter(listOf(alk), SearchFilters(authority = "最高人民法院", year = 2024)).size)
    }
}