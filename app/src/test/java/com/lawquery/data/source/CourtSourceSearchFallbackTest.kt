package com.lawquery.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最高法站内检索结果的标题兜底过滤(2026-10-08 修)。
 *
 * 背景:官方检索接口 `/search.html?content=` 本身做**正文匹配**,结果条目的标题
 * 未必含检索词。旧实现对每一条都做 `title.contains(keyword)` 过滤,会把只在正文
 * 命中的有效结果整片误杀 —— 例如搜「劳动合同」,《关于审理劳动争议案件适用法律
 * 问题的解释》标题里没有「劳动合同」,于是被丢掉,用户感知是「官网搜得到、这里搜不到」。
 */
class CourtSourceSearchFallbackTest {

    private val titles = listOf(
        "指导案例183号：房玥诉中美联泰大都会人寿保险有限公司劳动合同纠纷案",
        "关于审理劳动争议案件适用法律问题的解释（一）",
        "最高人民法院关于人民法院登记立案若干问题的规定",
    )

    @Test
    fun `整页有标题命中时原样采用官方结果`() {
        val out = applyTitleFallback(titles, listOf("劳动合同")) { it }
        // 三条都要在:官方检索含正文匹配,标题不含检索词也可能是命中
        assertEquals(3, out.size)
        assertTrue(out.contains("关于审理劳动争议案件适用法律问题的解释（一）"))
    }

    @Test
    fun `整页无标题命中时按标题重筛避免混入栏目列表`() {
        // 检索接口降级成栏目列表时,标题与关键词毫无关系 —— 此时才做本地过滤
        val out = applyTitleFallback(titles, listOf("劳动合同")) { t ->
            t.replace("劳动合同", "无关")
        }
        assertTrue(out.isEmpty())
    }

    @Test
    fun `多关键词按空白切分任一命中即可`() {
        val out = applyTitleFallback(titles, listOf("劳动合同", "登记立案")) { it }
        assertEquals(3, out.size)
    }

    @Test
    fun `无关键词时原样返回`() {
        assertEquals(titles, applyTitleFallback(titles, emptyList()) { it })
    }
}
