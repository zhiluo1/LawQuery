package com.lawquery.ui.browse

import com.lawquery.data.source.FlkAuthorities
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类页「发文机关」的候选与数量语义(2026-10-08 修)。
 *
 * 用户实测:司法解释分类里选「最高人民检察院」,列表实际 26 条,而筛选面板上写着 5,
 * 并且要一直往下翻、数字才跟着涨。两个根因:
 * 1. **发文机关从未下推给 flk**(见 FlkSource.zdjgCodeOf 的回归测试)—— 筛选退化成
 *    「在已加载的几十条里做客户端过滤」;
 * 2. **面板数量是从已加载条目里统计的** —— 那不是全库分布,显示出来必然误导。
 *
 * 这里锁定修复后的两条约定:
 * - 候选 = 官方制定机关字典(完整、且**能被 codeOf 解析下推**) + 已加载条目里的机关(兜底);
 * - 数量一律 `null`(不显示),因为除案例库外没有源能给出全库机关计数。
 */
class AuthorityOptionsTest {

    private fun ref(authority: String) = LawRef(
        id = "id-$authority",
        title = "文件-$authority",
        issuingAuthority = authority,
        docNumber = null,
        publishDate = LocalDate.of(2026, 1, 1),
        effectiveDate = null,
        status = LawStatus.CURRENT,
        source = SourceId.FLK,
        url = "",
    )

    @Test
    fun `官方字典机关排在候选最前且必然可下推`() {
        val items = listOf(ref("最高人民法院"), ref("最高人民法院"), ref("最高人民检察院"))
        val options = AuthorityOptions.forCategory(items, LawCategory.JUDICIAL)

        assertEquals(
            listOf("最高人民法院", "最高人民检察院"),
            options.take(2).map { it.first },
        )
        // 关键:候选里的机关名必须能被字典解析 —— 只有解析得出代码,
        // FlkSource 才会把 zdjgCodeId 下推给官方;解析不出来的选项点了等于没筛。
        options.take(2).forEach { (name, _) ->
            assertNotNull("$name 应能解析为官方机关代码", FlkAuthorities.codeOf(name))
        }
    }

    /** 字典里没有的机关名(官方返回的全称 / 联合署名)必须保留,不能因并入字典而丢 */
    @Test
    fun `字典之外的机关名仍保留在候选里`() {
        val items = listOf(ref("最高人民法院、最高人民检察院"))
        val options = AuthorityOptions.forCategory(items, LawCategory.JUDICIAL)

        assertTrue(options.any { it.first == "最高人民法院、最高人民检察院" })
        // 且它解析不出代码 → FlkSource 走「不下推、退回客户端过滤」分支
        assertNull(FlkAuthorities.codeOf("最高人民法院、最高人民检察院"))
    }

    @Test
    fun `候选机关去重`() {
        val items = listOf(ref("最高人民法院"), ref("最高人民检察院"))
        val names = AuthorityOptions.forCategory(items, LawCategory.JUDICIAL).map { it.first }
        assertEquals("字典与已加载条目重叠时只应出现一次", names.size, names.distinct().size)
    }

    /**
     * 数量一律不显示。
     *
     * 该源没有机关分面,数字只能统计已加载的几十条(实测 26 条却显示 5,翻页还会变),
     * 显示出来就是错的 —— UI 依 `null` 决定不渲染计数徽标。
     */
    @Test
    fun `候选数量一律不显示`() {
        val options = AuthorityOptions.forCategory(
            listOf(ref("最高人民法院"), ref("最高人民法院")),
            LawCategory.JUDICIAL,
        )
        assertTrue("不应有任何计数(全部为 null)", options.all { it.second == null })
    }

    /** 字典为空的分类(法律/地方性法规):候选退回「已加载条目里的机关」 */
    @Test
    fun `无字典的分类退回已加载机关`() {
        val items = listOf(ref("全国人民代表大会常务委员会"), ref("广东省人民代表大会常务委员会"))
        val names = AuthorityOptions.forCategory(items, LawCategory.LAW).map { it.first }

        assertTrue(names.contains("全国人民代表大会常务委员会"))
        assertTrue(names.contains("广东省人民代表大会常务委员会"))
    }

    /** 已加载为空时字典机关仍在 —— 不必先翻页才能看到可选项 */
    @Test
    fun `未加载任何条目时仍有字典机关可选`() {
        val options = AuthorityOptions.forCategory(emptyList(), LawCategory.JUDICIAL)
        assertEquals(listOf("最高人民法院", "最高人民检察院"), options.map { it.first })
    }

    /** 空机关名(个别源取不到制定机关)不得变成候选项 */
    @Test
    fun `空机关名不进入候选`() {
        val names = AuthorityOptions.forCategory(listOf(ref("")), LawCategory.LAW).map { it.first }
        assertTrue("空名应被过滤", names.none { it.isBlank() })
    }
}
