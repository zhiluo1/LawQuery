package com.lawquery.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章/节/条 结构化与中文数字单测(需求 F3:层级渲染、条号直达)。
 */
class StructureBuilderTest {

    @Test
    fun `编章节条层级构建`() {
        val blocks = listOf(
            "中华人民共和国民法典",
            "（2020年5月28日第十三届全国人民代表大会第三次会议通过）",
            "第一编　总则",
            "第一章　基本规定",
            "第一条　为了保护民事主体的合法权益，调整民事关系，维护社会和经济秩序，制定本法。",
            "第二条　民法调整平等主体的自然人、法人和非法人组织之间的人身关系和财产关系。",
            "第二章　自然人",
            "第一节　民事权利能力和民事行为能力",
            "第十三条　自然人从出生时起到死亡时止，具有民事权利能力。",
            "第一千零七十七条　自婚姻登记机关收到离婚登记申请之日起三十日内，任何一方不愿意离婚的，可以向婚姻登记机关撤回离婚登记申请。",
        )
        val result = StructureBuilder.build(blocks)

        assertTrue("应识别出条文结构", result.hasStructure)
        // 编/章均为章级;第一编(空)、第一章(条1-2)、第二章(节+条13、1077)
        assertEquals(3, result.chapters.size)
        val basic = result.chapters[1]
        assertEquals("第一章　基本规定", basic.title)
        assertEquals(2, basic.articles.size)
        assertEquals(1, basic.articles[0].arabicNumber)
        val chapter2 = result.chapters[2]
        assertEquals(1, chapter2.sections.size)
        assertEquals("第一节　民事权利能力和民事行为能力", chapter2.sections[0].title)
        assertEquals(2, chapter2.sections[0].articles.size)
        assertEquals(13, chapter2.sections[0].articles[0].arabicNumber)
        assertEquals(1077, chapter2.sections[0].articles[1].arabicNumber)
        assertEquals("第十三条", chapter2.sections[0].articles[0].number)
    }

    @Test
    fun `无章条结构时降级为纯文本`() {
        val result = StructureBuilder.build(listOf("这是一段没有条文结构的说明文字。"))
        assertFalse(result.hasStructure)
        assertEquals(0, result.chapters.size)
        assertTrue(result.preamble.isNotEmpty())
    }

    @Test
    fun `条前散置文本并入前言不丢失`() {
        val result = StructureBuilder.build(
            listOf("引言段落", "第一条　甲", "补充段落", "第二条　乙"),
        )
        assertTrue(result.hasStructure)
        assertEquals(listOf("引言段落"), result.preamble)
        assertEquals("补充段落", result.chapters[0].sections[0].articles[0].paragraphs.last())
    }

    @Test
    fun `中文数字解析`() {
        assertEquals(1, ChineseNumeral.parse("一"))
        assertEquals(10, ChineseNumeral.parse("十"))
        assertEquals(20, ChineseNumeral.parse("二十"))
        assertEquals(107, ChineseNumeral.parse("一百零七"))
        assertEquals(1077, ChineseNumeral.parse("一千零七十七"))
        assertEquals(2600, ChineseNumeral.parse("二千六百"))
        assertEquals(12, ChineseNumeral.parse("12"))
        assertEquals(1077, ChineseNumeral.parse("１０７７")) // 全角
        assertNull(ChineseNumeral.parse("abc"))
        assertNull(ChineseNumeral.parse(null))
    }

    @Test
    fun `条号识别辅助函数`() {
        assertTrue(StructureBuilder.isArticleHeading("第一百零七十七条　自婚姻登记机关…"))
        assertTrue(StructureBuilder.isChapterHeading("第一章　基本规定"))
        assertTrue(StructureBuilder.isChapterHeading("第一编　总则"))
        assertTrue(StructureBuilder.isSectionHeading("第二节　监护"))
        assertFalse(StructureBuilder.isArticleHeading("本法自2021年1月1日起施行。"))
        assertEquals(1077, StructureBuilder.numeralAfterDi("第一千零七十七条"))
    }

    @Test
    fun `目录里的空章被正文同名章复用而不重复建章`() {
        // 2026-10-08 实测:公安部规章库 htmlContent 开头是目录页(章名一行一个),
        // 正文再出现章标题(字内多空格)。修复前会建出成倍的空章,条文看似没挂在章下。
        val blocks = listOf(
            "道路交通安全违法行为记分管理办法",
            "第一章  总则", // —— 目录(无字内空格)
            "第二章  记分分值",
            "第三章  记分执行",
            "第一章  总  则", // —— 正文(字内有空格,归一化后与目录同名)
            "第一条  为充分发挥记分制度的管理、教育、引导功能，制定本办法。",
            "第二条  本办法所称交通违法行为记分，是指公安机关交通管理部门对机动车驾驶人的交通违法行为予以记分管理的行为。",
            "第二章  记 分 分 值",
            "第三条  交通违法行为记分分值为十二分、九分、六分、三分、一分。",
            "第三章  记分执行",
            "第四条  机动车驾驶人在一个记分周期内累积记分满12分的，应当参加学习和考试。",
        )
        val result = StructureBuilder.build(blocks)

        assertTrue(result.hasStructure)
        // 目录 3 个章被正文复用,不应出现 6 个章
        assertEquals(3, result.chapters.size)
        assertEquals("第一章  总则", result.chapters[0].title) // 保留先出现的目录标题
        assertEquals(2, result.chapters[0].articles.size)
        assertEquals(1, result.chapters[0].articles[0].arabicNumber)
        assertEquals(3, result.chapters[1].articles[0].arabicNumber)
        assertEquals(4, result.chapters[2].articles[0].arabicNumber)
    }

    @Test
    fun `正文真的重复出现同名章时仍各自成章`() {
        // 复用只针对「空章」;前一章已有内容后再次出现同名标题,按新章处理,不吞内容
        val blocks = listOf(
            "第一章  总则",
            "第一条  甲",
            "第一章  总则",
            "第二条  乙",
        )
        val result = StructureBuilder.build(blocks)
        assertEquals(2, result.chapters.size)
        assertEquals(1, result.chapters[0].articles.size)
        assertEquals(1, result.chapters[1].articles.size)
    }

    @Test
    fun `目录里的空节被正文同名节复用`() {
        val blocks = listOf(
            "第一章  总则", // 目录
            "第一节  一般规定", // 目录
            "第一章  总  则", // 正文,复用上面的空章
            "第一节  一般规定", // 正文,复用上面的空节
            "第一条  甲",
        )
        val result = StructureBuilder.build(blocks)
        assertEquals(1, result.chapters.size)
        assertEquals(1, result.chapters[0].sections.size)
        assertEquals(1, result.chapters[0].sections[0].articles.size)
    }
}
