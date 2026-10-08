package com.lawquery.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最高人民法院官网解析器单测(fixture 为 M0 实抓的官方页面)。
 */
class CourtParserTest {

    @Test
    fun `解析司法解释栏目列表页`() {
        val html = javaClass.getResourceAsStream("/samples/court_sjs_list.html")!!
            .readBytes().decodeToString()
        val parsed = CourtParser.parseList(html, "https://www.court.gov.cn/fabu/gengduo/16.html")

        assertTrue("栏目页应有 10+ 条目", parsed.items.size >= 10)
        val first = parsed.items.first { it.id == "512441" }
        assertEquals(
            "最高人民法院发布《关于审理涉及刑事犯罪的民事纠纷案件若干问题的规定》",
            first.title,
        )
        assertEquals(java.time.LocalDate.of(2026, 9, 21), first.date)
        assertTrue(first.url.startsWith("https://www.court.gov.cn/"))
        assertTrue("分页应识别到 16_2.html", parsed.nextPageUrl?.contains("16_2") == true)
    }

    @Test
    fun `解析司法解释详情页`() {
        val html = javaClass.getResourceAsStream("/samples/court_sjs_detail.html")!!
            .readBytes().decodeToString()
        val parsed = CourtParser.parseDetail(html, "https://www.court.gov.cn/fabu/xiangqing/436481.html")

        assertEquals("最高人民法院出台适用公司法时间效力司法解释", parsed.title)
        assertEquals("最高人民法院新闻局", parsed.sourceName)
        assertEquals(java.time.LocalDate.of(2024, 6, 30), parsed.publishDate)
        val allText = (parsed.structure.preamble + parsed.structure.chapters.flatMap { c ->
            c.articles.flatMap { a -> a.paragraphs }
        }).joinToString("")
        assertTrue("正文应含文号", allText.contains("法释[2024]7号") || allText.contains("法释〔2024〕7号"))
    }

    @Test
    fun `解析站内搜索结果页`() {
        val html = javaClass.getResourceAsStream("/samples/court_search.html")!!
            .readBytes().decodeToString()
        val parsed = CourtParser.parseList(html, "https://www.court.gov.cn/search.html?content=公司法")

        assertTrue("搜索结果页应解析出条目", parsed.items.size >= 5)
        assertTrue("结果标题应含关键词", parsed.items.any { it.title.contains("公司法") })
        // 日期为完整时间「2024-06-30 06:44:47」,取日期部分
        val hit = parsed.items.first { it.id == "436481" }
        assertEquals(java.time.LocalDate.of(2024, 6, 30), hit.date)
    }
}
