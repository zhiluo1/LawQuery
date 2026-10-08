package com.lawquery.data.source

import com.lawquery.domain.model.LocalDates
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 国务院政策文件库公开 JSON 检索接口 DTO(M0 实测,详见《数据源接入说明》)。
 * 接口:https://sousuo.www.gov.cn/search-gov/data?t={t}&q={q}&p={page}&n={n}&sort={sort}
 *
 * M0 实测响应结构:顶层为 {code, msg, data, searchVO};**条目列表位于 searchVO.listVO**
 * (catMap.*.listVO 为分组副本,不使用)。
 */
@Serializable
data class GovCnSearchResponse(
    val code: Int? = null,
    @SerialName("searchVO") val searchVo: GovCnSearchVo? = null,
    /** 防御性兜底:接口若把列表提到顶层,仍可解析 */
    @SerialName("listVO") val listVo: List<GovCnItem> = emptyList(),
)

@Serializable
data class GovCnSearchVo(
    val totalCount: Int? = null,
    val totalpage: Int? = null,
    val pageSize: Int? = null,
    /** 条目列表(M0 实测位于 searchVO 内) */
    @SerialName("listVO") val listVo: List<GovCnItem> = emptyList(),
)

@Serializable
data class GovCnItem(
    val id: String? = null,
    val title: String? = null,
    /** 发文字号,如「国令第843号」 */
    val pcode: String? = null,
    /** 发布时间文本,如「2026.08.13」 */
    @SerialName("pubtimeStr") val pubtimeStr: String? = null,
    /** 发布时间毫秒时间戳 */
    val ptime: Long? = null,
    val url: String? = null,
    val summary: String? = null,
    /** 发文单位(部门文件时有值) */
    val fwdw: String? = null,
    /** 文件类型(如「国令」「国发」) */
    val childtype: String? = null,
    val fwzh: String? = null,
)

/** DTO → LawRef 映射(纯函数) */
fun GovCnItem.toLawRef(): LawRef? {
    val rawTitle = title?.let { org.jsoup.Jsoup.parse(it).text() } ?: return null
    val itemUrl = url ?: return null
    val docId = id ?: itemUrl.substringAfterLast('/').substringBefore('.')
    return LawRef(
        id = docId,
        title = rawTitle,
        issuingAuthority = fwdw.orEmpty().ifBlank { "国务院" },
        docNumber = pcode?.takeIf { it.isNotBlank() } ?: fwzh?.takeIf { it.isNotBlank() },
        // pubtimeStr 为接口展示的「发布日期」;ptime 实测为成文日期时间戳,作兜底
        publishDate = pubtimeStr?.let { GovCnDateParser.parse(it) }
            ?: ptime?.let { LocalDates.fromEpochMillis(it) },
        effectiveDate = null,
        status = LawStatus.CURRENT, // 政策库接口不提供时效性字段,默认现行有效(见《数据源接入说明》)
        source = SourceId.GOV_CN,
        url = itemUrl,
    )
}

/** 「2026.08.13」「2026-08-13」「2026年8月13日」→ LocalDate */
object GovCnDateParser {
    fun parse(raw: String): java.time.LocalDate? {
        val std = Regex("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})").find(raw) ?: return null
        return runCatching {
            java.time.LocalDate.of(
                std.groupValues[1].toInt(),
                std.groupValues[2].toInt(),
                std.groupValues[3].toInt(),
            )
        }.getOrNull()
    }
}
