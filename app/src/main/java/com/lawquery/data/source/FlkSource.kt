package com.lawquery.data.source

import com.lawquery.core.net.RateLimitedException
import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.domain.model.Chapter
import com.lawquery.domain.model.LawArticle
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.Section
import com.lawquery.domain.model.SourceId
import com.lawquery.domain.model.VersionFingerprint
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 国家法律法规数据库 flk.npc.gov.cn(源 A1,原生检索通道)。
 *
 * 官方免费、**无需登录、无付费墙**,题录字段完整(标题/制定机关/公布与施行日期/时效性),
 * 覆盖宪法、法律、行政法规、监察法规、地方性法规、司法解释六个位阶 ——
 * 这六类因此全部改由本源供数,不再受商业库的订购权限限制。
 *
 * ## 接口契约(2026-10-06 实测,站点为 Vue SPA,旧 `/api/?type=` 已失效)
 *
 * - **必须带 `Referer: https://flk.npc.gov.cn/search`**,否则返回
 *   `Please enable JavaScript` 的 HTML 壳页而不是 JSON(最高频失败原因)。
 * - 列表 `POST /law-search/search/list`,body 字段名固定、**数组字段要显式传 `[]`**;
 * - 详情 `GET /law-search/search/flfgDetails?bbbs=<32位 hex>`(主键是 `bbbs` 不是 `id`);
 * - 正文签名 `GET /law-search/amazonFile/previewLink?filePath=<ossFile 里的路径>`。
 *
 * ## 两个必须处理的网关细节(实测,勿删)
 *
 * 1. **WZWS 防火墙 307 握手**:首次请求必被 307 重定向到同一路径,并下发
 *    `wzws_cid` Cookie(Max-Age 1800)。带上该 Cookie 重试即 200。
 *    OkHttp 默认会跟随重定向且不把响应的 `Set-Cookie` 用于重放,故这里关闭跟随、
 *    手工重放一次(第一次请求的副作用正是拿到该 Cookie)。
 * 2. **签名不可被 URL 解析器截断**:`previewLink` 返回的阅读器地址里,
 *    `_wr_sign`/`_wr_timestamp` 是**内层 URL 的 query 一部分**。用任何按 `&`
 *    切分的方式取 `file=` 都会丢掉签名,实测会得到空正文。见 [fileParamOf]。
 *
 * ## 正文为什么走官方阅读器而不是抽取文本
 *
 * 官方正文以 OFD/PDF 版式文件下发。阅读器(`数科轻阅读`,flkofd.npc.gov.cn)的
 * `GET /reader/text?file=<签名>&_i=<页码>` 本应返回单页版面数据,但**实测 `areas`
 * 恒为空数组** —— 用 CDP 抓官方阅读器页面在真实浏览器里的网络请求验证过:
 * 浏览器自己发的请求也只拿到 140 字节、`areas: []`。页面文字改由 `/reader/image`
 * 返回的 SVG 渲染,SVG 内只有印章位图、不含文字。这与 `/reader/perm` 的
 * `{"Read":true,"Copy":true,"Export":false,"Printable":false}` 一致:
 * 官方提供「阅读」,不提供可抽取的文本。
 *
 * 因此本源的 `fetchDocument` 返回**题录文档**(元信息 + 阅读器地址),
 * 正文由 [LawDocument.readerUrl] 在应用内 WebView 呈现 —— 官方授权的阅读形态,
 * 不涉及导出与再分发。
 */
