package com.lawquery.data.source

import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.SourceId
import com.lawquery.domain.model.VersionFingerprint
import java.time.OffsetDateTime

/** 源标识:领域层唯一定义(Models.kt),此处以别名复用,保持 4.2 接口签名与文档一致 */
typealias SourceId = com.lawquery.domain.model.SourceId

/** 通道能力:原生解析 / 仅官方直通(项目文档 4.2,需求 3.3 双通道) */
enum class SourceCapability { NATIVE, WEB_ONLY }

/** 失败原因(项目文档 4.3) */
enum class FailureReason { RATE_LIMITED, PARSE_FAILED, NETWORK, TIMEOUT, OFFLINE,

    /** 需登录的官方源(人民法院案例库):未登录或官方会话已失效 */
    NOT_LINKED,

    /**
     * 官方只下发正文预览(订阅/权限受限的典型形态)。
     *
     * 题录与检索不受影响,仅正文需在官方页阅读。
     * 与 [PARSE_FAILED] 分开,便于界面给出「非故障、而是需官方权限」的准确提示。
     */
    PREVIEW_ONLY }

/** 健康状态(需求 6.1 Room 源健康状态) */
enum class HealthStatus { OK, COOLDOWN, DEGRADED, UNKNOWN }

data class SourceHealth(
    val sourceId: SourceId,
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val lastSuccessAt: OffsetDateTime? = null,
    val lastFailureAt: OffsetDateTime? = null,
    val lastReason: FailureReason? = null,
    val cooldownUntil: OffsetDateTime? = null,
)

/** 搜索筛选(需求 F1 P1:效力位阶/发文机关/日期区间/时效性) */
data class SearchFilters(
    val authority: String? = null,
    val year: Int? = null,
    val status: com.lawquery.domain.model.LawStatus? = null,
    val dateFrom: java.time.LocalDate? = null,
    val dateTo: java.time.LocalDate? = null,
    /**
     * 地区筛选,**仅地方性法规**有意义,值为 `FlkAuthorities.code`(Int)。
     *
     * 地方性法规由各省、市人大常委会制定,flk 以制定机关(`zdjgCodeId`)承载地区维度,
     * 父节点 `165 地方人大及其常委会` 下挂 31 个省级地区。实测下推有效:
     * 不筛 28419 条,广东 1812 / 江苏 1511 / 北京 329 / 新疆 719。
     *
     * 其他数据源忽略该字段(见各自 `search` 实现)。
     */
    val region: Int? = null,
)

enum class SearchSort { RELEVANCE, PUBLISH_DATE_DESC }

/** 搜索请求:关键词 + 筛选 + 分页(项目文档 4.2) */
data class SearchQuery(
    val keyword: String,
    val category: LawCategory? = null,
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.RELEVANCE,
    val page: Int = 1,
    val pageSize: Int = 10,
)

/** 官方直通跳转信息:受限源在结果页渲染为置顶卡片(需求 F1) */
data class DirectJumpInfo(
    val label: String,
    val url: String,
    val keyword: String? = null,
)

/** 搜索页结果:列表 + 下一页游标 + 直通信息(项目文档 4.2) */
data class SearchPage(
    val items: List<LawRef>,
    val nextPage: Int?,
    val directJump: DirectJumpInfo? = null,
)

/** 跨层错误模型(项目文档 4.3),UI 映射见 ui/common/UiState */
sealed interface SourceResult<out T> {
    data class Success<T>(val data: T) : SourceResult<T>
    data class SourceUnavailable(val source: SourceId, val reason: FailureReason) : SourceResult<Nothing>
}

/**
 * 官方源统一接口(项目文档 4.2):新增源 = 新增实现类 + 注册,不改动上层。
 */
interface LawSource {
    val id: SourceId
    val capability: SourceCapability

    /** 原生搜索;WEB_ONLY 源返回带关键词的直通跳转信息 */
    suspend fun search(query: SearchQuery): SourceResult<SearchPage>

    /** 实时拉取全文(仅 NATIVE 源实现;不落盘) */
    suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument>

    /** 轻量元数据拉取,用于更新校验(需求 F4/6.2) */
    suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint>

    /** 由 SourceHealthTracker 驱动的健康快照 */
    fun healthSnapshot(): SourceHealth
}
