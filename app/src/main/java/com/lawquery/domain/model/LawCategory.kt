package com.lawquery.domain.model

/**
 * 法规分类(需求 F2 首页分类入口)。
 * nativeSourceIds:该分类可走原生解析通道的来源(SourceId.name,解耦不直接依赖 data 层);
 * flkType:国家法律法规数据库官方直通的分类参数(深链,M0 结论见《数据源接入说明》)。
 *
 * CASE_LIBRARY 特殊之处:原生解析依赖用户在应用内完成人民法院案例库官方登录,
 * 登录态由 AlkSession 承载;未登录时数据源管理展示「未对接」并引导登录。
 *
 * CONSTITUTION / LAW / ADMIN_REG / SUPERVISION / JUDICIAL / LOCAL 六个法规分类
 * 由国家法律法规数据库(flk)供数:官方免费、无需登录、无付费墙,题录字段完整,
 * 正文在应用内打开官方阅读器完整阅读(见 data.source.FlkSource)。
 */
enum class LawCategory(
    val key: String,
    val nativeSourceIds: List<String>,
    val flkType: String?,
) {
    CONSTITUTION("constitution", listOf("FLK"), "xf"),
    LAW("law", listOf("FLK"), "flfg"),
    ADMIN_REG("admin_reg", listOf("FLK"), "xzfg"),
    SUPERVISION("supervision", listOf("FLK"), "jcfg"),
    /**
     * 司法解释:flk 的司法解释系列(官方全文,含正式文本)+ 最高人民法院官网的
     * 「司法解释」栏目(法院发布的司法文件与解释)。
     *
     * ⚠️ 六个法规分类里,**只有本分类**由两个源共同供数(另一处多源分类是文件类的
     * [STATE_COUNCIL])。两源结果在列表页**按来源分组**展示(见 ui.browse.SourceGrouping):
     * flk 在前(带官方阅读器全文的正式文本),最高法官网在后(栏目列表,按日期倒序)。
     */
    JUDICIAL("judicial", listOf("FLK", "COURT"), "sfjs"),
    LOCAL("local", listOf("FLK"), "dfxfg"),
    /**
     * 国务院及部委文件:中国政府网(国务院文件、部委政策文件)+ 公安部规章库(部门规章)。
     * 两者都是免登录原生通道,缺任一方只影响该类覆盖面。
     */
    STATE_COUNCIL("state_council", listOf("GOV_CN", "MPS_REG"), null),

    /** 人民法院案例库:需官方账号登录后走原生通道(未登录时走登录引导) */
    CASE_LIBRARY("case_library", listOf("CASE_LIBRARY"), null);

    /**
     * ⚠️ 最高人民法院(COURT)接入在 [JUDICIAL] 分类下(**不新增分类入口**)。
     * 它供的是最高法官网「司法解释」栏目发布的解释与司法文件,与 flk 的司法解释
     * 系列是同一法律领域的两个官方来源 —— 前者是法院发布口径,后者是国家法规库
     * 收录的正式文本(带官方阅读器全文)。因此并列在「司法解释」分类里,
     * 由 UI 按来源分组呈现,而不是并入任何其他法规分类。
     * 早期注释写「COURT 保留在国务院及部委文件分类中」与代码不符,已更正;
     * 后续版本曾短暂写成「COURT 不在任何分类下」,本次改动已恢复分类入口。
     */

    /** 是否由国家法律法规数据库供数(六个法规分类) */
    val isFlkBacked: Boolean get() = nativeSourceIds.contains("FLK")

    /**
     * 该分类是否由**多个**原生源共同供数。
     *
     * 目前只有 [JUDICIAL](flk + 最高法)与 [STATE_COUNCIL](政府网 + 规章库)为真。
     * 分类列表据此把结果**按来源分组**展示 —— 多源按日期倒序混排时,用户很难
     * 分辨「这条是哪个官方来源发布的」,分组后每组有明确标题与条数,来源可见性
     * 与「显著标注来源」的既定要求一致(见 ui.browse.SourceGrouping)。
     */
    val isMultiSource: Boolean get() = nativeSourceIds.size > 1

    companion object {
        fun fromKey(key: String): LawCategory? = entries.firstOrNull { it.key == key }
    }
}