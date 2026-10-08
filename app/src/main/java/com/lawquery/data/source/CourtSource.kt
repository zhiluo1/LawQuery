package com.lawquery.data.source

import com.lawquery.core.parse.CourtParser
import com.lawquery.core.net.PageFetcher
import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.VersionFingerprint
import java.time.OffsetDateTime

/**
 * 最高人民法院官网(源 A3,原生解析通道)。
 *
 * M0 结论(docs/数据源接入说明.md):
 * - 司法解释栏目 /fabu/gengduo/16.html,司法文件 17.html;翻页 16_N.html;
 * - 官网无公开关键词检索接口,搜索采用「栏目列表 + 标题本地过滤」(不发批量请求);
 * - 详情:div.detail 结构,正文 div.txt_txt。
 */
class CourtSource(
    private val pageFetcher: PageFetcher,
    private val health: SourceHealthTracker,
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
) : LawSource {

    override val id = SourceId.COURT
    override val capability = SourceCapability.NATIVE

    companion object {
        const val JUDICIAL_CHANNEL = 16
        const val JUDICIAL_DOC_CHANNEL = 17
        const val BASE = "https://www.court.gov.cn"
        private const val LIST_PATH = "/fabu/gengduo/%d.html"
        private const val LIST_PAGE_PATH = "/fabu/gengduo/%d_%d.html"
        /** 官方站内检索(M0 实测):/search.html?content=关键词&page=N,服务端渲染结果列表 */
        private const val SEARCH_PATH = "/search.html?content=%s&page=%d"
    }

    override suspend fun search(query: SearchQuery): SourceResult<SearchPage> {
        return try {
            val keyword = query.keyword.trim()
            // 有关键词:官方站内检索(F1);无关键词:司法解释栏目按日期浏览(F2)
            val url = if (keyword.isNotEmpty()) {
                BASE + SEARCH_PATH.format(
                    java.net.URLEncoder.encode(keyword, "UTF-8"),
                    query.page.coerceAtLeast(1),
                )
            } else if (query.page <= 1) {
                BASE + LIST_PATH.format(JUDICIAL_CHANNEL)
            } else {
                BASE + LIST_PAGE_PATH.format(JUDICIAL_CHANNEL, query.page)
            }
            val parsed = CourtParser.parseList(pageFetcher.fetch(url), url)
            /*
             * 关键词检索走的是官方站内检索(`/search.html?content=`),官方本身就会
             * 做**正文匹配**并按其相关度排序 —— 结果条目的标题里未必出现检索词
             * (例如搜「劳动合同」,《关于审理劳动争议案件适用法律问题的解释》只在正文命中)。
             *
             * 因此**不能逐条按标题过滤**:那样会把只在正文命中的有效结果整片误杀,
             * 用户感知是「明明官网搜得到,这里却搜不到」。
             * 保留标题过滤只作为「检索接口降级成栏目列表」的兜底,判定见
             * [applyTitleFallback](纯函数,单测锁定)。
             */
            val tokens = keyword.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val refs = applyTitleFallback(parsed.items, tokens) { it.title }.map { it.toLawRef() }
            SourceResult.Success(
                SearchPage(
                    items = refs,
                    nextPage = if (parsed.nextPageUrl != null && refs.isNotEmpty()) query.page + 1 else null,
                )
            )
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> {
        return try {
            val html = pageFetcher.fetch(ref.url)
            val parsed = CourtParser.parseDetail(html, ref.url)
            val updatedRef = ref.copy(
                title = parsed.title ?: ref.title,
                issuingAuthority = parsed.sourceName ?: ref.issuingAuthority,
                publishDate = parsed.publishDate ?: ref.publishDate,
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

private fun CourtParser.ListItem.toLawRef(): LawRef = LawRef(
    id = id,
    title = title,
    issuingAuthority = "最高人民法院",
    docNumber = null,
    publishDate = date,
    effectiveDate = null,
    status = LawStatus.CURRENT,
    source = SourceId.COURT,
    url = url,
)

/**
 * 关键词检索结果的标题兜底过滤(纯函数,单测锁定)。
 *
 * 规则:**整页有一条标题命中就原样采用官方结果**(官方检索含正文匹配,标题不含
 * 检索词也可能是有效命中,逐条过滤会误杀);整页一条标题都不命中,才认为这一页
 * 不是检索结果页(例如检索接口降级返回了栏目列表),此时按标题重筛。
 *
 * @param items 官方返回的条目
 * @param tokens 关键词按空白切分(空表示不检索,原样返回)
 * @param titleOf 取条目标题
 */
internal fun <T> applyTitleFallback(
    items: List<T>,
    tokens: List<String>,
    titleOf: (T) -> String,
): List<T> {
    if (tokens.isEmpty()) return items
    val matched = items.filter { item -> tokens.any { titleOf(item).contains(it) } }
    return if (matched.isNotEmpty()) items else matched
}
