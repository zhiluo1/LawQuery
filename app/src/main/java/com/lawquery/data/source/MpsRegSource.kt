package com.lawquery.data.source

import com.lawquery.core.net.RateLimitedException
import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.core.parse.StructureBuilder
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.VersionFingerprint
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 公安部规章库(https://app.mps.gov.cn/gdnps/zc/list.jsp)原生通道。
 *
 * 归入「国务院及部委文件」分类,与中国政府网的国务院/部委文件并列:
 * 后者是国务院与各部门的**政策文件**,本源是公安部**部门规章**,两者互补而非重复。
 *
 * 实测契约(2026-10-07):
 * - 检索:`GET /gdnps/zc/searchIndex.jsp`,返回体是 JSONP(外层包着 `cb(...)` 回调壳);
 *   参数 `goPage`/`pageSize`/`zhengce=1`/`ownSubjectDn`/`title`/`content`/`yxx`。
 *   `title` 只匹配标题、`content` 只匹配正文,二者互斥(官网用单选按钮切换)。
 * - 详情:**同一接口加 `id=<id>`**,返回单条且 `htmlContent` 才有正文 HTML
 *   (列表里该字段恒为空串)。
 * - 默认排序已是发布日期倒序,无需额外 sort 参数。
 *
 * ⚠️ 站点前置**创宇盾**风控:短时间内连续请求会被临时封 IP(返回 403 HTML 页)。
 * 因此本源严格走全局 `ThrottleInterceptor`(同域名间隔 ≥ 2s、并发 ≤ 2),
 * 且一次检索最多 2 个请求(标题 + 正文),不做任何形式的重试风暴。
 *
 * 分页(2026-10-08 实测):官方 `pageSize` 上限可用 **50**,全库 63 条 → 2 页取全、
 * 页间不重复(pageSize=15 时官方自报 5 页)。界面统一分页窗口是 15,若照搬会分 5 次
 * 才拿全,故本源**固定按 [MPS_PAGE_SIZE] 取页**,既少打扰风控站点,也让首屏就基本覆盖全库。
 */
class MpsRegSource(
    private val client: OkHttpClient,
    private val health: SourceHealthTracker,
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
    /** 仅测试用:把请求指向本地 MockWebServer */
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
) : LawSource {

    override val id = SourceId.MPS_REG
    override val capability = SourceCapability.NATIVE

    override suspend fun search(query: SearchQuery): SourceResult<SearchPage> {
        return try {
            val keyword = query.keyword.trim()
            // 固定 50:本源全库仅 63 条(现行有效),官方接口实测可接受 pageSize=50,
            // 两页即可覆盖全库;若沿用界面的 15 会分 5 页,用户首屏只见 15 条(2026-10-08 反馈)。
            val size = MPS_PAGE_SIZE

            val items: List<LawRef>
            val hasMore: Boolean
            if (keyword.isEmpty()) {
                // 无关键词:按发布日期倒序浏览整个现行有效规章库
                val page = fetchList(page = query.page, size = size, keyword = null, inTitle = true)
                items = page.resultMap.mapNotNull { it.toLawRef() }
                hasMore = hasNextPage(page.totalPageNum, query.page)
            } else {
                // 与「名称优先、内容其次」的排序口径保持一致:先标题命中,再正文命中
                val titlePage = fetchList(query.page, size, keyword, inTitle = true)
                val contentPage = fetchList(query.page, size, keyword, inTitle = false)
                items = (titlePage.resultMap.mapNotNull { it.toLawRef() } +
                    contentPage.resultMap.mapNotNull { it.toLawRef() })
                    .distinctBy { it.key }
                hasMore = hasNextPage(titlePage.totalPageNum, query.page) ||
                    hasNextPage(contentPage.totalPageNum, query.page)
            }

            SourceResult.Success(
                SearchPage(
                    items = applyFilters(items, query.filters),
                    nextPage = (query.page + 1).takeIf { hasMore },
                )
            )
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> {
        return try {
            val response = MpsRegJson.parse(
                callApi(
                    page = 1,
                    size = 1,
                    keyword = null,
                    inTitle = true,
                    docId = ref.id,
                    doubleEncode = false,
                )
            )
            val item = response.resultMap.firstOrNull()
                ?: throw IOException("规章库未返回该条目:${ref.id}")

            val updatedRef = mergeMetadata(ref, item)
            val html = item.htmlContent?.takeIf { it.isNotBlank() }
            val structure = StructureBuilder.build(
                if (html == null) emptyList() else MpsRegContent.toBlocks(html)
            )
            SourceResult.Success(
                LawDocument(
                    ref = updatedRef,
                    fetchedAt = now(),
                    preamble = structure.preamble,
                    chapters = structure.chapters,
                    isPlainTextFallback = !structure.hasStructure,
                )
            )
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    override suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint> {
        return when (val r = fetchDocument(ref)) {
            is SourceResult.Success -> SourceResult.Success(r.data.ref.toFingerprint())
            is SourceResult.SourceUnavailable -> r
        }
    }

    override fun healthSnapshot() = health.snapshot(id)

    // ---- 内部 ----

    private suspend fun fetchList(
        page: Int,
        size: Int,
        keyword: String?,
        inTitle: Boolean,
    ): MpsRegResponse {
        val body = callApi(
            page = page,
            size = size,
            keyword = keyword,
            inTitle = inTitle,
            docId = null,
            doubleEncode = false,
        )
        val response = MpsRegJson.parse(body)
        /**
         * 官网 JS 会先 `encodeURIComponent` 再交给 jQuery 编码,等于把关键词**编码两次**。
         * 服务器端可能据此解码两次,导致只做一次编码时中文关键词检索结果为 0。
         * 这里不猜测具体行为:单次编码若一条都没召回(而全库非空),就用双次编码再试一次,
         * 仍为空则按原结果返回 —— 最多多花一个请求。
         */
        if (!keyword.isNullOrBlank() && response.resultMap.isEmpty() &&
            (response.totalContentNum ?: 0) == 0
        ) {
            val retried = MpsRegJson.parse(
                callApi(page, size, keyword, inTitle, docId = null, doubleEncode = true)
            )
            if (retried.resultMap.isNotEmpty()) return retried
        }
        return response
    }

    private suspend fun callApi(
        page: Int,
        size: Int,
        keyword: String?,
        inTitle: Boolean,
        docId: String?,
        doubleEncode: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(page, size, keyword, inTitle, docId, doubleEncode))
            .header("Accept", "*/*")
            .header(
                "Referer",
                if (docId == null) MPS_REG_LIST_URL else mpsRegContentUrl(docId),
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 403 || response.code == 429) {
                    throw RateLimitedException("HTTP ${response.code} 规章库已限流")
                }
                throw IOException("HTTP ${response.code} 规章库请求失败")
            }
            response.body?.string() ?: throw IOException("空响应体")
        }
    }

    private fun buildUrl(
        page: Int,
        size: Int,
        keyword: String?,
        inTitle: Boolean,
        docId: String?,
        doubleEncode: Boolean,
    ): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegment("gdnps")
            .addPathSegment("zc")
            .addPathSegment("searchIndex.jsp")
            .addQueryParameter("callback", "cb")
            .addQueryParameter("goPage", page.toString())
            .addQueryParameter("pageSize", size.toString())
            .addQueryParameter("zhengce", "1")
            .addQueryParameter("ownSubjectDn", OWN_SUBJECT_DN)
        if (docId != null) {
            // 详情形态:同一接口按 id 取单条,正文字段才有值
            builder.addQueryParameter("id", docId)
        } else {
            builder.addQueryParameter("title", "")
            builder.addQueryParameter("content", "")
            if (!keyword.isNullOrBlank()) {
                // 标题与正文互斥:一次只命中其一,由调用方决定是否两者都查
                put(builder, if (inTitle) "title" else "content", keyword, doubleEncode)
            }
        }
        return builder.build()
    }

    private fun put(builder: HttpUrl.Builder, key: String, value: String, doubleEncode: Boolean) {
        if (doubleEncode) {
            builder.addEncodedQueryParameter(key, doubleEncode(value))
        } else {
            builder.addQueryParameter(key, value)
        }
    }

    /** 复刻官网 `encodeURIComponent` 后再被 jQuery 编码一次的效果 */
    private fun doubleEncode(value: String): String =
        URLEncoder.encode(URLEncoder.encode(value, "UTF-8"), "UTF-8")

    private fun hasNextPage(totalPages: Int?, current: Int): Boolean =
        totalPages != null && current < totalPages

    /** 用详情返回的字段补全题录(列表里有值时以列表为准,缺的由详情补上) */
    private fun mergeMetadata(ref: LawRef, item: MpsRegItem): LawRef {
        val parsed = item.toLawRef() ?: return ref
        return ref.copy(
            title = parsed.title.takeIf { it.isNotBlank() } ?: ref.title,
            issuingAuthority = parsed.issuingAuthority.takeIf { it.isNotBlank() }
                ?: ref.issuingAuthority,
            docNumber = parsed.docNumber ?: ref.docNumber,
            publishDate = parsed.publishDate ?: ref.publishDate,
            effectiveDate = parsed.effectiveDate ?: ref.effectiveDate,
        )
    }

    /**
     * 服务端未提供年份/机关筛选参数,在客户端兜底。
     *
     * 界面层仍有 `SearchViewModel` 的统一筛选逻辑,此处与之叠加且不冲突。
     */
    private fun applyFilters(items: List<LawRef>, filters: SearchFilters): List<LawRef> {
        var result = items
        val year = filters.year
        if (year != null) {
            result = result.filter { it.publishDate?.year == year }
        }
        val authority = filters.authority?.trim()?.takeIf { it.isNotBlank() }
        if (authority != null) {
            result = result.filter {
                it.issuingAuthority.contains(authority) || authority.contains(it.issuingAuthority)
            }
        }
        val status = filters.status
        if (status != null) {
            result = result.filter { it.status == status }
        }
        return result
    }

    companion object {
        /** 「现行有效规章」栏目在站内信息树中的节点,下推才能得到规章数据 */
        private const val OWN_SUBJECT_DN = "/1/2257048/7387898/8244390/8244395"

        /**
         * 本源列表页固定容量。官方接口实测接受 50(2026-10-08),
         * 全库 63 条 → 2 页取全;界面统一分页窗口 15 会让首屏只出现 15 条。
         */
        const val MPS_PAGE_SIZE = 50

        /** 生产域名;测试可注入本地 MockWebServer 的地址 */
        val DEFAULT_BASE_URL: HttpUrl = "https://app.mps.gov.cn".toHttpUrl()
    }
}
