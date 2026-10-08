package com.lawquery.ui

import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lawquery.R
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.di.AppContainer
import com.lawquery.ui.browse.BrowseScreen
import com.lawquery.ui.detail.DetailScreen
import com.lawquery.ui.detail.DetailViewModel
import com.lawquery.ui.favorites.FavoritesScreen
import com.lawquery.ui.home.HomeScreen
import com.lawquery.ui.home.HomeViewModel
import com.lawquery.ui.home.NotificationScreen
import com.lawquery.ui.latest.LatestUpdatesScreen
import com.lawquery.ui.recents.RecentsScreen
import com.lawquery.ui.search.SearchScreen
import com.lawquery.ui.search.SearchViewModel
import com.lawquery.ui.settings.SettingsScreen
import com.lawquery.ui.settings.SourceManagementScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawquery.ui.web.DeepLinks
import com.lawquery.ui.web.OfficialWebScreen
import androidx.compose.runtime.LaunchedEffect
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

/** 路由定义(项目文档 M1 Navigation 骨架;参考图改版新增 sources 与搜索预填词) */
object Routes {
    const val HOME = "home"
    /** 导航用裸路由(query 参数可省略;带词搜索用 [searchWithQuery]) */
    const val SEARCH = "search"
    /** composable 模式:可选预填关键词 */
    const val SEARCH_PATTERN = "search?q={q}"
    const val BROWSE = "browse/{categoryKey}"
    const val DETAIL = "detail?ref={ref}"
    const val WEB = "web?url={url}&title={title}&keyword={keyword}"
    /** 需登录的官方页面(人民法院案例库):带「我已完成登录」复核入口 */
    const val WEB_LINK = "weblink?url={url}&title={title}"
    const val FAVORITES = "favorites"
    const val RECENTS = "recents"
    const val LATEST_UPDATES = "latest_updates"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"
    const val NOTIFICATIONS = "notifications"

    fun browse(categoryKey: String) = "browse/$categoryKey"

    fun searchWithQuery(query: String): String =
        "search?q=" + URLEncoder.encode(query, "UTF-8")

    fun detail(ref: LawRef): String = "detail?ref=" + URLEncoder.encode(NavArgs.encodeRef(ref), "UTF-8")

    fun web(url: String, title: String, keyword: String? = null): String {
        val q = listOfNotNull(
            "url=" + URLEncoder.encode(url, "UTF-8"),
            "title=" + URLEncoder.encode(title, "UTF-8"),
            keyword?.let { "keyword=" + URLEncoder.encode(it, "UTF-8") },
        ).joinToString("&")
        return "web?$q"
    }

    /** 需登录官方页(案例库):仅 url + title,登录复核由页面底部按钮触发 */
    fun webLink(url: String, title: String): String {
        val q = listOf(
            "url=" + URLEncoder.encode(url, "UTF-8"),
            "title=" + URLEncoder.encode(title, "UTF-8"),
        ).joinToString("&")
        return "weblink?$q"
    }
}

/** LawRef ↔ 路由参数编解码(不引入序列化框架,保持 domain 纯净) */
object NavArgs {
    fun encodeRef(ref: LawRef): String =
        listOf(
            ref.source.name,
            ref.id,
            ref.title,
            ref.issuingAuthority,
            ref.docNumber ?: "",
            ref.publishDate?.toEpochDay()?.toString() ?: "",
            ref.effectiveDate?.toEpochDay()?.toString() ?: "",
            ref.status.name,
            ref.url,
        ).joinToString("\u0001")

    fun decodeRef(raw: String): LawRef? {
        val parts = raw.split("\u0001")
        if (parts.size < 9) return null
        return LawRef(
            source = runCatching { com.lawquery.data.source.SourceId.valueOf(parts[0]) }.getOrNull() ?: return null,
            id = parts[1],
            title = parts[2],
            issuingAuthority = parts[3],
            docNumber = parts[4].takeIf { it.isNotEmpty() },
            publishDate = parts[5].takeIf { it.isNotEmpty() }?.let { LocalDate.ofEpochDay(it.toLong()) },
            effectiveDate = parts[6].takeIf { it.isNotEmpty() }?.let { LocalDate.ofEpochDay(it.toLong()) },
            status = runCatching { LawStatus.valueOf(parts[7]) }.getOrDefault(LawStatus.CURRENT),
            url = parts[8],
        )
    }

