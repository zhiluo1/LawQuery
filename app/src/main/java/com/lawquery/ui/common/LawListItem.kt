package com.lawquery.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lawquery.R
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.theme.BrandSecondary
import com.lawquery.util.TimeText

/** 命中词高亮标题(需求 F1) */
@Composable
fun HighlightText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    maxLines: Int = 2,
    highlightColor: androidx.compose.ui.graphics.Color = BrandSecondary,
) {
    val annotated = buildAnnotatedString {
        append(text)
        val q = query.trim()
        if (q.isNotEmpty()) {
            var from = 0
            while (true) {
                val idx = text.indexOf(q, from, ignoreCase = true)
                if (idx < 0) break
                addStyle(
                    SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold),
                    idx,
                    idx + q.length,
                )
                from = idx + q.length
            }
        }
    }
    Text(annotated, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * 原生结果列表项(需求 F1):标题(命中词高亮)、发文字号、发文机关、
 * 发布日期、时效性徽标、来源标识。
 */
@Composable
fun LawListItem(
    ref: LawRef,
    query: String = "",
    updated: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            HighlightText(text = ref.title, query = query)
            Spacer(Modifier.height(6.dp))
            // 元信息只拼接**真实存在**的字段。
            // 人民法院案例库部分案例的官方属性串里取不到可靠的入库时间
            // (入库编号不是时间,详见 CaseInfoParser),此时 publishDate 为 null ——
            // 早前这里无条件 `append(TimeText.date(null))` 会渲染出一个「—」,
            // 让用户以为"这条数据缺日期"是 bug。直接不拼这一段。
            val meta = buildList {
                ref.docNumber?.takeIf { it.isNotBlank() }?.let { add(it) }
                ref.issuingAuthority.takeIf { it.isNotBlank() }?.let { add(it) }
                ref.publishDate?.let { add(TimeText.date(it)) }
            }.joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LawStatusBadge(ref.status)
                SourceBadge(ref.source)
                if (updated) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(14.dp),
                        )
                        Text(
                            stringResource(R.string.favorites_updated_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                trailing?.invoke()
            }
        }
    }
}
