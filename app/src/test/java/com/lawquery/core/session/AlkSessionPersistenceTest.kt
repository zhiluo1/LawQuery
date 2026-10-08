package com.lawquery.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人民法院案例库会话的**长效保存**行为(2026-10-07 加固)。
 *
 * 用户反馈「登录后仍有概率退出」。定位到两个会把长期凭据毁掉的实现缺陷:
 *
 * 1. [AlkSession.updateCookie] 曾把空白直接写成 null —— 于是落盘变成
 *    「有 token、无 Cookie」的半残凭据;重启恢复出来必然被官方判为未登录。
 * 2. [AlkSession.updateToken] 曾把换不到新 token 也写成 null,等于在续期失败时
 *    **主动把还能用的旧 token 删掉**。
 *
 * 修复后的约定(即本测试锁定的不变式):
 * - 空值永不覆盖已有凭据;
 * - 换不到新 token 就保留旧 token;
 * - 只要 Cookie 还在,token 就可以自助续期([AlkSession.needsTokenRefresh]);
 * - [AlkSession.clear] 是唯一的硬删除入口,且必须把 token 时刻一并清零。
 */
class AlkSessionPersistenceTest {

    private val ttl = 12 * 60 * 60 * 1000L

    /** 已登录的会话:Cookie + token 齐全 */
    private fun loggedIn(): AlkSession = AlkSession().apply {
        updateCookie("JSESSIONID=abc123; cpwsAlToken=xyz")
        updateToken("user-token-1")
    }

    @Test
    fun `采到空 Cookie 时保留原有 Cookie 与 token`() {
        val s = loggedIn()
        s.updateCookie("")
        s.updateCookie("   ")
        s.updateCookie(null)
        assertEquals("JSESSIONID=abc123; cpwsAlToken=xyz", s.cookie())
        assertEquals("user-token-1", s.token())
        assertTrue("空值不得让已登录会话掉线", s.hasCredential)
        assertTrue(s.hasCookie())
    }

    @Test
    fun `换不到新 token 时保留旧 token`() {
        val s = loggedIn()
        s.updateToken(null)
        s.updateToken("")
        s.updateToken("   ")
        assertEquals("user-token-1", s.token())
        assertTrue(s.hasCredential)
    }

    @Test
    fun `非空的新 Cookie 与新 token 会正常覆盖`() {
        val s = loggedIn()
        s.updateCookie("JSESSIONID=new-cookie")
        s.updateToken("user-token-2")
        assertEquals("JSESSIONID=new-cookie", s.cookie())
        assertEquals("user-token-2", s.token())
    }

    @Test
    fun `token 仍在有效期内不需要续期`() {
        val s = loggedIn()
        val now = s.tokenAtMillis() + ttl - 1
        assertFalse(s.needsTokenRefresh(ttl, nowMillis = now))
    }

    @Test
    fun `token 超过有效期需要续期`() {
        val s = loggedIn()
        val now = s.tokenAtMillis() + ttl
        assertTrue(s.needsTokenRefresh(ttl, nowMillis = now))
    }

    /** updateToken 必须记录取得时刻 —— 否则无法判断是否该续期,长效保存就退化成"撞到 401 再补救" */
    @Test
    fun `updateToken 会记录 token 取得时刻`() {
        val s = loggedIn()
        assertTrue("token 时刻必须落库,否则无法做过期续期", s.tokenAtMillis() > 0L)
    }

    /** 没有 token 但 Cookie 还在 —— 这是「可以自助修复」的典型状态 */
    @Test
    fun `没有 token 但有 Cookie 时需要续期`() {
        val s = AlkSession()
        s.updateCookie("JSESSIONID=abc")
        assertFalse(s.hasCredential)
        assertTrue(s.needsTokenRefresh(ttl, nowMillis = 0L))
    }

    /** 从没登录过:不该反复去换 token */
    @Test
    fun `既无 token 也无 Cookie 时不需要续期`() {
        val s = AlkSession()
        assertFalse(s.needsTokenRefresh(ttl, nowMillis = 0L))
    }

    @Test
    fun `clear 是硬删除-凭据与 token 时刻一并清零`() {
        val s = loggedIn()
        assertTrue(s.linked.value)
        s.clear()
        assertFalse(s.linked.value)
        assertFalse(s.hasCredential)
        assertFalse(s.hasCookie())
        assertEquals(0L, s.tokenAtMillis())
    }

    /** 仅更新 Cookie 不会凭空把 linked 置真(linked 语义 = 持有可用 token) */
    @Test
    fun `只有 Cookie 没有 token 时 linked 仍为 false`() {
        val s = AlkSession()
        s.updateCookie("JSESSIONID=abc")
        assertFalse(s.linked.value)
        s.updateToken("t")
        assertTrue(s.linked.value)
    }
}