package com.lawquery.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lawquery.R
import com.lawquery.util.SystemShare
import kotlinx.coroutines.launch

/**
 * 官方直通 WebView(需求 F3 / 7.4 / 验收 C8-C10):
 * 原生壳仅提供标题栏(来源域名)、进度条、刷新、分享链接、系统浏览器打开;
 * 不注入任何修改内容的 JS,不截取 DOM,不读取页面数据;
 * 白名单外域名一律拦截,仅允许在系统浏览器打开;禁用文件与内容访问。
 *
 * 需登录的官方源(人民法院案例库):额外提供底部「我已完成登录」操作区。
 * 点击仅触发宿主去官方接口复核会话(用 WebView 自己的 Cookie),不解析页面内容;
 * 复核成功才置位登录标记并进入原生浏览,失败则明确提示用户重新登录。
 * 复核回调会拿到当前 WebView 实例,宿主可从中读取会话 Cookie(不注入、不读 DOM)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficialWebScreen(
    url: String,
    title: String,
    keyword: String?,
    onBack: () -> Unit,
    /** 需登录源:复核官方会话是否建立(返回 true 表示凭据可用),null 表示普通直通页 */
    onMarkLinked: (suspend (WebView) -> Boolean)? = null,
    /** 需登录源的底部提示文案与按钮文案(缺省沿用人民法院案例库的既有文案) */
    loginHint: String? = null,
    loginDoneLabel: String? = null,
    /** 未检测到凭据时的提示文案(缺省沿用案例库文案) */
    loginFailedMessage: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(title) }
    var mainFrameError by remember { mutableStateOf(false) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var checking by remember { mutableStateOf(false) }
    var linked by remember { mutableStateOf(false) }

    val linkedMessage = stringResource(R.string.alk_marked_message)
    val failedMessage = loginFailedMessage ?: stringResource(R.string.alk_login_failed)
    val hintText = loginHint ?: stringResource(R.string.alk_login_done_hint)
    val doneLabel = loginDoneLabel ?: stringResource(R.string.alk_login_done)

    /** flk 为纯桌面布局(内容宽约 1200dp,无 @media 断点):
     * 用 WebView 原生 setInitialScale 缩放——无纹理尺寸限制、触摸/滚动/双指缩放全部原生正确 */
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val initialScalePercent = remember(url) {
        if (hostOf(url) == "flk.npc.gov.cn") {
            (config.screenWidthDp * 100f / 1200f).toInt().coerceIn(25, 45)
        } else 0
    }

    val webView = remember(url) {
        createWebView(context, initialScalePercent)
    }

    DisposableEffect(url) {
        webView.webViewClient = buildClient(
            onTitle = { t -> if (t.isNotBlank()) pageTitle = t },
            onPageStarted = { u, ok ->
                currentUrl = u
                mainFrameError = !ok
            },
            onBlocked = { blockedUrl = it },
            onFinished = { mainFrameError = false },
        )
        // WebChromeClient:真实加载进度 + 承接登录页可能通过 window.open 打开的新窗口
        webView.webChromeClient = buildChromeClient(
            onProgress = { p -> progress = p },
            onTitle = { t -> if (t.isNotBlank()) pageTitle = t },
            onCreateWindow = { msg ->
                val transport = msg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url.toString()
                                if (UrlWhitelist.isAllowed(request.url)) {
                                    webView.loadUrl(target)
                                } else {
                                    blockedUrl = target
                                }
                                return true
                            }
                        }
                    }
                    msg.sendToTarget()
                }
                true
            },
        )
        webView.loadUrl(url)
        onDispose { webView.destroy() }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    title = {
                        Column {
                            Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                hostOf(currentUrl),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.web_refresh))
                        }
                        IconButton(onClick = { SystemShare.share(context, currentUrl) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.web_share_link))
                        }
                        IconButton(onClick = { SystemShare.openInBrowser(context, currentUrl) }) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = stringResource(R.string.web_open_in_browser))
                        }
                    },
                )
                if (progress < 100) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        bottomBar = {
            when {
                onMarkLinked != null -> Surface(tonalElevation = 2.dp) {
                    Column {
                        Text(
                            if (linked) linkedMessage else hintText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (linked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                if (checking || linked) return@Button
                                checking = true
                                scope.launch {
                                    val ok = runCatching { onMarkLinked(webView) }.getOrDefault(false)
                                    checking = false
                                    if (ok) {
                                        linked = true
                                        snackbarHostState.showSnackbar(linkedMessage)
                                    } else {
                                        snackbarHostState.showSnackbar(failedMessage)
                                    }
                                }
                            },
                            enabled = !checking && !linked,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                when {
                                    linked -> stringResource(R.string.alk_marked)
                                    checking -> stringResource(R.string.settings_source_checking)
                                    else -> doneLabel
                                }
                            )
                        }
                    }
                }
                keyword != null -> Surface(tonalElevation = 2.dp) {
                    // 深链未带入关键词时的兜底引导(M0 结论:复制关键词方案)
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(keyword)) },
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.padding(end = 6.dp))
                        Text(
                            stringResource(R.string.web_copy_keyword) + ":" + keyword,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 「以下为官方页面实时内容」横幅(需求 F3)
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.web_banner),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize().weight(1f),
            )

            if (mainFrameError) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.web_error_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.web_error_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { mainFrameError = false; webView.reload() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                        OutlinedButton(onClick = { SystemShare.openInBrowser(context, currentUrl) }) {
                            Text(stringResource(R.string.web_open_in_browser))
                        }
                    }
                }
            }
        }
    }

    // 白名单拦截弹窗(需求 7.4 / 验收 C9)
    blockedUrl?.let { blocked ->
        AlertDialog(
            onDismissRequest = { blockedUrl = null },
            title = { Text(stringResource(R.string.web_blocked_title)) },
            text = { Text(stringResource(R.string.web_blocked_message, blocked)) },
            confirmButton = {
                TextButton(onClick = {
                    SystemShare.openInBrowser(context, blocked)
                    blockedUrl = null
                }) { Text(stringResource(R.string.web_open_in_browser)) }
            },
            dismissButton = {
                TextButton(onClick = { blockedUrl = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

private fun hostOf(url: String): String =
    runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)

private fun createWebView(
    context: android.content.Context,
    initialScalePercent: Int,
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // 官方直通安全配置(需求 7.4)
        settings.javaScriptEnabled = true   // 官网为 JS 渲染单页应用
        settings.domStorageEnabled = true
        // 认证页(如人民法院案例库 OAuth)可能通过 window.open 打开新窗口
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // 视口适配(需求 1.1:官网移动端适配一般)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        // 桌面布局站点(flk):用 WebView 原生初始缩放使整页适配屏宽,用户可双指放大细读。
        // 仅为显示层设置,不注入、不修改页面内容。
        // (注:不能用 View.scale + 放大布局——部分设备 GPU 纹理上限不足会整页空白)
        if (initialScalePercent > 0) setInitialScale(initialScalePercent)
        // 其余情况使用系统默认 UA,以浏览器角色访问官方页面(需求 3.1.4)
    }
}

/** WebChromeClient:真实加载进度(此前进度条恒为 0)+ 承接登录页 window.open 新窗口 */
private fun buildChromeClient(
    onProgress: (Int) -> Unit,
    onTitle: (String) -> Unit,
    onCreateWindow: (android.os.Message?) -> Boolean,
): android.webkit.WebChromeClient {
    return object : android.webkit.WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            onTitle(title.orEmpty())
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean = onCreateWindow(resultMsg)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildClient(
    onTitle: (String) -> Unit,
    onPageStarted: (String, Boolean) -> Unit,
    onBlocked: (String) -> Unit,
    onFinished: () -> Unit,
): WebViewClient {
    return object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            return when {
                request.url.scheme == "http" -> {
                    // 明文链接升级为 https 后在应用内打开(需求 7.4 仅 HTTPS)
                    view.loadUrl(target.replaceFirst("http://", "https://"))
                    true
                }
                UrlWhitelist.isAllowed(request.url) -> false // 白名单内:WebView 正常加载
                else -> {
                    onBlocked(target) // 白名单外:拦截并提示
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStarted(url, true)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            onTitle(view.title ?: "")
            onFinished()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                onPageStarted(request.url.toString(), false)
            }
        }
    }
}
