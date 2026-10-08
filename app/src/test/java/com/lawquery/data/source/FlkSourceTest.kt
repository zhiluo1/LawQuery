package com.lawquery.data.source

import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * flk(国家法律法规数据库)接入契约测试。
 *
 * 锁定的都是**实测事实**,每一条都对应一个「不知道就会写错」的地方:
 * 1. 分类 codeId 必须是**叶子** —— 父节点下推一律0 条(实测四个父节点全部total=0);
 * 2. 列表标题在关键词命中时带 `<em class='highlight'>`,必须剥掉;
 * 3. 阅读器 `file=` 参数含签名,**不能**按 `&` 切分截取(丢了签名正文为空);
 * 4. 时效性 `sxx` 的 2 与 3 含义不同(已修订 vs 现行有效)。
 */
class FlkSourceTest {

    @Test
    fun `分类 codeId 全部为叶子且互不重叠`() {
        val cats = listOf(
            LawCategory.CONSTITUTION to 100,
            LawCategory.ADMIN_REG to 210,
            LawCategory.SUPERVISION to 220,
            LawCategory.JUDICIAL to 320,
            LawCategory.LOCAL to 230,
        )
        cats.forEach { (cat, leaf) ->
            val codes = FlkSource.codeIdsFor(cat)
            assertTrue("$cat 应含叶子 $leaf", codes.contains(leaf))
            // 叶子节点自身的 codeId 就是分类主码:不能出现父节点(实测父节点恒 0 条)
            assertFalse("$cat 不应包含父节点", codes.contains(cat.flfgCodeParentCode()))
        }
    }

    @Test
    fun `监察法规 codeId 为 220`() {
        // 记忆里此项长期标「未确认」,2026-10-06 实测确认:监察法规 = 220,全库 3 条
        assertEquals(listOf(220), FlkSource.codeIdsFor(LawCategory.SUPERVISION))
    }

    @Test
    fun `全局检索聚合六个位阶的叶子`() {
        val all = FlkSource.codeIdsFor(null)
        // 六个分类的每个叶子都必须在全库里
        listOf(
            LawCategory.CONSTITUTION, LawCategory.LAW, LawCategory.ADMIN_REG,
            LawCategory.SUPERVISION, LawCategory.JUDICIAL, LawCategory.LOCAL,
        ).forEach { cat ->
            FlkSource.codeIdsFor(cat).forEach { leaf ->
                assertTrue("全库应含 $cat 的叶子 $leaf", all.contains(leaf))
            }
        }
        assertEquals("全库叶子不应重复", all.size, all.distinct().size)
    }

    @Test
    fun `file 参数保留签名不被截断`() {
        // 阅读器地址样本(内层 URL 的 query 里带签名)。
        // ⚠️ 原样本取自真实响应,其中的内网 IP 已脱敏为文档示例地址(192.0.2.0/24),
        // 开源时不暴露第三方基础设施信息;签名字段保持原形态,不影响本用例的断言。
        val url = "https://flkofd.npc.gov.cn/reader?file=http://192.0.2.1:38080" +
            "/law-search/amazonFile/ofdGenerateLink?filePath=prod/20231129/a.pdf" +
            "&_wr_timestamp=1791263543875&_wr_app_id=2396972e5&_wr_sign=03329b796172f6f7"

        val f = FlkSource.fileParamOf(url)

        // 关键:签名三个参数都必须保住 —— 按 query 参数名解析会把它们丢掉,
        // 实测那样拿到的 file 是无签名的,阅读器只返回空版面数据
        assertTrue("file 应含 filePath", f.contains("filePath=prod/20231129/a.pdf"))
        assertTrue("file 应含 _wr_timestamp", f.contains("_wr_timestamp=1791263543875"))
        assertTrue("file 应含 _wr_app_id", f.contains("_wr_app_id=2396972e5"))
        assertTrue("file 应含 _wr_sign", f.contains("_wr_sign=03329b796172f6f7"))
    }

    @Test
    fun `file 参数在无 file 时返回空串`() {
        assertEquals("", FlkSource.fileParamOf("https://flkofd.npc.gov.cn/reader"))
    }

