package com.lawquery.data.source

import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.VersionFingerprint
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源路由与官方直通契约单测(需求 3.3 双通道 / F2 分类路由 / F1 直通卡片)。
 */
class SourceRoutingTest {

    private val tracker = SourceHealthTracker()

    private val webOnly = WebOnlySource(
        health = tracker,
        flkSearchUrl = { kw -> "https://flk.npc.gov.cn/fl.html?q=$kw" },
        flkCardLabel = { kw -> "在国家法律法规数据库中搜索『$kw』" },
    )

    @Test
    fun `受限源搜索返回直通卡片而非数据`() = runBlocking {
        val r = webOnly.search(SearchQuery(keyword = "民法典"))
        val page = (r as SourceResult.Success).data
        assertNull("WEB_ONLY 源不应有原生结果", page.items.takeIf { it.isNotEmpty() })
        val jump = page.directJump!!
        assertTrue(jump.url.contains("flk.npc.gov.cn"))
        assertTrue(jump.url.contains("民法典"))
        assertEquals("在国家法律法规数据库中搜索『民法典』", jump.label)
    }

    @Test
    fun `空关键词不产生直通卡片`() = runBlocking {
        val page = (webOnly.search(SearchQuery(keyword = "  ")) as SourceResult.Success).data
        assertNull(page.directJump)
    }

    @Test
    fun `受限源永远不提供原生全文`() = runBlocking {
        val ref = LawRef(
            id = "1", title = "宪法", issuingAuthority = "全国人大",
            docNumber = null, publishDate = LocalDate.of(1982, 12, 4),
            effectiveDate = null, status = LawStatus.CURRENT,
            source = SourceId.FLK_WEB, url = "https://flk.npc.gov.cn/",
        )
        assertTrue(webOnly.fetchDocument(ref) is SourceResult.SourceUnavailable)
        assertTrue(webOnly.fetchMeta(ref) is SourceResult.SourceUnavailable)
        assertEquals(SourceCapability.WEB_ONLY, webOnly.capability)
    }

    @Test
    fun `分类到数据源的路由映射(需求F2)`() {
        val registry = SourceRegistry(
            mapOf(
                SourceId.GOV_CN to GovCnSourceStub,
                SourceId.COURT to CourtSourceStub,
                SourceId.CASE_LIBRARY to CaseLibrarySourceStub,
                SourceId.FLK to FlkSourceStub,
                SourceId.MPS_REG to MpsRegSourceStub,
            )
        )
        // 宪法/法律/行政法规/监察法规/地方性法规:统一由国家法律法规数据库供数。
        // (司法解释同样以 flk 为主供数,另并列最高法官网源 —— 见下方单独断言)
        for (c in listOf(
            LawCategory.CONSTITUTION, LawCategory.LAW, LawCategory.ADMIN_REG,
            LawCategory.SUPERVISION, LawCategory.LOCAL,
        )) {
            assertEquals("分类 $c 应走 FLK", listOf("FLK"), registry.nativeSourcesFor(c).map { it.id.name })
            assertTrue("分类 $c 应由 flk 供数", c.isFlkBacked)
        }
        // 司法解释:flk 收录的司法解释正式文本 + 最高人民法院官网「司法解释」栏目。
        // 最高法源**不新增分类入口**,而是并列进本分类 —— 列表按来源分组展示,
        // 用户既能看到官方阅读器里的正式文本,也能看到法院官网发布口径。
        assertEquals(
            "司法解释应为 flk + 最高法两个源",
            listOf("FLK", "COURT"),
            registry.nativeSourcesFor(LawCategory.JUDICIAL).map { it.id.name },
        )
        assertTrue("司法解释仍应由 flk 供数", LawCategory.JUDICIAL.isFlkBacked)
        assertTrue("司法解释为多源分类(列表按来源分组)", LawCategory.JUDICIAL.isMultiSource)
        // 国务院及部委文件:中国政府网(国务院/部委政策文件)+ 公安部规章库(部门规章);
        // 人民法院案例库走案例库源
        assertEquals(
            listOf("GOV_CN", "MPS_REG"),
            registry.nativeSourcesFor(LawCategory.STATE_COUNCIL).map { it.id.name },
        )
        assertEquals(listOf("CASE_LIBRARY"), registry.nativeSourcesFor(LawCategory.CASE_LIBRARY).map { it.id.name })
        // 分类为 null:全部原生源(全局搜索 F1)—— 现为 5 个
        // (flk 官方直通已从注册表移除,见 AppContainer 的说明)
        assertEquals(5, registry.nativeSourcesFor(null).size)
    }

