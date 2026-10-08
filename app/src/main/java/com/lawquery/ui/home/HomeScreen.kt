package com.lawquery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawquery.R
import com.lawquery.data.repo.toLawRef
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawCategory
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.browse.categoryLabel
import com.lawquery.ui.common.LawStatusBadge
import com.lawquery.ui.theme.CatAdminBg
import com.lawquery.ui.theme.CatAdminFg
import com.lawquery.ui.theme.CatConstitutionBg
import com.lawquery.ui.theme.CatConstitutionFg
import com.lawquery.ui.theme.CatCaseBg
import com.lawquery.ui.theme.CatCaseFg
import com.lawquery.ui.theme.CatDeptBg
import com.lawquery.ui.theme.CatDeptFg
import com.lawquery.ui.theme.CatIntlBg
import com.lawquery.ui.theme.CatIntlFg
import com.lawquery.ui.theme.CatJudicialBg
import com.lawquery.ui.theme.CatJudicialFg
import com.lawquery.ui.theme.CatLawBg
import com.lawquery.ui.theme.CatLawFg
import com.lawquery.ui.theme.CatLocalBg
import com.lawquery.ui.theme.CatLocalFg
import com.lawquery.ui.theme.CatSupervisionBg
import com.lawquery.ui.theme.CatSupervisionFg
import com.lawquery.ui.theme.GradientEnd
import com.lawquery.ui.theme.GradientStart
import com.lawquery.ui.theme.StarGold
import com.lawquery.ui.theme.StatusCurrentColor
import com.lawquery.util.TimeText
import java.time.LocalDate

/** 分类展示元数据:彩色图标(参考图风格)+ 跳转目标 */
private data class CategoryUi(
    val category: LawCategory,
    val icon: ImageVector,
    val label: String,
    val bg: Color,
    val fg: Color,
)

@Composable
private fun rememberCategoryUiList(): List<CategoryUi> = listOf(
    CategoryUi(LawCategory.CONSTITUTION, Icons.Filled.AccountBalance, stringResource(R.string.category_constitution), CatConstitutionBg, CatConstitutionFg),
    CategoryUi(LawCategory.LAW, Icons.Filled.Balance, stringResource(R.string.category_law), CatLawBg, CatLawFg),
    CategoryUi(LawCategory.ADMIN_REG, Icons.Filled.Policy, stringResource(R.string.category_admin_reg), CatAdminBg, CatAdminFg),
    CategoryUi(LawCategory.SUPERVISION, Icons.Filled.Security, stringResource(R.string.category_supervision), CatSupervisionBg, CatSupervisionFg),
    CategoryUi(LawCategory.JUDICIAL, Icons.Filled.Gavel, stringResource(R.string.category_judicial), CatJudicialBg, CatJudicialFg),
    CategoryUi(LawCategory.LOCAL, Icons.Filled.MenuBook, stringResource(R.string.category_local), CatLocalBg, CatLocalFg),
    CategoryUi(LawCategory.STATE_COUNCIL, Icons.Filled.Description, stringResource(R.string.category_state_council), CatDeptBg, CatDeptFg),
    // 人民法院案例库:原生通道需先完成官方登录(未登录时先弹说明窗,再进登录页)
    // 图标用「奖章」:寓意官方收录的指导性/参考案例(精选案例),
    // 形状为圆形徽章,与天平/法槌/盾牌/书卷/文书等既有图标在缩略尺寸下也能一眼区分
    CategoryUi(LawCategory.CASE_LIBRARY, Icons.Filled.WorkspacePremium, stringResource(R.string.category_case_library), CatCaseBg, CatCaseFg),
)

/** 热门搜索标签(静态推荐词,点击即发起真实搜索) */
private val hotSearchWords = listOf("劳动合同", "公司法", "民法典", "行政处罚")

