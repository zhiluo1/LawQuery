package com.lawquery.data.source

import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.core.session.AlkSession
import com.lawquery.domain.model.Chapter
import com.lawquery.domain.model.LawArticle
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.Section
import com.lawquery.domain.model.VersionFingerprint
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 人民法院案例库(源 A4,原生解析通道,需登录)。
 *
 * 接口事实(2026-10-06 实测,官网列表页 /view/list.html 前端脚本):
 * - 入口:`POST https://rmfyalk.court.gov.cn/cpws_al_api/api/cpwsAl/search`
 *   Content-Type: application/json;charset=UTF-8;
 * - 请求体:`{page, size, lib, searchParams{userSearchType, isAdvSearch, selectValue,
 *   lib, sort_field, keyTitle, cpwsAl_year, cpws_al_gid...}}`
 *   (`keyTitle` = 检索词,`selectValue` = 检索字段,`cpwsAl_year` = 年份分面);
 * - 响应:`{code:"0", data:{datas:[...], totalCount}}`;未登录返回 `{code:"401"}`;
 * - 条目字段:`id / cpws_al_title / cpws_al_type(01 指导性·02 参考·04 特色) /
 *   cpws_al_status(02=已失效) / cpws_al_infos(法院·案号·日期属性串) / cpws_al_cpyz(裁判要旨)`;
 * - 详情页:`/view/content.html?id=<id>&lib=<zdx|ck|tssp>`。
 *
 * 登录态:接口鉴权依赖官方会话 Cookie,由用户在应用内 WebView 完成官方登录后
 * 经 CookieManager 同步到 [AlkSession](仅内存,不落盘)。
 * 合规边界:应用只按用户自己的会话做单次检索展示,不批量抓取、不落库、不代持账号。
 */
