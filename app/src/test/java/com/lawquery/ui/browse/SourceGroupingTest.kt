package com.lawquery.ui.browse

import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类列表的**多源按来源分组**(2026-10-08 观感优化)。
 *
 * 背景:「国务院及部委文件」(政府网 + 规章库)与「司法解释」(flk + 最高法)
 * 是把两个官方源的结果并成一条日期倒序列表的 —— 2008 年的部门规章会和 2026 年的
 * 国务院文件相邻,用户只能靠卡片底部的小徽标猜来源。分组后每个来源一段,
 * 标题写明来源名与条数。
 *
 * 这里锁定的都是「分组实现容易写错、且错了不易发现」的点:
 * 1. 组顺序必须由 `category.nativeSourceIds`(产品指定的权威顺序)决定,不能按条数排;
 * 2. 组内必须保持入参相对顺序 —— 调用方已按日期倒序排好,再排一次会打乱;
 * 3. **声明顺序漏写某个源时,该源的条目必须仍然显示**(追加在末尾),
 *    否则会静默丢数据,是最难排查的一类故障;
 * 4. 无条目的源不产生空组标题(空标题看起来像 bug)。
 */
class SourceGroupingTest {

    private fun ref(id: String, source: SourceId, day: Int = 1) = LawRef(
        id = id,
        title = "文件$id",
        issuingAuthority = "最高人民法院",
        docNumber = null,
        publishDate = LocalDate.of(2026, 1, day.coerceIn(1, 28)),
        effectiveDate = null,
        status = LawStatus.CURRENT,
        source = source,
        url = "",
    )

    @Test
    fun `按声明顺序分组且组内保持原顺序`() {
        val items = listOf(
            ref("g1", SourceId.GOV_CN, day = 20),
            ref("m1", SourceId.MPS_REG, day = 19),
            ref("g2", SourceId.GOV_CN, day = 18),
            ref("m2", SourceId.MPS_REG, day = 17),
        )
        val groups = SourceGrouping.group(items, listOf("GOV_CN", "MPS_REG"))

        assertEquals(listOf(SourceId.GOV_CN, SourceId.MPS_REG), groups.map { it.source })
        // 组内顺序 = 入参顺序(已是日期倒序),不能重排
        assertEquals(listOf("g1", "g2"), groups[0].items.map { it.id })
        assertEquals(listOf("m1", "m2"), groups[1].items.map { it.id })
    }

    @Test
    fun `司法解释场景 flk 组在最高法组之前`() {
        val items = listOf(
            ref("c1", SourceId.COURT, day = 20),
            ref("f1", SourceId.FLK, day = 19),
            ref("c2", SourceId.COURT, day = 18),
        )
        val groups = SourceGrouping.group(items, listOf("FLK", "COURT"))
        assertEquals(listOf(SourceId.FLK, SourceId.COURT), groups.map { it.source })
    }

    /** `order` 漏写某个源时,该源追加在末尾而不是被丢掉 */
    @Test
    fun `未在声明顺序里的来源追加末尾而不是丢弃`() {
        val items = listOf(
            ref("g1", SourceId.GOV_CN, day = 10),
            ref("m1", SourceId.MPS_REG, day = 9),
        )
        val groups = SourceGrouping.group(items, listOf("GOV_CN"))
        assertEquals(listOf(SourceId.GOV_CN, SourceId.MPS_REG), groups.map { it.source })
        assertEquals(2, groups.sumOf { it.items.size })
    }

    @Test
    fun `声明顺序含未注册来源名时忽略该名且不影响其他组`() {
        val items = listOf(ref("g1", SourceId.GOV_CN, day = 5))
        val groups = SourceGrouping.group(items, listOf("NOT_A_SOURCE", "GOV_CN"))
        assertEquals(listOf(SourceId.GOV_CN), groups.map { it.source })
    }

    @Test
    fun `无条目的来源不产生空组`() {
        val items = listOf(ref("g1", SourceId.GOV_CN, day = 5))
        val groups = SourceGrouping.group(items, listOf("GOV_CN", "MPS_REG"))
        assertEquals(1, groups.size)
        assertEquals(SourceId.GOV_CN, groups[0].source)
    }

    @Test
    fun `空列表返回空分组`() {
        assertTrue(SourceGrouping.group(emptyList(), listOf("GOV_CN")).isEmpty())
    }

    /** 不变式:分组不得丢条目、不得凭空造条目(LazyColumn 的 key 唯一性依赖这一点) */
    @Test
    fun `分组前后条目集合完全一致`() {
        val items = listOf(
            ref("g1", SourceId.GOV_CN, day = 9),
            ref("m1", SourceId.MPS_REG, day = 8),
            ref("g2", SourceId.GOV_CN, day = 7),
            ref("c1", SourceId.COURT, day = 6),
            ref("f1", SourceId.FLK, day = 5),
        )
        val groups = SourceGrouping.group(items, listOf("GOV_CN", "MPS_REG", "FLK", "COURT"))
        val flat = groups.flatMap { it.items }
        assertEquals("分组前后条数应一致", items.size, flat.size)
        assertEquals("分组前后 key 集合应一致", items.map { it.key }.toSet(), flat.map { it.key }.toSet())
        assertEquals("分组后不应出现重复 key", flat.size, flat.map { it.key }.distinct().size)
    }
}