/**
 * 首页(参考图风格):标题栏+通知、搜索入口、渐变检索卡+热门搜索、
 * 彩色分类宫格(等宽列对齐)、今日更新(每次进入实时获取)、最近浏览。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSearch: () -> Unit,
    onOpenSearchWithQuery: (String) -> Unit,
    onOpenCategory: (LawCategory) -> Unit,
    /** 人民法院案例库是否已完成官方登录(未登录时先弹说明,再进登录页) */
    caseLibraryLinked: Boolean = false,
    /** 打开人民法院案例库官方登录页(应用内 WebView,对接官网登录界面) */
    onOpenCaseLogin: () -> Unit = {},
    onOpenRecent: (LawRef) -> Unit,
    onOpenRecents: () -> Unit,
    onOpenLatestUpdates: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val latestUpdatedAt by viewModel.latestUpdatedAt.collectAsStateWithLifecycle()
    val latestFailed by viewModel.latestFailed.collectAsStateWithLifecycle()
    val showDisclaimer by viewModel.showDisclaimer.collectAsStateWithLifecycle()
    val categories = rememberCategoryUiList()

    var showAlkLoginNotice by remember { mutableStateOf(false) }

    /**
     * 打开分类。
     *
     * 六个法规分类由国家法律法规数据库原生供数(免登录)、国务院文件走中国政府网,
     * 都不再需要「本应用不收录、只能跳官方页」的告示 —— 该告示是接入前的遗留文案,
     * 与现在的实际接入方式矛盾,已移除。
     */
    fun openCategory(cat: LawCategory) = onOpenCategory(cat)

    /** 案例库入口:已登录直接进原生列表;未登录先说明政策限制与登录方式 */
    fun openCaseLibrary() {
        if (caseLibraryLinked) {
            onOpenCategory(LawCategory.CASE_LIBRARY)
        } else {
            showAlkLoginNotice = true
        }
    }

    // 实时更新机制:每次进入首页都重新从官方源获取「今日更新」
    LaunchedEffect(Unit) { viewModel.loadLatest() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        // 标题栏:首页 + 通知(单开通知界面:使用规则与声明)
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.nav_home),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onOpenNotifications) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.home_notifications),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // 时段问候(替换原先重复的顶部搜索框:搜索入口由下方检索卡承担)
        item {
            val now = remember { java.time.LocalDateTime.now() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(
                    text = TimeText.greetingForHour(now.hour),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.home_greeting_sub,
                        now.format(
                            java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 智能法律检索渐变卡(参考图:渐变底 + 内嵌白色搜索胶囊 + 热门搜索)
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(
                        Brush.horizontalGradient(listOf(GradientStart, GradientEnd)),
                        RoundedCornerShape(20.dp),
                    )
                    .clickable(onClick = onOpenSearch)
                    .padding(16.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.home_hero_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.home_hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.home_hero_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.search_action),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // FlowRow:一行放不下时整体换行,避免末尾标签被压缩成竖排单字(如“行政处罚”)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.home_hot_search),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        hotSearchWords.forEach { word ->
                            Text(
                                word,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                                    .clickable { onOpenSearchWithQuery(word) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // 彩色分类宫格(单卡内固定 4 列等宽,三行磁贴严格列对齐)
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    val rows = categories.chunked(4)
                    rows.forEach { rowItems ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            rowItems.forEach { cat ->
                                CategoryTile(cat, Modifier.weight(1f)) {
                                    if (cat.category == LawCategory.CASE_LIBRARY) {
                                        openCaseLibrary()
                                    } else {
                                        openCategory(cat.category)
                                    }
                                }
                            }
                            repeat(4 - rowItems.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // 今日更新(每次进入首页实时从官方源获取;带获取时间与失败重试)
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_latest_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(8.dp))
                val updatedAt = latestUpdatedAt
                when {
                    updatedAt != null -> Text(
                        stringResource(R.string.home_latest_updated_at, TimeText.dateTime(updatedAt).substringAfter(' ')),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    latestFailed -> Text(
                        stringResource(R.string.home_latest_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (latestFailed) {
                    TextButton(onClick = viewModel::loadLatest) {
                        Text(stringResource(R.string.action_retry))
                    }
                } else {
                    // 「更多」打开今日更新完整列表页(此前误跳国务院及部委文件分类)
                    TextButton(onClick = onOpenLatestUpdates) {
                        Text(stringResource(R.string.home_recent_more))
                    }
                }
            }
        }
        if (latest.isEmpty() && !latestFailed) {
            item {
                Text(
                    stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else if (latestFailed && latest.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.state_offline_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            items(latest, key = { "latest-" + it.key }) { ref ->
                LatestItem(ref) { onOpenRecent(ref) }
            }
        }

        // 最近浏览(首页仅展示前 3 条;「更多」进入独立浏览历史页)
        item {
            SectionHeader(stringResource(R.string.home_recent_title)) { onOpenRecents() }
        }
        if (recents.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            items(recents.take(3), key = { it.refKey }) { recent ->
                RecentItem(recent) { onOpenRecent(recent.toLawRef()) }
            }
        }

        item {
            Text(
                stringResource(R.string.home_footer_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { /* 首次必须确认 */ },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeDisclaimer) {
                    Text(stringResource(R.string.disclaimer_ack))
                }
            },
        )
    }

    // 人民法院案例库:政策限制与登录方式说明(用户本人登录,应用不代登录)
    if (showAlkLoginNotice) {
        AlertDialog(
            onDismissRequest = { showAlkLoginNotice = false },
            title = { Text(stringResource(R.string.alk_login_notice_title)) },
            text = { Text(stringResource(R.string.alk_login_notice_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showAlkLoginNotice = false
                    onOpenCaseLogin()
                }) { Text(stringResource(R.string.alk_login_notice_open)) }
            },
            dismissButton = {
                TextButton(onClick = { showAlkLoginNotice = false }) {
                    Text(stringResource(R.string.alk_login_notice_ack))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onMore) { Text(stringResource(R.string.home_recent_more)) }
    }
}

/** 今日更新条目:标题 + 新标 + 文号/发布日期 */
@Composable
private fun LatestItem(ref: LawRef, onClick: () -> Unit) {
    val isNew = ref.publishDate != null && ref.publishDate.isAfter(LocalDate.now().minusDays(7))
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ref.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isNew) {
                    Text(
                        stringResource(R.string.home_new_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(ref.docNumber, TimeText.date(ref.publishDate)).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 最近浏览条目:标题 + 时效性徽标 + 相对浏览时间 */
@Composable
private fun RecentItem(recent: com.lawquery.data.local.RecentEntity, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                recent.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(
                    R.string.home_browsed_suffix,
                    TimeText.relativeDate(recent.browsedAt),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 彩色分类磁贴:等宽列内居中,跨行列严格对齐 */
@Composable
private fun CategoryTile(cat: CategoryUi, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(cat.bg, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(cat.icon, contentDescription = cat.label, tint = cat.fg, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            cat.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
