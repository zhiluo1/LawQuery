package com.lawquery.ui.common

/** 跨层约定的 UI 状态(项目文档 4.3),与需求 F7 状态一一对应 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
    data object Empty : UiState<Nothing>
    data object Offline : UiState<Nothing>
    data class Error(val message: String? = null) : UiState<Nothing>
}
