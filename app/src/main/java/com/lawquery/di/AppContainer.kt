package com.lawquery.di

import android.content.Context
import android.os.Build
import com.lawquery.core.net.HttpClientFactory
import com.lawquery.core.net.PageFetcher
import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.core.sessioncache.SessionCache
import com.lawquery.data.local.AppDatabase
import com.lawquery.data.local.SettingsStore
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.LawDetailRepository
import com.lawquery.data.repo.SearchRepository
import com.lawquery.data.repo.SourceStatusRepository
import com.lawquery.data.repo.UpdateCheckService
import com.lawquery.data.source.CaseLibrarySource
import com.lawquery.data.source.CourtSource
import com.lawquery.data.source.GovCnApiFactory
import com.lawquery.data.source.GovCnSource
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceRegistry
import com.lawquery.ui.web.DeepLinks
import com.lawquery.util.NetworkMonitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 手动 DI 容器(项目文档 5:唯一装配依赖的地方,不引入 Hilt)。
 */
class AppContainer(context: Context) {

    /**
     * 应用级后台作用域(冷启动收藏校验、案例库会话自愈、凭证失效标记等)。
     *
     * ⚠️ 必须带 [CoroutineExceptionHandler]:`SupervisorJob` 只**隔离**子任务之间的失败,
     * 并不会吞掉异常 —— 任何未捕获异常都会冒泡到线程默认处理器,在 Android 上会**直接崩溃进程**。
     * 这里统一兜底并留日志:后台的非关键任务出问题时只丢一条日志,绝不打扰前台用户。
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                android.util.Log.w("LawQuery", "后台任务异常(已兜底,不影响前台)", e)
            },
    )

    val healthTracker = SourceHealthTracker()
    val networkMonitor = NetworkMonitor(context)
    val settingsStore = SettingsStore(context)

    /** 人民法院案例库登录会话(Keystore 加密落盘,重启后仍可用;标记见 SettingsStore.alkLinked) */
    val alkSession = com.lawquery.core.session.AlkSession(
        com.lawquery.core.session.SecureAlkStore(context)
    )

    private val okHttpClient: OkHttpClient =
        HttpClientFactory.create(healthTracker, debugLogging = false)

    private val pageFetcher = PageFetcher(okHttpClient)

    private val database = AppDatabase.create(context)

    // ---- 数据源(新增源 = 新增实现类 + 在此注册,项目文档 4.2) ----
    private val govCnSource = GovCnSource(
        // 必须传入限流管道客户端(项目文档 4.1:全部出网统一 UA/限流/退避)
        api = GovCnApiFactory.create(okHttpClient),
        pageFetcher = pageFetcher,
        health = healthTracker,
    )
    private val courtSource = CourtSource(
        pageFetcher = pageFetcher,
        health = healthTracker,
    )
    /**
     * 国家法律法规数据库 flk(宪法/法律/行政法规/监察法规/地方性法规/司法解释)。
     * 官方免费、无需登录、无付费墙,题录字段完整;正文在应用内官方阅读器呈现。
     * 免登录,故无需「已对接」标记 —— 装好即可用。
     *
     * 此前还有一个 `WebOnlySource`(只产出「去官网搜」的跳转卡片)与本源并存,
     * 因flk 已能直接出结果,那张卡片成了多余的中间步骤,已一并移除。
     */
    val flkNativeSource = com.lawquery.data.source.FlkSource(
        client = okHttpClient,
        health = healthTracker,
    )

    /**
     * 公安部规章库(app.mps.gov.cn/gdnps/zc/list.jsp)。
     *
     * 官方免费、无需登录、无付费墙:检索与详情走同一个 JSONP 接口,
     * 详情返回体里直接带 `htmlContent` 正文 HTML,不需要像 flk 那样借助官方阅读器。
     * 归入「国务院及部委文件」分类,与中国政府网的国务院/部委文件互补。
     *
     * ⚠️ 站点前置创宇盾风控:务必让它走全局限流管道(间隔 ≥ 2s、并发 ≤ 2),
     * 且一次检索最多两个请求(标题 + 正文)。这也是必须注入 `okHttpClient` 的原因。
     */
    private val mpsRegSource = com.lawquery.data.source.MpsRegSource(
        client = okHttpClient,
        health = healthTracker,
    )

    // 人民法院案例库:需用户本人完成官方登录;凭据经 Keystore 加密落盘(AlkSession)
    val caseLibrarySource = CaseLibrarySource(
        client = okHttpClient,
        session = alkSession,
        health = healthTracker,
        // 官方会话失效:复位「已对接」标记,首页/数据源管理回到未对接并引导重新登录
        onCredentialInvalid = {
            appScope.launch { settingsStore.setAlkLinked(false) }
        },
    )

    /**
     * 已注册的原生检索源 —— **只有这五个**,数据源管理页与之一致。
     * 其中 [SourceId.GOV_CN] 与 [SourceId.MPS_REG] 共同供数给「国务院及部委文件」。
     *
     * ⚠️ 不要把 [SourceId.FLK_WEB] 再注册进来:它只产出「去官网搜」的跳转卡片,
     * 而 flk 已是原生源、能直接出结果,这张卡片是多余的中间步骤。
     */
    val sourceRegistry = SourceRegistry(
        mapOf(
            SourceId.GOV_CN to govCnSource,
            SourceId.COURT to courtSource,
            SourceId.FLK to flkNativeSource,
            SourceId.CASE_LIBRARY to caseLibrarySource,
            SourceId.MPS_REG to mpsRegSource,
        )
    )

    // ---- 仓库 ----
    val historyRepository = HistoryRepository(database.searchHistoryDao(), database.recentDao())
    val favoriteRepository = FavoriteRepository(database.favoriteDao(), sourceRegistry)
    val searchRepository = SearchRepository(sourceRegistry)
    val lawDetailRepository = LawDetailRepository(sourceRegistry, SessionCache(), historyRepository)
    val updateCheckService = UpdateCheckService(favoriteRepository)
    val sourceStatusRepository = SourceStatusRepository(healthTracker, database.sourceHealthDao(), sourceRegistry, appScope)
}
