package com.lawquery.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lawquery.data.repo.FavoriteItem
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.UpdateCheckService
import com.lawquery.data.source.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 收藏分组(参考图分组 tabs):按来源归组,无需额外字段 */
object FavGroup {
    const val ALL = 0
    const val GOV = 1
    const val COURT = 2
    const val FLK = 3
    const val CASE = 4
}

/** 收藏条目 + 最近阅读时间(仅元数据;时间来自「最近浏览」表联接,不加列) */
data class FavoriteRow(
    val item: FavoriteItem,
    val lastReadAt: Long?,
)

/**
 * 收藏页(需求 F5;参考图风格:分组 tabs + 阅读时间)。
 */
class FavoritesViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val updateCheckService: UpdateCheckService,
    historyRepository: com.lawquery.data.repo.HistoryRepository,
) : ViewModel() {

    private val _group = MutableStateFlow(FavGroup.ALL)
    val group: StateFlow<Int> = _group.asStateFlow()

    fun setGroup(g: Int) {
        _group.value = g
    }

    private val favoritesFlow = favoriteRepository.observeItems()

    /** 收藏 × 最近浏览(refKey→browsedAt)联接,展示「xx浏览」 */
    private val rowsFlow = combine(favoritesFlow, historyRepository.observeRecents(50)) { favs, recents ->
        val readAt = recents.associate { it.refKey to it.browsedAt }
        favs.map { FavoriteRow(it, readAt[it.ref.key]) }
    }

    /**
     * 分组过滤。
     *
     * ⚠️ 分组口径必须与**实际注册的源**一致(2026-10-08 修):
     * - 「官方数据库」:现行数据来自 `FLK`(国家法律法规数据库原生源)。此前只匹配
     *   `FLK_WEB`,而该直通源早已从注册表移除 —— 结果用户在宪法/法律/司法解释等分类
     *   收藏的法规,**在「官方数据库」分组里一条都看不到**(只在「全部」里能看到)。
     *   历史收藏可能仍带 `FLK_WEB`,故两者都匹配。
     * - 「政策文件」:政府网(国务院/部委文件)**与公安部规章库**同属该类,此前漏了
     *   `MPS_REG`,规章库的收藏同样只在「全部」里可见。
     */
    val rows: StateFlow<List<FavoriteRow>> = combine(rowsFlow, _group) { list, group ->
        when (group) {
            FavGroup.GOV -> list.filter { it.item.ref.source in POLICY_SOURCES }
            FavGroup.COURT -> list.filter { it.item.ref.source == SourceId.COURT }
            FavGroup.FLK -> list.filter { it.item.ref.source in FLK_SOURCES }
            FavGroup.CASE -> list.filter { it.item.ref.source == SourceId.CASE_LIBRARY }
            else -> list
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 各分组计数(「全部 · N」);口径必须与 [rows] 完全一致,否则数字与列表对不上 */
    val counts: StateFlow<List<Int>> = rowsFlow.map { list ->
        listOf(
            list.size,
            list.count { it.item.ref.source in POLICY_SOURCES },
            list.count { it.item.ref.source == SourceId.COURT },
            list.count { it.item.ref.source in FLK_SOURCES },
            list.count { it.item.ref.source == SourceId.CASE_LIBRARY },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(0, 0, 0, 0, 0))

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 手动触发一次串行校验(间隔 ≥2s,需求 D4) */
    fun checkNow(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _checking.value = true
            val updated = runCatching { updateCheckService.runColdStartCheck() }.getOrDefault(emptyList())
            _checking.value = false
            onDone(updated.size)
        }
    }

    fun remove(refKey: String) {
        viewModelScope.launch { favoriteRepository.remove(refKey) }
    }

    fun clearAll() {
        viewModelScope.launch { favoriteRepository.clearAll() }
    }

    /**
     * 分组的来源集合 —— 集中定义而非散落在 `when` 里,是为了让 [rows] 与 [counts]
     * 永远共用同一口径(两处写不一致时会出现「tab 上写 3、点进去 0 条」)。
     */
    private companion object {
        /** 「政策文件」:国务院/部委文件(中国政府网)+ 部门规章(公安部规章库) */
        val POLICY_SOURCES = setOf(SourceId.GOV_CN, SourceId.MPS_REG)

        /** 「官方数据库」:flk 原生源 + 早期直通源(兼容升级前已收藏的条目) */
        val FLK_SOURCES = setOf(SourceId.FLK, SourceId.FLK_WEB)
    }

    class Factory(
        private val favoriteRepository: FavoriteRepository,
        private val updateCheckService: UpdateCheckService,
        private val historyRepository: com.lawquery.data.repo.HistoryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FavoritesViewModel(favoriteRepository, updateCheckService, historyRepository) as T
    }
}
