package com.lawquery.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawquery.R
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.FlkAuthorities
import com.lawquery.data.source.SearchFilters
import com.lawquery.data.source.SearchSort
import com.lawquery.domain.model.FilterOptions
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.ui.common.EmptyState
import com.lawquery.ui.common.ErrorState
import com.lawquery.ui.common.HighlightText
import com.lawquery.ui.common.LawStatusBadge
import com.lawquery.ui.common.LoadingState
import com.lawquery.ui.common.OfflineState
import com.lawquery.ui.common.SourceBadge
import com.lawquery.ui.common.SourceUnavailableBanner
import com.lawquery.ui.common.SkeletonList
import com.lawquery.ui.common.UiState
import com.lawquery.ui.theme.StarGold

/**
 * 搜索结果页(参考图风格):搜索框+取消、横向分类 tabs、「共找到 N 条结果」、
 * 结果卡(状态徽标+星标收藏+来源/机关标签)、筛选 FAB(底部弹层)。
 * 原有行为全部保留:联想/历史/多源聚合/分页/排序/筛选/离线重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenDetail: (LawRef) -> Unit,
    onOpenDirect: (url: String, keyword: String) -> Unit,
    /**
     * 人民法院案例库未登录时的登录入口(宿主导航到应用内官方登录页)。
     *
     * 全局检索会一并调用案例库,未登录时它返回「来源暂不可用」;若不给入口,
     * 用户看到案例一条都搜不出来却无处可去。null = 不提供(此时只显示降级横幅)。
     */
    onOpenCaseLogin: (() -> Unit)? = null,
) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val searchCategory by viewModel.searchCategory.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()

    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val data = (state as? UiState.Content)?.data
    val hasSearched = data?.keyword?.isNotBlank() == true || state is UiState.Loading ||
        state is UiState.Offline || state is UiState.Error

    var showFilterSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            val contentData = (state as? UiState.Content)?.data
            if (contentData != null && contentData.keyword.isNotBlank()) {
                SmallFloatingActionButton(
                    onClick = { showFilterSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = stringResource(R.string.search_filter_fab),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            // 搜索输入区:返回 + 输入框 + 取消(参考图)
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { viewModel.input.value = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = {
                        // 允许换行:窄屏/系统大字号下占位提示完整可见,不做省略截断
                        Text(stringResource(R.string.home_search_hint))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (input.isNotEmpty()) {
                            IconButton(onClick = { viewModel.input.value = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        viewModel.submit(input)
                    }),
                )
                TextButton(onClick = {
                    keyboard?.hide()
                    onBack()
                }) { Text(stringResource(R.string.search_cancel)) }
            }

            // 联想下拉(仅本地历史,F1 P1)
            if (suggestions.isNotEmpty() && !hasSearched) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column {
                        suggestions.take(6).forEach { s ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.submit(s) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            when (val s = state) {
                is UiState.Loading -> Column {
                    if (hasSearched) CategoryTabsRow(searchCategory, viewModel::setCategory)
                    SkeletonList()
                }
                is UiState.Offline -> OfflineState(onRetry = { viewModel.submit(input) })
                is UiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { viewModel.submit(input) },
                )
                is UiState.Empty -> {
                    CategoryTabsRow(searchCategory, viewModel::setCategory)
                    EmptyState(
                        title = stringResource(R.string.state_empty_title),
                        suggestion = stringResource(R.string.state_empty_suggestion),
                    )
                }
                is UiState.Content -> {
                    if (s.data.keyword.isNotBlank()) {
                        SearchResults(
                            viewModel = viewModel,
                            data = s.data,
                            sort = sort,
                            filters = filters,
                            favoriteKeys = favoriteKeys,
                            onOpenDetail = onOpenDetail,
                            onOpenDirect = onOpenDirect,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onOpenCaseLogin = onOpenCaseLogin,
                        )
                    }
                    // 关键词为空:未发起搜索,下方展示本地历史区(F1)
                }
            }

            // 未发起搜索时:展示本地历史(F1:仅存最近 50 条,可单删、清空)
            if (!hasSearched) {
                HistorySection(
                    history = history.map { it.query },
                    onDelete = viewModel::deleteHistory,
                    onClear = viewModel::clearHistory,
                    onClick = { viewModel.submit(it) },
                )
            }
        }
    }

    // 筛选底部弹层(参考图 FAB)
    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Text(
                stringResource(R.string.search_filter_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val searchCategoryForFilter by viewModel.searchCategory.collectAsStateWithLifecycle()
            FilterRow(
                sort = sort,
                filters = filters,
                years = FilterOptions.years(),
                authorities = data?.items?.let { viewModel.availableAuthorities(it) } ?: emptyList(),
                // 地区仅在「地方性法规」分类下有省级维度
                supportsRegion = viewModel.supportsRegion(searchCategoryForFilter),
                onToggleSort = viewModel::toggleSort,
                onFilters = viewModel::setFilters,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * 生效中的筛选条件摘要条。
 *
 * 只在有筛选时出现:逐条列出「时效性/年份/地区/发文机关」,末尾给「清空」。
 * 地区项仅在地方性法规分类下出现 —— 其余位阶由中央机关制定,无省级维度。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilterBar(
    filters: SearchFilters,
    supportsRegion: Boolean,
    onClear: () -> Unit,
) {
    val labels = buildList {
        filters.status?.let { add(stringResource(R.string.filter_label_status) + statusLabel(it)) }
        filters.year?.let { add(stringResource(R.string.filter_label_year) + it) }
        if (supportsRegion) {
            FlkAuthorities.nameOf(filters.region)?.let {
                add(stringResource(R.string.filter_label_region) + it)
            }
        }
        filters.authority?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(R.string.filter_label_authority) + it)
        }
    }
    if (labels.isEmpty()) return

    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        labels.forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        TextButton(onClick = onClear) {
            Text(
                stringResource(R.string.filter_reset),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SearchResults(
    viewModel: SearchViewModel,
    data: SearchUiData,
    sort: SearchSort,
    filters: SearchFilters,
    favoriteKeys: Set<String>,
    onOpenDetail: (LawRef) -> Unit,
    onOpenDirect: (String, String) -> Unit,
    onToggleFavorite: (LawRef) -> Unit,
    /** 人民法院案例库未登录时的登录入口(宿主导航到官方登录页);null 表示不提供 */
    onOpenCaseLogin: (() -> Unit)?,
) {
    val searchCategory by viewModel.searchCategory.collectAsStateWithLifecycle()
    val visible = viewModel.visibleItems(data, filters)
    val years = FilterOptions.years()
    val authorities = viewModel.availableAuthorities(data.items)
    val listState = rememberLazyListState()

    // 案例库未登录:横幅改为专门的登录引导卡(横幅文案只会说"来源暂不可用")
    val alkNeedsLogin = onOpenCaseLogin != null && viewModel.caseLibraryNeedsLogin(data)
    val degradedForBanner = if (alkNeedsLogin) {
        data.degraded.filterNot { it.second == FailureReason.NOT_LINKED }
    } else {
        data.degraded
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            data.nextPage != null && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore, data.items.size) {
        if (shouldLoadMore && !data.loadingMore) viewModel.loadMore()
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // 横向分类 tabs(参考图)
        item(key = "tabs") { CategoryTabsRow(searchCategory, viewModel::setCategory) }
        item(key = "count") {
            Text(
                stringResource(R.string.search_found_count, visible.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // 生效中的筛选摘要:筛选已下推到官方接口(不是只筛当前页),
        // 这里让用户一眼看到当前条件,并能一键清空
        item(key = "active_filters") {
            ActiveFilterBar(
                filters = filters,
                supportsRegion = viewModel.supportsRegion(searchCategory),
                onClear = { viewModel.setFilters(SearchFilters()) },
            )
        }
        item(key = "flk") {
            data.directJump?.let { FlkDirectCard(data.keyword, it, onOpenDirect) }
        }
        item(key = "degraded") {
            SourceUnavailableBanner(
                messages = degradedForBanner.map { (name, _) ->
                    stringResource(R.string.state_source_unavailable, name)
                },
            )
        }
        // 人民法院案例库未登录:给一个能真正解决问题的入口,而不是只丢一句"暂不可用"
        if (alkNeedsLogin) {
            item(key = "alk_login") {
                CaseLibraryLoginCard(onLogin = onOpenCaseLogin!!)
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.state_empty_title),
                    suggestion = stringResource(R.string.state_empty_suggestion),
                )
            }
        } else {
            itemsIndexed(visible, key = { _, ref -> ref.key }) { _, ref ->
                SearchResultCard(
                    ref = ref,
                    query = data.keyword,
                    favorited = ref.key in favoriteKeys,
                    onClick = { onOpenDetail(ref) },
                    onToggleFavorite = { onToggleFavorite(ref) },
                )
            }
        }
        item(key = "footer") {
            when {
                data.loadingMore -> Text(
                    stringResource(R.string.search_loading_more),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                data.nextPage == null && visible.isNotEmpty() -> Text(
                    stringResource(R.string.search_no_more),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/** 横向分类 tabs(全部 + 7 分类;选中紫色下划线) */
@Composable
private fun CategoryTabsRow(
    selected: LawCategory?,
    onSelect: (LawCategory?) -> Unit,
) {
    val tabs: List<Pair<LawCategory?, String>> = buildList {
        add(null to stringResource(R.string.search_tab_all))
        LawCategory.entries.forEach { cat ->
            add(cat to categoryLabelOf(cat))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (cat, label) ->
            val isSel = selected == cat
            Column(
                Modifier
                    .clickable { onSelect(cat) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            if (isSel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun categoryLabelOf(cat: LawCategory): String = when (cat) {
    LawCategory.CONSTITUTION -> stringResource(R.string.category_constitution)
    LawCategory.LAW -> stringResource(R.string.category_law)
    LawCategory.ADMIN_REG -> stringResource(R.string.category_admin_reg)
    LawCategory.SUPERVISION -> stringResource(R.string.category_supervision)
    LawCategory.JUDICIAL -> stringResource(R.string.category_judicial)
    LawCategory.LOCAL -> stringResource(R.string.category_local)
    LawCategory.STATE_COUNCIL -> stringResource(R.string.category_state_council)
    LawCategory.CASE_LIBRARY -> stringResource(R.string.category_case_library)
}

/** 搜索结果卡(参考图):标题+时效徽标;文号/日期+星标;来源/机关标签 */
@Composable
private fun SearchResultCard(
    ref: LawRef,
    query: String,
    favorited: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HighlightText(
                    text = ref.title,
                    query = query,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                LawStatusBadge(ref.status)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        ref.docNumber?.takeIf { it.isNotBlank() },
                        // 无日期就不拼(案例库部分案例取不到入库时间),避免显示「—」占位
                        ref.publishDate?.let { TimeTextDate(it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(30.dp)) {
                    Icon(
                        if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(
                            if (favorited) R.string.detail_favorite_remove else R.string.detail_favorite_add
                        ),
                        tint = if (favorited) StarGold else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceBadge(ref.source)
                if (ref.issuingAuthority.isNotBlank()) {
                    Text(
                        ref.issuingAuthority,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun TimeTextDate(d: java.time.LocalDate?): String = d?.toString() ?: "—"

/** 「在国家法律法规数据库中搜索『关键词』」官方直通卡片(需求 F1) */
@Composable
private fun FlkDirectCard(
    keyword: String,
    directJump: com.lawquery.data.source.DirectJumpInfo,
    onOpenDirect: (String, String) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onOpenDirect(directJump.url, keyword.ifBlank { directJump.keyword.orEmpty() }) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    stringResource(R.string.search_flk_card, keyword),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.search_flk_card_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * 人民法院案例库未登录时的登录引导卡(仅在全局检索里出现)。
 *
 * 与分类浏览页的登录引导同源文案;这里的场景是「结果里少了一个源」,
 * 所以重点是给出**能解决问题的入口**,而不是只丢一句"来源暂不可用"。
 */
@Composable
private fun CaseLibraryLoginCard(onLogin: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.alk_browse_unlinked_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.alk_browse_unlinked_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(10.dp))
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.alk_go_login))
            }
        }
    }
}

@Composable
private fun FilterRow(
    sort: SearchSort,
    filters: SearchFilters,
    years: List<Int>,
    authorities: List<String>,
    /** 当前分类是否支持地区筛选(仅地方性法规) */
    supportsRegion: Boolean,
    onToggleSort: () -> Unit,
    onFilters: (SearchFilters) -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }
    var yearMenu by remember { mutableStateOf(false) }
    var authorityMenu by remember { mutableStateOf(false) }
    var regionMenu by remember { mutableStateOf(false) }
    var regionQuery by remember { mutableStateOf("") }

    // 标签行可换行:窄屏下不会把末尾的筛选器压成竖排
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = sort == SearchSort.PUBLISH_DATE_DESC,
            onClick = onToggleSort,
            label = {
                Text(
                    if (sort == SearchSort.PUBLISH_DATE_DESC) {
                        stringResource(R.string.search_sort_date)
                    } else {
                        stringResource(R.string.search_sort_relevance)
                    }
                )
            },
        )

        Box {
            FilterChip(
                selected = filters.status != null,
                onClick = { statusMenu = true },
                label = { Text(filters.status?.let { statusLabel(it) } ?: stringResource(R.string.search_filter_status)) },
            )
            DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_status)) },
                    onClick = { onFilters(filters.copy(status = null)); statusMenu = false },
                )
                LawStatus.entries.forEach { st ->
                    DropdownMenuItem(
                        text = { Text(statusLabel(st)) },
                        onClick = { onFilters(filters.copy(status = st)); statusMenu = false },
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = filters.year != null,
                onClick = { yearMenu = true },
                label = { Text(filters.year?.toString() ?: stringResource(R.string.search_filter_year)) },
            )
            DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_year)) },
                    onClick = { onFilters(filters.copy(year = null)); yearMenu = false },
                )
                years.forEach { y ->
                    DropdownMenuItem(
                        text = { Text(y.toString()) },
                        onClick = { onFilters(filters.copy(year = y)); yearMenu = false },
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = filters.authority != null,
                onClick = { authorityMenu = true },
                label = {
                    Text(
                        filters.authority ?: stringResource(R.string.search_filter_authority),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            DropdownMenu(expanded = authorityMenu, onDismissRequest = { authorityMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_authority)) },
                    onClick = { onFilters(filters.copy(authority = null)); authorityMenu = false },
                )
                authorities.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onFilters(filters.copy(authority = a)); authorityMenu = false },
                    )
                }
            }
        }

        // 地区:仅地方性法规有省级维度,放在年份之前(该分类最常用的收敛条件)
        if (supportsRegion) {
            Box {
                FilterChip(
                    selected = filters.region != null,
                    onClick = { regionMenu = true; regionQuery = "" },
                    label = {
                        Text(
                            FlkAuthorities.nameOf(filters.region)
                                ?: stringResource(R.string.search_filter_region),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                DropdownMenu(
                    expanded = regionMenu,
                    onDismissRequest = { regionMenu = false },
                ) {
                    val q = regionQuery.trim()
                    val matched = remember(q) {
                        if (q.isBlank()) FlkAuthorities.PROVINCES
                        else FlkAuthorities.PROVINCES.filter {
                            it.name.contains(q, ignoreCase = true) ||
                                it.code.toString().contains(q) ||
                                it.group.contains(q, ignoreCase = true)
                        }
                    }
                    // 菜单以 intrinsic 测量宽度,内容不能是 LazyColumn
                    Column(Modifier.widthIn(min = 200.dp, max = 300.dp)) {
                        OutlinedTextField(
                            value = regionQuery,
                            onValueChange = { regionQuery = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    stringResource(R.string.filter_search_region),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .fillMaxWidth(),
                        )
                        if (matched.isEmpty()) {
                            Text(
                                stringResource(R.string.filter_no_match_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        val scrollState = rememberScrollState()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .verticalScroll(scrollState),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_region_all)) },
                                onClick = {
                                    onFilters(filters.copy(region = null)); regionMenu = false
                                },
                            )
                            if (q.isBlank()) {
                                matched.groupBy { it.group }.forEach { (group, list) ->
                                    Text(
                                        group,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 16.dp, top = 8.dp,
                                        ),
                                    )
                                    list.forEach { region ->
                                        DropdownMenuItem(
                                            text = { Text(region.name) },
                                            onClick = {
                                                onFilters(filters.copy(region = region.code))
                                                regionMenu = false
                                            },
                                        )
                                    }
                                }
                            } else {
                                matched.forEach { region ->
                                    DropdownMenuItem(
                                        text = { Text(region.name) },
                                        onClick = {
                                            onFilters(filters.copy(region = region.code))
                                            regionMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: LawStatus): String = when (status) {
    LawStatus.CURRENT -> stringResource(R.string.status_current)
    LawStatus.REVISED -> stringResource(R.string.status_revised)
    LawStatus.REPEALED -> stringResource(R.string.status_repealed)
    LawStatus.PENDING -> stringResource(R.string.status_pending)
}

@Composable
private fun HistorySection(
    history: List<String>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onClick: (String) -> Unit,
) {
    if (history.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.search_history_clear)) }
        }
        history.take(20).forEach { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(q) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onDelete(q) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.favorites_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
