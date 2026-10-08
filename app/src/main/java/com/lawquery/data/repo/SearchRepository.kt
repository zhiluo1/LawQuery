package com.lawquery.data.repo

import com.lawquery.data.source.DirectJumpInfo
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SearchSort
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceRegistry
import com.lawquery.data.source.SourceResult
import com.lawquery.domain.model.LawRef
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 搜索聚合(需求 F1 / 项目文档 1.3):
 * 一次搜索并行查询各原生源;受限源(flk)返回直通信息渲染为置顶卡片;
 * 单源失败不影响其他源结果(内嵌提示条),全部离线时进入统一离线页(F7)。
 */
class SearchRepository(private val registry: SourceRegistry) {

    /** 供分类页按需访问某个具体源(如案例库的检索分面),不改变聚合检索语义 */
    fun lawSource(id: SourceId): com.lawquery.data.source.LawSource? = registry.source(id)

    data class Aggregated(
        val items: List<LawRef>,
        val nextPage: Int?,
        val directJump: DirectJumpInfo?,
        /** 源显示名 → 失败原因,用于「××来源暂不可用」提示条 */
        val degraded: List<Pair<String, FailureReason>>,
        val offline: Boolean,
    )

    suspend fun search(query: SearchQuery): Aggregated = coroutineScope {
        val natives = registry.nativeSourcesFor(query.category)
        val web = registry.webSource()

        val nativeResults = natives.map { source -> async { source.search(query) } }
        val webResult = web?.search(query)

        val successItems = mutableListOf<LawRef>()
        var hasMoreFromAnySource = false
        val degraded = mutableListOf<Pair<String, FailureReason>>()
        var offlineCount = 0

        for (deferred in nativeResults) {
            when (val r = deferred.await()) {
                is SourceResult.Success -> {
                    successItems += r.data.items
                    if (r.data.nextPage != null) hasMoreFromAnySource = true
                }
                is SourceResult.SourceUnavailable -> {
                    degraded += displayName(r.source) to r.reason
                    if (r.reason == FailureReason.OFFLINE) offlineCount++
                }
            }
        }

        /**
         * 按 `LawRef.key`(源:源内 id)去重 —— 见 [dedupeByKey] 的说明:
         * 列表把该 key 交给 LazyColumn,重复即崩溃。
         */
        val unique = dedupeByKey(successItems)

        val merged = when (query.sort) {
            SearchSort.PUBLISH_DATE_DESC ->
                unique.sortedByDescending { it.publishDate ?: LocalDate.MIN }
            // 相关度:标题命中排前面,仅正文/其他字段命中的排后面
            SearchSort.RELEVANCE -> rankByRelevance(unique, query.keyword)
        }

        // 官方直通卡片:仅是跳转信息,不受源可用性影响
        val direct = (webResult as? SourceResult.Success)?.data?.directJump

        Aggregated(
            items = merged,
            // 返回空页时由上层停止加载(游标式翻页)
            nextPage = (query.page + 1).takeIf { unique.isNotEmpty() && hasMoreFromAnySource },
            directJump = direct,
            degraded = degraded,
            offline = natives.isNotEmpty() && offlineCount == natives.size,
        )
    }

    companion object {
        fun displayName(sourceId: SourceId): String = when (sourceId) {
            SourceId.GOV_CN -> "中国政府网"
            SourceId.COURT -> "最高人民法院"
            SourceId.FLK_WEB -> "国家法律法规数据库"
            SourceId.FLK -> "国家法律法规数据库"
            SourceId.CASE_LIBRARY -> "人民法院案例库"
            SourceId.MPS_REG -> "公安部规章库"
        }

        /**
         * 相关度排序:**标题命中在前,正文命中在后**。
         *
         * 需求:检索「标题包含关键词」的条目应排在「仅正文包含关键词」的条目之前,
         * 也就是先按标题检索、再按内容检索。官方检索接口对标题与正文是**同一次
         * 全文请求**返回的(flk 标题/全文、案例库 `selectValue=qw` 全文),
         * 返回顺序不代表相关度 —— 所以在聚合层按「标题是否包含检索词」重排,
         * 把标题命中的结果提到最前面,用户感知即为「先看标题命中,再看内容命中」。
         *
         * 分档(档内保持原有相对顺序,`sortedBy` 为稳定排序):
         * 0 = 标题与检索词完全一致 → 1 = 标题以检索词开头 → 2 = 标题包含检索词
         * → 3 = 标题不含(命中在正文/案号/机关等字段)。
         *
         * 检索词为空或结果不足两条时原样返回(不改变既有顺序)。
         */
        fun rankByRelevance(items: List<LawRef>, keyword: String): List<LawRef> {
            val kw = keyword.trim().lowercase()
            if (kw.isEmpty() || items.size < 2) return items
            fun tier(ref: LawRef): Int {
                val t = ref.title.lowercase()
                return when {
                    t == kw -> 0
                    t.startsWith(kw) -> 1
                    t.contains(kw) -> 2
                    else -> 3
                }
            }
            return items.sortedBy { tier(it) }
        }

        /**
         * 按 [LawRef.key] 去重并保留原有顺序(保留首条)。
         *
         * 列表层把 `ref.key` 直接当 LazyColumn 的 item key,同 key 出现两次会让
         * Compose 抛 `IllegalArgumentException: Key "..." was already used` **崩溃**
         * (2026-10-06 实测崩在 `COURT:188631`)。
         *
         * 两个调用点都要用:
         * - 本类 [search]:合并多个源的结果;
         * - 各页「加载更多」:跨页追加时,分页边界常把上页末条再返回一次。
         *
         * 单源内部重复也能挡住:最高法官网列表抓取在翻页边界会重复返回同一条。
         */
        fun dedupeByKey(items: List<LawRef>): List<LawRef> {
            if (items.size < 2) return items
            val seen = HashSet<String>(items.size)
            return items.filter { seen.add(it.key) }
        }

        /**
         * 把新一页追加到已有列表,并按 key 去重(跨页边界重复是常见来源)。
         */
        fun appendUnique(existing: List<LawRef>, more: List<LawRef>): List<LawRef> {
            if (more.isEmpty()) return existing
            if (existing.isEmpty()) return dedupeByKey(more)
            val seen = HashSet<String>(existing.size + more.size)
            existing.forEach { seen.add(it.key) }
            return existing + more.filter { seen.add(it.key) }
        }

        /**
         * 「浏览/日期倒序」场景的跨页追加:去重 **并整体重排**。
         *
         * 为什么必须重排:每页只在**页内**按发布日期排序,而各源每页容量并不相同
         * (中国政府网/最高法 15 条、公安部规章库 50 条、案例库 20 条),页码是各源
         * 独立的窗口 —— 直接拼接会出现「第 1 页末尾是 2008 年,第 2 页开头又回到
         * 2026 年」的日期倒跳。用户翻到第二页看到日期往回走,会以为列表乱了。
         *
         * 只在 [SearchSort.PUBLISH_DATE_DESC] 时重排:相关度序(标题命中前置)是
         * 各页独立计算的,不能跨页重排,否则会打乱「名称优先」的既定口径。
         */
        fun appendForBrowse(
            existing: List<LawRef>,
            more: List<LawRef>,
            sort: SearchSort,
        ): List<LawRef> {
            val merged = appendUnique(existing, more)
            if (sort != SearchSort.PUBLISH_DATE_DESC || merged.size < 2) return merged
            return merged.sortedByDescending { it.publishDate ?: LocalDate.MIN }
        }
    }
}
