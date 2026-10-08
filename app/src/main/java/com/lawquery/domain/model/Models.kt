package com.lawquery.domain.model

import java.time.LocalDate

/**
 * 源标识(需求 3.2 数据源清单)。定义在领域层,数据层以 typealias 复用:
 * 官方源身份是领域概念,数据访问细节不得反向渗入领域模型。
 */
enum class SourceId { GOV_CN, COURT, FLK_WEB, CASE_LIBRARY,

    /**
     * 国家法律法规数据库(flk.npc.gov.cn)原生通道。
     *
     * 与 [FLK_WEB] 的区别:后者只是「官方直通」跳转卡片,不产出列表项;
     * 本源是真检索 —— 题录(标题/制定机关/公布与施行日期/时效性/文号)取自官方
     * `/law-search/search/list`,列表与检索都走它,免费且无需登录。
     */
    FLK,

    /**
     * 公安部规章库(app.mps.gov.cn/gdnps/zc/list.jsp)原生通道。
     *
     * 官方免费、无需登录;检索与详情走同一个 JSONP 接口 `zc/searchIndex.jsp`
     * (详情即同一接口加 `id` 参数,正文字段 `htmlContent` 有值)。
     * 归属「国务院及部委文件」分类,与中国政府网的国务院/部委文件并列。
     */
    MPS_REG }

/**
 * 时效性(需求 11.1 术语表):现行有效 / 已修订 / 已废止 / 尚未生效
 */
enum class LawStatus { CURRENT, REVISED, REPEALED, PENDING }

/**
 * 列表项/元数据(需求 5.3 领域模型)。可入库,即"版本指纹"的载体。
 */
data class LawRef(
    val id: String,
    val title: String,
    val issuingAuthority: String,
    val docNumber: String?,
    val publishDate: LocalDate?,
    val effectiveDate: LocalDate?,
    val status: LawStatus,
    val source: SourceId,
    val url: String,
) {
    /** 全局唯一键:源 + 源内 id */
    val key: String get() = "${source.name}:$id"
}

/**
 * 条文正文文档(仅内存,禁止入库,需求 5.3)。
 */
data class LawDocument(
    val ref: LawRef,
    /** 获取时间,详情页常显(需求 F3/F4) */
    val fetchedAt: java.time.OffsetDateTime,
    val preamble: List<String>,
    /** 章 → 节 → 条 层级(需求 F3) */
    val chapters: List<Chapter>,
    /** 解析降级标记:结构缺失时为纯文本 + 查看原文(项目文档 core/parse) */
    val isPlainTextFallback: Boolean = false,
    /**
     * 正文只下发了可公开部分(官方订购权限限制,实测 2026-10-06)。
     * 此时 [chapters] 里是**已开放那部分条文**,界面须提示「以下为可阅读部分」
     * 并保留到官方页读全文的入口 —— 不能当完整正文展示,也不该整篇判为失败。
     */
    val isPartial: Boolean = false,
    /**
     * 官方在线阅读器地址(签名有时效,**须现取现用,不要缓存到磁盘**)。
     *
     * 国家法律法规数据库(flk)的正文以 OFD/PDF 版式文件下发,官方不下发可抽取的
     * 文本层(实测阅读器 `/reader/text` 的 `areas` 恒为空,页面文字由 SVG 渲染),
     * 因此正文在应用内 WebView 打开该地址阅读 —— 官方授权的阅读形态。
     * 非空时详情页应把正文区域交给官方阅读器,而不是渲染 [chapters]。
     */
    val readerUrl: String? = null,
) {
    val articleCount: Int get() = chapters.sumOf { it.articles.size }
}

data class Chapter(
    val title: String,
    val arabicNumber: Int?,
    val sections: List<Section>,
) {
    val articles: List<LawArticle> get() = sections.flatMap { it.articles }
}

data class Section(
    val title: String,
    val articles: List<LawArticle>,
)

data class LawArticle(
    /** 条号原文,如「第一百零七十七条」 */
    val number: String,
    /** 阿拉伯数字条号,用于条号直达(需求 F3 目录抽屉) */
    val arabicNumber: Int?,
    val paragraphs: List<String>,
) {
    val text: String get() = paragraphs.joinToString("\n")
    val displayNumber: String get() = number.ifBlank { arabicNumber?.toString() ?: "" }
}

/**
 * 版本指纹(需求 6.2):{发文字号, 公布日期, 施行日期, 时效性} 四元组。
 */
data class VersionFingerprint(
    val docNumber: String?,
    val publishDate: LocalDate?,
    val effectiveDate: LocalDate?,
    val status: LawStatus,
) {
    /** 规范化比较:空白字符串与 null 等价,避免误报"已更新" */
    fun normalized(): VersionFingerprint = copy(
        docNumber = docNumber?.takeIf { it.isNotBlank() },
        effectiveDate = effectiveDate
    )
}
