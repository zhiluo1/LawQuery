package com.lawquery.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lawquery.data.repo.SearchRepository
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.SearchFilters
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SearchSort
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.common.UiState
import com.lawquery.util.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class BrowseUiData(
    val category: LawCategory,
    val items: List<LawRef> = emptyList(),
    val degraded: List<Pair<String, FailureReason>> = emptyList(),
    val nextPage: Int? = null,
    val loadingMore: Boolean = false,
)

/**
 * 分类浏览(需求 F2):按发布日期倒序、筛选(时效性/发文机关/年份)、滚动分页。
 */
class BrowseViewModel(
    private val searchRepository: SearchRepository,
    private val networkMonitor: NetworkMonitor,
    val category: LawCategory,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<BrowseUiData>>(UiState.Loading)
    val state: StateFlow<UiState<BrowseUiData>> = _state.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    /**
     * 已提交的检索词(即当前列表结果所对应的那个词)。
     *
     * 界面据此区分「已输入但尚未检索」:输入只负责记录文字,何时发起检索由用户决定
     * (点「搜索」按钮或按输入法搜索键),不再逐字自动触发。
     */
    private val _appliedKeyword = MutableStateFlow("")
    val appliedKeyword: StateFlow<String> = _appliedKeyword.asStateFlow()

    /** 案例库源(仅案例库分类使用):用于拉取官方分面,使筛选基于全库而非已加载页 */
    private val caseLibrarySource =
        searchRepository.lawSource(com.lawquery.data.source.SourceId.CASE_LIBRARY)
            as? com.lawquery.data.source.CaseLibrarySource

    private val _alkYears = MutableStateFlow<List<com.lawquery.data.source.CaseLibrarySource.FacetOption>>(emptyList())

    /** 库中真实存在的审判年份及其数量(来自官方年份分面) */
    val alkYears: StateFlow<List<com.lawquery.data.source.CaseLibrarySource.FacetOption>> =
        _alkYears.asStateFlow()

    private val _alkCourts = MutableStateFlow<List<com.lawquery.data.source.CaseLibrarySource.FacetOption>>(emptyList())

    /** 库中真实存在的审理法院及其数量(来自官方法院分面) */
    val alkCourts: StateFlow<List<com.lawquery.data.source.CaseLibrarySource.FacetOption>> =
        _alkCourts.asStateFlow()

    init {
        load(page = 1)
        if (category == LawCategory.CASE_LIBRARY) loadCaseLibraryFacets()
        // 网络恢复自动重试(F7);StateFlow 本身已去重,无需 distinctUntilChanged
        viewModelScope.launch {
            networkMonitor.online.collect { online ->
                if (online && _state.value is UiState.Offline) load(page = 1)
            }
        }
    }

    /**
     * 拉取案例库官方分面(年份 / 审理法院)。
     * 分面覆盖整个案例库(受当前关键词约束),因此年份选择器能列出所有有数据的年份,
     * 不再受"只加载了最近几页"的限制。
     */
    fun loadCaseLibraryFacets() {
        val source = caseLibrarySource ?: return
        if (category != LawCategory.CASE_LIBRARY) return
        viewModelScope.launch {
            val probe = SearchQuery(
                keyword = _keyword.value.trim(),
                category = category,
                page = 1,
                pageSize = 1,
            )
            if (_alkYears.value.isEmpty()) {
                _alkYears.value = source.fetchFacetOptions(
                    com.lawquery.data.source.CaseLibrarySource.Facet.YEAR, probe
                )
            }
            if (_alkCourts.value.isEmpty()) {
                _alkCourts.value = source.fetchFacetOptions(
                    com.lawquery.data.source.CaseLibrarySource.Facet.COURT, probe
                )
            }
        }
    }

    /**
     * 加载代际(2026-10-08 修):每次 [load] 递增,协程写状态前核对。
     *
     * 分类页的「年份深翻」一次会连发多页请求(见下方 deepProbe),耗时可达数秒;
     * 期间用户改筛选、输关键词都会再触发一次 load —— 若不做代际校验,先发的那次
     * 后返回就会把**旧条件**的结果写回界面,用户感知是「筛选没生效」。
     * 翻页([loadMore])经由本方法发起,同样受保护。
     */
    private var loadGeneration = 0

    fun load(page: Int) {
        val generation = ++loadGeneration
        viewModelScope.launch {
            if (generation != loadGeneration) return@launch
            // 记下本次加载所对应的检索词:界面据此判断输入是否「尚未提交」
            _appliedKeyword.value = _keyword.value
            if (page == 1) _state.value = UiState.Loading
            val base = (_state.value as? UiState.Content)?.data ?: BrowseUiData(category = category)
            var items: List<LawRef> = if (page == 1) emptyList() else base.items
            var degraded: List<Pair<String, FailureReason>> =
                if (page == 1) emptyList() else base.degraded
            var nextPage: Int? = page
            var pages = 0
            // 年份筛选:GovCn 在服务端按年过滤(首屏即命中);Court 无服务端参数,
            // 列表按日期倒序,自动连续深翻定位到目标年份(仍受统一限流约束);
            // 有关键词时走源站检索(相关度序),不做深翻页;
            // 案例库年份由服务端分面(year_cpwsAl)过滤,同样无需深翻
            val deepProbe = category != LawCategory.CASE_LIBRARY &&
                _filters.value.year != null && _keyword.value.isBlank()
            val maxPages = if (deepProbe) 1 + AUTO_DEEP_PAGES else 1
            while (nextPage != null && pages < maxPages) {
                val agg = searchRepository.search(
                    SearchQuery(
                        keyword = _keyword.value.trim(),
                        category = category,
                        filters = _filters.value,
                        // ⚠️ 排序口径(需求:分类内部搜索「名称优先、内容其次」):
                        // 此前这里恒为 PUBLISH_DATE_DESC,导致 SearchRepository 的
                        // rankByRelevance 永远不会被调用 —— 分类内搜「劳动合同」时,
                        // 标题命中的法条被埋在只有正文命中的条目后面。
                        // 现在:有检索词走相关度(标题命中前置),无检索词仍是日期倒序浏览全库。
                        sort = if (_keyword.value.isBlank()) {
                            SearchSort.PUBLISH_DATE_DESC
                        } else {
                            SearchSort.RELEVANCE
                        },
                        page = nextPage,
                        pageSize = PAGE_SIZE,
                    )
                )
                // 期间已发起新一轮加载:本轮(含深翻的中间页)结果作废
                if (generation != loadGeneration) return@launch
                if (agg.offline) {
                    _state.value = UiState.Offline
                    return@launch
                }
                items = com.lawquery.data.repo.SearchRepository.appendForBrowse(
                    items,
                    agg.items,
                    // 与本次检索实际使用的排序口径一致:浏览=日期倒序(跨页重排,
                    // 避免「翻页后日期往回跳」);关键词检索=相关度(不重排,保持名称优先)
                    sort = if (_keyword.value.isBlank()) {
                        SearchSort.PUBLISH_DATE_DESC
                    } else {
                        SearchSort.RELEVANCE
                    },
                )
                degraded = (degraded + agg.degraded).distinctBy { it.first }
                nextPage = agg.nextPage
                pages++
                if (!deepProbe) break
                if (items.any { it.publishDate?.year == _filters.value.year }) break
            }
            // 最终写回前再核对一次:过期结果一律丢弃
            if (generation != loadGeneration) return@launch
            _state.value = UiState.Content(
                base.copy(
                    items = items,
                    degraded = degraded,
                    nextPage = nextPage,
                    loadingMore = false,
                )
            )
            if (page == 1 && items.isEmpty() && degraded.isEmpty()) {
                _state.value = UiState.Empty
            }
        }
    }

    fun loadMore() {
        val data = (_state.value as? UiState.Content)?.data ?: return
        if (data.loadingMore || data.nextPage == null) return
        val generation = loadGeneration
        viewModelScope.launch {
            // 读取快照与写入之间若已换了筛选/关键词,这块快照就不能再写回去
            if (generation != loadGeneration) return@launch
            _state.value = UiState.Content(data.copy(loadingMore = true))
            load(data.nextPage)
        }
    }

    fun setFilters(f: SearchFilters) {
        val changed = f != _filters.value
        _filters.value = f
        // 筛选条件变化即重新从服务端加载(年份/发文机关下推为源参数,组合生效)
        if (changed) load(page = 1)
    }

    /**
     * 分类内检索框输入:**只记录文字,不发起检索**。
     *
     * ⚠️ 此前这里带 700ms 防抖自动 `load(1)`:用户每敲一个字就自动发一次请求 ——
     * 输「行政处罚」会连发 4 次,中间的「行」「行政」「行政处」还会各自把列表刷成
     * 空/半截,表现为「列表乱跳、一输字就闪空态」。检索时机现改由用户自己掌握:
     * 点「搜索」或按输入法搜索键 → [submitKeyword]。
     */
    fun onKeywordInput(k: String) {
        _keyword.value = k
    }

    /** 提交检索:按当前输入框内容重新加载(与筛选条件组合生效) */
    fun submitKeyword() {
        // 词没变且当前已有结果时不必重复发请求;失败/离线状态下仍可点「搜索」重试
        if (_keyword.value == _appliedKeyword.value && _state.value is UiState.Content) return
        load(page = 1)
    }

    fun visibleItems(data: BrowseUiData, filters: SearchFilters): List<LawRef> =
        data.items.filter { ref ->
            (filters.status == null || ref.status == filters.status) &&
                // 案例库的年份由服务端分面参数(year_cpwsAl)过滤,本地不再二次判定:
                // 官方属性串偶尔取不到裁判日期,二次过滤会把有效结果误杀
                (category == LawCategory.CASE_LIBRARY ||
                    filters.year == null || ref.publishDate?.year == filters.year) &&
                (filters.authority.isNullOrBlank() || ref.issuingAuthority.contains(filters.authority!!))
        }

    fun availableYears(items: List<LawRef>): List<Int> =
        items.mapNotNull { it.publishDate?.year }.distinct().sortedDescending()

    // 「发文机关」的候选项与数量语义已迁至 ui/browse/AuthorityOptions(纯函数、可单测):
    // 候选并入官方制定机关字典(不必先翻页才选得到),数量只在有官方分面的源显示。
    // 原先的 authorityCounts 只统计**已加载条目**,数字与全库不符(用户实测 26 vs 5),
    // 且无法被 FlkSource 解析下推,已删除。

    class Factory(
        private val searchRepository: SearchRepository,
        private val networkMonitor: NetworkMonitor,
        private val category: LawCategory,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseViewModel(searchRepository, networkMonitor, category) as T
    }

    companion object {
        const val PAGE_SIZE = 15

        /** 年份筛选下单次加载自动深翻的最大页数;未定位到时用户可继续上滑加载更早页 */
        const val AUTO_DEEP_PAGES = 5
    }
}
