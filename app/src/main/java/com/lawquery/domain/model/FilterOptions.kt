package com.lawquery.domain.model

import java.time.LocalDate

/**
 * 通用筛选选项:年份范围 1978 年至当前年份。
 * 当前年份动态取系统日期,不硬编码(需求:收录官网 1978 年至今数据)。
 */
object FilterOptions {
    const val MIN_YEAR = 1978

    fun years(today: LocalDate = LocalDate.now()): List<Int> =
        (MIN_YEAR..today.year).sortedDescending()
}