    fun fromNavEncoded(encoded: String): LawRef? =
        runCatching { decodeRef(URLDecoder.decode(encoded, "UTF-8")) }.getOrNull()
}

/** 应用根:底部导航(手机)/ 侧边导航栏(平板与展开宽度,需求 7.5 覆盖平板) */
@Composable
fun AppRoot(container: AppContainer, widthSizeClass: WindowWidthSizeClass) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val isTopLevel = currentRoute in listOf(Routes.HOME, Routes.FAVORITES, Routes.SETTINGS)
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    if (useRail) {
        Row(Modifier.fillMaxSize()) {
            if (isTopLevel) {
                AppNavigationRail(navController, currentRoute)
            }
            AppNavHost(navController, container, Modifier.weight(1f))
        }
    } else {
        Scaffold(
            // 状态栏 inset 交由各页面自行处理(内层 Scaffold/TopAppBar 已含状态栏边距,
            // 此处若再计入会造成双重 inset,顶部出现大片空白)
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (isTopLevel) {
                    AppNavigationBar(navController, currentRoute)
                }
            },
        ) { padding ->
            AppNavHost(navController, container, Modifier.padding(padding))
        }
    }
}

private data class TabSpec(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    TabSpec(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    TabSpec(Routes.FAVORITES, R.string.nav_favorites, Icons.Filled.StarBorder),
    TabSpec(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

private fun navigateTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavigationBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { navigateTab(navController, tab.route) },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(navController: NavHostController, currentRoute: String?) {
    NavigationRail {
        TABS.forEach { tab ->
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = { navigateTab(navController, tab.route) },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@Composable
fun AppNavHost(navController: NavHostController, container: AppContainer, modifier: Modifier) {
    /**
     * 统一的「打开法规条目」入口(首页/分类页/收藏页/历史页共用)。
     *
     * 所有原生源都进入应用内原生详情页:正文经官方接口解析后本地渲染,不落盘。
     */
    fun openRef(ref: LawRef) {
        navController.navigate(Routes.detail(ref))
    }

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = HomeViewModel.Factory(
                    container.historyRepository,
                    container.settingsStore,
                    container.favoriteRepository,
                    container.searchRepository,
                ),
            )
            val flkTitle = stringResource(R.string.source_flk)
            val alkTitle = stringResource(R.string.source_alk)
            val alkLinked by container.settingsStore.alkLinked.collectAsStateWithLifecycle(false)
            // 案例库「已对接」= 持久化标记 或 本进程已持有可用凭据。
            // 登录成功会同时置位两者,但 DataStore 是异步读取:只看 alkLinked 时,
            // 登录后跳回首页的头几帧仍会读成未登录并弹「需要登录」说明窗 —— 用会话状态兜底即可即时生效。
            val alkSessionLinked by container.alkSession.linked.collectAsStateWithLifecycle()
            val caseLibraryLinked = alkLinked || alkSessionLinked
            HomeScreen(
                viewModel = vm,
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSearchWithQuery = { q -> navController.navigate(Routes.searchWithQuery(q)) },
                // 人民法院案例库:已登录直接进原生列表;未登录由首页弹窗引导后进官方登录页
                caseLibraryLinked = caseLibraryLinked,
                onOpenCaseLogin = {
                    navController.navigate(Routes.webLink(DeepLinks.ALK_LIST, alkTitle))
                },
                onOpenCategory = { cat ->
                    // 六个法规分类(宪法/法律/行政法规/监察法规/地方性法规/司法解释)
                    // 由国家法律法规数据库原生供数,直接进分类列表页 —— 官方免费、
                    // 无需登录、无订购限制;点条目进详情页,正文在官方阅读器内呈现。
                    // 国务院及部委文件另走中国政府网(见 LawCategory.nativeSourceIds)。
                    if (cat.nativeSourceIds.isEmpty()) {
                        navController.navigate(Routes.web(DeepLinks.flkCategoryUrl(cat.flkType), flkTitle))
                    } else {
                        navController.navigate(Routes.browse(cat.key))
                    }
                },
                onOpenRecent = { ref -> openRef(ref) },
                // 「更多」打开独立的浏览历史页(此前误跳收藏,需求 F5)
                onOpenRecents = { navController.navigate(Routes.RECENTS) },
                // 「更多」打开独立的今日更新完整列表页(此前误跳国务院及部委文件分类)
                onOpenLatestUpdates = { navController.navigate(Routes.LATEST_UPDATES) },
                onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
            )
        }

        composable(Routes.RECENTS) {
            RecentsScreen(
                historyRepository = container.historyRepository,
                onBack = { navController.popBackStack() },
                onOpenDetail = { ref -> openRef(ref) },
            )
        }

        composable(Routes.LATEST_UPDATES) {
            LatestUpdatesScreen(
                searchRepository = container.searchRepository,
                onBack = { navController.popBackStack() },
                onOpenDetail = { ref -> openRef(ref) },
            )
        }

        composable(Routes.SEARCH_PATTERN) { entry ->
            val prefill = entry.arguments?.getString("q").orEmpty()
            val vm: SearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = SearchViewModel.Factory(
                    container.searchRepository,
                    container.historyRepository,
                    container.networkMonitor,
                    container.favoriteRepository,
                ),
            )
            val flkTitle = stringResource(com.lawquery.R.string.source_flk)
            val alkSearchTitle = stringResource(com.lawquery.R.string.source_alk)
            LaunchedEffect(prefill) {
                if (prefill.isNotBlank()) vm.submit(prefill)
            }
            SearchScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenDetail = { ref -> navController.navigate(Routes.detail(ref)) },
                onOpenDirect = { url, keyword ->
                    navController.navigate(Routes.web(url, flkTitle, keyword))
                },
                // 人民法院案例库未登录时,结果页给出官方登录入口(全局检索会一并命中它)
                onOpenCaseLogin = {
                    navController.navigate(Routes.webLink(DeepLinks.ALK_LIST, alkSearchTitle))
                },
            )
        }

        composable(Routes.BROWSE) { entry ->
            val categoryKey = entry.arguments?.getString("categoryKey") ?: ""
            val category = LawCategory.fromKey(categoryKey) ?: LawCategory.STATE_COUNCIL
            val flkTitle = stringResource(com.lawquery.R.string.source_flk)
            val alkTitle = stringResource(com.lawquery.R.string.source_alk)
            val alkLinked by container.settingsStore.alkLinked.collectAsStateWithLifecycle(false)
            // 同首页:持久化标记 或 会话已持有凭据,任一成立即视为已对接(避免 DataStore 异步读取期间误判未登录)
            val alkSessionLinked by container.alkSession.linked.collectAsStateWithLifecycle()
            val caseLibraryLinked = alkLinked || alkSessionLinked
            BrowseScreen(
                category = category,
                searchRepository = container.searchRepository,
                networkMonitor = container.networkMonitor,
                onBack = { navController.popBackStack() },
                onOpenDetail = { ref -> openRef(ref) },
                onOpenFlk = { url ->
                    navController.navigate(Routes.web(url, flkTitle))
                },
                // 人民法院案例库:登录态与重新登录入口(唯一需登录的源)
                caseLibraryLinked = caseLibraryLinked,
                onOpenCaseLogin = {
                    navController.navigate(Routes.webLink(DeepLinks.ALK_LIST, alkTitle))
                },
            )
        }

        composable(Routes.DETAIL) { entry ->
            val ref = NavArgs.fromNavEncoded(entry.arguments?.getString("ref") ?: "")
            if (ref == null) {
                navController.popBackStack()
                return@composable
            }
            val vm: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                key = ref.key,
                factory = DetailViewModel.Factory(
                    container.lawDetailRepository,
                    container.favoriteRepository,
                    container.settingsStore,
                    container.networkMonitor,
                    ref,
                ),
            )
            val alkTitleForDetail = stringResource(R.string.source_alk)
            DetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenOriginal = { r ->
                    navController.navigate(Routes.web(r.url, r.title))
                },
                // 取正文失败且原因为「需登录」时:目前仅人民法院案例库需官方登录
                // (六个法规分类已由国家法律法规数据库供数,免登录)
                onOpenCaseLogin = {
                    navController.navigate(Routes.webLink(DeepLinks.ALK_LIST, alkTitleForDetail))
                },
            )
        }

        composable(Routes.WEB) { entry ->
            val url = entry.arguments?.getString("url") ?: return@composable
            val title = entry.arguments?.getString("title") ?: ""
            val keyword = entry.arguments?.getString("keyword")
            OfficialWebScreen(
                url = url,
                title = title,
                keyword = keyword,
                onBack = { navController.popBackStack() },
            )
        }

        /**
         * 人民法院案例库登录页(需登录官方源)。
         * 应用不解析页面内容、不代填账号:仅用 WebView 自己的 Cookie 调官方 getUserInfo
         * 复核会话并换取 userToken(与官网前端同一路径),成功后置位「已对接」并进入原生列表。
         */
        composable(Routes.WEB_LINK) { entry ->
            val initialUrl = entry.arguments?.getString("url") ?: return@composable
            val title = entry.arguments?.getString("title") ?: ""
            OfficialWebScreen(
                url = initialUrl,
                title = title,
                keyword = null,
                onBack = { navController.popBackStack() },
                onMarkLinked = { _ ->
                    val cookie = android.webkit.CookieManager.getInstance()
                        .getCookie(DeepLinks.ALK_BASE)
                    // ⚠️ 先记住有没有可用会话:这次点按钮若换不到新 token,
                    // 不能顺手把**原本长期有效的凭据**一起清掉(用户会莫名掉登录)。
                    val hadCredential = container.alkSession.hasCredential
                    container.alkSession.updateCookie(cookie)
                    val ok = container.caseLibrarySource.syncSession()
                    if (ok) {
                        container.settingsStore.setAlkLinked(true)
                        navController.navigate(Routes.browse(LawCategory.CASE_LIBRARY.key)) {
                            popUpTo(Routes.HOME)
                        }
                    } else if (!hadCredential) {
                        // 本来就没有可用凭据,这次也没换到 → 清掉半截状态,等用户重登
                        container.alkSession.clear()
                        container.settingsStore.setAlkLinked(false)
                    }
                    ok
                },
            )
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(
                favoriteRepository = container.favoriteRepository,
                updateCheckService = container.updateCheckService,
                historyRepository = container.historyRepository,
                onOpenDetail = { ref -> navController.navigate(Routes.detail(ref)) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsStore = container.settingsStore,
                historyRepository = container.historyRepository,
                favoriteRepository = container.favoriteRepository,
                sourceStatusRepository = container.sourceStatusRepository,
                onBack = { navController.popBackStack() },
                onOpenSources = { navController.navigate(Routes.SOURCES) },
            )
        }

        composable(Routes.SOURCES) {
            val alkTitle = stringResource(com.lawquery.R.string.source_alk)
            SourceManagementScreen(
                sourceStatusRepository = container.sourceStatusRepository,
                settingsStore = container.settingsStore,
                onBack = { navController.popBackStack() },
                // 人民法院案例库:唯一需用户本人官方登录的源,未登录时在此引导
                onOpenCaseLogin = {
                    navController.navigate(Routes.webLink(DeepLinks.ALK_LIST, alkTitle))
                },
            )
        }

        composable(Routes.NOTIFICATIONS) {
            NotificationScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
