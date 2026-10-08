package com.lawquery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lawquery.R

private data class NoticeItem(
    val icon: ImageVector,
    val iconBg: Color,
    val iconFg: Color,
    val titleRes: Int,
    val bodyRes: Int,
)

/**
 * 消息通知页(单开界面):集中展示应用的使用规则、声明与说明,
 * 用户打开即可直观了解使用规则等事项(用户要求:通知单开一个界面)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
) {
    val notices = listOf(
        NoticeItem(Icons.Filled.Gavel, Color(0xFFE3F0FF), Color(0xFF3E7BFA), R.string.notif_item_rules_title, R.string.notif_item_rules_body),
        NoticeItem(Icons.Filled.PrivacyTip, Color(0xFFDCF5E7), Color(0xFF27A868), R.string.notif_item_privacy_title, R.string.notif_item_privacy_body),
        NoticeItem(Icons.Filled.Info, Color(0xFFFFE7C2), Color(0xFF8A5A00), R.string.notif_item_sources_title, R.string.notif_item_sources_body),
        NoticeItem(Icons.Filled.Update, Color(0xFFE7E3FD), Color(0xFF6C5CE7), R.string.notif_item_realtime_title, R.string.notif_item_realtime_body),
        NoticeItem(Icons.Filled.Star, Color(0xFFFFF1DC), Color(0xFFF5A623), R.string.notif_item_favorite_title, R.string.notif_item_favorite_body),
        NoticeItem(Icons.Filled.NewReleases, Color(0xFFFFE5E3), Color(0xFFF25C54), R.string.notif_item_version_title, R.string.notif_item_version_body),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = { Text(stringResource(R.string.notif_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column {
                        Text(
                            stringResource(R.string.notif_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(notices.size) { index ->
                val item = notices[index]
                NoticeCard(item)
            }
            item(key = "bottom") {
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun NoticeCard(item: NoticeItem) {
    androidx.compose.material3.Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(item.iconBg, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = item.iconFg,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(item.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
