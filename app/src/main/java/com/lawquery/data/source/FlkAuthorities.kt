package com.lawquery.data.source

/**
 * 国家法律法规数据库(flk)的**制定机关**代码表。
 *
 * 取值来自官方 `/law-search/search/enumData` 的 `zdjgfl` 字典(2026-10-06 实测),
 * 用于把「地区」「发文机关」两个筛选维度下推为接口参数 `zdjgCodeId`。
 *
 * ## 为什么地方性法规用「制定机关」表达地区
 *
 * 地方性法规由**各省、市人大常委会**制定,一个地区对应一组机关,flk 以
 * `165 地方人大及其常委会` 为父节点,下挂 31 个省级地区(北京 170 … 新疆 470)。
 * 实测下推有效:不筛 28419 条,广东 1812、江苏 1511、北京 329、新疆 719。
 *
 * 顶层机关同样可筛:行政法规 `120 国务院`、监察法规 `130 国家监察委员会`、
 * 司法解释 `140 最高人民法院` / `150 最高人民检察院`(实测均能收敛结果)。
 */
object FlkAuthorities {

    data class Authority(
        val code: Int,
        val name: String,
        /** 分组标题(地理大区 / 机关类别),仅用于界面分组 */
        val group: String,
        /** 是否省级地区(地方性法规的地区筛选用这批) */
        val isProvince: Boolean = false,
    )

    // ---- 顶层机关(按官方字典的层级语义分组) ----

    private const val G_NATIONAL = "全国"
    private const val G_LOCAL = "地方"
    private const val G_JUDICIAL = "审判检察机关"

    /**
     * 31 个省级地区(flk `165 地方人大及其常委会` 的叶子)。
     *
     * 顺序按地理分组(华北→东北→华东→华中→华南→西南→西北)便于界面展开阅读,
     * 代码值严格取自官方字典(北京 170 … 新疆 470),不可自行编排。
     * 名称用通行简称,与用户心智一致;官方返回的 `zdjgName` 是全称
     * (如「广东省人民代表大会常务委员会」),不参与筛选值的构造。
     *
     * 声明在 [ALL] 之前:`ALL` 由它拼装,顺序反了会初始化失败。
     */
    val PROVINCES: List<Authority> = listOf(
        Authority(170, "北京", "华北", isProvince = true),
        Authority(180, "天津", "华北", isProvince = true),
        Authority(190, "河北", "华北", isProvince = true),
        Authority(200, "山西", "华北", isProvince = true),
        Authority(210, "内蒙古", "华北", isProvince = true),
        Authority(220, "辽宁", "东北", isProvince = true),
        Authority(230, "吉林", "东北", isProvince = true),
        Authority(240, "黑龙江", "东北", isProvince = true),
        Authority(250, "上海", "华东", isProvince = true),
        Authority(260, "江苏", "华东", isProvince = true),
        Authority(270, "浙江", "华东", isProvince = true),
        Authority(280, "安徽", "华东", isProvince = true),
        Authority(290, "福建", "华东", isProvince = true),
        Authority(300, "江西", "华东", isProvince = true),
        Authority(310, "山东", "华东", isProvince = true),
        Authority(320, "河南", "华中", isProvince = true),
        Authority(330, "湖北", "华中", isProvince = true),
        Authority(340, "湖南", "华中", isProvince = true),
        Authority(350, "广东", "华南", isProvince = true),
        Authority(360, "广西", "华南", isProvince = true),
        Authority(370, "海南", "华南", isProvince = true),
        Authority(380, "重庆", "西南", isProvince = true),
        Authority(390, "四川", "西南", isProvince = true),
        Authority(400, "贵州", "西南", isProvince = true),
        Authority(410, "云南", "西南", isProvince = true),
        Authority(420, "西藏", "西南", isProvince = true),
        Authority(430, "陕西", "西北", isProvince = true),
        Authority(440, "甘肃", "西北", isProvince = true),
        Authority(450, "青海", "西北", isProvince = true),
        Authority(460, "宁夏", "西北", isProvince = true),
        Authority(470, "新疆", "西北", isProvince = true),
    )

    /** 全部可筛制定机关(顶层机关 + 31 个省级地区) */
    val ALL: List<Authority> = listOf(
        // 全国层面
        Authority(90, "全国人大及其常委会", G_NATIONAL),
        Authority(100, "全国人大", G_NATIONAL),
        Authority(110, "全国人大常委会", G_NATIONAL),
        Authority(120, "国务院", G_NATIONAL),
        Authority(130, "国家监察委员会", G_NATIONAL),
        Authority(140, "最高人民法院", G_JUDICIAL),
        Authority(150, "最高人民检察院", G_JUDICIAL),
    ) + PROVINCES

    private val byCode: Map<Int, Authority> = ALL.associateBy { it.code }

    /** 代码 → 名称;未知代码返回 null(便于外部构造的脏值不误显示) */
    fun nameOf(code: Int?): String? = code?.let { byCode[it]?.name }

    /** 名称 → 代码(筛选值回显用) */
    fun codeOf(name: String?): Int? = name?.let { n -> ALL.firstOrNull { it.name == n }?.code }

    /** 各分类可用的顶层机关(地区维度只对地方性法规开放) */
    fun topLevelFor(category: com.lawquery.domain.model.LawCategory): List<Authority> =
        when (category) {
            com.lawquery.domain.model.LawCategory.ADMIN_REG -> ALL.filter { it.code == 120 }
            com.lawquery.domain.model.LawCategory.SUPERVISION -> ALL.filter { it.code == 130 }
            com.lawquery.domain.model.LawCategory.JUDICIAL ->
                ALL.filter { it.code == 140 || it.code == 150 }
            com.lawquery.domain.model.LawCategory.CONSTITUTION ->
                ALL.filter { it.group == G_NATIONAL }
            else -> emptyList()
        }

    /**
     * 校验外部传入的筛选值是否在表内。
     *
     * 三个约束任一不满足就不下推该参数:宁可不过滤,也不要把无效值发给官方导致
     * 结果被清空(宁可不过滤,也不把无效值发给官方)。
     */
    fun resolve(code: Int?): Int? = code?.takeIf { byCode.containsKey(it) }
}