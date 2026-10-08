package com.lawquery.data.source

import com.lawquery.core.parse.GovCnParser
import com.lawquery.core.net.PageFetcher
import com.lawquery.core.net.RateLimitedException
import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.VersionFingerprint
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/** 政策文件库 JSON 检索接口(若接口失效,可降级为 HTML 直取,仅改本类) */
interface GovCnSearchApi {
    @GET("search-gov/data")
    suspend fun search(
        @Query("t") type: String,
        @Query("q") keyword: String,
        @Query("p") page: Int,
        @Query("n") size: Int,
        @Query("sort") sort: String,
        @Query("timetype") timeType: String = "timeqb",
        @Query("pubtimeyear") publishYear: String? = null,
        @Query("puborg") publishOrg: String? = null,
    ): GovCnSearchResponse
}

object GovCnApiFactory {
    /**
     * 必须注入经过限流管道的 OkHttpClient(项目文档 4.1:全部出网统一 UA/限流/退避,
     * 验收 E2 按此统计);不注入则 Retrofit 使用裸客户端,绕过合规拦截链。
     */
    fun create(
        client: okhttp3.OkHttpClient,
        baseUrl: String = "https://sousuo.www.gov.cn/",
    ): GovCnSearchApi {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GovCnSearchApi::class.java)
    }
}

/**
 * 中国政府网·政策文件库(源 A2,原生解析通道)。
 *
 * M0 结论(docs/数据源接入说明.md):
 * - 检索:公开 JSON 接口;t=zhengcelibrary_gw(国务院文件,含国令/行政法规)与
 *   t=zhengcelibrary_bm(部门文件);sort=score|publishdate;空关键词可按日期浏览;
 * - 详情:HTML 页,#UCAP-CONTENT 正文,table.bd1 元信息。
 */
class GovCnSource(
    private val api: GovCnSearchApi,
    private val pageFetcher: PageFetcher,
    private val health: SourceHealthTracker,
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
) : LawSource {

    override val id = SourceId.GOV_CN
    override val capability = SourceCapability.NATIVE

    override suspend fun search(query: SearchQuery): SourceResult<SearchPage> {
        return try {
            // 政策库两个子库:gw=国务院文件(含国令/行政法规),bm=部门文件(需求 F2 STATE_COUNCIL)
            val types = listOf("zhengcelibrary_gw", "zhengcelibrary_bm")
            val sortParam = when (query.sort) {
                SearchSort.RELEVANCE -> "score"
                SearchSort.PUBLISH_DATE_DESC -> "pubtime" // M0 实测值(接口侧为 pubtime)
            }
            val refs = mutableListOf<LawRef>()
            for (t in types) {
                val resp = api.search(
                    type = t,
                    keyword = query.keyword,
                    page = query.page,
                    size = query.pageSize.coerceIn(1, 50),
                    sort = sortParam,
                    publishYear = query.filters.year?.toString(),
                    publishOrg = query.filters.authority?.takeIf { it.isNotBlank() },
                )
                // M0 实测:条目在 searchVO.listVO;顶层 listVO 作为兜底合并(按 id 去重)
                val items = (resp.searchVo?.listVo.orEmpty() + resp.listVo)
                refs += items.mapNotNull { it.toLawRef() }
            }
            val deduped = refs.distinctBy { it.id }
            val sorted = if (query.sort == SearchSort.PUBLISH_DATE_DESC) {
                deduped.sortedByDescending { it.publishDate ?: java.time.LocalDate.MIN }
            } else {
                deduped
            }
            SourceResult.Success(
                SearchPage(
                    items = sorted,
                    // 仍有返回即视为可继续翻页;UI 以"返回空即停止"兜底
                    nextPage = if (sorted.isNotEmpty()) query.page + 1 else null,
                )
            )
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> {
        return try {
            val html = pageFetcher.fetch(ref.url)
            val parsed = GovCnParser.parseDetail(html, ref.url)
            val updatedRef = ref.copy(
                title = parsed.title ?: ref.title,
                issuingAuthority = parsed.authority ?: ref.issuingAuthority,
                docNumber = parsed.docNumber ?: ref.docNumber,
                publishDate = parsed.publishDate ?: ref.publishDate,
                effectiveDate = parsed.effectiveDate ?: ref.effectiveDate,
            )
            SourceResult.Success(
                LawDocument(
                    ref = updatedRef,
                    fetchedAt = now(),
                    preamble = parsed.structure.preamble,
                    chapters = parsed.structure.chapters,
                    isPlainTextFallback = !parsed.structure.hasStructure,
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
}

/** LawRef 元数据 → 版本指纹(需求 6.2) */
fun LawRef.toFingerprint() = VersionFingerprint(
    docNumber = docNumber,
    publishDate = publishDate,
    effectiveDate = effectiveDate,
    status = status,
)

/** 统一异常 → SourceResult 映射(项目文档 4.3) */
fun Throwable.toUnavailable(sourceId: SourceId): SourceResult.SourceUnavailable {
    val reason = when (this) {
        is RateLimitedException -> FailureReason.RATE_LIMITED
        is SocketTimeoutException -> FailureReason.TIMEOUT
        is UnknownHostException -> FailureReason.OFFLINE
        is PageFetcher.PageFetchException -> FailureReason.NETWORK
        is IOException -> FailureReason.NETWORK
        else -> FailureReason.PARSE_FAILED
    }
    return SourceResult.SourceUnavailable(sourceId, reason)
}

/** 默认时效性:官方接口未提供时的兜底(见《数据源接入说明》) */
val DEFAULT_STATUS: LawStatus = LawStatus.CURRENT
