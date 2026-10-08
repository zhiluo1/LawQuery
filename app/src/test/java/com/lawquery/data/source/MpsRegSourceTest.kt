package com.lawquery.data.source

import com.lawquery.core.net.HostSourceMapper
import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.SourceId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公安部规章库(app.mps.gov.cn)接入契约单测。
 *
 * ⚠️ 全部断言都用 2026-10-07 实抓的报文做样本,**不发任何网络请求** ——
 * 站点前置创宇盾风控,抓取样本时本机出口 IP 已被临时判定可疑,
 * 契约一旦用测试固死,后续回归就不需要再去打扰对方站点。
 */
class MpsRegSourceTest {

    /** 实抓:`/gdnps/zc/searchIndex.jsp` 第一页(节选,原始 JSONP 外壳保留) */
    private val listFixture = """
cb({"class":"class com.assoft.search.model.QueryResult","curPage":1,"resultMap":[{"publishTime":"20260807180000","ownSubjectId":"8244395","syh":"000000000/2026-00019","tpb":"10576575.pdf","zlrq":"2026-08-07","title":"公安机关网络空间安全监督检查办法","htmlContent":"","ownSubjectDn":"/1/2257048/7387898/8244390/8244395","wh":"公安部令第176号","mc":"公安机关网络空间安全监督检查办法","fbjg":"其他","yxx":"是","fbt":"2026年8月7日公安部令第176号发布 自2026年10月1日起施行","scrq":"2026-08-07","id":"10576548","wzb":"10576574.docx","subjectName":"规章"},{"publishTime":"20251205102126","ownSubjectId":"8244395","syh":"000000000/2025-00021","tpb":"10322167.pdf","zlrq":"2025-12-05","title":"关于修改《警车管理规定》的决定","htmlContent":"","ownSubjectDn":"/1/2257048/7387898/8244390/8244395","mc":"关于修改《警车管理规定》的决定","fbjg":"交通管理局","yxx":"是","fbt":"2025年11月25日公安部令第174号发布 自2025年12月29日起施行","scrq":"2025-12-05","id":"10319709","wzb":"10322166.docx","subjectName":"规章"}],"totalContentNum":63,"totalPageNum":21});
    """.trimIndent()

    @Test
    fun `剥掉 JSONP 外壳得到纯 JSON`() {
        val resp = MpsRegJson.parse(listFixture)
        assertEquals(63, resp.totalContentNum)
        assertEquals(21, resp.totalPageNum)
        assertEquals(1, resp.curPage)
        assertEquals(2, resp.resultMap.size)
    }

    @Test
    fun `裸 JSON 也能解析`() {
        val body = MpsRegJson.unwrap(listFixture)
        assertEquals(body, MpsRegJson.unwrap(body))
        assertTrue(body.startsWith("{"))
        assertTrue(body.endsWith("}"))
    }

