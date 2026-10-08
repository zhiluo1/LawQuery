package com.lawquery.ui.web

import android.net.Uri
import java.net.URLEncoder

/**
 * 官方源 URL 白名单(需求 7.4):
 * flk.npc.gov.cn、flkofd.npc.gov.cn(国家法律法规数据库的正文阅读器域)、
 * *.www.gov.cn、www.gov.cn、www.court.gov.cn;
 * 白名单外跳转一律拦截,仅允许在系统浏览器打开。
 */
object UrlWhitelist {

    private const val FLK = "flk.npc.gov.cn"

    /** flk 的 OFD/PDF 阅读器域,与站点主域分开部署(实测正文页面在此域) */
    private const val FLK_READER = "flkofd.npc.gov.cn"
    private const val COURT = "www.court.gov.cn"

    /** 公安部规章库(部门规章数据源,详情页「查看原文」指向此站) */
    private const val MPS = "mps.gov.cn"

    fun isAllowed(url: String): Boolean = runCatching { isAllowed(Uri.parse(url)) }.getOrDefault(false)

    fun isAllowed(uri: Uri): Boolean = isAllowedHost(uri.scheme, uri.host)

    /**
     * 白名单判定**纯函数**(不依赖 android.net.Uri,便于 JVM 单测)。
     * `isAllowed(uri)` 只是取 scheme/host 后转调本函数。
     */
    fun isAllowedHost(scheme: String?, host: String?): Boolean {
        if (scheme != "https") return false
        val h = host?.lowercase() ?: return false
        return h == FLK ||
            h == FLK_READER ||
            h == COURT ||
            h == "www.gov.cn" ||
            h.endsWith(".www.gov.cn") ||
            h.endsWith(".court.gov.cn") ||
            /*
             * 公安部规章库:数据源已原生接入(详情正文由 htmlContent 解析),
             * 但「在官方源查看原文」仍会打开 app.mps.gov.cn 的正文页 —— 该页内部
             * 跳转(附件、相关规章、站点自身重定向)此前一律被判为白名单外并弹窗拦截。
             */
            h == MPS ||
            h.endsWith(".$MPS")
    }
}

/**
 * 官方直通深链(项目文档 4.4:集中定义,禁止散落硬编码)。
 *
 * M0 结论(docs/数据源接入说明.md):
 * - flk.npc.gov.cn 为 Vue 单页应用,历史版本支持 fl.html 查询参数深链
 *   (?type=分类参数&searchType=title;vague&q=关键词);真机若发现未自动带入,
 *   兜底方案为「复制关键词引导」,WebView 壳提供复制按钮。
 */
object DeepLinks {

    const val FLK_HOME = "https://flk.npc.gov.cn/index"
    const val FLK_SEARCH_PAGE = "https://flk.npc.gov.cn/fl.html"
    const val GOV_CN_HOME = "https://zhengce.www.gov.cn/"
    const val COURT_HOME = "https://www.court.gov.cn/"

    /**
     * 人民法院案例库(需登录源)。
     * 登录入口直接使用官网列表页:未登录时该页的官网脚本会自行弹出登录提示并跳转
     * account.court.gov.cn 完成 OAuth 授权,回调后回到本页 —— 应用不实现任何登录表单,
     * 也不代持账号,只提供 WebView 载体。
     */
    const val ALK_BASE = "https://rmfyalk.court.gov.cn"
    const val ALK_LIST = "$ALK_BASE/view/list.html"

    /** flk 关键词搜索深链 */
    fun flkSearchUrl(keyword: String): String =
        "$FLK_SEARCH_PAGE?type=&searchType=${URLEncoder.encode("title;vague", "UTF-8")}" +
            "&q=${URLEncoder.encode(keyword, "UTF-8")}"

    /** flk 分类浏览深链(宪法 xf / 法律 flfg / 行政法规 xzfg / 监察法规 jcfg / 司法解释 sfjs / 地方性法规 dfxfg) */
    fun flkCategoryUrl(flkType: String?): String =
        if (flkType.isNullOrBlank()) FLK_HOME
        else "$FLK_SEARCH_PAGE?type=${URLEncoder.encode(flkType, "UTF-8")}"

    /** 分类官方直通入口(首页/浏览页「官方数据库查看」) */
    fun flkDirectLabel(): String = "国家法律法规数据库"
}
