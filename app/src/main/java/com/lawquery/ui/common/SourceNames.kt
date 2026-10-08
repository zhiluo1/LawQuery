package com.lawquery.ui.common

import com.lawquery.data.source.SourceId

/** 来源展示名(需求 7.2.5:所有列表/详情/分享文案显著标注来源) */
object SourceNames {
    fun displayName(sourceId: SourceId): String = when (sourceId) {
        SourceId.GOV_CN -> "中国政府网"
        SourceId.COURT -> "最高人民法院"
        SourceId.FLK_WEB -> "国家法律法规数据库"
        SourceId.CASE_LIBRARY -> "人民法院案例库"
        SourceId.FLK -> "国家法律法规数据库"
        SourceId.MPS_REG -> "公安部规章库"
    }
}
