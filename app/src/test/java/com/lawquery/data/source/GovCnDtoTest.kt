package com.lawquery.data.source

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 政策文件库检索接口 DTO 单测(fixture 为 M0 实抓的接口响应)。
 * 接口实测:条目位于 searchVO.listVO;标题含 <em> 高亮标记需在映射时剥离。
 */
class GovCnDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun decode(): GovCnSearchResponse {
        val text = javaClass.getResourceAsStream("/samples/gov_search.json")!!
            .readBytes().decodeToString()
        return json.decodeFromString(GovCnSearchResponse.serializer(), text)
    }

    @Test
    fun `条目列表位于 searchVO 内(M0 实测结构)`() {
        val resp = decode()
        val items = resp.searchVo?.listVo.orEmpty()
        assertTrue("searchVO.listVO 不应为空(接口结构回归)", items.isNotEmpty())
        assertTrue("totalCount 应有值(分页依据)", (resp.searchVo?.totalCount ?: 0) > 0)
    }

    @Test
    fun `DTO到LawRef映射含标题发文字号与日期`() {
        val ref = decode().searchVo?.listVo.orEmpty()
            .first { it.id == "26743315" }
            .toLawRef()!!

        assertEquals("国务院办公厅关于发展体育赛事激发消费活力的意见", ref.title)
        assertEquals("国办发〔2026〕28号", ref.docNumber)
        assertEquals(LocalDate.of(2026, 9, 30), ref.publishDate)
        assertEquals(
            "https://www.gov.cn/zhengce/zhengceku/202609/content_7082493.htm",
            ref.url,
        )
        assertEquals(SourceId.GOV_CN, ref.source)
    }

    @Test
    fun `标题中的em高亮标签被剥离`() {
        val ref = decode().searchVo?.listVo.orEmpty()
            .first { it.id == "26665196" }
            .toLawRef()!!

        assertFalse("不得残留 <em> 标记", ref.title.contains("<em>"))
        assertTrue(ref.title.contains("烟花爆竹"))
        assertTrue("剥离后应保留命中词", ref.title.contains("安全"))
    }

    @Test
    fun `无标题或URL的条目被安全丢弃`() {
        val bad = GovCnItem(id = "x", title = null, url = null)
        assertEquals(null, bad.toLawRef())
    }
}
