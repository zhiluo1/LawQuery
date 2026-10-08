package com.lawquery.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lawquery.R
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.FlkAuthorities
import com.lawquery.data.source.SearchFilters
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.FilterOptions
import com.lawquery.ui.common.EmptyState
import com.lawquery.ui.common.ErrorState
import com.lawquery.ui.common.LawListItem
import com.lawquery.ui.common.LoadingState
import com.lawquery.ui.common.OfflineState
import com.lawquery.ui.common.SourceUnavailableBanner
import com.lawquery.ui.common.SkeletonList
import com.lawquery.ui.common.SourceNames
import com.lawquery.ui.common.UiState
import com.lawquery.ui.theme.SourceCaseColor
import com.lawquery.ui.theme.SourceCourtColor
import com.lawquery.ui.theme.SourceFlkColor
import com.lawquery.ui.theme.SourceGovColor
import com.lawquery.ui.theme.SourceMpsColor
import com.lawquery.ui.web.DeepLinks
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 分类浏览列表页(需求 F2):按发布日期倒序;筛选:时效性、发文机关、年份;滚动分页。
 * 仅 A1 覆盖的分类提供「官方直通」入口。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    category: LawCategory,
    searchRepository: com.lawquery.data.repo.SearchRepository,
    networkMonitor: com.lawquery.util.NetworkMonitor,
    onBack: () -> Unit,
    onOpenDetail: (LawRef) -> Unit,
    onOpenFlk: (String) -> Unit,
    /** 人民法院案例库专用:是否已完成官方登录(其余分类恒为 true) */
    caseLibraryLinked: Boolean = true,
    /** 人民法院案例库专用:打开官方登录页重新建立会话 */
    onOpenCaseLogin: () -> Unit = {},
) {
    val viewModel: BrowseViewModel = viewModel(
        key = category.key,
        factory = BrowseViewModel.Factory(searchRepository, networkMonitor, category),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = { Text(categoryLabel(category), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))
            is UiState.Offline -> OfflineState(Modifier.padding(padding), onRetry = { viewModel.load(1) })
            is UiState.Error -> ErrorState(Modifier.padding(padding), onRetry = { viewModel.load(1) })
            is UiState.Empty -> Column(Modifier.padding(padding)) {
                // 人民法院案例库 —— ⚠️ 这里的判定曾被写反:原先写 `sessionExpired = caseLibraryLinked`,
                // 把「已登录」当成了「登录已失效」。后果是已登录用户只要一次检索 0 命中,
                // 整页就被替换成「官方登录状态已失效,请重新登录后继续浏览」,
                // 连检索框一起消失 —— 用户感知就是「登录了却无法搜索」。
                //
                // 语义上必须分清:UiState.Empty 只在「源正常应答、本次确无命中」时出现;
                // 未登录 / 官方会话失效会让源返回 NOT_LINKED,走 degraded → UiState.Content,
                // 由 BrowseList 负责渲染登录引导(带「尚未登录 / 会话已失效」两种标题)。
                // 因此进入本分支时用户**通常已经登录**,只有本地确实没凭据时才给登录引导,
                // 且此时也不该说「已失效」(那是 401 的场景)。
                if (category == LawCategory.CASE_LIBRARY && !caseLibraryLinked) {
                    CaseLibraryLoginGuide(
                        sessionExpired = false,
                        onLogin = onOpenCaseLogin,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                // 六个法规分类由国家法律法规数据库供数(免登录);
                // 仅人民法院案例库需用户本人官方登录
                if (category.flkType != null) {
                    CoverageNotice(category, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    AssistChip(
                        onClick = { onOpenFlk(DeepLinks.flkCategoryUrl(category.flkType)) },
                        label = { Text(stringResource(R.string.browse_flk_entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // 案例库已登录时,「无命中」就是无命中:照常给通用空态,不再指向登录
                if (category != LawCategory.CASE_LIBRARY || caseLibraryLinked) {
                    EmptyState(
                        title = stringResource(R.string.state_empty_title),
                        suggestion = stringResource(R.string.state_empty_suggestion),
                    )
                }
            }
            is UiState.Content -> BrowseList(
                Modifier.padding(padding),
                viewModel = viewModel,
                data = s.data,
                filters = filters,
                onOpenDetail = onOpenDetail,
                onOpenFlk = onOpenFlk,
                caseLibraryLinked = caseLibraryLinked,
                onOpenCaseLogin = onOpenCaseLogin,
            )
        }
    }
}

@Composable
private fun BrowseList(
    modifier: Modifier,
    viewModel: BrowseViewModel,
    data: BrowseUiData,
    filters: SearchFilters,
    onOpenDetail: (LawRef) -> Unit,
    onOpenFlk: (String) -> Unit,
    caseLibraryLinked: Boolean,
    onOpenCaseLogin: () -> Unit,
) {
    // 人民法院案例库:未登录或官方会话已失效时,只给登录引导
    // (不渲染注定为空的搜索/筛选/列表,避免用户误以为"库里没有案例")
    val alkSessionExpired = data.category == LawCategory.CASE_LIBRARY &&
        data.degraded.any { it.second == FailureReason.NOT_LINKED }
    if (data.category == LawCategory.CASE_LIBRARY && (!caseLibraryLinked || alkSessionExpired)) {
        Column(modifier.fillMaxSize()) {
            CaseLibraryLoginGuide(
                sessionExpired = alkSessionExpired,
                onLogin = onOpenCaseLogin,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        return
    }

    val visible = viewModel.visibleItems(data, filters)
    // 案例库:年份/审理法院选项来自官方分面(覆盖全库),其余分类沿用本地派生
    val alkYears by viewModel.alkYears.collectAsStateWithLifecycle()
    val alkCourts by viewModel.alkCourts.collectAsStateWithLifecycle()
    val isCaseLibrary = data.category == LawCategory.CASE_LIBRARY
    // 年份选项固定为 1978 年至当前年份(动态),不依赖已加载页
    val fallbackYears = remember { FilterOptions.years() }
    val years = if (isCaseLibrary && alkYears.isNotEmpty()) {
        alkYears.mapNotNull { it.id.toIntOrNull() }.distinct().sortedDescending()
    } else {
        fallbackYears
    }
    /*
     * 机关候选项与**数量语义**(2026-10-08 修):
     * - 案例库:官方分面覆盖全库,数量准确 → 显示;
     * - 其余源:没有机关分面,数字只能从已加载的几十条里统计(用户实测 26 条却显示 5,
     *   翻页还会变)→ **不显示**,数量为 null。候选本身来自「官方制定机关字典 + 已加载条目」,
     *   不必先翻页才能选到后面的机关。详见 AuthorityOptions。
     */
    val authorities: List<Pair<String, Int?>> = if (isCaseLibrary && alkCourts.isNotEmpty()) {
        alkCourts.map { Pair(it.name, it.count as Int?) }
    } else {
        AuthorityOptions.forCategory(data.items, data.category)
    }
    // 已加载条目中最新的年份,用于年份面板打开时定位到有数据的区间
    val latestDataYear = data.items.firstNotNullOfOrNull { it.publishDate?.year }
    val hasFilter = filters.status != null || filters.year != null ||
        filters.authority != null || filters.region != null
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val appliedKeyword by viewModel.appliedKeyword.collectAsStateWithLifecycle()
    // 输入内容与「当前结果所对应的词」不一致 → 存在未提交的改动,此时才点亮「搜索」
    val keywordPending = keyword != appliedKeyword
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    /*
     * 「滚动到底自动加载下一页」。
     *
     * ⚠️ 触发时机**不能**是「items 一变就重新评估」(原实现是
     * `LaunchedEffect(shouldLoadMore, data.items.size)`):浏览态每次追加都会按日期
     * **整体重排**(见 SearchRepository.appendForBrowse —— 各源页容量不同,不重排会
     * 出现「翻页后日期往回跳」),于是新加载的条目被插到列表**上方**,而用户视口仍停在
     * 底部 —— 「接近底部」永久成立,便一轮接一轮地自动请求下一页:界面一直挂着
     * 「正在加载更多…」,同时持续消耗官方接口配额(政府网按「本页有返回就继续」翻页,
     * 数据量大时能一直翻下去)。
     *
     * 现在把评估绑定到**用户的滚动动作**上:每次滚动停止后评估一次,视口静止时
     * 绝不重复触发;用户真的继续下滑,才会加载下一页。
     */
    val tryAutoLoadMore by rememberUpdatedState {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (data.nextPage != null && !data.loadingMore && last >= info.totalItemsCount - 4) {
            viewModel.loadMore()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it } // 只在「滚动停止」的那一刻评估
            .collect { tryAutoLoadMore() }
    }
    // 首屏就位后补一次:内容未满一屏、或首屏恰好已到底时,也能继续取下一页
    LaunchedEffect(appliedKeyword, data.items.isNotEmpty()) {
        tryAutoLoadMore()
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        item(key = "degraded") {
            SourceUnavailableBanner(
                messages = data.degraded.map { (name, _) ->
                    stringResource(R.string.state_source_unavailable, name)
                },
            )
        }

        // 人民法院案例库:已连接但可能只是会话仍在,明示数据获取方式
        if (data.category == LawCategory.CASE_LIBRARY) {
            item(key = "alk_linked_hint") {
                Text(
                    stringResource(R.string.alk_browse_linked_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        // 有官方直通补充的分类:官方直通入口 + 收录声明(需求 F2 数据路由)
        if (data.category.flkType != null) {
            item(key = "coverage_notice") {
                CoverageNotice(data.category, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item(key = "flk") {
                AssistChip(
                    onClick = { onOpenFlk(DeepLinks.flkCategoryUrl(data.category.flkType)) },
                    label = { Text(stringResource(R.string.browse_flk_entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // 行政法规/司法解释:收录特点由上方 CoverageNotice 一并声明

        // 分类内检索:输入框只记录文字,**何时发起检索由用户决定** ——
        // 点「搜索」按钮或按输入法搜索键才发请求。
        // ⚠️ 此前逐字防抖 700ms 自动加载:输「行政处罚」会连发 4 次请求,
        // 中间的「行」「行政」「行政处」还会各自把列表刷成空/半截,
        // 表现为「一输字列表就乱跳、闪到空态」,且白白消耗官方接口配额。
        item(key = "browse_search") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = viewModel::onKeywordInput,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.browse_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (keyword.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.onKeywordInput("") },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.search_history_clear),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        viewModel.submitKeyword()
                    }),
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(6.dp))
                TextButton(
                    onClick = {
                        keyboard?.hide()
                        viewModel.submitKeyword()
                    },
                    // 仅在「有未提交的改动」时可点,给用户明确的提交时机反馈
                    enabled = keywordPending,
                ) {
                    Text(stringResource(R.string.search_action))
                }
            }
        }

        item(key = "filters") {
            // 地区筛选只对地方性法规开放:地区维度以制定机关代码下推给国家法律法规数据库
            FilterRow(
                filters = filters,
                years = years,
                authorities = authorities,
                latestDataYear = latestDataYear,
                hasFilter = hasFilter,
                supportsRegion = data.category == LawCategory.LOCAL,
                onFilters = viewModel::setFilters,
            )
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = if (hasFilter) stringResource(R.string.filter_no_match_title)
                    else stringResource(R.string.state_empty_title),
                    suggestion = when {
                        hasFilter && filters.status != null && filters.status != LawStatus.CURRENT ->
                            stringResource(R.string.filter_no_status_hint)
                        hasFilter -> stringResource(R.string.filter_no_match_suggestion)
                        else -> stringResource(R.string.state_empty_suggestion)
                    },
                )
            }
        } else if (data.category.isMultiSource) {
            /*
             * 多源分类按来源分组。
             *
             * 「国务院及部委文件」(政府网 + 规章库)与「司法解释」(flk + 最高法)
             * 的结果在聚合层被并成一条日期倒序列表,两个官方来源的条目交错出现 ——
             * 用户只能靠卡片底部的小徽标分辨「这条谁发的」,观感混乱、来源不可见。
             * 分组后每组有明确标题与条数,组内仍是日期倒序(见 SourceGrouping)。
             *
             * item key 约定:分组标题用 `group-<源名>`,条目的 key 是
             * `LawRef.key`(= `<源名>:<源内id>`)—— 两者不会撞,满足 LazyColumn
             * 对 key 全局唯一的要求(撞了会直接抛异常崩溃,见 SearchRepository.dedupeByKey)。
             */
            SourceGrouping.group(visible, data.category.nativeSourceIds).forEach { g ->
                item(key = "group-" + g.source.name) {
                    SourceGroupHeader(source = g.source, count = g.items.size)
                }
                items(g.items, key = { it.key }) { ref ->
                    LawListItem(ref = ref, onClick = { onOpenDetail(ref) })
                }
            }
        } else {
            itemsIndexed(visible, key = { _, ref -> ref.key }) { _, ref ->
                LawListItem(ref = ref, onClick = { onOpenDetail(ref) })
            }
        }

        item(key = "footer") {
            /*
             * 三态(此前只判 loadingMore,到底后页脚仍是空文本 + 上一次的加载文案观感,
             * 用户以为「卡住了、还在加载」):
             * - 加载中 → 「正在加载更多…」;
             * - 已到底(没有下一页且列表非空)→ 「已加载全部结果」,给一个明确的收尾;
             * - 还有下一页但尚未触发加载 → 不显示任何文字(静待用户下滑)。
             */
            val footerText = when {
                data.loadingMore -> stringResource(R.string.search_loading_more)
                data.nextPage == null && visible.isNotEmpty() ->
                    stringResource(R.string.search_no_more)
                else -> null
            }
            if (footerText != null) {
                Text(
                    footerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/**
 * 多源分类的**来源分组标题**:左侧一条来源色竖条 + 「来源名 · N 条」。
 *
 * 色条沿用该来源在列表项徽标上的同一色系(见 [com.lawquery.ui.common.SourceBadge]),
 * 让分组标题与组内条目的来源色一眼对应。
 */
@Composable
private fun SourceGroupHeader(source: SourceId, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(sourceAccent(source), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.browse_group_header, SourceNames.displayName(source), count),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分组标题色条:与 [com.lawquery.ui.common.SourceBadge] 的徽标色一一对应 */
private fun sourceAccent(source: SourceId) = when (source) {
    SourceId.GOV_CN -> SourceGovColor
    SourceId.COURT -> SourceCourtColor
    SourceId.FLK_WEB -> SourceFlkColor
    SourceId.FLK -> SourceFlkColor
    SourceId.CASE_LIBRARY -> SourceCaseColor
    SourceId.MPS_REG -> SourceMpsColor
}

@Composable
private fun FilterRow(
    filters: SearchFilters,
    years: List<Int>,
    authorities: List<Pair<String, Int?>>,
    latestDataYear: Int?,
    hasFilter: Boolean,
    /** 该分类是否支持地区筛选(仅地方性法规:数据源以制定机关承载地区) */
    supportsRegion: Boolean,
    onFilters: (SearchFilters) -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }

    // 标签行可换行:窄屏(如一加13)下不会把末尾标签压缩成竖排
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
        // 地区:仅地方性法规有地域维度,放在年份之前(它是该分类最常用的收敛条件)
        if (supportsRegion) RegionPicker(filters, onFilters)
        YearPicker(filters, years, latestDataYear, onFilters)
        AuthorityPicker(filters, authorities, onFilters)
        if (hasFilter) {
            AssistChip(
                onClick = { onFilters(SearchFilters()) },
                label = { Text(stringResource(R.string.filter_reset)) },
            )
        }
    }
}

/**
 * 地区选择器(仅地方性法规):列出 31 个省级行政区,按地理分组并支持按名搜索。
 *
 * 取值是**国家法律法规数据库的制定机关代码**(`FlkAuthorities.code`,Int),
 * 而非显示名 —— 显示名只用于界面,交由数据源下推为 `zdjgCodeId`。
 * 地方性法规由各省、市人大常委会制定,flk 以「地方人大及其常委会」为父节点下挂 31 个地区,
 * 地区维度就落在这个制定机关上(实测下推有效:广东 1812 / 江苏 1511 / 北京 329 条)。
 * 分组标题不可点,选中态以勾选标记。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionPicker(
    filters: SearchFilters,
    onFilters: (SearchFilters) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selectedName = FlkAuthorities.nameOf(filters.region)

    Box {
        FilterChip(
            selected = filters.region != null,
            onClick = { menu = true; query = "" },
            label = {
                Text(
                    selectedName ?: stringResource(R.string.search_filter_region),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val q = query.trim()
            val matched = remember(q) {
                if (q.isBlank()) FlkAuthorities.PROVINCES
                else FlkAuthorities.PROVINCES.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.code.toString().contains(q) ||
                        it.group.contains(q, ignoreCase = true)
                }
            }
            // 与机关选择器同理:菜单以 intrinsic 测量宽度,搜索框用固定宽度,
            // 且内容不能是 LazyColumn(SubcomposeLayout 不支持 intrinsic,展开即崩)
            Column(Modifier.widthIn(min = 200.dp, max = 300.dp)) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                stringResource(R.string.filter_search_region),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.search_history_clear),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(180.dp),
                    )
                }
                if (matched.isEmpty()) {
                    Text(
                        stringResource(R.string.filter_no_match_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                val scrollState = rememberScrollState()
                LaunchedEffect(query) { scrollState.scrollTo(0) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(scrollState),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filter_region_all)) },
                        onClick = { onFilters(filters.copy(region = null)); menu = false },
                    )
                    // 搜索态不打分组标题,直接平铺结果
                    if (q.isBlank()) {
                        matched.groupBy { it.group }.forEach { (group, list) ->
                            Text(
                                group,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            )
                            list.forEach { region ->
                                RegionItem(region, filters.region) {
                                    onFilters(filters.copy(region = region.code)); menu = false
                                }
                            }
                        }
                    } else {
                        matched.forEach { region ->
                            RegionItem(region, filters.region) {
                                onFilters(filters.copy(region = region.code)); menu = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionItem(
    region: FlkAuthorities.Authority,
    selectedCode: Int?,
    onClick: () -> Unit,
) {
    val selected = region.code == selectedCode
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    region.name,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    region.group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        onClick = onClick,
    )
}

/** 年份选择器:固定高度滚动列表替代整列平铺;打开时定位到选中年份或最新有数据年份 */
@Composable
private fun YearPicker(
    filters: SearchFilters,
    years: List<Int>,
    latestDataYear: Int?,
    onFilters: (SearchFilters) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = filters.year != null,
            onClick = { menu = true },
            label = { Text(filters.year?.toString() ?: stringResource(R.string.search_filter_year)) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val scrollState = rememberScrollState()
            val density = LocalDensity.current
            LaunchedEffect(menu) {
                if (!menu) return@LaunchedEffect
                val target = filters.year ?: latestDataYear ?: years.firstOrNull()
                    ?: return@LaunchedEffect
                val idx = years.indexOf(target)
                if (idx > 0) {
                    // 非懒列表:按 DropdownMenuItem 默认最小高度 48dp 估算滚动定位
                    val itemPx = with(density) { 48.dp.toPx() }
                    scrollState.scrollTo(((idx + 1) * itemPx).toInt())
                }
            }
            // DropdownMenu 以 intrinsic 测量宽度,内容不能是 LazyColumn(SubcomposeLayout
            // 不支持 intrinsic,展开即崩),这里用普通 Column + verticalScroll
            Column(
                Modifier
                    .widthIn(min = 150.dp)
                    .height(280.dp)
                    .verticalScroll(scrollState),
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.filter_year_all),
                            color = if (filters.year == null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onFilters(filters.copy(year = null)); menu = false },
                )
                years.forEach { y ->
                    val selected = y == filters.year
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    y.toString(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.weight(1f))
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        onClick = { onFilters(filters.copy(year = y)); menu = false },
                    )
                }
            }
        }
    }
}

/** 发文机关选择器:仅列当前分类真实发布过文件的单位(按数据量降序),顶部支持按名称搜索 */
@Composable
private fun AuthorityPicker(
    filters: SearchFilters,
    authorities: List<Pair<String, Int?>>,
    onFilters: (SearchFilters) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        FilterChip(
            selected = filters.authority != null,
            onClick = { menu = true; query = "" },
            label = {
                Text(
                    filters.authority ?: stringResource(R.string.search_filter_authority),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // 宽度随内容自适应(由最宽机关条目决定):intrinsic 测量下 fillMaxWidth 的
            // 文本框会返回屏幕宽把菜单撑满,所以文本框用固定宽度短路该查询
            Column(Modifier.widthIn(min = 240.dp, max = 330.dp)) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                stringResource(R.string.filter_search_authority),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.search_history_clear),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(216.dp),
                    )
                }
                val filtered = if (query.isBlank()) authorities
                else authorities.filter { it.first.contains(query.trim(), ignoreCase = true) }
                val scrollState = rememberScrollState()
                LaunchedEffect(query) { scrollState.scrollTo(0) }
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.filter_no_match_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                // 同年份选择器:菜单内容不能是 LazyColumn(intrinsic 测量崩溃)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(scrollState),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filter_authority_all)) },
                        onClick = { onFilters(filters.copy(authority = null)); menu = false },
                    )
                    filtered.forEach { (name, count) ->
                        val selected = name == filters.authority
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    // 数量只在数据源能给**全库准确计数**时显示(案例库官方分面);
                                    // 其余源的数字只统计了已加载的几十条,显示即误导 —— 见 AuthorityOptions
                                    if (count != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Text(
                                                count.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                            onClick = { onFilters(filters.copy(authority = name)); menu = false },
                        )
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
fun categoryLabel(category: LawCategory): String = when (category) {
    LawCategory.CONSTITUTION -> stringResource(R.string.category_constitution)
    LawCategory.LAW -> stringResource(R.string.category_law)
    LawCategory.ADMIN_REG -> stringResource(R.string.category_admin_reg)
    LawCategory.SUPERVISION -> stringResource(R.string.category_supervision)
    LawCategory.JUDICIAL -> stringResource(R.string.category_judicial)
    LawCategory.LOCAL -> stringResource(R.string.category_local)
    LawCategory.STATE_COUNCIL -> stringResource(R.string.category_state_council)
    LawCategory.CASE_LIBRARY -> stringResource(R.string.category_case_library)
}

/**
 * 人民法院案例库登录引导:未登录/会话失效时展示,说明政策限制与获取方式,并提供登录入口。
 */
@Composable
private fun CaseLibraryLoginGuide(
    sessionExpired: Boolean,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(
                    if (sessionExpired) R.string.alk_session_expired
                    else R.string.alk_browse_unlinked_title
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.alk_browse_unlinked_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alk_go_login))
            }
        }
    }
}

/** 收录/时效说明条:告知数据可能滞后或遗漏,引导到国家法律法规数据库实时检索 */
/**
 * 分类收录声明卡片(面向用户,只讲「收录什么系列 / 怎么筛 / 怎么读 / 到哪核验」)。
 *
 * 单一入口:原先通用提示与行政法规、司法解释的专属说明是两块独立文本,内容重叠、
 * 读起来重复,现合并为一张卡片 —— 通用句讲筛选与核验,分类专属句只补充该系列的收录特点。
 * 全部面向 APP 用户,不出现效力位阶、制定机关代码等内部实现术语。
 */
@Composable
private fun CoverageNotice(category: LawCategory, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.browse_coverage_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val series = when (category) {
                LawCategory.CONSTITUTION -> R.string.browse_series_constitution
                LawCategory.LAW -> R.string.browse_series_law
                LawCategory.ADMIN_REG -> R.string.browse_admin_reg_note
                LawCategory.SUPERVISION -> R.string.browse_series_supervision
                LawCategory.JUDICIAL -> R.string.browse_judicial_note
                LawCategory.LOCAL -> R.string.browse_series_local
                else -> null
            }
            if (series != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(series),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}