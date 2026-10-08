package com.lawquery.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lawquery.ui.home.HomeViewModel
import com.lawquery.ui.search.SearchViewModel

/**
 * 手动 DI 的 ViewModel 工厂(项目文档 2.1:不引入 Hilt)。
 * 新增 ViewModel 时在 create() 中补一个分支即可。
 */
class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val c = container
        return when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(
                historyRepository = c.historyRepository,
                settingsStore = c.settingsStore,
                favoriteRepository = c.favoriteRepository,
                searchRepository = c.searchRepository,
            )
            SearchViewModel::class.java -> SearchViewModel(
                searchRepository = c.searchRepository,
                historyRepository = c.historyRepository,
                networkMonitor = c.networkMonitor,
                favoriteRepository = c.favoriteRepository,
            )
            else -> throw IllegalArgumentException("未注册的 ViewModel:${modelClass.name}")
        } as T
    }
}