    /**
     * flk 已是原生源,不再需要「去官网搜」的直通卡片。
     *
     * 该通道从注册表移除后,搜索结果页不应再出现中间跳转卡片 ——
     * 否则等于让用户多点一次去一个应用内已经能给出结果的官方站点。
     */
    @Test
    fun `flk 原生化后不再产出官方直通卡片`() {
        val registry = SourceRegistry(
            mapOf(
                SourceId.GOV_CN to GovCnSourceStub,
                SourceId.COURT to CourtSourceStub,
                SourceId.FLK to FlkSourceStub,
                SourceId.CASE_LIBRARY to CaseLibrarySourceStub,
                SourceId.MPS_REG to MpsRegSourceStub,
            )
        )
        assertNull("不应再有 flk 直通源", registry.webSource())
    }

    /**
     * 不变式:全局检索必须覆盖**每一个分类**所用的数据源。
     *
     * 用户诉求:「检查全局搜索是否能够对接所有分类数据源搜索」。
     * 一旦某个分类的源没被 `nativeSourcesFor(null)` 收进来,全局搜索就会
     * **静默漏掉**该分类 —— 不报错、不降级,只是"结果少了点",极难察觉。
     * 这里把「全局集合 ⊇ 各分类集合的并集」钉死。
     */
    @Test
    fun `全局检索覆盖所有分类所用的数据源`() {
        val registry = allStubs()
        val global = registry.nativeSourcesFor(null).map { it.id }.toSet()
        val perCategory = LawCategory.entries
            .flatMap { registry.nativeSourcesFor(it) }
            .map { it.id }
            .toSet()
        assertEquals(
            "这些分类的源没被全局检索覆盖,全局搜索会静默漏掉它们",
            emptySet<SourceId>(),
            perCategory - global,
        )
    }

    /**
     * 每个分类都至少要有一个原生源。
     *
     * 分类的 `nativeSourceIds` 写错(空列表 / 写了未注册的 id)会让该分类
     * 点进去永远空白,且没有任何报错 —— 用一条不变式把这类配置错误挡住。
     */
    @Test
    fun `每个分类都至少有一个原生数据源`() {
        val registry = allStubs()
        for (c in LawCategory.entries) {
            assertTrue("分类 $c 没有任何原生数据源", registry.nativeSourcesFor(c).isNotEmpty())
        }
    }

    private fun allStubs() = SourceRegistry(
        mapOf(
            SourceId.GOV_CN to GovCnSourceStub,
            SourceId.COURT to CourtSourceStub,
            SourceId.FLK to FlkSourceStub,
            SourceId.CASE_LIBRARY to CaseLibrarySourceStub,
            SourceId.MPS_REG to MpsRegSourceStub,
        )
    )

    // ---- 测试桩:仅实现接口,不发起网络 ----
    private object GovCnSourceStub : LawSource {
        override val id = SourceId.GOV_CN
        override val capability = SourceCapability.NATIVE
        override suspend fun search(query: SearchQuery) = SourceResult.Success(SearchPage(emptyList(), null))
        override suspend fun fetchDocument(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override suspend fun fetchMeta(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override fun healthSnapshot() = SourceHealth(id)
    }

    private object CourtSourceStub : LawSource {
        override val id = SourceId.COURT
        override val capability = SourceCapability.NATIVE
        override suspend fun search(query: SearchQuery) = SourceResult.Success(SearchPage(emptyList(), null))
        override suspend fun fetchDocument(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override suspend fun fetchMeta(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override fun healthSnapshot() = SourceHealth(id)
    }

    private object CaseLibrarySourceStub : LawSource {
        override val id = SourceId.CASE_LIBRARY
        override val capability = SourceCapability.NATIVE
        override suspend fun search(query: SearchQuery) = SourceResult.Success(SearchPage(emptyList(), null))
        override suspend fun fetchDocument(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override suspend fun fetchMeta(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override fun healthSnapshot() = SourceHealth(id)
    }

    /** flk 原生通道桩:只参与路由断言,不发网络请求 */
    private object FlkSourceStub : LawSource {
        override val id = SourceId.FLK
        override val capability = SourceCapability.NATIVE
        override suspend fun search(query: SearchQuery) = SourceResult.Success(SearchPage(emptyList(), null))
        override suspend fun fetchDocument(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override suspend fun fetchMeta(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override fun healthSnapshot() = SourceHealth(id)
    }

    /** 公安部规章库桩:只参与路由断言,不发网络请求 */
    private object MpsRegSourceStub : LawSource {
        override val id = SourceId.MPS_REG
        override val capability = SourceCapability.NATIVE
        override suspend fun search(query: SearchQuery) = SourceResult.Success(SearchPage(emptyList(), null))
        override suspend fun fetchDocument(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override suspend fun fetchMeta(ref: LawRef) = SourceResult.SourceUnavailable(id, FailureReason.NETWORK)
        override fun healthSnapshot() = SourceHealth(id)
    }
}