    @Test
    fun `六个法规分类均由 flk 供数`() {
        listOf(
            LawCategory.CONSTITUTION, LawCategory.LAW, LawCategory.ADMIN_REG,
            LawCategory.SUPERVISION, LawCategory.JUDICIAL, LawCategory.LOCAL,
        ).forEach { cat ->
            assertTrue("$cat 应由 flk 供数", cat.isFlkBacked)
            // flk 恒为**第一个**源:列表按来源分组时,官方正式文本排在最前
            assertEquals("$cat 原生源首项应为 FLK", "FLK", cat.nativeSourceIds.first())
        }
        // 司法解释另并列最高法官网源(不新增分类入口,见 LawCategory.JUDICIAL 注释)
        assertEquals(
            "司法解释应为 flk + 最高法",
            listOf("FLK", "COURT"),
            LawCategory.JUDICIAL.nativeSourceIds,
        )
        // 其余五个法规分类仍然只有 flk 一个源
        listOf(
            LawCategory.CONSTITUTION, LawCategory.LAW, LawCategory.ADMIN_REG,
            LawCategory.SUPERVISION, LawCategory.LOCAL,
        ).forEach { cat ->
            assertEquals("$cat 原生源应只有 FLK", listOf("FLK"), cat.nativeSourceIds)
        }
    }

    @Test
    fun `阅读器域与站点主域不同且均需白名单`() {
        // 阅读器部署在独立域 flkofd,白名单漏掉会导致正文被拦截
        assertTrue(FlkSource.READER_BASE.contains("flkofd"))
        assertTrue(FlkSource.BASE.contains("flk.npc.gov.cn"))
        assertFalse(FlkSource.READER_BASE == FlkSource.BASE)
    }

    // ---- 官方文章深链(跳转「查看原文」用) ----

    @Test
    fun `文章深链走 detail 路由而非接口路径`() {
        val url = FlkSource.detailPageUrl("ff8081818c24df58018c660e621058d0", "天津市食品安全条例")
        // 官方 SPA 路由是 /detail,参数名是 id;`/flfgDetails` 只是接口路径,
        // 当页面打开只会得到 552B 的 SPA 空壳
        assertTrue(url, url.contains("/detail?"))
        assertTrue(url, url.contains("id=ff8081818c24df58018c660e621058d0"))
        assertFalse("不应退到接口路径", url.contains("/flfgDetails?"))
    }

    @Test
    fun `文章深链带标题且中文被正确编码`() {
        val url = FlkSource.detailPageUrl("abc123", "中华人民共和国监察法实施条例")
        // title 用于浏览器标签标题,官方路由的 beforeEnter 会读它
        assertTrue(url, url.contains("&title="))
        // 中文必须是百分号编码,不能裸拼进 query
        assertFalse("标题不应裸拼", url.contains("title=中华人民共和国"))
        assertTrue(url, url.contains("%E7%9B%91%E5%AF%9F"))  // 「监」的 UTF-8 编码
    }

    @Test
    fun `文章深链缺标题时不追加空参数`() {
        val url = FlkSource.detailPageUrl("abc123", null)
        assertEquals("https://flk.npc.gov.cn/detail?id=abc123", url)
        assertFalse(url, url.contains("title"))
    }

    // ---- 地区/机关筛选(下推 zdjgCodeId) ----

    @Test
    fun `地区代码取自官方字典且为省级叶子`() {
        // 实测 zdjgCodeId 下推真实收敛:广东 1812 / 江苏 1511 / 北京 329 / 新疆 719
        assertEquals(350, FlkAuthorities.PROVINCES.first { it.name == "广东" }.code)
        assertEquals(260, FlkAuthorities.PROVINCES.first { it.name == "江苏" }.code)
        assertEquals(170, FlkAuthorities.PROVINCES.first { it.name == "北京" }.code)
        assertEquals(470, FlkAuthorities.PROVINCES.first { it.name == "新疆" }.code)
        assertEquals("31 个省级地区", 31, FlkAuthorities.PROVINCES.size)
    }