class CaseLibrarySource(
    private val client: OkHttpClient,
    private val session: AlkSession,
    private val health: SourceHealthTracker,
    /** 官方会话被判定失效(401)时回调:宿主据此把「已对接」标记复位,引导重新登录 */
    private val onCredentialInvalid: () -> Unit = {},
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
    /**
     * 「今天」,宿主可注入以便测试固定日期。
     *
     * 用途:属性串里解析到的日期若晚于今天,一律判为噪声(发布日期不可能在未来)。
     * 这条兜底与 [CaseInfoParser] 的边界正则配合 —— 正则挡住"从入库编号里截日期",
     * 这里再挡住"官方属性串本身写错/格式怪"。
     */
    private val today: () -> LocalDate = { LocalDate.now() },
) : LawSource {

    override val id: SourceId = SourceId.CASE_LIBRARY
    override val capability = SourceCapability.NATIVE

    companion object {
        const val BASE = "https://rmfyalk.court.gov.cn"
        private const val SEARCH_PATH = "/cpws_al_api/api/cpwsAl/search"
        private const val CONTENT_API = "/cpws_al_api/api/cpwsAl/content"
        private const val USER_INFO_PATH = "/cpws_al_api/api/user/getUserInfo"
        private const val CONTENT_PATH = "/view/content.html"
        private const val LIB_KEY = "cpwsAl_qb"

        /** 官方会话请求头(官网前端同名头) */
        private const val TOKEN_HEADER = "faxin-cpws-al-token"

        /**
         * userToken 的主动续期阈值(12 小时)。
         *
         * 官方未公开 userToken 有效期,这里取一个保守值:太短会频繁多发一次换 token
         * 请求(虽不致命,但徒增网络往返);太长则容易先撞上 401 再被动续期。
         * 真正的兜底是 [postAuthenticated] 里「401 → 续期 → 重试」,阈值只是优化。
         */
        private const val TOKEN_TTL_MILLIS = 12 * 60 * 60 * 1000L

        private val JSON = "application/json;charset=UTF-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 案例类型 → 详情页 lib 参数 */
        fun detailLib(type: String?): String = when (type) {
            "01" -> "zdx"
            "02" -> "ck"
            "04" -> "tssp"
            else -> ""
        }

        /** 官方详情页地址(应用内以官方页面直通展示,正文不落盘) */
        fun detailUrl(id: String, type: String?): String {
            val lib = detailLib(type)
            val q = listOf(
                "id=" + URLEncoder.encode(id, "UTF-8"),
                "lib=" + URLEncoder.encode(lib, "UTF-8"),
            ).joinToString("&")
            return "$BASE$CONTENT_PATH?$q"
        }

        /** 列表页深链:关键词带入官方列表(未登录场景下的兜底入口) */
        fun listUrl(keyword: String): String =
            "$BASE/view/list.html?isAdvSearch=0&lib=$LIB_KEY&key=qw&keyName=%E5%85%A8%E6%96%87" +
                "&value=" + URLEncoder.encode(keyword, "UTF-8")
    }

    /**
     * 官方检索分面(对应官网列表页左侧聚类容器,路径取自 `data-url`)。
     * 分面值即为该分面的服务端过滤参数值(`a` 前缀由官网剥掉后才提交)。
     */
    enum class Facet(val path: String) {
        YEAR("yearNextLeftCluster"),
        COURT("slfyNextLeftCluster"),
        CASE_SORT("sortNextLeftCluster"),
        COURT_LEVEL("fyjbNextLeftCluster"),
        PROCEDURE("slcxNextLeftCluster"),
        KEYWORD("keywordNextLeftCluster"),
    }

    /** 分面项:id = 服务端过滤参数值,name = 展示名,count = 命中数量 */
    data class FacetOption(val id: String, val name: String, val count: Int) {
        internal fun matchesName(other: String): Boolean =
            name == other || name.contains(other) || other.contains(name)
    }

    /** 审理法院分面(名 → id 映射)缓存;仅内存,随会话失效自然重建 */
    private val courtFacet = java.util.concurrent.atomic.AtomicReference<List<FacetOption>?>(null)

    /**
     * token 续期互斥锁。
     *
     * 全局检索会**并发**调用本源(搜索 + 年份分面 + 法院分面)。若不加锁,
     * 一次 401 会同时触发多次 getUserInfo:既是多余请求,也可能互相覆盖 token。
     */
    private val refreshMutex = Mutex()

    override suspend fun search(query: SearchQuery): SourceResult<SearchPage> {
        // 本地没有 token 有两种成因:①从没登录过;②Cookie 还在、token 丢了或已过期。
        // ②完全可以自助修复 —— 先用 Cookie 换新 token,换不到才判定需要登录。
        if (!session.hasCredential && !refreshToken()) return sessionUnavailable()
        return try {
            // 发文机关(审理法院)需要分面 id 才能下推为服务端条件
            if (query.filters.authority != null) ensureCourtFacet(query)
            val raw = postAuthenticated(SEARCH_PATH, buildBody(query))
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            when (root.str("code")) {
                "0" -> parsePage(root, query)
                "401" -> sessionUnavailable()
                else -> SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            }
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    /**
     * 官方判定会话不可用:清凭据、复位「已对接」标记,交由 UI 引导用户重新登录
     * (不再表现为含糊的"获取失败")。
     *
     * ⚠️ 走到这里说明**续期已经试过且失败**(见 [postAuthenticated])——
     * 即会话 Cookie 也被官方拒了,重新登录是唯一出路。
     * 此前本方法在「本地没 token」时同样无条件 `session.clear()`,而全局检索会
     * 并发调用本源(搜索 + 两个分面),一次偶发失败就把长期凭据删掉,
     * 表现为「登录后仍有概率退出」。现在只有"确实存着凭据却已失效"才清。
     */
    private fun sessionUnavailable(): SourceResult.SourceUnavailable {
        val had = session.hasCredential || session.hasCookie()
        if (had) {
            android.util.Log.w(
                "CaseLibrary",
                "会话 Cookie 已失效(续期失败),清除本地凭据并复位「已对接」",
            )
            session.clear()
        }
        onCredentialInvalid()
        return SourceResult.SourceUnavailable(id, FailureReason.NOT_LINKED)
    }

    /**
     * 用会话 Cookie 换一个新的 userToken。
     *
     * **这是「一次登录长期可用」的关键**:官方 `getUserInfo` 只认会话 Cookie
     * (实测登录流程本身就是"拿 Cookie 换 token"),所以 userToken 过期可以自助续期,
     * 完全不必让用户重新登录。只有 Cookie 本身失效才是必须重新登录的边界。
     *
     * @return 是否成功拿到可用 token
     */
    suspend fun refreshToken(): Boolean = refreshMutex.withLock {
        if (!session.hasCookie()) return@withLock false
        try {
            val raw = withContext(Dispatchers.IO) {
                // 此时可能已有旧 token,但续期请求只用 Cookie 即可,带上也无妨
                post(USER_INFO_PATH, "{\"state\":\"\"}")
            }
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return@withLock false
            if (root.str("code") != "0") return@withLock false
            val alUser = (root["data"] as? JsonObject)?.get("alUser") as? JsonObject
            val token = alUser?.str("userToken")
            if (token.isNullOrBlank()) return@withLock false
            session.updateToken(token)
            android.util.Log.i("CaseLibrary", "token 续期成功")
            true
        } catch (e: Throwable) {
            android.util.Log.w("CaseLibrary", "token 续期失败:${e.message}")
            false
        }
    }

    /**
     * 发起一次需鉴权的请求,并在官方回 401 时**自动续期一次再原地重试**。
     *
     * 为什么必须有这层:userToken 会被官方置旧(有效期未公开)。若把 401 直接当作
     * 「登录失效」就清凭据,用户会毫无征兆地被反复要求重新登录 —— 这正是
     * 「登录后仍有概率退出」的成因。现在 401 先续期并重试,只有续期也失败才判定掉登录。
     */
    private suspend fun postAuthenticated(
        path: String,
        body: String,
        refererPath: String = "/view/list.html",
    ): String {
        // token 年龄未知(旧版本落盘的数据)或已超阈值时,先静默续期,减少 401 往返。
        // 续期失败不致命:手里这个旧 token 可能还有效,照常发请求即可。
        if (session.needsTokenRefresh(TOKEN_TTL_MILLIS)) refreshToken()

        var raw = withContext(Dispatchers.IO) { post(path, body, refererPath) }
        if (!isUnauthorized(raw)) return raw

        android.util.Log.i("CaseLibrary", "$path 收到 401,尝试用 Cookie 续期 token 后重试")
        if (!refreshToken()) return raw
        raw = withContext(Dispatchers.IO) { post(path, body, refererPath) }
        return raw
    }

    /**
     * 官方「未登录」信号(**实测为 HTTP 200 + `{"msg":"未登录","code":401}`**),
     * 不是 HTTP 状态码 —— 这里判的是响应体里的业务 code。
     */
    private fun isUnauthorized(raw: String): Boolean = peekCode(raw) == "401"

    /**
     * 登录完成后同步官方会话(需求 2:登录接口对接官网)。
     *
     * 用户在应用内 WebView 走完官网 OAuth 登录后,宿主把 WebView 的 Cookie 交给本方法;
     * 这里用该 Cookie 调用官网同款 `getUserInfo` 接口换取 `userToken`,
     * 之后检索请求按官网约定携带 `faxin-cpws-al-token` 头。
     *
     * 实现上就是 [refreshToken] —— 登录后的首次换 token 与日常续期是同一条路径,
     * 少一处重复逻辑,也避免两条路径行为不一致(旧实现只判 `code=="0"`,漏了续期语义)。
     *
     * @return 是否成功取得可用凭据
     */
    suspend fun syncSession(): Boolean = refreshToken()

    /**
     * 案例正文原生解析(需求 3:与其他数据源一致的原生详情页)。
     *
     * 官方正文接口(官网 content.js 实测):
     * `POST /cpws_al_api/api/cpwsAl/content` body `{"gid":"<案例id>"}`,
     * 响应 `{code:"0", data:{data:{...}}}`;未登录 `{code:"401"}`。
     *
     * 正文由若干固定模块组成(裁判要旨/基本案情/裁判结果/裁判理由/关联索引/案例价值),
     * 这里把每个非空模块映射为一个「章 + 节」、模块内的自然段映射为条目,
     * 与司法解释详情的层级渲染完全一致,手机端可直接阅读。
     */
    override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> {
        // 同 search:没 token 先尝试用 Cookie 续期,别直接判「未登录」
        if (!session.hasCredential && !refreshToken()) return sessionUnavailable()
        return try {
            // 官网以 encodeURIComponent(id) 作为 gid 提交,这里保持一致
            val body = buildJsonObject {
                put("gid", java.net.URLEncoder.encode(ref.id, "UTF-8"))
            }.toString()
            val raw = postAuthenticated(CONTENT_API, body, CONTENT_PATH)
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            when (root.str("code")) {
                "0" -> {
                    val content = ((root["data"] as? JsonObject)?.get("data")) as? JsonObject
                        ?: return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
                    SourceResult.Success(buildDocument(ref, content))
                }
                "401" -> sessionUnavailable()
                else -> SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            }
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    /** 把官方正文响应组装为统一文档模型(模块 → 章/节,自然段 → 条目) */
    private fun buildDocument(ref: LawRef, content: JsonObject): LawDocument {
        val title = content.str("cpws_al_title")?.takeIf { it.isNotBlank() } ?: ref.title
        val caseNo = content.str("cpws_al_no")?.takeIf { it.isNotBlank() } ?: ref.docNumber
        val subTitle = content.str("cpws_al_sub_title").orEmpty()
        val keywords = (content["cpws_al_keyword"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            .orEmpty()

        val preamble = buildList {
            caseNo?.let { add("入库编号：$it") }
            if (subTitle.isNotBlank()) add(subTitle.replace(Regex("^[—-]*"), "——"))
            if (keywords.isNotEmpty()) add("关键词：${keywords.joinToString("；")}")
        }

        // 模块定义与官网 content.js 一致(标题按通用语义命名,字段缺失则整块跳过)
        val modules = listOf(
            "裁判要旨" to content.str("cpws_al_cpyz"),
            "基本案情" to content.str("cpws_al_jbaq"),
            "裁判结果" to content.str("cpws_al_cpjg"),
            "裁判理由" to content.str("cpws_al_cply"),
            "关联索引" to content.str("cpws_al_glsy"),
            "案例价值" to content.str("cpws_al_aljz"),
        )
        val chapters = modules.mapNotNull { (name, rawHtml) ->
            val paragraphs = htmlToParagraphs(rawHtml ?: return@mapNotNull null)
            if (paragraphs.isEmpty()) return@mapNotNull null
            Chapter(
                title = name,
                arabicNumber = null,
                sections = listOf(
                    Section(
                        title = name,
                        articles = paragraphs.map { p ->
                            LawArticle(number = "", arabicNumber = null, paragraphs = listOf(p))
                        },
                    )
                ),
            )
        }

        return LawDocument(
            ref = ref.copy(title = title, docNumber = caseNo),
            fetchedAt = now(),
            preamble = preamble,
            chapters = chapters,
            // 模块全部为空(异常响应)时按纯文本兜底提示,引导用户查看官方原文
            isPlainTextFallback = chapters.isEmpty(),
        )
    }

    override suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint> =
        SourceResult.Success(ref.toFingerprint())

    override fun healthSnapshot() = health.snapshot(id)

    // ---- 内部实现 ----

    /**
     * 组装检索请求体。
     *
     * 分面参数名取自官网列表页分面容器(`data-key`,见 list.html):
     * `year_cpwsAl` = 审判年份、`slfy_id_cpwsAl` = 审理法院(值为分面 key,非名称);
     * 传空串表示该维度不限。
     */
    private fun buildBody(query: SearchQuery): String {
        val keyword = query.keyword.trim()
        val year = query.filters.year?.toString().orEmpty()
        // 发文机关在案例库语境下 = 审理法院,服务端要求分面 id;取不到映射时留空,由本地过滤兜底
        val courtId = query.filters.authority?.let { name ->
            courtFacet.get()?.firstOrNull { it.matchesName(name) }?.id
        }.orEmpty()
        val searchParams = buildJsonObject {
            put("userSearchType", 1)
            put("isAdvSearch", "0")
            put("selectValue", "qw")
            put("lib", LIB_KEY)
            put("sort_field", "")
            put("keyTitle", keyword)
            put("year_cpwsAl", year)
            put("slfy_id_cpwsAl", courtId)
        }
        return buildJsonObject {
            put("page", query.page.coerceAtLeast(1))
            // 官网分页档位固定为 10/20/30/50,不取档位外的值
            put("size", effectivePageSize(query.pageSize))
            put("lib", "qb")
            put("searchParams", searchParams)
        }.toString()
    }

    /** 把请求大小归到官网支持的分页档位(10/20/30/50) */
    private fun effectivePageSize(requested: Int): Int = when {
        requested <= 10 -> 10
        requested <= 20 -> 20
        requested <= 30 -> 30
        else -> 50
    }

    /**
     * 拉取官方检索分面(左侧聚类),用于把筛选维度建立在全库而不是已加载页上。
     * 例:审判年份分面返回库里真实存在的年份及各自数量。
     */
    suspend fun fetchFacetOptions(facet: Facet, query: SearchQuery): List<FacetOption> {
        if (!session.hasCredential && !refreshToken()) return emptyList()
        return try {
            val raw = postAuthenticated("/cpws_al_api/api/cpwsAl/" + facet.path, buildBody(query))
            parseFacet(raw)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** 确保法院分面已缓存(供发文机关筛选下推为服务端参数) */
    private suspend fun ensureCourtFacet(query: SearchQuery) {
        if (courtFacet.get() != null) return
        val list = fetchFacetOptions(Facet.COURT, query.copy(filters = query.filters.copy(authority = null)))
        if (list.isNotEmpty()) courtFacet.set(list)
    }

    /** 携带官方会话凭据的单次请求(Cookie 供换取/复核会话,token 供业务接口鉴权) */
    private fun post(
        path: String,
        body: String,
        refererPath: String = "/view/list.html",
    ): String {
        val builder = Request.Builder()
            .url(BASE + path)
            .post(body.toRequestBody(JSON))
            // Referer 与官网一致:检索接口来自列表页,正文接口来自正文页
            .header("Referer", BASE + refererPath)
            .header("Origin", BASE)
            .header("X-Requested-With", "XMLHttpRequest")
        val cookieValue = session.cookie()
        val tokenValue = session.token()
        cookieValue?.let { builder.header("Cookie", it) }
        tokenValue?.let { builder.header(TOKEN_HEADER, it) }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            // 诊断日志(adb logcat -s CaseLibrary):排查「已登录却仍报未登录」时,
            // 关键看 cookie/token 是否真的挂上了本次请求,以及官方返回的 code/msg
            // (未登录时官方为 HTTP 200 + `{"msg":"未登录","code":401}`)。
            val code = peekCode(text)
            val line = "POST $path -> http=${resp.code} code=$code msg=${peekMsg(text)} " +
                "len=${text.length} cookie=${!cookieValue.isNullOrBlank()} token=${!tokenValue.isNullOrBlank()}"
            if (code == "0") android.util.Log.i("CaseLibrary", line)
            else android.util.Log.w("CaseLibrary", line)
            return text
        }
    }

    /** 从响应体里取出业务 code(仅用于日志,失败时返回 "?") */
    private fun peekCode(text: String): String =
        runCatching {
            ((json.parseToJsonElement(text) as? JsonObject)?.str("code")) ?: "?"
        }.getOrDefault("?")

    /** 从响应体里取出官方提示语(如 "未登录"),仅用于日志 */
    private fun peekMsg(text: String): String =
        runCatching {
            ((json.parseToJsonElement(text) as? JsonObject)?.str("msg")) ?: ""
        }.getOrDefault("")

    private fun parsePage(root: JsonObject, query: SearchQuery): SourceResult<SearchPage> {
        val data = root["data"] as? JsonObject
        val arr = (data?.get("datas") as? JsonArray) ?: JsonArray(emptyList())
        val items = arr.mapNotNull { (it as? JsonObject)?.toLawRef() }
        val total = data?.get("totalCount")?.asIntOrNull() ?: items.size
        val hasMore = items.isNotEmpty() &&
            query.page.coerceAtLeast(1) * effectivePageSize(query.pageSize) < total
        return SourceResult.Success(
            SearchPage(
                items = items,
                nextPage = if (hasMore) query.page + 1 else null,
            )
        )
    }

    /** 解析分面响应:`{code:"0", data:[{key, value, count}]}` */
    private fun parseFacet(raw: String): List<FacetOption> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyList()
        if (root.str("code") != "0") return emptyList()
        val arr = (root["data"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj.str("value") ?: id
            FacetOption(id = id, name = name, count = obj["count"]?.asIntOrNull() ?: 0)
        }
    }

    private fun JsonObject.toLawRef(): LawRef? {
        val rawId = str("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = str("cpws_al_title")?.takeIf { it.isNotBlank() } ?: return null
        val type = str("cpws_al_type")
        val infos = stripHtml(str("cpws_al_infos").orEmpty())
        val attrs = CaseInfoParser.parse(infos, today())
        // cpws_al_status == "02" 为官方标注的失效案例,映射到统一时效性维度
        val status = if (str("cpws_al_status") == "02") LawStatus.REPEALED else LawStatus.CURRENT
        return LawRef(
            id = rawId,
            title = title,
            issuingAuthority = attrs.court ?: CASE_LIBRARY_FALLBACK_AUTHORITY,
            docNumber = attrs.caseNumber,
            publishDate = attrs.judgeDate,
            effectiveDate = null,
            status = status,
            source = SourceId.CASE_LIBRARY,
            url = detailUrl(rawId, type),
        )
    }
}

/** 属性串缺失法院时的兜底来源名(列表项「发文机关」位置不留空) */
private const val CASE_LIBRARY_FALLBACK_AUTHORITY = "人民法院案例库"

private val TAG_RE = Regex("<[^>]+>")

/**
 * 官方正文是 HTML 片段:先把块级标签换成换行,再剥离标签,得到自然段列表。
 * 与官网前端 content.js 的处理保持一致(p/br/6 个 # 均视为换行)。
 */
internal fun htmlToParagraphs(html: String): List<String> {
    if (html.isBlank()) return emptyList()
    return html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("#{6}"), "\n")
        .replace(TAG_RE, "")
        .replace("&nbsp;", "　")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun stripHtml(s: String): String =
    s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(TAG_RE, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * 属性串宽松解析:`cpws_al_infos` 的字段与分隔符随案例类型略有差异,
 * 这里按内容特征识别而不是按位置切分 —— 法院取含「法院/法庭」的片段,
 * 案号取 `(年份)…号` 形态,日期取常见年月日写法,任一项缺失都允许。
 *
 * @param today 今天,用于丢弃"晚于今天"的日期(发布日期不可能在未来)
 */
internal object CaseInfoParser {

    data class Attrs(val court: String?, val caseNumber: String?, val judgeDate: LocalDate?)

    private val CASE_NO = Regex("[（(]\\d{4}[）)][^，,。;；\\s]*?号")
    private val CASE_NO_LOOSE = Regex("[（(][0-9]{4}[）)][0-9A-Za-z\\u4e00-\\u9fa5\\-]+号?")

    /**
     * 日期必须在**完整片段**里成立:前后都不能再接数字或连字符。
     *
     * ⚠️ 旧正则 `(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?` 没有任何边界,会从
     * **入库编号**里截出日期 —— 例如 `2026-12-3-001-005` 被截成「2026-12-3」,
     * 于是详情页显示「2026-12-03 发布」,比当天还晚两个月(实测该案例即如此)。
     * 入库编号只是入库顺序号,**不代表任何时间**,绝不能当日期用。
     *
     * 加上前后否定断言后,`2026-12-3-001-005` 整串不再产生任何日期匹配,
     * 而 `2024-03-15`、`2024年3月15日` 这类独立片段仍能正常命中。
     */
    private val DATE = Regex(
        "(?<![\\d\\-/.年])(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?(?![\\d\\-/.])"
    )

    private val SPLIT = Regex("[|｜·;；,，\\s]{1,}")

    fun parse(infos: String, today: LocalDate): Attrs {
        val parts = infos.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val caseNumber = (CASE_NO.find(infos) ?: CASE_NO_LOOSE.find(infos))?.value
        // 取**第一个合理**日期:串里可能有多处日期形态,晚于今天的一律当噪声丢弃。
        // 一个都取不到时返回 null —— 由界面决定不展示发布时间,而不是编一个出来。
        val judgeDate = DATE.findAll(infos)
            .mapNotNull { m ->
                runCatching {
                    LocalDate.of(
                        m.groupValues[1].toInt(),
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(),
                    )
                }.getOrNull()
            }
            .firstOrNull { !it.isAfter(today) }
        // 法院:优先含「法院」，其次「法庭」，再次「人民检察院」
        val court = parts.firstOrNull { it.contains("法院") }
            ?: parts.firstOrNull { it.contains("法庭") }
            ?: parts.firstOrNull { it.contains("检察院") }
        return Attrs(court = court?.takeIf { it.isNotBlank() }, caseNumber = caseNumber, judgeDate = judgeDate)
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullCompat()

private fun JsonPrimitive.contentOrNullCompat(): String? = content.takeIf { it.isNotBlank() && it != "null" }

private fun JsonElement.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.content?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
