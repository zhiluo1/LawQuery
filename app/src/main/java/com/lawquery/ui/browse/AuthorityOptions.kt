package com.lawquery.ui.browse

import com.lawquery.data.source.FlkAuthorities
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef

/**
 * 分类页「发文机关」筛选的候选与数量语义(纯函数,单测锁定)。
 *
 * ## 数量只在「确实准确」时显示
 *
 * 案例库有**官方分面**(年份 / 审理法院,覆盖全库),数量可信 → 显示。
 * 其他源没有机关分面,数字只能从**已加载的那几十条**里统计 —— 用户实测:
 * 选「最高人民检察院」实际 26 条,面板上却写着 5,而且每翻一页数字还会变。
 * 这个数字是**错的**,显示出来只会误导,所以一律不显示(与「地区」选择器的做法一致)。
 * 返回 `null` 即表示「不显示数量」。
 *
 * ## 候选机关为什么并入官方字典
 *
 * 过去候选完全从已加载条目派生 → 只加载了第一页时,后面页才出现的机关
 * **根本选不到**,用户必须先翻页 —— 这正是反馈里「必须向下翻找」的另一半。
 * flk 有一份完整的制定机关字典([FlkAuthorities]),并入后候选**一开始就完整**,
 * 且字典里的机关排在前面。
 *
 * ⚠️ 并入字典还有个**必要**理由:机关名必须与字典**逐字一致**,才能被
 * [FlkAuthorities.codeOf] 解析、进而把 `zdjgCodeId` 下推给官方(见
 * [com.lawquery.data.source.FlkSource.zdjgCodeOf])。官方返回的机关名有时是全称
 * (「全国人民代表大会常务委员会」)或联合署名(「最高人民法院、最高人民检察院」),
 * 这些解析不出来,只能退回客户端过滤 —— 也就是「筛了等于没筛」。
 */
object AuthorityOptions {

    /**
     * 构造某分类的机关候选项。
     *
     * @param items 当前已加载的条目(用于补充字典没有覆盖到的机关)
     * @param category 当前分类(决定取哪一批官方字典机关)
     * @return 机关名 → 数量;数量恒为 `null`(该源无分面,不显示)
     */
    fun forCategory(items: List<LawRef>, category: LawCategory): List<Pair<String, Int?>> {
        // 已加载条目里出现过的机关,按条数降序、同名升序(与原先 local 统计同口径,
        // 保持「出现得多、更可能是用户想找的」这个既有观感)
        val fromItems = items.map { it.issuingAuthority }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        // 官方字典里该分类可用的机关排在最前:权威、完整,且**保证能被 codeOf 解析下推**
        val fromDict = FlkAuthorities.topLevelFor(category).map { it.name }
        return (fromDict + fromItems).distinct().map { it to null }
    }
}
