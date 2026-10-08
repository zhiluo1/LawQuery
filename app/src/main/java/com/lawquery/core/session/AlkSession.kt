package com.lawquery.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 人民法院案例库登录会话(需求 2 登录态存储策略)。
 *
 * 官方鉴权事实(2026-10-06 实测,官网前端 base.min.js):
 * - 登录为 OAuth 跳转式:`POST /cpws_al_api/api/user/getGdLoginUrl` 取授权地址 →
 *   跳 account.court.gov.cn → 回调带 code/state → `POST .../user/loginGd` 换会话;
 * - 会话建立后,官网前端统一给每个请求加 **请求头 `faxin-cpws-al-token: <userToken>`**
 *   (token 取自 `POST /cpws_al_api/api/user/getUserInfo` 的 `data.alUser.userToken`);
 * - 因此原生通道需要两样东西:会话 **Cookie**(换取 token)与 **userToken**(业务接口鉴权)。
 *
 * 存储策略:
 * - 凭据(cookie + userToken + token 取得时刻)经 [SecureAlkStore] 用 Android Keystore
 *   密钥加密后落盘,用户登录一次即可长期使用(此前仅驻内存,进程重启即失效,
 *   表现为"详情页获取失败");
 * - 「已对接」标记另存 SettingsStore(仅布尔);
 * - userToken 失效时先由数据层用 Cookie 续期(见
 *   [com.lawquery.data.source.CaseLibrarySource.refreshToken]),续不了才引导重新登录。
 *
 * **长效保存的三条硬约束**(2026-10-07 用户反馈「登录后仍有概率退出」后加固):
 * 1. **空值不得覆盖已有凭据**。此前 [updateCookie] 会把空白直接写成 null 而 token 仍在,
 *    于是「Cookie 被清空、token 还在」的半残凭据落盘;重启恢复出来必然被官方判为
 *    未登录 —— 这正是「偶发掉登录」的主要来源。现在空值一律忽略。
 * 2. **token 可续期**。userToken 会被官方置旧,但会话 Cookie 仍然有效:
 *    `getUserInfo` 只认 Cookie 就能换出新的 userToken。所以这里记录 token 取得时刻,
 *    供上层判断是否需要主动续期([needsTokenRefresh])。
 * 3. **失效判定要看 Cookie**。只有 Cookie 也被官方拒绝,才真的需要用户重新登录;
 *    单次 401 ≠ 掉登录。
 */
class AlkSession(private val store: SecureAlkStore? = null) {

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var userToken: String? = null

    /** 当前 userToken 的取得时刻(epoch millis);0 = 未知(旧版本落盘的数据) */
    @Volatile
    private var tokenAt: Long = 0L

    private val _linked = MutableStateFlow(false)

    /**
     * 「当前进程已持有可用凭据」的即时信号(与 [hasCredential] 同义,供 UI 直接订阅)。
     *
     * 为什么需要它:登录成功后宿主写的是 SettingsStore(DataStore,**异步**读取),
     * 跳转到案例库页的头几帧仍会读到旧值 false,页面先渲染一次「尚未登录」再自行纠正。
     * 把会话状态本身暴露成 Flow 后,「已对接」可在登录成功的那一刻即时生效。
     * 注意语义:这里只表示**本地持有凭据**,不代表官方会话仍然有效 —— 官方失效以
     * 接口返回的 401 为准(见 CaseLibrarySource)。
     */
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    init {
        // 进程启动即恢复上次登录的凭据
        store?.load()?.let { (c, t, at) ->
            cookie = c
            userToken = t
            tokenAt = at
        }
        syncLinked()
    }

    /**
     * WebView 登录完成后由 CookieManager 同步的官方会话 Cookie。
     *
     * ⚠️ 空白值**直接忽略**,绝不覆盖已存的 Cookie(类注释「硬约束 1」)。
     * 旧实现会把 blank 写成 null,造成「有 token、无 Cookie」的半残凭据落盘。
     */
    fun updateCookie(raw: String?) {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return
        cookie = value
        persist()
    }

    /**
     * 官方 getUserInfo 换回的 userToken(业务接口请求头凭据)。
     *
     * ⚠️ 换不到新 token 时**保留旧 token**,只当这次续期没成功 ——
     * 旧 token 或许还能用,贸然清空等于直接替用户注销。
     */
    fun updateToken(raw: String?) {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return
        userToken = value
        tokenAt = System.currentTimeMillis()
        persist()
        syncLinked()
    }

    fun cookie(): String? = cookie

    fun token(): String? = userToken

    /** 是否具备发起原生检索/取正文的条件 */
    val hasCredential: Boolean get() = !userToken.isNullOrBlank()

    /**
     * 是否还持有会话 Cookie。
     *
     * Cookie 才是**长期有效的根本**:userToken 会过期,而 Cookie 还能换出新 token。
     * 「Cookie 没了」才是必须重新登录的判据。
     */
    fun hasCookie(): Boolean = !cookie.isNullOrBlank()

    /** 当前 userToken 的取得时刻(epoch millis,0 = 未知) */
    fun tokenAtMillis(): Long = tokenAt

    /**
     * 是否应该用 Cookie 主动换一个新的 userToken。
     *
     * @param ttlMillis token 的最长使用时长,超过即认为该续期
     * @param nowMillis 当前时刻(可注入,便于测试)
     * @return true = 调用方应先续期再发请求;false = token 还新鲜,直接用
     */
    fun needsTokenRefresh(ttlMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (hasCredential && tokenAt > 0L && nowMillis - tokenAt < ttlMillis) return false
        // 没有 token,或 token 年龄未知/超期 —— 只要 Cookie 还在就还有救
        return hasCookie()
    }

    fun clear() {
        cookie = null
        userToken = null
        tokenAt = 0L
        store?.clear()
        syncLinked()
    }

    private fun persist() {
        store?.save(cookie, userToken, tokenAt)
    }

    private fun syncLinked() {
        _linked.value = hasCredential
    }
}