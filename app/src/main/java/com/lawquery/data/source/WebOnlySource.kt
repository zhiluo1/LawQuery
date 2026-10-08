package com.lawquery.data.source

import com.lawquery.core.net.SourceHealthTracker
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.VersionFingerprint

/**
 * 国家法律法规数据库 flk.npc.gov.cn(源 A1,官方直通通道)。
 *
 * 需求 3.2 / 7.2.1:robots.txt 全站禁止自动化采集,本源**永不**程序化解析;
 * 仅产出"官方直通"跳转信息,由 UI 渲染为置顶卡片,用户在受控 WebView 内浏览官方页面。
 */
class WebOnlySource(
    private val health: SourceHealthTracker,
    /** 搜索深链构造器由 DI 注入(DeepLinks 定义于 ui/web,数据层不依赖 UI) */
    private val flkSearchUrl: (keyword: String) -> String,
    private val flkCardLabel: (keyword: String) -> String,
) : LawSource {

    override val id = SourceId.FLK_WEB
    override val capability = SourceCapability.WEB_ONLY

    override suspend fun search(query: SearchQuery): SourceResult<SearchPage> {
        val keyword = query.keyword.trim()
        return SourceResult.Success(
            SearchPage(
                items = emptyList(),
                nextPage = null,
                directJump = if (keyword.isEmpty()) {
                    null
                } else {
                    DirectJumpInfo(
                        label = flkCardLabel(keyword),
                        url = flkSearchUrl(keyword),
                        keyword = keyword,
                    )
                },
            )
        )
    }

    override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> =
        SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)

    override suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint> =
        SourceResult.SourceUnavailable(id, FailureReason.PARSE_FAILED)

    override fun healthSnapshot() = health.snapshot(id)
}
