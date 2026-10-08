package com.lawquery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lawquery.data.local.SettingsStore
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.SourceStatusRepository
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置(需求 F6):阅读(字号/深色跟随系统)、数据源清单与健康状态、
 * 关于(版本/开源许可/免责声明/隐私政策)、清除数据。
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val historyRepository: HistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val sourceStatusRepository: SourceStatusRepository,
) : ViewModel() {

    val fontSizeIndex: StateFlow<Int> = settingsStore.fontSizeIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2)

    /** 深色模式(需求 F6):0=跟随系统 / 1=浅色 / 2=深色,持久化并在 Activity 根部即时生效 */
    val darkMode: StateFlow<Int> = settingsStore.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 行间距档位 0..2(紧凑/适中/宽松),全局记忆 */
    val lineSpacingIndex: StateFlow<Int> = settingsStore.lineSpacingIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    val sourceStatuses: StateFlow<Map<SourceId, SourceHealth>> =
        sourceStatusRepository.statuses
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _checkingSource = MutableStateFlow<SourceId?>(null)
    val checkingSource: StateFlow<SourceId?> = _checkingSource.asStateFlow()

    fun setFontSize(index: Int) {
        viewModelScope.launch { settingsStore.setFontSizeIndex(index) }
    }

    fun setDarkMode(mode: Int) {
        viewModelScope.launch { settingsStore.setDarkMode(mode) }
    }

    fun setLineSpacing(index: Int) {
        viewModelScope.launch { settingsStore.setLineSpacingIndex(index) }
    }

    fun checkSource(sourceId: SourceId) {
        viewModelScope.launch {
            _checkingSource.value = sourceId
            runCatching { sourceStatusRepository.check(sourceId) }
            _checkingSource.value = null
        }
    }

    fun clearHistory(onDone: () -> Unit = {}) {
        viewModelScope.launch { historyRepository.clearHistory(); onDone() }
    }

    fun clearRecents(onDone: () -> Unit = {}) {
        viewModelScope.launch { historyRepository.clearRecents(); onDone() }
    }

    fun clearFavorites(onDone: () -> Unit = {}) {
        viewModelScope.launch { favoriteRepository.clearAll(); onDone() }
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val historyRepository: HistoryRepository,
        private val favoriteRepository: FavoriteRepository,
        private val sourceStatusRepository: SourceStatusRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(settingsStore, historyRepository, favoriteRepository, sourceStatusRepository) as T
    }
}
