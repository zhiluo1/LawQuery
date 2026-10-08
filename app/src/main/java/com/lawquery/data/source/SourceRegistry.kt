package com.lawquery.data.source

import com.lawquery.domain.model.LawCategory

/**
 * 源注册与路由(项目文档 4.1/5,需求 3.4 降级链路):
 * - 哪些分类走原生解析、哪些走官方直通;
 * - 降级:某原生源不可用时,搜索/浏览仅剔除该源结果并提示,不中断其他源。
 */
class SourceRegistry(
    private val sources: Map<SourceId, LawSource>,
) {
    fun source(id: SourceId): LawSource? = sources[id]

    /** 全部原生解析源(搜索时并行调用) */
    fun nativeSources(): List<LawSource> = sources.values.filter { it.capability == SourceCapability.NATIVE }

    /**
     * 指定分类的原生源(分类为 null 时返回全部原生源)。
     *
     * ⚠️ 返回顺序**按分类声明的 `nativeSourceIds`**,不是注册表的插入顺序。
     * 声明顺序承载产品意图([com.lawquery.domain.model.LawCategory.JUDICIAL] 里
     * flk 在前 = 官方正式文本优先),而注册表顺序与分类配置无关 —— 若按后者返回,
     * 在注册表里调整一行源的位置就会悄悄改变调用与拼接次序,极难排查。
     */
    fun nativeSourcesFor(category: LawCategory?): List<LawSource> {
        if (category == null) return nativeSources()
        val byId = nativeSources().associateBy { it.id }
        return category.nativeSourceIds.mapNotNull { name ->
            val id = runCatching { SourceId.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            byId[id]
        }
    }

    /** 官方直通源(flk 的「去官网搜」跳转通道)
     *
     * ⚠️ 当前**恒为 null** —— 该通道已从注册表移除:flk 现为原生检索源能直接出结果,
     * 搜索结果页那张「在国家法律法规数据库中搜索『x』」的中间跳转卡片已不再需要。
     * 保留此方法是为了不改动上层聚合逻辑([com.lawquery.data.repo.SearchRepository]
     * 仍按「有则渲染卡片、无则跳过」处理),若将来要恢复该能力,在此重新注册即可。
     */
    fun webSource(): LawSource? = sources[SourceId.FLK_WEB]
}