class FlkSource(
    private val client: okhttp3.OkHttpClient,
    private val health: SourceHealthTracker,
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
    /** 「今天」,宿主注入便于测试固定日期 */
    private val today: () -> LocalDate = { LocalDate.now() },
) : LawSource {

    override val id: SourceId = SourceId.FLK
    override val capability = SourceCapability.NATIVE

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val jsonMedia = "application/json;charset=utf-8".toMediaType()

    /**
     * WZWS 握手 Cookie(会话级)。
     *
     * 用 `@Volatile` 整体替换即可:并发重复存一次无害(值相同),
     * 这里要的是「拿到值」而不是「只存一次」。
     */
    @Volatile
    private var wafCookie: String? = null

    override suspend fun search(query: SearchQuery): SourceResult<SearchPage> {
        val codes = codeIdsFor(query.category)
        // 分类不在 flk 覆盖范围(国务院及部委文件 / 案例库)时不由本源供数
        if (codes.isEmpty()) return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
        return try {
            val resp = withContext(Dispatchers.IO) { postList(codes, query) }
            if (resp.contains(JS_SHELL)) {
                android.util.Log.w(TAG, "missing Referer -> JS shell page")
                return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            }
            val obj = runCatching { json.parseToJsonElement(resp) as? JsonObject }.getOrNull()
                ?: return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            val rows = obj["rows"] as? JsonArray ?: JsonArray(emptyList())
            val total = (obj["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val items = rows.mapNotNull { (it as? JsonObject)?.toLawRef() }
            android.util.Log.i(
                TAG,
                "list codes=$codes kw=${query.keyword} total=$total parsed=${items.size} page=${query.page}",
            )
            if (items.isEmpty() && total > 0) {
                // 有命中数却一条都解不出:结构变化,保留诊断信息便于快速适配
                android.util.Log.w(TAG, "rows unparsed head=" + resp.take(500))
                return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            }
            SourceResult.Success(
                SearchPage(
                    items = items,
                    // 下一页判定:见 nextPageOf 的说明(纯函数,单测锁定)
                    nextPage = nextPageOf(
                        page = query.page,
                        pageSize = query.pageSize,
                        total = total,
                        itemCount = items.size,
                    ),
                )
            )
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    override suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint> =
        SourceResult.Success(ref.toFingerprint())

    /**
     * 题录文档:元信息 + 官方阅读器地址。
     *
     * 章节骨架只放一个「正文」占位节承载阅读器地址,让详情页的目录/正文 Tab
     * 与其他分类保持同一套结构与交互;真正的条文内容在官方阅读器里呈现。
     */
    override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> {
        return try {
            val resp = withContext(Dispatchers.IO) {
                get("$BASE/law-search/search/flfgDetails?bbbs=${ref.id}")
            }
            if (resp.contains(JS_SHELL)) {
                return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)
            }
            val data = runCatching {
                (json.parseToJsonElement(resp) as? JsonObject)?.get("data") as? JsonObject
            }.getOrNull() ?: return SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)

val title = data.str("title")?.takeIf { it.isNotBlank() } ?: ref.title
        val oss = data["ossFile"] as? JsonObject

        /**
         * 正文文件路径选取 —— **顺序即优先级,不要按「PDF 优先」直觉改回去**。
         *
         * 实测(2026-10-06,同一部《电影管理条例》三种格式对照):
         * | 格式 | `/reader/image` | `/reader/text` | 渲染 |
         * |---|---|---|---|
         * | `ossWordOfdPath` | 67,619 B | 77 KB / 4 areas / 18 行 | ✅ 完整文字 |
         * | `ossPdfOfdPath` | 926 B | 136 B / **0 areas** | ❌ 整页空白 |
         * | `ossPdfPath` | 929 B | 136 B / **0 areas** | ❌ 整页空白 |
         *
         * 根因:老 PDF 是**扫描件**,正文是位图,阅读器渲染不出矢量内容。
         * 官方页面本身不受影响(用户实测「跳转官方源没问题」),说明文件可读,
         * 只是内嵌 WebView 这条路径对扫描件支持不好 —— 故优先选 **OFD**
         * (版式文件,由 Word 转出,始终带完整文字层)。
         *
         * 纯 Word(.docx/.doc)实测阅读器返 550,不作为候选。
         */
        val filePath = pickBodyPath(
            buildMap {
                oss?.str("ossWordOfdPath")?.let { put("ossWordOfdPath", it) }
                oss?.str("ossPdfOfdPath")?.let { put("ossPdfOfdPath", it) }
                oss?.str("ossPdfPath")?.let { put("ossPdfPath", it) }
            }
        )

            // 阅读器签名有时效,必须现取现用(不可缓存到磁盘,也不可跨会话复用)
            val readerUrl = filePath?.let { path ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        val link = get(
                            "$BASE/law-search/amazonFile/previewLink?filePath=" +
                                URLEncoder.encode(path, "UTF-8")
                        )
                        (runCatching { json.parseToJsonElement(link) as? JsonObject }.getOrNull()
                            ?.get("data") as? JsonObject)?.str("url")
                    }
                }.getOrNull()
            }

            android.util.Log.i(
                TAG,
                "detail ${ref.id}: title=$title filePath=${filePath != null} readerUrl=${readerUrl != null}",
            )

            SourceResult.Success(
                LawDocument(
                    ref = ref.copy(
                        title = title,
                        docNumber = ref.docNumber ?: data.str("wh"),
                        // 详情接口的 sxx/sxrq 比列表更权威,按同一规则重算时效性:
                        // 已公布但施行日期未到 → 尚未生效(见 mapStatus)
                        effectiveDate = parseDate(data.str("sxrq")) ?: ref.effectiveDate,
                        status = mapStatus(
                            sxx = (data["sxx"] as? JsonPrimitive)?.content?.toIntOrNull(),
                            effectiveDate = parseDate(data.str("sxrq")) ?: ref.effectiveDate,
                            today = today(),
                        ),
                    ),
                    fetchedAt = now(),
                    preamble = buildList {
                        data.str("flxz")?.takeIf { it.isNotBlank() }?.let { add("效力位阶:" + it) }
                        data.str("zdjgName")?.takeIf { it.isNotBlank() }?.let { add("制定机关:" + it) }
                        val published = data.str("gbrq")
                        if (!published.isNullOrBlank()) add("公布日期:" + published)
                        val effective = data.str("sxrq")
                        if (!effective.isNullOrBlank() && effective != published) {
                            add("施行日期:" + effective)
                        }
                    },
                    chapters = listOf(
                        Chapter(
                            title = "正文",
                            arabicNumber = null,
                            sections = listOf(
                                Section(
                                    title = "",
                                    articles = listOf(
                                        LawArticle(
                                            number = "",
                                            arabicNumber = null,
                                            paragraphs = listOf(
                                                readerUrl
                                                    ?: "官方未提供在线阅读文件,请点「查看原文」在官方页面阅读。"
                                            ),
                                        )
                                    ),
                                )
                            ),
                        )
                    ),
                    // 正文不是抽取来的文本,而是官方阅读器页面:显式标记,
                    // 避免界面把它误称为本地已获取的正文
                    isPlainTextFallback = true,
                    readerUrl = readerUrl,
                )
            )
        } catch (e: Throwable) {
            e.toUnavailable(id)
        }
    }

    override fun healthSnapshot() = health.snapshot(id)

    // ---- HTTP ----

    /**
     * POST 列表检索。
     *
     * body 字段名固定、数组字段必须显式传 `[]`(省略会被服务端当作无效请求),
     * 这两点是官方接口契约,不要「优化」掉。
     *
     * 三个筛选维度的下推(实测均能真实收敛结果):
     * - `zdjgCodeId`:地区/发文机关(地方性法规 = 省级地区,见 [FlkAuthorities]);
     * - `sxx`:时效性(2=已修订 / 3=现行有效);
     * - `gbrqYear`:公布年份。
     *
     * 下推前一律经 [zdjgCodeOf] 校验:无效值宁可不过滤,
     * 也不要把脏值发给官方导致结果被清空。
     */
    private fun postList(codes: List<Int>, query: SearchQuery): String {
        val authorityCode = zdjgCodeOf(query.filters.region, query.filters.authority)
        val body = buildString {
            append("{")
            append("\"searchRange\":1,")
            append("\"sxrq\":[],\"gbrq\":[],")
            // 年份筛选下推为 gbrqYear(官方按公布年份过滤,实测 2024→141 条)
            append("\"gbrqYear\":").append(
                query.filters.year?.let { "[$it]" } ?: "[]"
            ).append(",")
            append("\"searchType\":2,")
            // 时效性筛选下推为 sxx(实测:4=尚未生效、2=已被修改;「现行有效」档
            // 不下发该参数 —— 官方对现行有效用了 1/3 两种码且部分不带 sxx,
            // 下发单一码会漏掉它们,故这一档由客户端过滤,见 sxxCodeOf)
            append("\"sxx\":").append(
                query.filters.status?.let { sxxCodeOf(it) }?.let { "[$it]" } ?: "[]"
            ).append(",")
            append("\"flfgCodeId\":").append(codes.joinToString(",", "[", "]")).append(",")
            // 地区/发文机关下推:仅在表内且非空时下发
            append("\"zdjgCodeId\":").append(
                authorityCode?.let { "[$it]" } ?: "[]"
            ).append(",")
            append("\"searchContent\":").append(quote(query.keyword.trim())).append(",")
            append("\"orderByParam\":{\"order\":\"-1\",\"sort\":\"\"},")
            append("\"pageNum\":").append(query.page.coerceAtLeast(1)).append(",")
            append("\"pageSize\":").append(query.pageSize.coerceIn(1, 50))
            append("}")
        }
        return execute(
            Request.Builder()
                .url("$BASE/law-search/search/list")
                .post(body.toRequestBody(jsonMedia))
                .build()
        )
    }

    /**
     * 领域时效性 → 官方 `sxx` 码,用于「时效性」筛选下推。
     *
     * 与 [mapStatus] 同源,但方向相反,注意映射**不是互逆的**:
     * 官方 `sxx=1` 不代表未生效(见 [mapStatus] 的审计反例),所以「现行有效」
     * 这一档**不能用单一码表达** —— 官方对现行有效用了 1/3 两种值,还有 20 条
     * 「修改、废止的决定」压根不带 `sxx`。
     *
     * 因此:
     * - 尚未生效 → 只下发 `4`(实测 11 条样本全是未到施行日,语义最干净);
     * - 已被修改 → 下发 `2`;
     * - 现行有效 → **不下发 `sxx` 参数**(靠客户端过滤),避免漏掉那些
     *   官方未给 `sxx` 或给了 1 的现行有效文本。
     *
     * @return 需要下发的码;null 表示该档位不下发此参数
     */
    private fun sxxCodeOf(status: LawStatus): Int? = when (status) {
        LawStatus.PENDING -> 4
        LawStatus.REVISED -> 2
        LawStatus.CURRENT, LawStatus.REPEALED -> null
    }

    private fun get(url: String): String =
        execute(Request.Builder().url(url).get().build())

    /**
     * 执行请求并自动完成 WZWS 307 握手。
     *
     * 客户端**不能**跟随重定向:OkHttp 跟随 307 不会把响应头的 `Set-Cookie`
     * 用于重放,结果会永远卡在第一道 307。故关闭跟随、手工重放一次。
     */
    private fun execute(request: Request): String {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var last = ""
        repeat(3) { round ->
            val resp = noRedirect.newCall(withFlkHeaders(request)).execute()
            resp.use {
                val code = it.code
                val body = it.body?.string().orEmpty()
                last = body
                // WZWS Cookie:存下来供后续请求带上
                it.headers.values("Set-Cookie")
                    .firstNotNullOfOrNull { raw -> parseCookie(raw, WAF_COOKIE) }
                    ?.let { wafCookie = it }

                when {
                    // 重定向目标恒为同一路径;重放一次(此时 Cookie 已在手)
                    code == 307 -> if (round == 2) return body
                    code in 200..299 -> return body
                    code == 429 -> throw RateLimitedException("flk rate limited")
                    else -> throw IllegalStateException("flk http $code: ${body.take(200)}")
                }
            }
        }
        return last
    }

    private fun withFlkHeaders(request: Request): Request {
        val b = request.newBuilder()
            // 不带 Referer 会拿到 `Please enable JavaScript` 的 HTML 壳页(最高频失败原因)
            .header("Referer", "$BASE/search")
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", DESKTOP_UA)
        wafCookie?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        return b.build()
    }

    /** 从 `Set-Cookie` 头里取出指定名字的 `名=值`(忽略 Path/Max-Age 等属性) */
    private fun parseCookie(setCookie: String, name: String): String? =
        setCookie.split(';')
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.startsWith("$name=") }

    // ---- JSON 映射 ----

    private fun quote(raw: String): String = buildString {
        append('"')
        raw.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }
            ?.content?.takeIf { it.isNotBlank() }

    /**
     * 列表行 → 题录。
     *
     * `title` 在关键词命中时会被官方塞进 `<em class='highlight'>` 高亮标签,
     * 必须剥掉(实测「行政复议」检索命中的标题就是这种形态)。
     */
    private fun JsonObject.toLawRef(): LawRef? {
        val bbbs = str("bbbs") ?: return null
        val rawTitle = str("title") ?: return null
        val sxx = (this["sxx"] as? JsonPrimitive)?.content?.toIntOrNull()
        val effective = parseDate(str("sxrq"))
        return LawRef(
            id = bbbs,
            title = stripHighlight(rawTitle),
            issuingAuthority = str("zdjgName") ?: SOURCE_NAME,
            // flk 列表不下发文号(详情接口也没有 wh 字段实测),列表项先留空
            docNumber = null,
            publishDate = parseDate(str("gbrq")),
            effectiveDate = effective,
            status = mapStatus(sxx, effective, today()),
            source = SourceId.FLK,
            url = detailPageUrl(bbbs, stripHighlight(rawTitle)),
        )
    }

    private fun stripHighlight(raw: String): String = raw.replace(HIGHLIGHT_TAG, "").trim()

    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val m = DATE_PATTERN.find(raw) ?: return null
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }

    /**
     * 官方时效性码 `sxx` + 施行日期 → 领域枚举。
     *
     * ## `sxx` 的真实语义(2026-10-06 全库抽样审计:六分类 × 2 页 = 170 条)
     *
     * | sxx | 样本 | 施行日期分布 | 含义 |
     * |---|---|---|---|
     * | 3 | 138 | **全部已施行** | 现行有效 |
     * | 4 | 11 | **全部未到施行日** | 尚未生效 |
     * | 2 | 1 | 已施行 | 已被修改(同法规另有历史文本) |
     * | null | 20 | 18 已施行 / 1 未到 / 1 空 | 多为「修改、废止的决定」,无标记 |
     *
     * ⚠️ **不要把 `sxx=1` 当成「尚未生效」** —— 早期版本这么映射,导致大量已施行法规
     * 被误标。实测反例:契税暂行条例(`sxrq=2019-03-02`)、最高人民法院关于审理掩饰隐瞒
     * 犯罪所得的解释(`sxrq=2021-04-15`)都是 `sxx=1`,但施行日期早已过去,属现行有效。
     * 官方并未给出 `sxx=1` 的可解释语义,故这里**不依据它判生效状态**。
     *
     * 判定顺序:
     * 1. 施行日期晚于今天 → [LawStatus.PENDING]尚未生效(已公布未施行);
     * 2. `sxx=2` → [LawStatus.REVISED]已被修改(该文本是历史版本);
     * 3. 其余(含 `sxx=1`)→ [LawStatus.CURRENT]现行有效。
     *
     * @param effectiveDate 官方 `sxrq`,可能为 null(实测老法规常见)
     * @param today 由宿主注入,便于测试固定「今天」
     */
    private fun mapStatus(sxx: Int?, effectiveDate: LocalDate?, today: LocalDate): LawStatus =
        statusOf(sxx, effectiveDate, today)

    companion object {
        const val BASE = "https://flk.npc.gov.cn"

        /** 阅读器域(与站点主域分开,需单独加入 URL 白名单) */
        const val READER_BASE = "https://flkofd.npc.gov.cn"

        private const val TAG = "Flk"

        /** 缺少 Referer 时官方返回的 HTML 壳页特征 */
        private const val JS_SHELL = "Please enable JavaScript"

        /** WZWS 防火墙 Cookie 名 */
        private const val WAF_COOKIE = "wzws_cid"

        private const val SOURCE_NAME = "国家法律法规数据库"

        /** 桌面 UA:官方接口对移动 UA 支持不稳定,统一用桌面形态 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val HIGHLIGHT_TAG = Regex("<[^>]*>")
        private val DATE_PATTERN = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")

        /**
         * 下一页判定(纯函数,便于单测锁定)。
         *
         * 官方按 `pageNum` 分页,判据必须是**服务端总数 vs 已消费的页窗口**。
         *
         * 这里替换掉的旧写法是 `itemCount >= pageSize && total > itemCount`,两处都错:
         * 1. `total > itemCount` 比的是「总数 vs 本页条数」—— 总条数恰好是每页容量的
         *    整数倍时,末页仍会给出下一页;再请求就拿到 0 条,而「有 total 却一条都
         *    解不出」会被判为 PARSE_FAILED,于是用户滑到底时莫名弹出
         *    「国家法律法规数据库 来源暂不可用」;
         * 2. `itemCount >= pageSize` 依赖本页**解析成功**的条数 —— 只有几行字段异常被
         *    `mapNotNull` 丢掉,本页条数就小于容量,于是提前停止翻页,长尾结果拿不到
         *    (表现为「这个分类明明有几百条,只能翻两三页」)。
         *
         * 现在以「已消费条数 = page × pageSize」为准,与 `itemCount` 无关。
         */
        fun nextPageOf(page: Int, pageSize: Int, total: Int, itemCount: Int): Int? {
            if (itemCount <= 0) return null
            val p = page.coerceAtLeast(1)
            val size = pageSize.coerceIn(1, 50)
            return if (total > p * size) p + 1 else null
        }

        /**
         * 「制定机关」筛选 → 官方 `zdjgCodeId` 参数值(纯函数,单测锁定)。
         *
         * flk 只有**一个**制定机关参数,而界面上有两个维度落在它上面:
         * - 地方性法规的「地区」([com.lawquery.data.source.SearchFilters.region],省级代码,如 广东 350);
         * - 其余分类的「发文机关」([com.lawquery.data.source.SearchFilters.authority],机关名,如 最高人民检察院)。
         * 地方性法规**只**开放地区维度、其他分类**只**开放机关维度,两者不会同时有值;
         * 万一都有,以地区为准(它是地方性法规的主维度)。
         *
         * ⚠️ 2026-10-08 修:此前这里**只**取了 `region`,发文机关从未下推 ——
         * 于是「按发文机关筛选」实际只是在**已加载的那几十条里**做客户端过滤
         * (见 `BrowseViewModel.visibleItems`),全库中该机关的其他条目要一路翻页
         * 才陆续出现,用户感知即「选了机关还得不停往下翻,数量才对得上」。
         *
         * 机关名必须能在 [FlkAuthorities.ALL] 里查到才下推 —— 官方返回的机关名有时是
         * 全称(「全国人民代表大会常务委员会」)或联合署名(「最高人民法院、最高人民检察院」),
         * 这些查不到就不下发,退回客户端过滤:宁可不过滤,也不发脏值把结果清空。
         */
        fun zdjgCodeOf(region: Int?, authority: String?): Int? =
            FlkAuthorities.resolve(region) ?: FlkAuthorities.codeOf(authority)

        /**
         * 分类 → flk 法规分类**叶子** codeId 列表。
         *
         * ⚠️ 官方 `enumData` 的分类字典是树状的,**父节点 codeId 直接下推一律返回 0 条**
         * (实测:宪法 100 有 7 条,而「法律 101」「行政法规 201」「地方法规 221」
         * 「司法解释 311」四个父节点全部 total=0),数据只落在叶子节点上。
         * 这里的取值即各父节点的 `codeIdList` 展开结果(2026-10-06 实测)。
         *
         * 各分类实测命中量:宪法 7 / 法律 734 / 行政法规 851 / 监察法规 3 /
         * 地方性法规 28419 / 司法解释 881。
         */
        fun codeIdsFor(category: LawCategory?): List<Int> = when (category) {
            LawCategory.CONSTITUTION -> listOf(100)
            LawCategory.LAW -> listOf(102, 110, 120, 130, 140, 150, 155, 160, 170, 180, 190, 195, 200)
            LawCategory.ADMIN_REG -> listOf(210, 215)
            LawCategory.SUPERVISION -> listOf(220)
            LawCategory.JUDICIAL -> listOf(320, 330, 340, 350)
            LawCategory.LOCAL -> listOf(230, 260, 270, 290, 295, 300, 305, 310)
            // 全库:六个位阶的叶子并集(全局检索,分类为 null 时用)
            else -> listOf(
                100, 102, 110, 120, 130, 140, 150, 155, 160, 170, 180, 190, 195, 200,
                210, 215, 220, 230, 260, 270, 290, 295, 300, 305, 310, 320, 330, 340, 350,
            )
        }

        /**
         * 官方详情页深链(**浏览器地址**,分享/收藏/「查看原文」用)。
         *
         * ⚠️ 路由是 `/detail`,**不是** `/flfgDetails` —— 后者是接口路径
         * (`/law-search/search/flfgDetails`),当作页面打开只会得到 SPA 空壳。
         * 深链格式取自官方 SPA 路由定义(2026-10-06 实测):
         * `{path:"/detail", query:{id, fileId, type, title}}`
         * 即法规主键传在 **`id`** 上,`title` 用于浏览器标签标题,两者都要带。
         */
        fun detailPageUrl(bbbs: String, title: String? = null): String {
            val base = "$BASE/detail?id=" + URLEncoder.encode(bbbs, "UTF-8")
            return if (title.isNullOrBlank()) base
            else base + "&title=" + URLEncoder.encode(title, "UTF-8")
        }

        /**
         * 时效性判定(供测试与 [mapStatus] 共用,语义见该方法注释)。
         *
         * 抽成 companion 的目的是让规则可被单测锁定 —— 这条规则曾因误读官方
         * `sxx` 语义而错两次(先把 3 当未生效、后又把 1 当未生效)。
         *
         * @param sxx 官方时效性码(可空)
         * @param effectiveDate 官方 `sxrq` 施行日期(可空)
         * @param today 今天
         */
        fun statusOf(sxx: Int?, effectiveDate: LocalDate?, today: LocalDate): LawStatus {
            // 已公布但尚未到施行日 → 尚未生效(优先于 sxx 判断)
            if (effectiveDate != null && effectiveDate.isAfter(today)) return LawStatus.PENDING
            // sxx=2:同一法规存在更新文本,当前这条是历史版本
            if (sxx == 2) return LawStatus.REVISED
            // sxx=1 / 3 / null 一律按现行有效 —— 见 mapStatus 的审计反例
            return LawStatus.CURRENT
        }

        /**
         * 正文格式选取:**OFD 优先**(实测理由见方法内注释)。
         *
         * 纯 Word(.docx/.doc)实测阅读器返 550,不作为候选。
         *
         * @param oss 官方 `ossFile` 的键值对(只含可用格式)
         * @return 正文文件路径;无可用格式时为 null
         */
        fun pickBodyPath(oss: Map<String, String?>): String? =
            oss["ossWordOfdPath"]?.takeIf { it.isNotBlank() }
                ?: oss["ossPdfOfdPath"]?.takeIf { it.isNotBlank() }
                ?: oss["ossPdfPath"]?.takeIf { it.isNotBlank() }

        /**
         * 从 `previewLink` 返回的阅读器地址里取出 `file=` 的**原始全串**(含签名)。
         *
         * 不能用 `HttpUrl.queryParameter("file")`:签名 `_wr_sign` 与 `_wr_timestamp`
         * 跟在 `file=` 值之后、以 `&` 分隔,按参数名解析会把它们当成独立查询参数丢掉,
         * 实测这样拿到的 `file` 是**无签名**的,阅读器只会返回空版面数据。
         */
        fun fileParamOf(readerUrl: String): String {
            val idx = readerUrl.indexOf("file=")
            return if (idx < 0) "" else readerUrl.substring(idx + "file=".length)
        }
    }
}