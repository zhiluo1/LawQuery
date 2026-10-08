package com.lawquery.ui.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方直通 WebView 域名白名单(需求 7.4)。
 *
 * 2026-10-08 修:公安部规章库(app.mps.gov.cn)已原生接入数据源,但
 * 「在官方源查看原文」打开的仍是该站页面 —— 白名单里漏了它,页内任何跳转
 * (附件、相关规章、站点自身重定向)都会弹「白名单外域名」并拦下。
 *
 * 这里只测纯函数 [UrlWhitelist.isAllowedHost]:`android.net.Uri` 在 JVM 单测里
 * 是未实现的 stub,直接测 isAllowed(uri) 会抛 "not mocked"。
 */
class UrlWhitelistTest {

    @Test
    fun `公安部规章库域名放行`() {
        assertTrue(UrlWhitelist.isAllowedHost("https", "app.mps.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "mps.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "www.mps.gov.cn"))
    }

    @Test
    fun `已接入的其它官方源都在白名单内`() {
        assertTrue(UrlWhitelist.isAllowedHost("https", "flk.npc.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "flkofd.npc.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "www.court.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "rmfyalk.court.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "www.gov.cn"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "zhengce.www.gov.cn"))
    }

    /** 非白名单域名必须拦下(否则 WebView 会变成任意站点浏览器) */
    @Test
    fun `白名单外域名一律拦下`() {
        assertFalse(UrlWhitelist.isAllowedHost("https", "example.com"))
        assertFalse(UrlWhitelist.isAllowedHost("https", "evil-mps.gov.cn.attacker.com"))
        // 后缀必须成段匹配:不能把 mps.gov.cn.attacker.com 这种放进来
        assertFalse(UrlWhitelist.isAllowedHost("https", "app.mps.gov.cn.attacker.com"))
    }

    /** 只允许 https:明文链接会被升级后再打开 */
    @Test
    fun `明文与其它协议一律不允许`() {
        assertFalse(UrlWhitelist.isAllowedHost("http", "app.mps.gov.cn"))
        assertFalse(UrlWhitelist.isAllowedHost("file", "app.mps.gov.cn"))
        assertFalse(UrlWhitelist.isAllowedHost(null, "app.mps.gov.cn"))
        assertFalse(UrlWhitelist.isAllowedHost("https", null))
    }

    /** 大小写不敏感(host 已 lowercase 处理) */
    @Test
    fun `域名大小写不敏感`() {
        assertTrue(UrlWhitelist.isAllowedHost("https", "APP.MPS.GOV.CN"))
        assertTrue(UrlWhitelist.isAllowedHost("https", "WWW.Court.Gov.CN"))
    }
}
