package com.lawquery.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lawquery.R

/**
 * F7 通用状态组件(需求 F7 / 项目文档 ui/common):
 * 加载中(骨架屏/居中进度,超 8s 提示)、空结果、离线、源故障、错误。
 */

@Composable
fun LoadingState(modifier: Modifier = Modifier, hint: String? = null) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8_000)
        slow = true
    }
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = hint ?: androidx.compose.ui.res.stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (slow) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.state_loading_slow),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 8) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .alpha(alpha)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.85f)
                            .height(16.dp)
                            .alpha(alpha)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .alpha(alpha)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, title: String, suggestion: String? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (suggestion != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                suggestion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun OfflineState(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    StateBlock(
        modifier = modifier,
        icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp)) },
        title = androidx.compose.ui.res.stringResource(R.string.state_offline_title),
        primaryAction = androidx.compose.ui.res.stringResource(R.string.state_offline_action) to onRetry,
    )
}

/**
 * 错误状态块。
 *
 * [fill] = false 时不占满高度,供调用方在下方继续追加说明与操作
 * (详情页的「查看原文」按钮就靠这个才不会被挤出屏幕)。
 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    message: String? = null,
    onRetry: () -> Unit,
    fill: Boolean = true,
) {
    StateBlock(
        modifier = modifier,
        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp)) },
        title = androidx.compose.ui.res.stringResource(R.string.state_error_title),
        subtitle = message,
        primaryAction = androidx.compose.ui.res.stringResource(R.string.action_retry) to onRetry,
        fill = fill,
    )
}

/**
 * 通用状态块(图标 + 标题 + 副标题 + 主操作)。
 *
 * [fill] 控制是否占满可用高度:
 * - `true`(默认)用于**独占整屏**的状态页(空列表、离线、加载失败);
 * - `false` 用于**与其它内容同屏**的场景 —— 详情页错误态下方还要接
 *   「失败原因说明」与「查看原文」按钮,若这里 `fillMaxSize` + 垂直居中,
 *   状态块会占满全高把下方内容挤出屏幕(实测:付费墙降级时按钮完全不可见)。
 */
@Composable
private fun StateBlock(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    primaryAction: Pair<String, () -> Unit>? = null,
    fill: Boolean = true,
) {
    Column(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (fill) Arrangement.Center else Arrangement.Top,
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (primaryAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = primaryAction.second) { Text(primaryAction.first) }
        }
    }
}

/** 源故障提示条(需求 F7:列表内嵌提示条) */
@Composable
fun SourceUnavailableBanner(messages: List<String>, modifier: Modifier = Modifier) {
    if (messages.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            messages.forEach { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}