    @Test
    fun `网关风控页伪装成 200 时要判为限流而不是解析失败`() {
        val waf = """<html><head></head><body>创宇盾提示您:您的IP最近有可疑的攻击行为</body></html>"""
        val ex = runCatching { MpsRegJson.parse(waf) }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "应判为风控以获得冷却语义,实际:$ex",
            ex is com.lawquery.core.net.RateLimitedException,
        )
    }

    @Test
    fun `列表条目映射为 LawRef`() {
        val item = MpsRegJson.parse(listFixture).resultMap[0]
        val ref = item.toLawRef()!!
        assertEquals("10576548", ref.id)
        assertEquals("公安机关网络空间安全监督检查办法", ref.title)
        assertEquals("公安部令第176号", ref.docNumber)
        assertEquals(LocalDate.of(2026, 8, 7), ref.publishDate)
        // 施行日期晚于发布日期是规章常态,必须原样保留
        assertEquals(LocalDate.of(2026, 10, 1), ref.effectiveDate)
        assertEquals(LawStatus.CURRENT, ref.status)
        assertEquals(SourceId.MPS_REG, ref.source)
        assertEquals("https://app.mps.gov.cn/gdnps/zc/content.jsp?id=10576548", ref.url)
        assertEquals("MPS_REG:10576548", ref.key)
    }

    @Test
    fun `发布机关按官方口径拼接且不产生怪话`() {
        val items = MpsRegJson.parse(listFixture).resultMap
        // fbjg=其他 → 只留「公安部」,不拼成「公安部其他」
        assertEquals("公安部", items[0].toLawRef()!!.issuingAuthority)
        // fbjg=交通管理局 → 「公安部交通管理局」
        assertEquals("公安部交通管理局", items[1].toLawRef()!!.issuingAuthority)
    }

    @Test
    fun `标题缺失时回落到 mc 字段`() {
        val item = MpsRegItem(id = "9", title = null, mc = "某规定", zlrq = "2026-01-02")
        assertEquals("某规定", item.toLawRef()!!.title)
    }

    @Test
    fun `缺 id 或完全无标题的条目被丢弃而不是产生脏数据`() {
        assertEquals(null, MpsRegItem(id = null, title = "无主键").toLawRef())
        assertEquals(null, MpsRegItem(id = "7", title = "  ", mc = null).toLawRef())
    }

    @Test
    fun `yxx 为否时标为已废止`() {
        val item = MpsRegItem(id = "1", title = "旧规章", yxx = "否", zlrq = "2020-01-01")
        assertEquals(LawStatus.REPEALED, item.toLawRef()!!.status)
    }

    @Test
    fun `日期解析覆盖 hyphen 格式与十四位时间戳`() {
        assertEquals(LocalDate.of(2026, 8, 7), MpsRegDates.iso("2026-08-07"))
        assertEquals(LocalDate.of(2026, 8, 7), MpsRegDates.timestamp("20260807180000"))
        assertEquals(null, MpsRegDates.iso(null))
        assertEquals(null, MpsRegDates.timestamp("不是时间戳"))
        // 缺 fail.zlrq 时用十四位时间戳兜底
        val item = MpsRegItem(id = "2", title = "兜底", publishTime = "20260101120000")
        assertEquals(LocalDate.of(2026, 1, 1), item.toLawRef()!!.publishDate)
    }

    @Test
    fun `施行日期从副标题里取自之后那一个`() {
        assertEquals(
            LocalDate.of(2026, 10, 1),
            MpsRegDates.effectiveFrom("2026年8月7日公安部令第176号发布 自2026年10月1日起施行"),
        )
        assertEquals(null, MpsRegDates.effectiveFrom("2026年8月7日公安部令第176号发布"))
    }

    @Test
    fun `详情页返回的 htmlContent 能建出章条层级`() {
        val html = """
            <p style="text-align:center;">公安机关网络空间安全监督检查办法</p>
            <p style="text-indent: 2em;"><span>第一条</span>为规范监督检查工作,制定本办法。</p>
            <p style="text-indent: 2em;"><span>第二条</span>本办法适用于公安机关。</p>
        """.trimIndent()
        val blocks = MpsRegContent.toBlocks(html)
        assertTrue(blocks.any { it.startsWith("第一条") })
        assertTrue(blocks.any { it.startsWith("第二条") })
        val structure = com.lawquery.core.parse.StructureBuilder.build(blocks)
        assertTrue("正文应解析出条结构", structure.hasStructure)
        assertEquals(2, structure.chapters.sumOf { it.articles.size })
    }

    @Test
    fun `br 换行的正文也能切成多个文本块`() {
        val html = "第一条 为规范监督检查工作。<br>第二条 本办法适用范围。"
        val blocks = MpsRegContent.toBlocks(html)
        assertEquals(2, blocks.size)
    }

    /**
     * 2026-10-08 真机反馈 + 实抓(id=8282949《道路交通安全违法行为记分管理办法》)回归:
     * htmlContent 开头是目录页(每章一行),正文再出现章标题(字内多空格)。
     * 修复前建出成倍空章、条文看似没挂在章下;修复后目录章被正文回填。
     */
    @Test
    fun `实抓规章正文 - 目录不产生空章且条文挂对章节`() {
        val html = """
            <p style="text-indent: 2em"><font style="font-family: 黑体">第一章&nbsp; 总则</font></p>
            <p style="text-indent: 2em"><font style="font-family: 黑体">第二章&nbsp; 记分分值</font></p>
            <p style="text-indent: 2em"><font style="font-family: 黑体">第三章&nbsp; 记分执行</font></p>
            <p style="text-indent: 2em">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; </p>
            <p style="text-align: center"><font style="font-family: 黑体">第一章&nbsp; 总&nbsp; 则</font></p>
            <p style="text-indent: 2em"><font style="font-family: 黑体">第一条</font>&nbsp; 为充分发挥记分制度的管理、教育、引导功能，制定本办法。</p>
            <p style="text-indent: 2em"><font style="font-family: 黑体">第二条</font>&nbsp; 本办法所称记分，是指对机动车驾驶人交通违法行为予以记分管理的行为。</p>
            <p style="text-align: center"><font style="font-family: 黑体">第二章&nbsp; 记&nbsp; 分&nbsp; 分&nbsp; 值</font></p>
            <p style="text-indent: 2em"><font style="font-family: 黑体">第三条</font>&nbsp; 交通违法行为记分分值为十二分、九分、六分、三分、一分。</p>
            <p style="text-align: center"><font style="font-family: 黑体">第三章&nbsp; 记&nbsp; 分&nbsp; 执&nbsp; 行</font></p>
            <p style="text-indent: 2em"><font style="font-family: 黑体">第四条</font>&nbsp; 机动车驾驶人在一个记分周期内累积记分满12分的，应当参加学习和考试。</p>
        """.trimIndent()
        val structure = com.lawquery.core.parse.StructureBuilder.build(MpsRegContent.toBlocks(html))

        assertTrue(structure.hasStructure)
        assertEquals(3, structure.chapters.size)
        assertEquals(2, structure.chapters[0].articles.size)
        assertEquals(1, structure.chapters[0].articles[0].arabicNumber)
        assertEquals(3, structure.chapters[1].articles[0].arabicNumber)
        assertEquals(4, structure.chapters[2].articles[0].arabicNumber)
        // 目录行不应残留在前言里
        assertTrue(structure.preamble.none { it.startsWith("第") && it.length <= 12 })
    }

    @Test
    fun `国务院及部委文件同时由中国政府网与公安部规章库供数`() {
        assertTrue(
            LawCategory.STATE_COUNCIL.nativeSourceIds.containsAll(listOf("GOV_CN", "MPS_REG")),
        )
    }

    @Test
    fun `规章库域名计入本体源以便走限流与冷却`() {
        assertEquals(SourceId.MPS_REG, HostSourceMapper.sourceIdOf("app.mps.gov.cn"))
    }

    @Test
    fun `健康画像以规章库自身为键`() {
        val tracker = SourceHealthTracker()
        val health = tracker.snapshot(SourceId.MPS_REG)
        assertEquals(SourceId.MPS_REG, health.sourceId)
    }

    // ---- 分页契约(2026-10-08 实测:官方 pageSize 上限 50,全库 63 条 = 2 页) ----

    private fun jsonp(page: Int, count: Int, total: Int, totalPages: Int, startId: Int = 1): String {
        val items = (0 until count).joinToString(",") { i ->
            val id = startId + i
            """{"id":"$id","title":"规章${id}","mc":"规章${id}","wh":"公安部令第${id}号","""
                .plus(""""zlrq":"2020-01-01","fbjg":"其他","yxx":"是","htmlContent":"","subjectName":"规章"}""")
        }
        return """cb({"curPage":$page,"resultMap":[$items],"totalContentNum":$total,"totalPageNum":$totalPages});"""
    }

    private fun newSource(server: okhttp3.mockwebserver.MockWebServer) = MpsRegSource(
        client = okhttp3.OkHttpClient(),
        health = SourceHealthTracker(),
        baseUrl = server.url("/"),
    )

    @Test
    fun `列表按每页容量 50 请求且在叶子节点下推`() = kotlinx.coroutines.test.runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(jsonp(1, 50, 63, 2)))
        val source = newSource(server)

        val result = source.search(SearchQuery(keyword = "", category = LawCategory.STATE_COUNCIL, page = 1))
        val page = (result as com.lawquery.data.source.SourceResult.Success).data

        assertEquals(50, page.items.size)
        assertEquals(2, page.nextPage)

        val url = server.takeRequest().requestUrl!!
        assertEquals("50", url.queryParameter("pageSize"))
        assertEquals("1", url.queryParameter("goPage"))
        assertEquals("cb", url.queryParameter("callback"))
        assertEquals("1", url.queryParameter("zhengce"))
        // 父节点下推会返回 0 条,必须是叶子节点
        assertEquals("/1/2257048/7387898/8244390/8244395", url.queryParameter("ownSubjectDn"))
        // 浏览模式:关键词两个字段都留空
        assertEquals("", url.queryParameter("title"))
        assertEquals("", url.queryParameter("content"))
        server.shutdown()
    }

    @Test
    fun `两页覆盖全库且末页之后不再翻页`() = kotlinx.coroutines.test.runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(jsonp(1, 50, 63, 2, startId = 1)))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(jsonp(2, 13, 63, 2, startId = 51)))
        val source = newSource(server)

        val first = (source.search(SearchQuery(keyword = "", page = 1)) as
            com.lawquery.data.source.SourceResult.Success).data
        val second = (source.search(SearchQuery(keyword = "", page = 2)) as
            com.lawquery.data.source.SourceResult.Success).data

        val all = com.lawquery.data.repo.SearchRepository
            .appendUnique(first.items, second.items)
        assertEquals(63, all.size)
        assertEquals(63, all.map { it.key }.toSet().size)
        assertEquals(2, first.nextPage)
        // 末页(MPS_PAGE_SIZE=50 下官方自报 totalPageNum=2)不再翻页
        assertEquals(null, second.nextPage)
        server.shutdown()
    }

    @Test
    fun `分页请求始终按 50 取页而不跟着界面 15 的窗口缩小`() = kotlinx.coroutines.test.runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(jsonp(1, 50, 63, 2)))
        val source = newSource(server)

        source.search(SearchQuery(keyword = "", page = 1, pageSize = 15))
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("pageSize"))
        server.shutdown()
    }
}
