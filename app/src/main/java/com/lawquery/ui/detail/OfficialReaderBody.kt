package com.lawquery.ui.detail

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lawquery.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 官方阅读器正文区(flk 正文呈现)。
 *
 * 国家法律法规数据库的正文以 OFD/PDF 版式文件下发,官方**不下发可抽取的文本层**
 * (实测阅读器 `/reader/text` 的 `areas` 恒为空,页面文字由 SVG 渲染),因此正文
 * 只能在官方阅读器里看。本组件把该阅读器嵌进详情页的正文区。
 *
 * ## 缩放为什么默认 100%
 *
 * 官方页面是 A4 版面(实测 SVG viewBox 793.7×1122.5 CSS px),正文约 14px。
 * 早期版本按 `屏宽/900` 算初始缩放(360dp 屏 → 40%),把**已经自适应的移动版
 * 阅读器二次缩小**,正文只剩 6px 左右,几乎无法阅读。现在默认 100% 可直接读。
 *
 * ## 缩放是怎么施加的(踩过的坑,勿改回)
 *
 * 官方阅读器禁用了 viewport 用户缩放,`WebView.zoomBy` 会被**静默吞掉**:
 * 状态与倍率数字都正常变化,页面像素却一动不动(实测 100% → 136% 无视差)。
 * 因此改为用 `evaluateJavascript` 给文档根元素设 CSS `zoom`(见 [applyScale])。
 *
 * 之所以不靠 `setInitialScale`:它只在 `loadUrl` 之前生效,改它就得 reload ——
 * 而阅读器地址里的 `_wr_sign` 签名**有时效**,反复 reload 有失效风险。
 *
 * [ZoomHolder] 记住上一次已施加的倍率,使每次施加都是「设为绝对值」而非
 * 相对累乘,避免重复点击导致倍率失控。
 *
 * ⚠️ 记「已施加倍率」**不能**用 `view.setTag(key, value)`:该 API 要求 key 是
 * 已声明的 `android:id` 资源,传任意整数会抛 `IllegalArgumentException`。
 * 早期版本正是踩了这个坑,又把异常包在 `runCatching` 里静默吞掉,
 * 结果点「整页」「+/-」毫无反应。现在改用 [ZoomHolder] 持有状态。
 *
 * 安全配置与 [com.lawquery.ui.web.OfficialWebScreen] 一致:不注入修改内容的 JS、
 * 不读 DOM,只作显示载体;域名白名单校验,关闭文件与内容访问,明文 http 升级 https。
 */
private const val TAG = "FlkReader"

/** 官方 A4 版面宽度(CSS px,实测 SVG viewBox 宽 793.73) */
private const val PAGE_CSS_WIDTH = 794f

/** 缩放下限(再小就看不清了) */
private const val MIN_ZOOM = 0.6f

/** 缩放上限(2.4 倍足够看小字注释) */
private const val MAX_ZOOM = 2.4f

/** 「+ / −」每档倍率 */
private const val ZOOM_STEP = 1.15f

/** 施加后延迟复查的间隔(见下方说明) */
private val REAPPLY_DELAYS_MS = longArrayOf(600L, 1500L, 3000L)

