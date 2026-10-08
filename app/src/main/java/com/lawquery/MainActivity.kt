package com.lawquery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lawquery.data.local.DarkModePref
import com.lawquery.di.AppContainer
import com.lawquery.ui.AppRoot
import com.lawquery.ui.theme.LawQueryTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 单 Activity 承载全部 Compose 页面(项目文档 2.1)。
 * 路由见 ui/AppNavHost(M1 Navigation Compose 骨架:首页/搜索/浏览/详情/WebView/收藏/设置)。
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LawQueryApplication).container
        startColdStartFavoriteCheck(container)
        restoreAlkSessionIfNeeded(container)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            // 深色模式(需求 F6):0=跟随系统 / 1=浅色 / 2=深色,持久化并在根部即时生效
            val darkModePref by container.settingsStore.darkMode.collectAsStateWithLifecycle(
                initialValue = DarkModePref.SYSTEM,
            )
            val darkTheme = when (darkModePref) {
                DarkModePref.LIGHT -> false
                DarkModePref.DARK -> true
                else -> isSystemInDarkTheme()
            }
            // 切换时同步系统栏对比度与窗口背景,避免旧样式残留
            LaunchedEffect(darkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                )
                window.decorView.setBackgroundColor(
                    if (darkTheme) 0xFF101418.toInt() else 0xFFFAFAFC.toInt()
                )
            }
            LawQueryTheme(darkTheme = darkTheme) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    AppRoot(container = container, widthSizeClass = windowSizeClass.widthSizeClass)

                    // 品牌启动页:打开 APP 后展示 2~3s(需求:图片 + 正在加载法律数据库 + 加载圈)
                    var showSplash by rememberSaveable { mutableStateOf(true) }
                    if (showSplash) {
                        LaunchedEffect(Unit) {
                            delay(2600)
                            showSplash = false
                        }
                        SplashOverlay()
                    }
                }
            }
        }
    }

    /**
     * 冷启动时修复人民法院案例库的本地会话(需求:一次登录后长期自动对接)。
     *
     * 为什么需要这一步:应用内 WebView 的**官方会话 Cookie 本身是跨进程重启存活的**
     * (落在应用数据目录里,除非用户清除应用数据或卸载)。而我们加密落盘的凭据
     * 一旦丢失(备份恢复到别的设备、Keystore 密钥失效、升级时被清),案例库就会
     * 退回「未登录」,逼用户重新登录一遍。
     *
     * 这里在启动时检查:本地没有 Cookie、但 WebView Cookie 罐里还留着官方会话时,
     * 用它换一次新的 userToken —— 相当于把「一次登录」真正变成长期自动对接。
     *
     * 只在**本地确实没有 Cookie** 时才动作,已有会话不发起任何多余请求。
     */
    private fun restoreAlkSessionIfNeeded(container: AppContainer) {
        val session = container.alkSession
        if (session.hasCookie()) return
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance()
                .getCookie(com.lawquery.ui.web.DeepLinks.ALK_BASE)
        }.getOrNull()
        if (cookie.isNullOrBlank()) return

        container.appScope.launch {
            session.updateCookie(cookie)
            val ok = container.caseLibrarySource.syncSession()
            if (ok) {
                container.settingsStore.setAlkLinked(true)
                android.util.Log.i("CaseLibrary", "冷启动自愈:已用 WebView Cookie 恢复会话")
            } else {
                // WebView 里的官方会话也已过期,清掉以免每次启动都白跑一次换 token
                session.clear()
                android.util.Log.i("CaseLibrary", "冷启动自愈:WebView Cookie 亦已失效,需重新登录")
            }
        }
    }

    /**
     * 冷启动后台串行校验收藏元数据(需求 F4/6.2/D4):
     * 串行、间隔 ≥2s、失败静默;结果驱动收藏列表「已更新」徽标。
     */
    private fun startColdStartFavoriteCheck(container: AppContainer) {
        container.appScope.launch {
            runCatching { container.updateCheckService.runColdStartCheck() }
        }
    }
}

/** 品牌启动页:海报图全屏铺满,「正在加载法律数据库…」+ 加载圈叠加在图片底部 */
@Composable
private fun SplashOverlay() {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.splash_image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        ) {
            Text(
                stringResource(R.string.splash_loading),
                color = Color(0xFFE7E3FD),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(10.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.5.dp,
                color = Color(0xFFBDB4FF),
            )
        }
    }
}