    /**
     * 省级地区代码必须与官方 `enumData` 的 `zdjgfl` 字典**逐个对齐**。
     *
     * 曾因手工誊抄漏掉河南(320)——只断言「数量」能发现少一个,但发现不了
     * 「错位一个」,故这里锁定完整代码集合。
     */
    @Test
    fun `省级地区代码与官方字典逐个对齐`() {
        val expected = setOf(
            170, 180, 190, 200, 210, 220, 230, 240, 250, 260, 270, 280, 290, 300, 310,
            320, 330, 340, 350, 360, 370, 380, 390, 400, 410, 420, 430, 440, 450, 460, 470,
        )
        val actual = FlkAuthorities.PROVINCES.map { it.code }.toSet()
        assertEquals("漏掉: ${expected - actual}", expected, actual)
        assertEquals("多出: ${actual - expected}", expected, actual)
    }

    @Test
    fun `省级地区代码不重复且都在可筛表内`() {
        val codes = FlkAuthorities.PROVINCES.map { it.code }
        assertEquals("省级地区代码不应重复", codes.size, codes.distinct().size)
        codes.forEach { assertTrue("代码 $it 应在 ALL 内", FlkAuthorities.resolve(it) == it) }
    }

    @Test
    fun `机关代码与名称双向可查`() {
        for (a in FlkAuthorities.ALL) {
            assertEquals(a.name, FlkAuthorities.nameOf(a.code))
            assertEquals(a.code, FlkAuthorities.codeOf(a.name))
        }
    }

    @Test
    fun `未知机关代码不猜测`() {
        assertNull(FlkAuthorities.nameOf(99999))
        assertNull(FlkAuthorities.nameOf(null))
        assertNull(FlkAuthorities.codeOf(null))
        assertNull(FlkAuthorities.codeOf("不存在省"))
        // 脏值不得下推:宁可不过滤,也不要把无效值发给官方导致结果被清空
        assertNull(FlkAuthorities.resolve(99999))
        assertNull(FlkAuthorities.resolve(null))
    }

    @Test
    fun `顶层机关按分类给出`() {
        // 行政法规=国务院、监察法规=国家监察委员会、司法解释=两高
        assertEquals(listOf(120), FlkAuthorities.topLevelFor(LawCategory.ADMIN_REG).map { it.code })
        assertEquals(listOf(130), FlkAuthorities.topLevelFor(LawCategory.SUPERVISION).map { it.code })
        assertEquals(
            listOf(140, 150),
            FlkAuthorities.topLevelFor(LawCategory.JUDICIAL).map { it.code },
        )
        // 地方性法规的地区维度走省级列表,顶层机关不适用
        assertTrue(FlkAuthorities.topLevelFor(LawCategory.LOCAL).isEmpty())
    }

    // ---- 制定机关下推(2026-10-08 修:发文机关此前从未下推) ----

    /**
     * 「地区」与「发文机关」共用官方 `zdjgCodeId`,地区优先。
     *
     * 两者不会同时有值(地方性法规只开地区维度,其他分类只开机关维度),
     * 这里把优先级钉死,免得日后有人把顺序调反。
     */
    @Test
    fun `制定机关下推地区优先于机关`() {
        assertEquals(350, FlkSource.zdjgCodeOf(350, "最高人民检察院"))
        assertEquals(150, FlkSource.zdjgCodeOf(null, "最高人民检察院"))
    }

    /**
     * ⚠️ 回归:发文机关**必须**能下推。
     *
     * 此前 `postList` 只读了 `filters.region`(注释却写着「地区/发文机关」),
     * 于是机关筛选退化成**在已加载的几十条里做客户端过滤** ——
     * 用户感知即「选了机关还得不停往下翻,数量才对得上」。
     */
    @Test
    fun `发文机关可解析为官方机关代码并下推`() {
        assertEquals(140, FlkSource.zdjgCodeOf(null, "最高人民法院"))
        assertEquals(150, FlkSource.zdjgCodeOf(null, "最高人民检察院"))
        assertEquals(120, FlkSource.zdjgCodeOf(null, "国务院"))
    }