/** 记住 WebView 上一次被施加的缩放倍率(不能用 View.tag,见类注释) */
private class ZoomHolder {
    var applied: Float = 1f
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficialReaderBody(
    readerUrl: String,
    modifier: Modifier = Modifier,
    /** 初始缩放倍率(1.0 = 100%);父级可记忆用户上次选择 */
    initialZoom: Float = 1f,
    /** 缩放变化回调,用于把用户选择保留到详情页会话内 */
    onZoomChange: (Float) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var zoom by remember(readerUrl) { mutableStateOf(initialZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    val holder = remember(readerUrl) { ZoomHolder() }

    val webView = remember(readerUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true      // 阅读器是 JS 渲染
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                // 保留系统缩放能力:用户双指放大时仍可用(阅读器页面自身禁用了它,
                // 这里放开 WebView 侧的开关,配合 CSS zoom 生效)
                setSupportZoom(true)
                displayZoomControls = false
                // 基准 100%;实际倍率由 applyScale 用 CSS zoom 施加
                setInitialScale(100)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    return when {
                        url.scheme == "http" -> {
                            view.loadUrl(url.toString().replaceFirst("http://", "https://"))
                            true
                        }
                        // 只允许官方阅读器域(含其静态资源),白名单外一律拦下
                        com.lawquery.ui.web.UrlWhitelist.isAllowed(url) -> false
                        else -> true
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // 阅读器异步渲染:load 完成后才逐步插入 SVG 与文字节点。
                    // 只施加一次的话,后插入的节点仍按原尺寸渲染 ——
                    // 表现为「倍率数字变了、画面没变」。故分几轮重施(幂等)。
                    holder.applied = 1f
                    REAPPLY_DELAYS_MS.forEach { delay ->
                        view.postDelayed({
                            // 期间用户可能已手动改过倍率,此时不该覆盖
                            if (view.width > 0) {
                                holder.applied = 1f
                                applyScale(view, holder, zoom)
                            }
                        }, delay)
                    }
                }
            }
        }
    }

    DisposableEffect(readerUrl) {
        webView.loadUrl(readerUrl)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    // 缩放变化即时生效,不重载(签名有时效,重载有失效风险)
    LaunchedEffect(zoom) {
        applyScale(webView, holder, zoom)
    }

    /**
     * 「整页」倍率 = WebView 可视宽度 / A4 版面宽度。
     *
     * 用 WebView 的**真实像素宽度**(撑满布局,MATCH_PARENT)而不是屏幕宽估算 ——
     * 分屏、横屏、有导航栏时两者并不相等。
     */
    fun fitZoom(): Float {
        val w = webView.width
        if (w <= 0) return zoom
        return (w / PAGE_CSS_WIDTH).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun apply(next: Float) {
        zoom = next.coerceIn(MIN_ZOOM, MAX_ZOOM)
        onZoomChange(zoom)
    }

    Column(modifier) {
        // 工具行:整页 / 缩小 / 倍率 / 放大 + 一句说明
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { apply(fitZoom()) }) {
                Icon(Icons.Filled.FitScreen, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.detail_reader_zoom_fit),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = { apply(zoom / ZOOM_STEP) },
                enabled = zoom > MIN_ZOOM + 0.01f,
            ) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.detail_reader_zoom_out),
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { apply(zoom * ZOOM_STEP) },
                enabled = zoom < MAX_ZOOM - 0.01f,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.detail_reader_zoom_in),
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.detail_reader_notice_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                // WebView 拿到尺寸后补施加一次:工具行可能在它完成布局前就被点击
                .onSizeChanged { applyScale(webView, holder, zoom) }
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 把目标倍率施加到 WebView。
 *
 * ## 为什么必须走 CSS `zoom` 而不是 [WebView.zoomBy]
 *
 * 官方阅读器(数科轻阅读)的 viewport 禁用了用户缩放,`zoomBy` 会被静默吞掉:
 * 实测点击「整页」后状态从 100% 变 136%、`delta` 计算正确,但**页面像素毫无变化**,
 * 且 `zoomBy` 的返回值在 Kotlin 侧是 `Unit`,连"是否成功"都观测不到。
 * 故改为直接给文档根元素设 CSS `zoom` —— 这是**显示层**控制,只改变呈现比例,
 * 不读取、不改写官方正文,与"不解析官方内容"的约束不冲突。
 *
 * 只在根元素上设一个 `zoom` 属性,幂等:重复施加同倍率不会叠加。
 */
private fun applyScale(view: WebView, holder: ZoomHolder, target: Float) {
    if (view.width <= 0 || view.height <= 0) {
        // 还没完成布局,跳过本次;onPageFinished / onSizeChanged 会补
        return
    }
    val delta = target / holder.applied
    if (abs(delta - 1f) < 0.01f) return
    val percent = (target * 100).roundToInt()
    // 先清空再设:若上一轮已设过 zoom,直接覆盖在部分浏览器上会与已有缩放叠加。
    // 幂等保证:同一目标倍率重复施加,结果一致。
    val js = "try{var d=document.documentElement,b=document.body;" +
        "if(d)d.style.zoom='';if(b)b.style.zoom='';" +
        "var z='$percent%';if(d)d.style.zoom=z;if(b)b.style.zoom=z}catch(e){}"
    try {
        view.evaluateJavascript(js, null)
        holder.applied = target
        Log.i(TAG, "apply zoom=$percent% (delta=$delta)")
    } catch (e: Throwable) {
        Log.w(TAG, "apply zoom failed target=$target", e)
    }
}