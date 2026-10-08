package com.lawquery.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lawquery.data.local.SettingsStore
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.SearchRepository
import com.lawquery.data.repo.toLawRef
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SearchSort
import com.lawquery.domain.model.LawRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页(需求 F2 分类入口、F5 最近浏览、7.2.7 首启免责声明;参考图改版:今日更新)。
 */
class HomeViewModel(
    historyRepository: HistoryRepository,
    private val settingsStore: SettingsStore,
    favoriteRepository: FavoriteRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    /** 最近浏览(仅元数据,最多 50 条,首页展示前 8 条) */
    val recents = historyRepository.observeRecents(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoritesCount = favoriteRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _showDisclaimer = MutableStateFlow(false)
    val showDisclaimer: StateFlow<Boolean> = _showDisclaimer.asStateFlow()

    /** 今日更新:官方来源按发布日期倒序的最新文件(真实接口数据,非本地统计) */
    private val _latest = MutableStateFlow<List<LawRef>>(emptyList())
    val latest: StateFlow<List<LawRef>> = _latest.asStateFlow()

    /** 最近一次成功获取时间(「更新于 HH:mm」)与失败标记;每次进入首页实时刷新 */
    private val _latestUpdatedAt = MutableStateFlow<java.time.OffsetDateTime?>(null)
    val latestUpdatedAt: StateFlow<java.time.OffsetDateTime?> = _latestUpdatedAt.asStateFlow()

    private val _latestFailed = MutableStateFlow(false)
    val latestFailed: StateFlow<Boolean> = _latestFailed.asStateFlow()

    private var latestLoading = false

    init {
        viewModelScope.launch {
            settingsStore.disclaimerAcknowledged.collect { ack ->
                _showDisclaimer.value = !ack
            }
        }
        loadLatest()
    }

    /**
     * 「今日更新」实时机制:空关键词 + 发布日期倒序,实时从各官方源获取最新文件。
     * 每次进入首页都会调用;成功记录获取时间,失败置失败标记供 UI 重试。
     */
    fun loadLatest() {
        if (latestLoading) return
        latestLoading = true
        viewModelScope.launch {
            try {
                val agg = searchRepository.search(
                    SearchQuery(keyword = "", sort = SearchSort.PUBLISH_DATE_DESC, page = 1, pageSize = 6)
                )
                if (agg.items.isNotEmpty()) {
                    _latest.value = agg.items.take(3)
                    _latestUpdatedAt.value = java.time.OffsetDateTime.now()
                    _latestFailed.value = false
                } else if (_latest.value.isEmpty()) {
                    // 无结果:源全部失败(离线/受限)时标记,允许重试
                    _latestFailed.value = agg.offline || agg.degraded.isNotEmpty()
                } else {
                    _latestFailed.value = false
                }
            } finally {
                latestLoading = false
            }
        }
    }

    fun acknowledgeDisclaimer() {
        viewModelScope.launch { settingsStore.acknowledgeDisclaimer() }
    }

    class Factory(
        private val historyRepository: HistoryRepository,
        private val settingsStore: SettingsStore,
        private val favoriteRepository: FavoriteRepository,
        private val searchRepository: SearchRepository,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(historyRepository, settingsStore, favoriteRepository, searchRepository) as T
    }
}