    /**
     * 官方返回的机关名有时是**全称**或**联合署名**,字典里查不到 → 不下推(退回客户端过滤)。
     * 宁可不过滤,也不能把解析不出含义的值发给官方把结果清空。
     */
    @Test
    fun `字典里没有的机关名不下推`() {
        assertNull(FlkSource.zdjgCodeOf(null, "全国人民代表大会常务委员会"))
        assertNull(FlkSource.zdjgCodeOf(null, "最高人民法院、最高人民检察院"))
        assertNull(FlkSource.zdjgCodeOf(null, null))
        assertNull(FlkSource.zdjgCodeOf(null, ""))
    }

    // ---- 正文格式选取:老法规空白页的修复(勿回退) ----

    /**
     * 正文路径必须 **OFD 优先**,不能按「PDF 优先」直觉。
     *
     * 同一部《电影管理条例》(2001)三种格式实测:
     * `ossWordOfdPath` → text 77KB / 4 areas / 18 行,SVG 67KB,渲染正常;
     * `ossPdfOfdPath` 与 `ossPdfPath` → text 136B / **0 areas**,SVG 仅 926B,整页空白。
     * 老 PDF 是扫描件,渲染不出矢量内容。
     */
    @Test
    fun `正文格式优先选 OFD 以避开扫描件空白页`() {
        val oss = mapOf(
            "ossWordPath" to "prod/20011225/a.docx",
            "ossWordOfdPath" to "prod/20011225/a.ofd",
            "ossPdfPath" to "prod/20011225/b.pdf",
            "ossPdfOfdPath" to "prod/20011225/b.ofd",
        )
        // 返回的是路径值(不是键名):OFD 胜出,不能落到扫描件 PDF 上
        assertEquals("prod/20011225/a.ofd", FlkSource.pickBodyPath(oss))
    }

    /** 没有 OFD 时才退到 PDF(总比没有正文强) */
    @Test
    fun `无 OFD 时回落 PDF`() {
        assertEquals(
            "prod/x.pdf",
            FlkSource.pickBodyPath(mapOf("ossPdfPath" to "prod/x.pdf")),
        )
        assertEquals(
            "prod/x.ofd",
            FlkSource.pickBodyPath(mapOf("ossPdfOfdPath" to "prod/x.ofd")),
        )
    }

    /** 纯 Word 不可选(阅读器返 550),且全空时返回 null */
    @Test
    fun `纯 Word 不作为候选且全空返回 null`() {
        assertEquals(null, FlkSource.pickBodyPath(mapOf("ossWordPath" to "prod/x.docx")))
        assertEquals(null, FlkSource.pickBodyPath(emptyMap()))
    }

    // ---- 时效性:sxx 真实语义(2026-10-06 全库 170 条抽样审计结论) ----

    /**
     * 已施行的法规**不能**被标成「尚未生效」。
     *
     * 早期把 `sxx=1` 当成未生效,导致大量误标,实测反例:
     * - 契税暂行条例:`gbrq=2019-03-02` `sxrq=2019-03-02` `sxx=1`
     * - 最高人民法院关于审理掩饰、隐瞒犯罪所得的解释:`sxrq=2021-04-15` `sxx=1`
     * 两者施行日期都早已过去,应显示「现行有效」。
     */
    @Test
    fun `已施行法规不被误标为尚未生效`() {
        val today = LocalDate.of(2026, 10, 6)
        // sxx=1 且施行日期已过 -> 现行有效(不是尚未生效)
        assertEquals(
            LawStatus.CURRENT,
            FlkSource.statusOf(1, LocalDate.of(2019, 3, 2), today),
        )
        assertEquals(
            LawStatus.CURRENT,
            FlkSource.statusOf(1, LocalDate.of(2021, 4, 15), today),
        )
    }

