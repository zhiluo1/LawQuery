package com.lawquery.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 政策文件库详情页解析器单测(fixture 为 M0 实抓的官方页面:
 * 《网络数据安全管理条例》国务院令第790号)。
 */
class GovCnParserTest {

    private val html: String by lazy {
        javaClass.getResourceAsStream("/samples/gov_detail_790.html")!!
            .readBytes().decodeToString()
    }

    @Test
    fun `解析元信息与发文字号`() {
        val parsed = GovCnParser.parseDetail(html, "https://www.gov.cn/zhengce/zhengceku/202409/content_6977767.htm")

        assertEquals("国务院", parsed.authority)
        assertNotNull(parsed.docNumber)
        assertTrue("发文字号应含「790」", parsed.docNumber!!.contains("790"))
        assertTrue("标题应含条例名", parsed.title!!.contains("网络数据安全管理条例"))
        assertEquals(java.time.LocalDate.of(2024, 9, 30), parsed.publishDate)
    }

    @Test
    fun `解析施行日期与条文结构`() {
        val parsed = GovCnParser.parseDetail(html, "https://www.gov.cn/x")

        // 正文含「自2025年1月1日起施行」
        assertEquals(java.time.LocalDate.of(2025, 1, 1), parsed.effectiveDate)
        assertTrue("条例应有章/条结构", parsed.structure.hasStructure)
        assertTrue(
            "网络数据安全管理条例共七章七十九条,条文数应远大于 10",
            parsed.structure.chapters.sumOf { it.articles.size } > 10,
        )
    }

    @Test
    fun `结构缺失时正文仍完整提取(容错)`() {
        val broken = "<html><body><p>只有一段纯文本,没有条文编号。</p></body></html>"
        val parsed = GovCnParser.parseDetail(broken, "https://www.gov.cn/x")
        assertTrue(!parsed.structure.hasStructure)
        assertEquals(listOf("只有一段纯文本,没有条文编号。"), parsed.structure.preamble)
    }

    @Test
    fun `日期解析兼容多种格式`() {
        assertEquals(java.time.LocalDate.of(2024, 9, 30), GovCnParser.parseDate("2024年9月30日"))
        assertEquals(java.time.LocalDate.of(2024, 9, 30), GovCnParser.parseDate("2024.09.30"))
        assertEquals(java.time.LocalDate.of(2024, 9, 30), GovCnParser.parseDate("2024-9-30"))
        assertEquals(null, GovCnParser.parseDate("无日期"))
    }
}
