package com.lawquery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏表(项目文档 4.5):仅元数据 + 收藏时版本指纹,禁止任何正文字段(需求 F5/6.2)。
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val refKey: String,          // source:id
    val sourceId: String,
    val docId: String,
    val title: String,
    val url: String,
    val issuingAuthority: String,
    val docNumber: String?,
    val publishDateEpochDay: Long?,
    val effectiveDateEpochDay: Long?,
    val status: String,
    // 收藏时版本指纹(需求 6.2:{发文字号, 公布日期, 施行日期, 时效性})
    val fingerprintDocNumber: String?,
    val fingerprintPublishEpochDay: Long?,
    val fingerprintEffectiveEpochDay: Long?,
    val fingerprintStatus: String,
    val favoritedAt: Long,
)

/**
 * 最近浏览表:仅元数据(无指纹),≤50 条滚动淘汰(需求 F5)。
 */
@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val refKey: String,
    val sourceId: String,
    val docId: String,
    val title: String,
    val url: String,
    val issuingAuthority: String,
    val docNumber: String?,
    val publishDateEpochDay: Long?,
    val effectiveDateEpochDay: Long?,
    val status: String,
    val browsedAt: Long,
)

/**
 * 数据源健康状态表(需求 6.1)。
 */
@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val sourceId: String,
    val status: String,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastReason: String?,
    val cooldownUntil: Long?,
)

/**
 * 搜索历史查询词表:仅查询词,≤50 条(需求 F1)。
 */
@Entity(tableName = "search_history", indices = [Index(value = ["searchedAt"])])
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)
