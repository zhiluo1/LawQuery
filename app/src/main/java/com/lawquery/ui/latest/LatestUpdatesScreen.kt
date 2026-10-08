package com.lawquery.ui.latest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lawquery.R
import com.lawquery.data.repo.SearchRepository
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.SearchFilters
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SearchSort
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.ui.common.ErrorState
import com.lawquery.ui.common.LawListItem
import com.lawquery.ui.common.LoadingState
import com.lawquery.ui.common.SourceUnavailableBanner
import com.lawquery.util.TimeText
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 「今日更新」独立页(首页区块的完整列表)。
 *
 * ## 时效性怎么保证
 *
 * - **每次进入实时拉取**:不读本地缓存、不读历史,直接向各源要
 *   「空关键词 + 发布日期倒序 + 第 1 页」,即每个源各自最新的那批文件;
 * - 顶部显示**本次抓取时刻**,让用户知道数据新鲜到哪一刻;
 * - 支持**按分类**(全部/法律/行政法规/地方性法规/司法解释/宪法/监察法规/国务院文件)
 *   与**时效性**(全部/现行有效/尚未生效)筛选,两者都**下推到源接口**,
 *   不是在已加载的那一页里做客户端过滤(否则一筛就空);
 * - 滚动到底自动翻页,可一直往下翻。
 *
 * 单一来源失败不影响其他源(顶部提示条),与全局检索同一策略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LatestUpdatesScreen(
    searchRepository: SearchRepository,
    onBack: () -> Unit,
    onOpenDetail: (LawRef) -> Unit,
) {
    val viewModel: LatestUpdatesViewModel = viewModel(
        factory = LatestUpdatesViewModel.Factory(searchRepository),
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    val updatedAt by viewModel.updatedAt.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val degraded by viewModel.degraded.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val nextPage by viewModel.nextPage.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    /**
     * 时效性筛选的**客户端兜底**。
     *
     * flk 支持把该维度下推到官方接口(`sxx`)、公安部规章库在源内自筛,
     * 但中国政府网与最高人民法院**不认这个维度** —— 只靠下推的话,选「尚未生效」
     * 仍会把政府网那批现行有效文件列出来,用户看到的就是「筛选没生效」。
     * 与检索结果页([SearchViewModel.visibleItems])、分类页([BrowseViewModel.visibleItems])
     * 保持同一口径:源侧收敛之外再兜一层客户端过滤(已下推的维度重复过滤是幂等的)。
     */
    val visible = if (status == null) items else items.filter { it.status == status }
    /*
     * 「滚动到底自动加载下一页」——与分类页([com.lawquery.ui.browse.BrowseScreen])
     * 是同一套语义、同一个坑:每次追加都会按日期**整体重排**(各源页容量不同,
     * 不重排会「翻页后日期往回跳」),新内容被插到列表**上方**,而用户视口仍在底部。
     * 若以「items 变化」为触发时机,「接近底部」会永久成立 → 一轮接一轮自动请求,
     * 页面就永远停在加载中。
     * 因此改为:**用户滚动停止后评估一次**,视口静止时不重复触发。
     */
    val tryAutoLoadMore by rememberUpdatedState {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (nextPage != null && !loadingMore && last >= info.totalItemsCount - 3) {
            viewModel.loadMore()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it } // 只在「滚动停止」的那一刻评估
            .collect { tryAutoLoadMore() }
    }
    // 首屏就位后补一次:内容未满一屏、或首屏恰好已到底时也能继续取下一页
    LaunchedEffect(category, status, items.isNotEmpty()) {
        tryAutoLoadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = {
                    Column {
                        Text(stringResource(R.string.home_latest_title))
                        val updated = updatedAt
                        if (updated != null) {
                            Text(
                                stringResource(
                                    R.string.home_latest_updated_at,
                                    TimeText.dateTime(updated).substringAfter(' '),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 分类筛选:横向滚动,下推到源接口
            CategoryFilterRow(
                selected = category,
                onSelect = viewModel::setCategory,
            )
            // 时效性筛选
            StatusFilterRow(
                selected = status,
                onSelect = viewModel::setStatus,
            )

            when {
                items.isEmpty() && !failed -> LoadingState(Modifier.fillMaxSize())
                failed && items.isEmpty() -> ErrorState(
                    modifier = Modifier.fillMaxSize(),
                    message = stringResource(R.string.home_latest_failed),
                    onRetry = viewModel::load,
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "degraded") {
                        SourceUnavailableBanner(
                            messages = degraded.map { (name, _) ->
                                stringResource(R.string.state_source_unavailable, name)
                            },
                        )
                    }
                    items(visible, key = { "latest-" + it.key }) { ref ->
                        LawListItem(ref = ref, onClick = { onOpenDetail(ref) })
                    }
                    // 有数据但被时效性筛选全部滤掉:给出明确说明,避免页面看似空白
                    if (visible.isEmpty() && items.isNotEmpty()) {
                        item(key = "filter_empty") {
                            Text(
                                stringResource(R.string.latest_filter_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item(key = "footer") {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            } else if (nextPage == null && items.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.latest_no_more),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(R.string.home_footer_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分类筛选行(null = 全部) */
@Composable
private fun CategoryFilterRow(
    selected: LawCategory?,
    onSelect: (LawCategory?) -> Unit,
) {
    val options: List<Pair<LawCategory?, Int>> = listOf(
        null to R.string.latest_filter_all_categories,
        LawCategory.CONSTITUTION to R.string.category_constitution,
        LawCategory.LAW to R.string.category_law,
        LawCategory.ADMIN_REG to R.string.category_admin_reg,
        LawCategory.SUPERVISION to R.string.category_supervision,
        LawCategory.JUDICIAL to R.string.category_judicial,
        LawCategory.LOCAL to R.string.category_local,
        LawCategory.STATE_COUNCIL to R.string.category_state_council,
    )
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.first?.key ?: "all" }) { (cat, labelRes) ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text(stringResource(labelRes), maxLines = 1) },
            )
        }
    }
}

/** 时效性筛选行(null = 全部) */
@Composable
private fun StatusFilterRow(
    selected: LawStatus?,
    onSelect: (LawStatus?) -> Unit,
) {
    val options: List<Pair<LawStatus?, Int>> = listOf(
        null to R.string.latest_filter_all_status,
        LawStatus.CURRENT to R.string.status_current,
        LawStatus.PENDING to R.string.status_pending,
        LawStatus.REVISED to R.string.status_revised,
    )
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.first?.name ?: "all" }) { (st, labelRes) ->
            FilterChip(
                selected = selected == st,
                onClick = { onSelect(st) },
                label = { Text(stringResource(labelRes), maxLines = 1) },
            )
        }
    }
}

/**
 * 「今日更新」列表的加载状态。
 *
 * 每次 [load] 都重新向各源实时取数(不读缓存);筛选变更同样走 [load],
 * 保证「选了分类」拿到的是**该分类**的最新数据,而不是在旧数据里过滤。
 */
class LatestUpdatesViewModel(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<LawRef>>(emptyList())
    val items: StateFlow<List<LawRef>> = _items.asStateFlow()

    private val _updatedAt = MutableStateFlow<OffsetDateTime?>(null)
    val updatedAt: StateFlow<OffsetDateTime?> = _updatedAt.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** 源暂不可用提示(单源失败不影响其他源) */
    private val _degraded = MutableStateFlow<List<Pair<String, FailureReason>>>(emptyList())
    val degraded: StateFlow<List<Pair<String, FailureReason>>> = _degraded.asStateFlow()

    private val _nextPage = MutableStateFlow<Int?>(null)
    val nextPage: StateFlow<Int?> = _nextPage.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /** 分类筛选(null = 全部);下推到源接口 */
    private val _category = MutableStateFlow<LawCategory?>(null)
    val category: StateFlow<LawCategory?> = _category.asStateFlow()

    /** 时效性筛选(null = 全部);下推到源接口 */
    private val _status = MutableStateFlow<LawStatus?>(null)
    val status: StateFlow<LawStatus?> = _status.asStateFlow()

    /**
     * 加载代际(2026-10-08 修)。
     *
     * 原先用 `if (loading) return` 防重入 —— 但它会把**用户在加载过程中新选的筛选直接丢掉**:
     * 分类 chip 已高亮成新选项,列表却还是上一批数据,两边对不上(静默失效,最难察觉)。
     * 改为代际号:每次 [load] 递增,响应回来时只接受最新一代 —— 连点筛选时旧结果被丢弃,
     * 而不是覆盖掉更新的结果。
     */
    private var loadGeneration = 0

    init {
        load()
    }

    fun setCategory(cat: LawCategory?) {
        if (_category.value == cat) return
        _category.value = cat
        load()
    }

    fun setStatus(st: LawStatus?) {
        if (_status.value == st) return
        _status.value = st
        load()
    }

    fun load() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            val agg = searchRepository.search(
                SearchQuery(
                    keyword = "",
                    category = _category.value,
                    filters = SearchFilters(status = _status.value),
                    sort = SearchSort.PUBLISH_DATE_DESC,
                    page = 1,
                    pageSize = PAGE_SIZE,
                )
            )
            // 期间用户又换了筛选:丢弃本次结果,别覆盖更新的那一批
            if (generation != loadGeneration) return@launch
            _items.value = agg.items
            _degraded.value = agg.degraded
            _nextPage.value = agg.nextPage
            if (agg.items.isNotEmpty()) {
                _updatedAt.value = OffsetDateTime.now()
                _failed.value = false
            } else {
                // 一条都没取到才判失败;有部分源降级时提示但不挡路
                _failed.value = agg.offline || agg.degraded.isNotEmpty()
            }
        }
    }

    fun loadMore() {
        val page = _nextPage.value ?: return
        if (_loadingMore.value) return
        _loadingMore.value = true
        // 沿用发起时的代际:期间若换了筛选,这页数据作废(由新的 load 负责整表刷新)
        val generation = loadGeneration
        viewModelScope.launch {
            try {
                val agg = searchRepository.search(
                    SearchQuery(
                        keyword = "",
                        category = _category.value,
                        filters = SearchFilters(status = _status.value),
                        sort = SearchSort.PUBLISH_DATE_DESC,
                        page = page,
                        pageSize = PAGE_SIZE,
                    )
                )
                if (generation == loadGeneration && !agg.offline) {
                    // 跨页去重 + 日期倒序整体重排:多源页容量不同,直接拼接会让
                    // 第二页里较新的条目排在第一页较旧条目之后(日期倒跳)
                    _items.value = com.lawquery.data.repo.SearchRepository.appendForBrowse(
                        _items.value, agg.items, SearchSort.PUBLISH_DATE_DESC
                    )
                    _nextPage.value = agg.nextPage
                }
            } finally {
                _loadingMore.value = false
            }
        }
    }

    class Factory(
        private val searchRepository: SearchRepository,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LatestUpdatesViewModel(searchRepository) as T
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}