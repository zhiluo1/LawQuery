package com.lawquery.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lawquery.R
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.UpdateCheckService
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.common.EmptyState
import com.lawquery.ui.theme.StarGold
import com.lawquery.util.TimeText

/**
 * 收藏页(需求 F5;参考图风格):分组 tabs、计数、收藏卡(标题+金标+时效徽标+文号/日期+阅读时间)。
 * 「阅读时间」来自最近浏览表联接,仅元数据、零新增字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favoriteRepository: FavoriteRepository,
    updateCheckService: UpdateCheckService,
    historyRepository: com.lawquery.data.repo.HistoryRepository,
    onOpenDetail: (LawRef) -> Unit,
) {
    val viewModel: FavoritesViewModel = viewModel(
        factory = FavoritesViewModel.Factory(favoriteRepository, updateCheckService, historyRepository),
    )
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = { Text(stringResource(R.string.favorites_title)) },
                actions = {
                    // 标语 + 品牌徽标(替代原「测试连接」按钮;源状态检测保留在设置页)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Balance,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.favorites_slogan),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // 分组 tabs(参考图)+ 计数
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val labels = listOf(
                    stringResource(R.string.fav_group_all),
                    stringResource(R.string.fav_group_gov),
                    stringResource(R.string.fav_group_court),
                    stringResource(R.string.fav_group_flk),
                    stringResource(R.string.fav_group_case),
                )
                labels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = group == index,
                        onClick = { viewModel.setGroup(index) },
                        label = {
                            Text(if (index == FavGroup.ALL) "$label · ${counts[0]}" else label)
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (rows.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.favorites_empty_title),
                    suggestion = stringResource(R.string.favorites_empty_suggestion),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.item.ref.key }) { row ->
                        FavoriteCard(
                            ref = row.item.ref,
                            readAtText = row.lastReadAt?.let {
                                stringResource(R.string.home_browsed_suffix, TimeText.relativeDate(it))
                            },
                            onOpen = { onOpenDetail(row.item.ref) },
                            onRemove = { pendingRemove = row.item.ref.key },
                        )
                    }
                }
            }
        }
    }

    pendingRemove?.let { key ->
        val target = rows.firstOrNull { it.item.ref.key == key }
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.favorites_remove)) },
            text = { Text(stringResource(R.string.favorites_remove_confirm, target?.item?.ref?.title ?: "")) },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(key); pendingRemove = null }) {
                    Text(stringResource(R.string.favorites_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.favorites_cancel)) }
            },
        )
    }
}

private fun statusTextOf(status: com.lawquery.domain.model.LawStatus): String = when (status) {
    com.lawquery.domain.model.LawStatus.CURRENT -> "现行有效"
    com.lawquery.domain.model.LawStatus.REVISED -> "已修订"
    com.lawquery.domain.model.LawStatus.REPEALED -> "已废止"
    com.lawquery.domain.model.LawStatus.PENDING -> "尚未生效"
}

@Composable
private fun FavoriteCard(
    ref: LawRef,
    readAtText: String?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ref.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.favorites_title),
                    tint = StarGold,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.lawquery.ui.common.LawStatusBadge(ref.status)
                val meta = listOfNotNull(ref.docNumber, TimeText.date(ref.publishDate)).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (readAtText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    readAtText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.favorites_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
