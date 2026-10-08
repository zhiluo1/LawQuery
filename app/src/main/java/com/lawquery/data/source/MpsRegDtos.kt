package com.lawquery.data.source

import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

/** 规章库检索/列表页:HttpRequest 的 Referer,也是「查看原文」入口 */
const val MPS_REG_LIST_URL = "https://app.mps.gov.cn/gdnps/zc/list.jsp"

/** 规章正文页(WebView 直通查看) */
fun mpsRegContentUrl(id: String): String =
    "https://app.mps.gov.cn/gdnps/zc/content.jsp?id=${id.trim()}"

/**
 * 公安部规章库 JSONP 接口响应(2026-10-07 实测)。
 *
 * 真实报文形如 `cb({"class":"...","curPage":1,"resultMap":[...],"totalContentNum":63,
 * "totalPageNum":21});`,是**带回调壳的 JSON**,不是裸 JSON。
 *
 * 条目字段(实测样本:
 * `id=10576548`《公安机关网络空间安全监督检查办法》、 `id=10551425`、
 * `id=10319709`《关于修改<警车管理规定>的决定》):
 * - `id` 站内主键,也是详情页 `content.jsp?id=` 的参数
 * - `title` / `mc` 标题(两者通常一致,互为兜底)
 * - `wh` 文号,如「公安部令第176号」
 * - `zlrq` / `scrq` 发布日期(yyyy-MM-dd);`publishTime` 为 14 位时间戳,作兜底
 * - `fbt` 副标题,形如「2026年8月7日公安部令第176号发布 自2026年10月1日起施行」
 * - `fbjg` 发布机构(二级,如「交通管理局」;「其他」表示无更细机构)
 * - `yxx`「是/否」是否现行有效
 * - `wzb` / `tpb` 文字版(docx)与图片版(pdf)附件文件名
 * - `htmlContent` 正文 HTML —— **列表接口返回空串,需按 id 再请求一次才有值**
 */
@Serializable
data class MpsRegResponse(
    val curPage: Int? = null,
    val resultMap: List<MpsRegItem> = emptyList(),
    val totalContentNum: Int? = null,
    val totalPageNum: Int? = null,
)

@Serializable
data class MpsRegItem(
    val id: String? = null,
    val title: String? = null,
    val mc: String? = null,
    val wh: String? = null,
    val syh: String? = null,
    val zlrq: String? = null,
    val scrq: String? = null,
    val publishTime: String? = null,
    val fbt: String? = null,
    val fbjg: String? = null,
    val yxx: String? = null,
    val wzb: String? = null,
    val tpb: String? = null,
    val htmlContent: String? = null,
    val ztfl: String? = null,
    val tcfl: String? = null,
    val subjectName: String? = null,
    val ownSubjectDn: String? = null,
)

/**
 * JSONP 外壳处理。
 *
 * 接口固定以 `callback` 参数包一层 JS 函数名(`cb({...});`)。
 * 同时需要识别「看着像 200 其实是拦截页」的情况:网关被风控时会在 200 状态下
 * 返回一段 HTML([MpsRegJson.parse] 会抛 [com.lawquery.core.net.RateLimitedException]),
 * 这样才能让上层走进冷却而不是当成解析失败反复重试。
 */
object MpsRegJson {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** 回调壳:`cb(` / `jQuery123_456(` 等 */
    private val CALLBACK = Regex("^[A-Za-z_$][A-Za-z0-9_$.]*\\(")

    /** 去掉 JSONP 外壳;本身已是裸 JSON 则原样返回 */
    fun unwrap(raw: String): String {
        val s = raw.trim()
        val open = CALLBACK.find(s) ?: return s
        var body = s.substring(open.value.length).trim()
        if (body.endsWith(");")) body = body.dropLast(2)
        else if (body.endsWith(")")) body = body.dropLast(1)
        return body.trim()
    }