    /** 施行日期晚于今天 -> 尚未生效(与 sxx 无关,哪怕 sxx=3) */
    @Test
    fun `施行日期未到即尚未生效`() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals(
            LawStatus.PENDING,
            FlkSource.statusOf(3, LocalDate.of(2027, 1, 1), today),
        )
        // 边界:施行当天即视为已生效
        assertEquals(
            LawStatus.CURRENT,
            FlkSource.statusOf(3, today, today),
        )
        assertEquals(
            LawStatus.CURRENT,
            FlkSource.statusOf(3, today.minusDays(1), today),
        )
    }

    /** sxx=2 是「已被修改」:同一法规另有更新文本,当前这条是历史版本 */
    @Test
    fun `sxx 等于 2 视为已被修改`() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals(LawStatus.REVISED, FlkSource.statusOf(2, LocalDate.of(2021, 9, 20), today))
    }

    /** 施行日期缺失(老法规常见)时按 sxx 回落,不做臆测 */
    @Test
    fun `施行日期为空时按 sxx 回落`() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals(LawStatus.CURRENT, FlkSource.statusOf(3, null, today))
        assertEquals(LawStatus.REVISED, FlkSource.statusOf(2, null, today))
        assertEquals(LawStatus.CURRENT, FlkSource.statusOf(null, null, today))
    }

    // ---- 翻页判定(2026-10-08 修)----

    /**
     * 末页必须停住:总条数恰为每页容量整数倍时,旧判据 `total > itemCount` 仍会
     * 给出下一页 → 再请求拿到 0 条 → 被判「有 total 却解不出」= PARSE_FAILED,
     * 用户滑到底时莫名弹出「国家法律法规数据库 来源暂不可用」。
     */
    @Test
    fun `总数是每页容量整数倍时末页不再给下一页`() {
        assertNull(FlkSource.nextPageOf(page = 10, pageSize = 10, total = 100, itemCount = 10))
        assertNull(FlkSource.nextPageOf(page = 74, pageSize = 10, total = 734, itemCount = 10))
        // 前一页仍应继续
        assertEquals(10, FlkSource.nextPageOf(page = 9, pageSize = 10, total = 100, itemCount = 10))
        assertEquals(74, FlkSource.nextPageOf(page = 73, pageSize = 10, total = 734, itemCount = 10))
    }

    /**
     * 判据不能依赖「本页解析出的条数」:个别行字段异常被丢弃后本页会少于容量,
     * 旧写法会因此**提前停止翻页**(表现为「几百条的分类只能翻两三页」)。
     */
    @Test
    fun `本页解析条数偏少也继续翻页`() {
        assertEquals(2, FlkSource.nextPageOf(page = 1, pageSize = 10, total = 734, itemCount = 7))
        assertEquals(3, FlkSource.nextPageOf(page = 2, pageSize = 10, total = 734, itemCount = 1))
    }

    /** 本页一条都没解出来:不再往下翻(由上层按空页处理) */
    @Test
    fun `空页不再翻页`() {
        assertNull(FlkSource.nextPageOf(page = 1, pageSize = 10, total = 734, itemCount = 0))
    }

    /** pageSize 与 page 的脏值都被夹住,不能算出比实际更小的窗口 */
    @Test
    fun `脏分页参数被夹到合法区间`() {
        // pageSize 上限 50(与 postList 下推一致)
        assertEquals(2, FlkSource.nextPageOf(page = 1, pageSize = 999, total = 63, itemCount = 50))
        assertNull(FlkSource.nextPageOf(page = 2, pageSize = 999, total = 63, itemCount = 13))
        // page 至少为 1
        assertEquals(2, FlkSource.nextPageOf(page = 0, pageSize = 10, total = 100, itemCount = 10))
    }

    /** 主源域名必须计入本源:否则其请求不计健康、403 不触发 10 分钟冷却 */
    @Test
    fun `flk 域名计入本源以便记录健康与冷却`() {
        assertEquals(SourceId.FLK, com.lawquery.core.net.HostSourceMapper.sourceIdOf("flk.npc.gov.cn"))
    }
}

/** 各分类在官方 enumData 里的**父节点** codeId(实测下推返回 0 条,列表不应包含) */
private fun LawCategory.flfgCodeParentCode(): Int = when (this) {
    LawCategory.CONSTITUTION -> 0        // 宪法本身即叶子,无父节点
    LawCategory.LAW -> 101
    LawCategory.ADMIN_REG -> 201
    LawCategory.SUPERVISION -> 0         // 监察法规本身即叶子(220)
    LawCategory.JUDICIAL -> 311
    LawCategory.LOCAL -> 221
    else -> -1
}