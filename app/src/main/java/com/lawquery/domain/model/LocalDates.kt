package com.lawquery.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 时间工具(领域层,无框架依赖) */
object LocalDates {
    fun fromEpochMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