    @Throws(com.lawquery.core.net.RateLimitedException::class)
    fun parse(raw: String): MpsRegResponse {
        val body = unwrap(raw)
        // 网关风控页 / 站点维护页会以 HTML 伪装成 200 返回,必须显式挡掉
        if (!body.startsWith("{")) {
            throw com.lawquery.core.net.RateLimitedException("规章库返回非 JSON 响应(疑似网关拦截)")
        }
        return json.decodeFromString(body)
    }
}

/** DTO → LawRef 映射(纯函数) */
fun MpsRegItem.toLawRef(): LawRef? {
    val rawId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val rawTitle = (title?.trim()?.takeIf { it.isNotEmpty() }
        ?: mc?.trim()?.takeIf { it.isNotEmpty() }) ?: return null
    return LawRef(
        id = rawId,
        title = rawTitle,
        issuingAuthority = issuingAuthority(),
        docNumber = wh?.trim()?.takeIf { it.isNotEmpty() },
        publishDate = publishDate(),
        effectiveDate = MpsRegDates.effectiveFrom(fbt),
        // 规章库 pulling 的是「现行有效」子库,yxx 字段是官方给的唯一时效信号
        status = if (yxx?.trim() == "否") LawStatus.REPEALED else LawStatus.CURRENT,
        source = SourceId.MPS_REG,
        url = mpsRegContentUrl(rawId),
    )
}

/**
 * 发布机关:详情页官方写法是「公安部 + fbjg」,但 `fbjg` 为「其他」时拼出来是
 * 「公安部其他」这种怪话,此时只保留「公安部」。
 */
private fun MpsRegItem.issuingAuthority(): String {
    val branch = fbjg?.trim().orEmpty()
    return if (branch.isEmpty() || branch == "其他") "公安部" else "公安部$branch"
}

private fun MpsRegItem.publishDate(): LocalDate? =
    MpsRegDates.iso(zlrq) ?: MpsRegDates.iso(scrq) ?: MpsRegDates.timestamp(publishTime)

/**
 * 正文 HTML → 文本行列表(供 [com.lawquery.core.parse.StructureBuilder] 建层级)。
 *
 * 官方详情页把 `htmlContent` 里的 `<br>` 当作换行、`<p>`/`div` 当作段落,
 * 此处按此还原:`<br>` 转为换行后再按行切块,空行丢弃。
 * 正文可能整个没有子元素(纯文本),故兜底按整段处理。
 */
object MpsRegContent {

    fun toBlocks(html: String): List<String> {
        val fragment = Jsoup.parseBodyFragment(html)
        fragment.select("br").before("\n")
        fragment.select("br").remove()

        val blocks = mutableListOf<String>()
        for (element in fragment.body().children()) {
            collect(element.wholeText(), blocks)
        }
        if (blocks.isEmpty()) {
            collect(fragment.body().wholeText(), blocks)
        }
        return blocks
    }

    private fun collect(text: String, sink: MutableList<String>) {
        for (line in text.split('\n')) {
            val cleaned = line.replace('\u00a0', ' ').trim()
            if (cleaned.isNotEmpty()) sink += cleaned
        }
    }
}

/** 规章库日期解析(字段形态见 [MpsRegItem]) */
object MpsRegDates {

    private val ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val CN = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日")
    private val TS = Regex("(\\d{4})(\\d{2})(\\d{2})")

    /** 「2026-08-07」 */
    fun iso(raw: String?): LocalDate? = from(ISO, raw)

    /** 14 位时间戳「20260807180000」取日期部分 */
    fun timestamp(raw: String?): LocalDate? = from(TS, raw?.trim())

    /**
     * 从副标题取施行日期:「...发布 自2026年10月1日起施行」→ 2026-10-01。
     *
     * 只认紧跟「自」之后的那一个中文日期。规章公布后隔一段时间才施行是常态,
     * 所以施行日期**晚于今天是正确的**,这里不做丢弃。
     */
    fun effectiveFrom(subtitle: String?): LocalDate? {
        val raw = subtitle?.trim() ?: return null
        val idx = raw.indexOf('自')
        if (idx < 0) return null
        return from(CN, raw.substring(idx))
    }

    private fun from(re: Regex, raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val m = re.find(raw) ?: return null
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }
}
