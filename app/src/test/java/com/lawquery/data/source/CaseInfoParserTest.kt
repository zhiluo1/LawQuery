package com.lawquery.data.source

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 人民法院案例库属性串解析(入库时间)。
 *
 * ## 为什么要加边界与合理性校验
 *
 * 用户实测发现:某些案例详情页显示「2026-12-03 发布」,而当天是 2026-10-07 ——
 * 发布时间远远大于当前时间。
 *
 * 根因不是官方数据错,而是**解析器把入库编号当成了日期**:
 * 官方 `cpws_al_infos` 里含有形如 `2026-12-3-001-005` 的入库编号,旧正则
 * `(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?` 没有边界,会从这串数字里截出
 * 「2026-12-3」当作裁判日期。
 *
 * **入库编号只是入库顺序号,不代表任何时间**(用户明确指出)。修法两条:
 * 1. 日期必须占完整片段(前后不能再接数字/连字符)—— 编号整串不再产生匹配;
 * 2. 晚于今天的日期一律判为噪声 —— 兜住"格式怪/官方写错"的情况。
 *
 * 两条都取不到时返回 null,由界面**不展示发布时间**(而不是编一个出来)。
 */
class CaseInfoParserTest {

    /** 固定"今天",避免测试随系统日期漂移 */
    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    /**
     * 核心回归:入库编号里的数字段不能被当成日期。
     *
     * 这里刻意把 today 设成 2030 年 —— 若解析器仍从编号里截日期,
     * `2026-12-3` 会被判为"过去的合法日期"而返回,断言即可失败。
     * 只靠"未来日期兜底"是拦不住这个 bug 的。
     */
    @Test
    fun `入库编号不被当成日期`() {
        val infos = "2026-12-3-001-005|宁夏回族自治区高级人民法院"
        val attrs = CaseInfoParser.parse(infos, LocalDate.of(2030, 1, 1))
        assertNull("入库编号是顺序号,不能当日期", attrs.judgeDate)
    }

    /** 各种编号形态都要挡住:不能因为分隔符不同就漏出去 */
    @Test
    fun `不同写法的入库编号都不会产出日期`() {
        val samples = listOf(
            "2026-12-3-001-005",
            "2026-12-03-001-005",
            "2026-12-3-1",
            "2026123-001005",
        )
        for (s in samples) {
            assertNull("「$s」不应解析出日期", CaseInfoParser.parse(s, LocalDate.of(2030, 1, 1)).judgeDate)
        }
    }

    /** 独立成片的正常日期仍要能解析(不能为了挡编号把真日期也挡了) */
    @Test
    fun `正常日期仍能解析`() {
        val cases = mapOf(
            "宁夏回族自治区高级人民法院|2024-03-15|行政" to LocalDate.of(2024, 3, 15),
            "宁夏回族自治区高级人民法院|2024年3月15日|行政" to LocalDate.of(2024, 3, 15),
            "最高人民法院 2023-12-29 刑事" to LocalDate.of(2023, 12, 29),
            "（2022-07-01）发布" to LocalDate.of(2022, 7, 1),
        )
        for ((infos, expected) in cases) {
            assertEquals(
                "「$infos」应解析出 $expected",
                expected,
                CaseInfoParser.parse(infos, today).judgeDate,
            )
        }
    }

    /** 晚于今天的日期一律丢弃(发布日期不可能在未来) */
    @Test
    fun `晚于今天的日期被判为噪声`() {
        assertNull(
            CaseInfoParser.parse("某法院|2027-05-06|刑事", today).judgeDate,
        )
    }

    /** 串里同时有噪声日期与正常日期时,取第一个合理的(而不是整体丢弃) */
    @Test
    fun `跳过未来日期取第一个合理日期`() {
        val infos = "2027-05-06|宁夏回族自治区高级人民法院|2024-03-15|行政"
        assertEquals(
            LocalDate.of(2024, 3, 15),
            CaseInfoParser.parse(infos, today).judgeDate,
        )
    }

    /** 今天本身是合法边界(不含未来) */
    @Test
    fun `当天的日期不算未来`() {
        assertEquals(
            today,
            CaseInfoParser.parse("某法院|$today|刑事", today).judgeDate,
        )
    }

    /** 完全取不到日期时返回 null,由界面隐藏发布时间(而不是显示「—」) */
    @Test
    fun `无日期信息时返回 null`() {
        assertNull(CaseInfoParser.parse("宁夏回族自治区高级人民法院", today).judgeDate)
        assertNull(CaseInfoParser.parse("", today).judgeDate)
    }

    /** 加固日期解析不得破坏原有的法院 / 案号识别 */
    @Test
    fun `法院与案号解析不受影响`() {
        val attrs = CaseInfoParser.parse(
            "宁夏回族自治区高级人民法院|（2024）宁01行终初123号|2024-03-15",
            today,
        )
        assertEquals("宁夏回族自治区高级人民法院", attrs.court)
        assertEquals("（2024）宁01行终初123号", attrs.caseNumber)
        assertEquals(LocalDate.of(2024, 3, 15), attrs.judgeDate)
    }

    /** 法庭/检察院同样识别(回归:防止改动波及原有分支) */
    @Test
    fun `法庭与检察院也能识别`() {
        // 片段同时含「法院」时按优先级归给法院,所以这里用只含「法庭」的样本
        assertEquals(
            "第三巡回法庭",
            CaseInfoParser.parse("第三巡回法庭", today).court,
        )
        assertEquals(
            "某市人民检察院",
            CaseInfoParser.parse("某市人民检察院", today).court,
        )
    }
}