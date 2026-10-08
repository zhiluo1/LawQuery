package com.lawquery.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络状态监视(需求 F7 离线页:监听网络恢复自动重试)。
 *
 * 合规说明(需求 7.2.8/E5:权限清单仅 INTERNET):本类不申请 ACCESS_NETWORK_STATE,
 * 在未授予该权限的系统上相关调用会失败——全部以 runCatching 守卫并退化为
 * "假定在线";离线判定主要由请求异常(UnknownHostException → OFFLINE)驱动,
 * 离线页始终提供手动重试入口。
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        runCatching { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }.getOrNull()

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
        }

        override fun onLost(network: Network) {
            _online.value = currentlyOnline()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    init {
        runCatching {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val cm = connectivityManager ?: return@runCatching true
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
