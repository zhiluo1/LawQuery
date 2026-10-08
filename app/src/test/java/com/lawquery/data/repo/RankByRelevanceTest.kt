package com.lawquery.data.repo

import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 相关度排序:**标题命中在前,正文命中在后**(需求:先按标题检索,再按内容检索)。
 *
 * 背景:官方检索接口(flk / 人民法院案例库 / 最高法 / 中国政府网)对标题与正文是
 * **同一次全文请求**返回的,返回顺序由各源自己决定,并不代表"标题命中更相关"。
 * 于是搜「劳动合同」时,标题里根本没有「劳动合同」四字、只在正文里出现过的条目
 * 会和标题命中的条目混在一起,用户要自己翻找。
 *
 * [SearchRepository.rankByRelevance] 在聚合层按「标题是否包含检索词」重排,
 * 把标题命中的结果提到最前面。
 */
class RankByRelevanceTest {

    private fun ref(id: String, title: String) = LawRef(
        id = id,
        title = title,
        issuingAuthority = "最高人民法院",
        docNumber = null,
        publishDate = null,
        effectiveDate = null,
        status = LawStatus.CURRENT,
        source = SourceId.FLK,
        url = "",
    )

    /** 核心需求:标题命中的条目必须排在仅正文命中的条目之前 */
    @Test
    fun `标题命中的排在正文命中的前面`() {
        // 「工伤保险条例」标题里没有"劳动合同"四字 —— 属于只在正文里命中的那类
        val contentOnly = ref("a", "工伤保险条例")
        val titleMid = ref("b", "关于劳动合同订立的相关规定")
        val out = SearchRepository.rankByRelevance(
            listOf(contentOnly, titleMid),
            "劳动合同",
        )
        assertEquals(listOf("b", "a"), out.map { it.id })
    }

    /** 分档优先级:完全一致 > 标题前缀 > 标题包含 */
    @Test
    fun `完全匹配优先于前缀匹配优先于包含匹配`() {
        val contains = ref("c", "中华人民共和国劳动合同法")
        val prefix = ref("b", "劳动合同争议调解仲裁办法")
        val exact = ref("a", "劳动合同")
        val none = ref("d", "劳动法")
        val out = SearchRepository.rankByRelevance(
            listOf(contains, none, prefix, exact),
            "劳动合同",
        )
        assertEquals(listOf("a", "b", "c", "d"), out.map { it.id })
    }

    /** 同一档内保持原有相对顺序(稳定排序,不因排序打乱同档条目) */
    @Test
    fun `同档内保持原顺序`() {
        val input = listOf(
            ref("1", "劳动合同法"),
            ref("2", "劳动合同法"),
            ref("3", "劳动合同法"),
        )
        assertEquals(input, SearchRepository.rankByRelevance(input, "劳动合同"))
    }

    /** 标题匹配忽略大小写与首尾空格 */
    @Test
    fun `匹配忽略大小写与首尾空格`() {
        val upper = ref("u", "Labor Contract Law")
        val lower = ref("l", "labor contract rules")
        val out = SearchRepository.rankByRelevance(listOf(upper, lower), "  LABOR ")
        assertEquals(listOf("u", "l"), out.map { it.id })
    }

    /** 检索词为空(浏览全库)或只有一条时不改变顺序 */
    @Test
    fun `空检索词与单条结果原样返回`() {
        val input = listOf(ref("1", "劳动合同法"), ref("2", "民法典"))
        assertEquals(input, SearchRepository.rankByRelevance(input, ""))
        assertEquals(input, SearchRepository.rankByRelevance(input, "   "))

        val one = listOf(ref("x", "民法典"))
        assertEquals(one, SearchRepository.rankByRelevance(one, "民法"))
    }

    @Test
    fun `空表原样返回`() {
        assertEquals(0, SearchRepository.rankByRelevance(emptyList(), "劳动合同").size)
    }

    /** 排序不得增删条目:进 N 条出 N 条 */
    @Test
    fun `排序不改变条目数量`() {
        val input = (1..8).map { ref("id$it", "标题$it 劳动合同") }
        assertEquals(input.size, SearchRepository.rankByRelevance(input, "劳动合同").size)
    }
}