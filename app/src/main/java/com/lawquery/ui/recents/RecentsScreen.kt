package com.lawquery.ui.recents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawquery.R
import com.lawquery.data.local.RecentEntity
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.toLawRef
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.common.EmptyState
import com.lawquery.ui.common.LawStatusBadge
import com.lawquery.ui.common.SourceBadge
import com.lawquery.util.TimeText

/**
 * 浏览历史独立页(需求 F5「最近浏览」):展示全部最近浏览记录,
 * 仅元数据(标题/来源/时效性等,≤50 条滚动淘汰),点击进入详情;不存任何正文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    historyRepository: HistoryRepository,
    onBack: () -> Unit,
    onOpenDetail: (LawRef) -> Unit,
) {
    val recents by historyRepository.observeRecents(50)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = { Text(stringResource(R.string.recents_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (recents.isEmpty()) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                EmptyState(title = stringResource(R.string.home_recent_empty))
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                items(recents, key = { it.refKey }) { recent ->
                    RecentHistoryItem(recent) { onOpenDetail(recent.toLawRef()) }
                }
                item(key = "footer") {
                    Text(
                        stringResource(R.string.home_footer_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

/** 浏览历史条目:标题 + 相对浏览时间 + 来源与时效性徽标 */
@Composable
private fun RecentHistoryItem(recent: RecentEntity, onClick: () -> Unit) {
    val ref = recent.toLawRef()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recent.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.home_browsed_suffix, TimeText.relativeDate(recent.browsedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LawStatusBadge(ref.status)
                SourceBadge(ref.source)
            }
        }
    }
}
