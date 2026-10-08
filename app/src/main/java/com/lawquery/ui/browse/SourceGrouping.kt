package com.lawquery.ui.browse

import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawRef

/**
 * 分类列表的**多源分组**(纯函数,单测锁定)。
 *
 * ## 为什么需要
 *
 * 「国务院及部委文件」由中国政府网 + 公安部规章库两源供数,「司法解释」由
 * 国家法律法规数据库 + 最高人民法院官网两源供数。聚合层把所有结果并成一条
 * 按发布日期倒序的列表 —— 于是 2008 年的部门规章和 2026 年的国务院文件相邻,
 * 用户只能靠每条卡片底部的小徽标去猜「这条到底谁发的」,观感混乱、来源不可见。
 *
 * 分组后:每个来源一个组(标题写明来源名与条数),组内仍按日期倒序。用户先看到
 * 「中国政府网(28 条)」整段,再看到「公安部规章库(50 条)」整段,来源一目了然。
 *
 * ## 约定
 *
 * - **组顺序**由 `order`(即 `LawCategory.nativeSourceIds`)决定,而不是按条数:
 *   声明顺序承载了「谁更权威/先看谁」的产品判断(flk 正式文本在前、最高法栏目在后),
 *   按条数排会让这个判断随数据波动。
 * - **组内保持入参顺序**:调用方传入的已是日期倒序(见
 *   [com.lawquery.data.repo.SearchRepository.appendForBrowse]),这里只做划分,
 *   不再排序 —— 否则相关度检索态(标题命中前置)会被打乱。
 * - `order` 里未声明的来源**追加在末尾**而不是丢弃:配置漏写一个源时,该源的条目
 *   仍会显示(只是没有预期的优先级),避免「源明明有数据却整体消失」这种最难排查的故障。
 * - 入参为空或全部条目的来源都无法识别时返回空列表,由调用方走空态。
 */
object SourceGrouping {

    /** 一个来源分组:来源标识 + 该源在本次结果里的条目(保持入参相对顺序) */
    data class Group(val source: SourceId, val items: List<LawRef>)

    fun group(items: List<LawRef>, order: List<String>): List<Group> {
        if (items.isEmpty()) return emptyList()
        // groupBy 返回 LinkedHashMap:保留「首次出现顺序」,兜底组的相对次序因此稳定
        val bySource = items.groupBy { it.source }
        val groups = ArrayList<Group>(bySource.size)
        val taken = HashSet<SourceId>(bySource.size)

        for (name in order.distinct()) {
            val sid = runCatching { SourceId.valueOf(name) }.getOrNull() ?: continue
            val list = bySource[sid] ?: continue
            if (list.isEmpty() || !taken.add(sid)) continue
            groups += Group(sid, list)
        }
        // 兜底:声明顺序里没有的源(配置漏写/新源未登记)追加在末尾,不丢条目
        for ((sid, list) in bySource) {
            if (list.isNotEmpty() && taken.add(sid)) groups += Group(sid, list)
        }
        return groups
    }
}
