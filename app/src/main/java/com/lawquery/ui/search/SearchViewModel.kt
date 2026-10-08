package com.lawquery.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lawquery.data.local.SearchHistoryEntity
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.SearchRepository
import com.lawquery.data.source.DirectJumpInfo
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.SearchFilters
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SearchSort
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.common.UiState
import com.lawquery.util.NetworkMonitor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 搜索结果页数据(F1) */
data class SearchUiData(
    val keyword: String = "",
    val items: List<LawRef> = emptyList(),
    val directJump: DirectJumpInfo? = null,
    val degraded: List<Pair<String, FailureReason>> = emptyList(),
    val nextPage: Int? = null,
    val loadingMore: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val historyRepository: HistoryRepository,
    private val networkMonitor: NetworkMonitor,
    private val favoriteRepository: com.lawquery.data.repo.FavoriteRepository,
) : ViewModel() {

    val input = MutableStateFlow("")

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    val history: StateFlow<List<SearchHistoryEntity>> = historyRepository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 已收藏 refKey 集合(结果卡星标) */
    val favoriteKeys: StateFlow<Set<String>> = favoriteRepository.observeAll()
        .map { list -> list.map { it.refKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _state = MutableStateFlow<UiState<SearchUiData>>(UiState.Content(SearchUiData()))
    val state: StateFlow<UiState<SearchUiData>> = _state.asStateFlow()

    private val _sort = MutableStateFlow(SearchSort.RELEVANCE)
    val sort: StateFlow<SearchSort> = _sort.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    /** 结果页横向分类 tabs(参考图):null=全部 */
    private val _searchCategory = MutableStateFlow<LawCategory?>(null)
    val searchCategory: StateFlow<LawCategory?> = _searchCategory.asStateFlow()

    private var lastQuery: String? = null

    /**
     * 检索请求代际号(2026-10-08 修)。
     *
     * 每次发起**新检索**时递增;协程在写任何状态之前都要核对代际,不是最新代际就整体丢弃。
     *
     * 为什么必须这样:网络返回顺序与发起顺序无关。用户快速改关键词、连点筛选/分类时,
     * 先发的请求若**后返回**,会把旧条件的结果写回界面(此时输入框已是新词),
     * 表现为「搜索结果和输入对不上、筛选像没生效」。翻页([loadMore])沿用发起时的代际 ——
     * 期间若有新检索,这页数据同样作废,不能用旧快照覆盖新结果。
     */
    private var requestGeneration = 0

    init {
        // 输入停顿 300ms 后基于本地历史联想(需求 F1 P1,联想不上传)
        viewModelScope.launch {
            input.debounce(300).distinctUntilChanged().collectLatest { q ->
                _suggestions.value = if (q.isBlank()) emptyList() else historyRepository.suggest(q)
            }
        }
        // F7:离线页在网络恢复后自动重试当前操作(StateFlow 已去重)
        viewModelScope.launch {
            networkMonitor.online.collect { online ->
                if (online && _state.value is UiState.Offline) {
                    lastQuery?.let { submitInternal(it, page = 1, append = false) }
                }
            }
        }
    }

    fun submit(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        input.value = q
        submitInternal(q, page = 1, append = false)
    }

    /** 切换分类 tab(参考图结果页):重新搜索 */
    fun setCategory(category: LawCategory?) {
        if (_searchCategory.value == category) return
        _searchCategory.value = category
        lastQuery?.let { submitInternal(it, page = 1, append = false) }
    }

    private fun submitInternal(q: String, page: Int, append: Boolean) {
        // 先算「是否换了关键词」再赋值:切筛选/切分类会复用同一个 q,
        // 那时不该整页 Loading(否则筛选面板会因失去数据源而关闭)
        val isNewKeyword = q != lastQuery
        lastQuery = q
        val generation = ++requestGeneration
        viewModelScope.launch {
            // 已有更新的检索在跑:本次连 Loading 都不要写,直接退出
            if (generation != requestGeneration) return@launch
            if (page == 1) {
                historyRepository.recordHistory(q)
                // ⚠️ 只有**换关键词**才清空筛选。
                // 早期写成「只要 page==1 就清」,而改筛选/切分类/切排序走的也是
                // page==1 —— 于是用户刚选的条件在发请求前就被抹掉,
                // 表现就是「点了筛选没反应」。判断依据必须是关键词是否变化。
                if (isNewKeyword && _filters.value != SearchFilters()) {
                    _filters.value = SearchFilters()
                }
            }
            // ⚠️ 只有「换关键词」才整页 Loading。
            // 改筛选/切分类/切排序时**保留当前结果**(只是把 loadingMore 置 true),
            // 否则每次调筛选都会先清空列表再跳 Loading ——
            // 筛选面板随之失去数据源而关闭,用户感知就是「筛选没生效」。
            if (isNewKeyword || (_state.value !is UiState.Content)) {
                _state.value = UiState.Loading
            } else {
                val cur = (_state.value as? UiState.Content)?.data
                _state.value = UiState.Content((cur ?: SearchUiData()).copy(loadingMore = true))
            }
            val agg = searchRepository.search(
                SearchQuery(
                    keyword = q,
                    category = _searchCategory.value,
                    filters = _filters.value,
                    sort = _sort.value,
                    page = page,
                    pageSize = PAGE_SIZE,
                )
            )
            // 返回时已不是最新检索(用户又改了关键词/筛选/分类):丢弃这页结果,
            // 否则旧条件的结果会覆盖新结果,界面与输入框对不上
            if (generation != requestGeneration) return@launch
            when {
                agg.offline -> _state.value = UiState.Offline
                else -> {
                    val current = (_state.value as? UiState.Content)?.data ?: SearchUiData()
                    val newData = current.copy(
                        keyword = q,
                        // 跨页追加必须去重:分页边界常把上页末条再返回一次,
                        // 重复 key 会让 LazyColumn 直接崩(见 SearchRepository.dedupeByKey)
                        items = if (append) {
                            com.lawquery.data.repo.SearchRepository.appendUnique(
                                current.items, agg.items
                            )
                        } else {
                            agg.items
                        },
                        directJump = agg.directJump,
                        degraded = agg.degraded,
                        nextPage = agg.nextPage,
                        loadingMore = false,
                    )
                    _state.value = when {
                        // 官方源确无此内容且无直通入口(F1 边界)
                        !append && newData.items.isEmpty() && newData.degraded.isEmpty() &&
                            newData.directJump == null -> UiState.Empty
                        // 来源暂不可用且无任何结果(F7)
                        !append && newData.items.isEmpty() && newData.degraded.isNotEmpty() -> UiState.Error()
                        else -> UiState.Content(newData)
                    }
                }
            }
        }
    }

    /**
     * 本次结果里是否出现「人民法院案例库未登录」。
     *
     * 全局检索(分类为「全部」)会一并调用案例库,未登录时它返回 `NOT_LINKED`。
     * 降级横幅只会说「××来源暂不可用」,既没说明原因也**没有登录入口** ——
     * 用户看到案例一条都搜不出来却无处可去。这里把该状态显式暴露给界面。
     *
     * 注:`NOT_LINKED` 目前只有人民法院案例库会产生,故按原因判定即可,不必匹配来源名。
     */
    fun caseLibraryNeedsLogin(data: SearchUiData): Boolean =
        data.degraded.any { it.second == FailureReason.NOT_LINKED }

    fun loadMore() {
        val data = (_state.value as? UiState.Content)?.data ?: return
        if (data.loadingMore || data.nextPage == null) return
        // 沿用发起时的代际:期间若用户改了关键词/筛选,这页数据就作废
        val generation = requestGeneration
        viewModelScope.launch {
            if (generation != requestGeneration) return@launch
            _state.value = UiState.Content(data.copy(loadingMore = true))
            val agg = searchRepository.search(
                SearchQuery(
                    keyword = data.keyword,
                    category = _searchCategory.value,
                    // 翻页必须带上当前筛选,否则会混入未筛选的条目
                    filters = _filters.value,
                    sort = _sort.value,
                    page = data.nextPage,
                    pageSize = PAGE_SIZE,
                )
            )
            // 期间已发起新检索:丢弃本页,不能用旧快照覆盖新结果
            if (generation != requestGeneration) return@launch
            // 翻页失败静默保留当前结果(避免打断阅读)
            if (!agg.offline) {
                _state.value = UiState.Content(
                    data.copy(
                        // 跨页去重;日期倒序时整体重排(各源页容量不同,拼接会日期倒跳)
                        items = com.lawquery.data.repo.SearchRepository.appendForBrowse(
                            data.items, agg.items, _sort.value
                        ),
                        nextPage = agg.nextPage,
                        loadingMore = false,
                    )
                )
            } else {
                _state.value = UiState.Content(data.copy(loadingMore = false))
            }
        }
    }

    fun toggleSort() {
        _sort.value = if (_sort.value == SearchSort.RELEVANCE) SearchSort.PUBLISH_DATE_DESC else SearchSort.RELEVANCE
        lastQuery?.let { submitInternal(it, page = 1, append = false) }
    }

    /**
     * 设置筛选条件并**重新检索**。
 *
     * ⚠️ 关键:筛选必须下推到源接口,不能只在已加载的那一页里做客户端过滤 ——
     * 否则「选了年份 2020」只会把当前页(按日期倒序的最新 10 条)里没有 2020 的
     * 条目筛掉,表现为「一筛就空」,而库里明明有大量 2020 年数据。
     * flk 侧可下推 `zdjgCodeId`(地区/机关)、`sxx`(时效性)、`gbrqYear`(年份)
     * 三个维度,实测均真实收敛结果。
     */
    fun setFilters(f: SearchFilters) {
        if (_filters.value == f) return
        _filters.value = f
        lastQuery?.let { submitInternal(it, page = 1, append = false) }
    }

    /**
     * 客户端二次过滤(**只作兜底,不是筛选的主要手段**)。
     *
     * 筛选已下推到源接口(flk 支持时效性/年份/地区三个维度),这里的过滤只在
     * 「源不支持该维度」或「下推后仍有边界条目」时补漏。
     *
     * ⚠️ 故意的设计:翻页追加的条目**也**会被这里过滤。宁可少显示几条,
     * 也不能让用户在同一份筛选下看到「前 10 条里 3 条、后 10 条里 0 条符合」
     * 这种自相矛盾的结果 —— 那才是真正的「筛选时有时无」。
     * 已下推的维度重复过滤是幂等的,不会误删。
     *
     * ⚠️ 人民法院案例库例外:年份已由官方 `year_cpwsAl` 服务端分面过滤,本地
     * **不再二次判定**。官方属性串偶尔取不到裁判日期(此时 `publishDate == null`),
     * 若在这里按年份过滤,会把服务端明明返回了的有效案例整片误杀 ——
     * 表现为「一按年份筛选,案例库结果全没了」。分类浏览页
     * ([com.lawquery.ui.browse.BrowseViewModel.visibleItems])早已有同一豁免,
     * 这里补齐,避免两处行为不一致。
     */
    fun visibleItems(data: SearchUiData, filters: SearchFilters): List<LawRef> =
        data.items.filter { ref ->
            (filters.status == null || ref.status == filters.status) &&
                (filters.year == null || ref.source == SourceId.CASE_LIBRARY ||
                    ref.publishDate?.year == filters.year) &&
                (filters.authority.isNullOrBlank() || ref.issuingAuthority.contains(filters.authority!!)) &&
                (filters.dateFrom == null || (ref.publishDate != null && !ref.publishDate.isBefore(filters.dateFrom))) &&
                (filters.dateTo == null || (ref.publishDate != null && !ref.publishDate.isAfter(filters.dateTo)))
        }

    /**
     * 可选的地区(仅地方性法规有地区维度)。
     *
     * 搜索页全局检索时,地区只在「地方性法规」分类下有意义 ——
     * 其余位阶由中央机关制定,没有省级维度。
     */
    fun supportsRegion(category: LawCategory?): Boolean = category == LawCategory.LOCAL

    fun availableYears(items: List<LawRef>): List<Int> =
        items.mapNotNull { it.publishDate?.year }.distinct().sortedDescending()

    fun availableAuthorities(items: List<LawRef>): List<String> =
        items.map { it.issuingAuthority }.filter { it.isNotBlank() }.distinct().take(12)

    fun deleteHistory(q: String) {
        viewModelScope.launch { historyRepository.deleteHistory(q) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clearHistory() }
    }

    /** 结果卡星标:切换收藏(仅元数据,需求 F5) */
    fun toggleFavorite(ref: LawRef) {
        viewModelScope.launch { favoriteRepository.toggle(ref) }
    }

    companion object {
        const val PAGE_SIZE = 10
    }

    class Factory(
        private val searchRepository: SearchRepository,
        private val historyRepository: HistoryRepository,
        private val networkMonitor: NetworkMonitor,
        private val favoriteRepository: com.lawquery.data.repo.FavoriteRepository,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(searchRepository, historyRepository, networkMonitor, favoriteRepository) as T
    }
}
