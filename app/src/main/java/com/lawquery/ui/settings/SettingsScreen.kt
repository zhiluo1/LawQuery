package com.lawquery.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lawquery.BuildConfig
import com.lawquery.R
import com.lawquery.data.local.DarkModePref
import com.lawquery.data.local.SettingsStore
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.SourceStatusRepository
import com.lawquery.data.source.HealthStatus
import com.lawquery.data.source.SourceId
import com.lawquery.ui.theme.ReadingStyle
import com.lawquery.ui.theme.StatusCurrentColor
import com.lawquery.ui.theme.StatusRepealedColor
import com.lawquery.ui.theme.StatusRevisedColor
import com.lawquery.update.UpdateManager
import com.lawquery.update.UpdateState
import com.lawquery.util.TimeText
import kotlinx.coroutines.launch

/**
 * 设置与关于(需求 F6 / E4 / E5)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    historyRepository: HistoryRepository,
    favoriteRepository: FavoriteRepository,
    sourceStatusRepository: SourceStatusRepository,
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(settingsStore, historyRepository, favoriteRepository, sourceStatusRepository),
    )
    val fontSizeIndex by viewModel.fontSizeIndex.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val lineSpacingIndex by viewModel.lineSpacingIndex.collectAsStateWithLifecycle()
    val statuses by viewModel.sourceStatuses.collectAsStateWithLifecycle()

    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var cacheText by remember { mutableStateOf(cacheSizeText(context)) }

    // 检查更新(需求:自更新机制,用户主动触发)
    val scope = rememberCoroutineScope()
    val updateManager = remember { UpdateManager(context) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    fun checkUpdate() {
        updateState = UpdateState.Checking
        scope.launch { updateState = updateManager.check() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 顶部渐变横幅(嵌入参考图)
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.settings_banner),
                contentDescription = stringResource(R.string.settings_banner_title),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alignment = androidx.compose.ui.Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f)
                    .clip(MaterialTheme.shapes.large),
            )
            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.settings_group_reading))
            // 文字大小
            Text(
                stringResource(R.string.settings_font_size_row),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingStyle.levelLabels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = fontSizeIndex == index,
                        onClick = { viewModel.setFontSize(index) },
                        label = { Text(label) },
                    )
                }
            }
            // 字号实时预览(需求 F6:全局记忆、即时生效)
            // 标签与示例分离:小字标签说明用途,示例为条文文字并按所选字号渲染,避免被误读为声明
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.settings_font_preview_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // 预览同时体现字号与行间距两个设置档位
                    Text(
                        stringResource(R.string.settings_font_preview_sample),
                        fontSize = ReadingStyle.sp(fontSizeIndex).sp,
                        lineHeight = (ReadingStyle.sp(fontSizeIndex) * ReadingStyle.lineSpacing(lineSpacingIndex)).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 行间距(参考图设置页新增,简单项)
            Text(
                stringResource(R.string.settings_line_spacing),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    stringResource(R.string.line_spacing_compact),
                    stringResource(R.string.line_spacing_normal),
                    stringResource(R.string.line_spacing_loose),
                ).forEachIndexed { index, label ->
                    FilterChip(
                        selected = lineSpacingIndex == index,
                        onClick = { viewModel.setLineSpacing(index) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 深色模式(需求 F6):跟随系统 / 浅色 / 深色,持久化并在全局即时生效
            Text(
                stringResource(R.string.settings_dark_mode),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = darkMode == DarkModePref.SYSTEM,
                    onClick = { viewModel.setDarkMode(DarkModePref.SYSTEM) },
                    label = { Text(stringResource(R.string.settings_dark_mode_system)) },
                )
                FilterChip(
                    selected = darkMode == DarkModePref.LIGHT,
                    onClick = { viewModel.setDarkMode(DarkModePref.LIGHT) },
                    label = { Text(stringResource(R.string.settings_dark_mode_light)) },
                )
                FilterChip(
                    selected = darkMode == DarkModePref.DARK,
                    onClick = { viewModel.setDarkMode(DarkModePref.DARK) },
                    label = { Text(stringResource(R.string.settings_dark_mode_dark)) },
                )
            }
            Spacer(Modifier.height(8.dp))

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle(stringResource(R.string.settings_group_data))
            // 数据源管理(参考图:迁入独立二级页)
            SettingsValueRow(
                label = stringResource(R.string.settings_sources_row),
                // 数据源数量 = 数据源管理页实际列出的源(5 个),不是 SourceId.entries.size
                // ——后者还含未注册的直通通道,前者才是用户看到的接入源
                value = stringResource(R.string.settings_sources_summary, 5),
                onClick = onOpenSources,
            )
            // 清除缓存(网页缓存等;本应用不缓存条文正文)
            SettingsValueRow(
                label = stringResource(R.string.settings_clear_cache),
                value = cacheText,
                onClick = { dialog = SettingsDialog.ClearCache },
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle(stringResource(R.string.settings_about))

            SettingsRow(stringResource(R.string.settings_version)) { dialog = SettingsDialog.Version }
            SettingsRow(stringResource(R.string.settings_check_update)) { checkUpdate() }
            SettingsRow(stringResource(R.string.settings_licenses)) { dialog = SettingsDialog.Licenses }
            SettingsRow(stringResource(R.string.settings_disclaimer)) { dialog = SettingsDialog.Disclaimer }
            SettingsRow(stringResource(R.string.settings_privacy)) { dialog = SettingsDialog.Privacy }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle(stringResource(R.string.settings_clear_data))

            SettingsRow(stringResource(R.string.settings_clear_history)) { dialog = SettingsDialog.ClearHistory }
            SettingsRow(stringResource(R.string.settings_clear_recent)) { dialog = SettingsDialog.ClearRecents }
            SettingsRow(stringResource(R.string.settings_clear_favorites)) { dialog = SettingsDialog.ClearFavorites }

            Spacer(Modifier.height(24.dp))
        }
    }

    when (dialog) {
        SettingsDialog.Version -> SimpleDialog(stringResource(R.string.settings_version), onDismiss = { dialog = null }) {
            // 版本号一律取构建产物:此前这里写死「1.0.0 (versionCode 1)」,
            // 而 APK 早已是 1.0.4 —— 发版时必漏改,关于页与实际版本对不上。
            Text(
                "法条速查 LawQuery ${BuildConfig.VERSION_NAME} " +
                    "(versionCode ${BuildConfig.VERSION_CODE})\n" +
                    "Kotlin + Jetpack Compose + Material 3",
            )
        }
        SettingsDialog.Licenses -> SimpleDialog(stringResource(R.string.licenses_title), onDismiss = { dialog = null }) {
            Text(stringResource(R.string.licenses_text))
        }
        SettingsDialog.Disclaimer -> SimpleDialog(stringResource(R.string.disclaimer_title), onDismiss = { dialog = null }) {
            Text(stringResource(R.string.disclaimer_text))
        }
        SettingsDialog.Privacy -> SimpleDialog(stringResource(R.string.privacy_title), onDismiss = { dialog = null }) {
            Text(stringResource(R.string.privacy_text))
        }
        SettingsDialog.ClearHistory -> ConfirmClearDialog(stringResource(R.string.settings_clear_history)) {
            viewModel.clearHistory(); dialog = null
        }
        SettingsDialog.ClearRecents -> ConfirmClearDialog(stringResource(R.string.settings_clear_recent)) {
            viewModel.clearRecents(); dialog = null
        }
        SettingsDialog.ClearFavorites -> ConfirmClearDialog(stringResource(R.string.settings_clear_favorites)) {
            viewModel.clearFavorites(); dialog = null
        }
        SettingsDialog.ClearCache -> ConfirmClearDialog(stringResource(R.string.settings_clear_cache)) {
            runCatching { context.cacheDir.deleteRecursively() }
            cacheText = cacheSizeText(context)
            dialog = null
        }
        null -> Unit
    }

    // ---- 检查更新状态对话框 ----
    when (val us = updateState) {
        is UpdateState.Checking -> SimpleDialog(
            stringResource(R.string.settings_check_update),
            onDismiss = { updateState = UpdateState.Idle },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.update_state_checking))
            }
        }
        is UpdateState.UpToDate -> SimpleDialog(
            stringResource(R.string.settings_check_update),
            onDismiss = { updateState = UpdateState.Idle },
        ) {
            Text(stringResource(R.string.update_up_to_date, us.currentVersionName))
        }
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { updateState = UpdateState.Idle },
            title = { Text(stringResource(R.string.update_available_title, us.manifest.versionName)) },
            text = {
                Column {
                    Text(stringResource(R.string.update_current_version, us.currentVersionName))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.update_notes_label))
                    Text(us.manifest.notes)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_install_perm_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val manifest = us.manifest
                    updateState = UpdateState.Downloading(0)
                    scope.launch {
                        updateState = updateManager.download(manifest) { p ->
                            updateState = UpdateState.Downloading(p)
                        }
                    }
                }) { Text(stringResource(R.string.update_action_download)) }
            },
            dismissButton = {
                TextButton(onClick = { updateState = UpdateState.Idle }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_check_update)) },
            text = {
                Column {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { us.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.update_downloading, us.percent))
                }
            },
            confirmButton = {},
        )
        is UpdateState.ReadyToInstall -> {
            LaunchedEffect(us.file) {
                updateManager.install(us.file)
                updateState = UpdateState.Idle
            }
            SimpleDialog(
                stringResource(R.string.settings_check_update),
                onDismiss = { updateState = UpdateState.Idle },
            ) {
                Text(stringResource(R.string.update_ready))
            }
        }
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { updateState = UpdateState.Idle },
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(us.message) },
            confirmButton = {
                TextButton(onClick = { checkUpdate() }) {
                    Text(stringResource(R.string.action_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { updateState = UpdateState.Idle }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
        else -> Unit
    }
}

private enum class SettingsDialog { Version, Licenses, Disclaimer, Privacy, ClearHistory, ClearRecents, ClearFavorites, ClearCache }

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text("›", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 带右侧取值的设置行(参考图:数据源管理/清除缓存) */
@Composable
private fun SettingsValueRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Text("›", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 缓存大小(仅 WebView/临时文件;本应用不缓存条文正文) */
private fun cacheSizeText(context: android.content.Context): String {
    val dir = context.cacheDir
    val bytes = runCatching {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format("%.1fMB", mb) else "${bytes / 1024}KB"
}

/**
 * 数据源卡片(面向用户):来源官网、覆盖内容、呈现方式、连接状态。
 * robots/解析等技术细节不放 UI,统一见 docs/数据源接入说明.md。
 */
@Composable
private fun SourceRow(
    id: SourceId,
    health: com.lawquery.data.source.SourceHealth?,
    checking: Boolean,
    onCheck: () -> Unit,
) {
    val isNative = id != SourceId.FLK_WEB
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // 头行:状态点 + 官网名称(+ 测试连接)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor(health))
                    )
                    Text(
                        sourceName(id),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isNative) {
                    TextButton(onClick = onCheck, enabled = !checking) {
                        Text(
                            if (checking) {
                                stringResource(R.string.settings_source_checking)
                            } else {
                                stringResource(R.string.settings_source_check)
                            }
                        )
                    }
                }
            }

            // 覆盖内容
            Text(
                coverageText(id),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))

            // 呈现方式
            Text(
                if (isNative) stringResource(R.string.source_mode_live) else stringResource(R.string.source_mode_browse),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            // 连接状态(用户话术)与失败原因
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    statusLabel(health),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor(health),
                )
                if (health?.status == HealthStatus.OK && health.lastSuccessAt != null) {
                    Text(
                        stringResource(R.string.source_last_success, TimeText.dateTime(health.lastSuccessAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (health?.status == HealthStatus.DEGRADED || health?.status == HealthStatus.COOLDOWN) {
                health.lastReason?.let { reason ->
                    Text(
                        reasonText(reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceName(id: SourceId): String = when (id) {
    SourceId.GOV_CN -> stringResource(R.string.source_gov_cn)
    SourceId.COURT -> stringResource(R.string.source_court)
    SourceId.FLK_WEB -> stringResource(R.string.source_flk)
    SourceId.FLK -> stringResource(R.string.source_flk)
    SourceId.CASE_LIBRARY -> stringResource(R.string.source_alk)
    SourceId.MPS_REG -> stringResource(R.string.source_mps_reg)
}

@Composable
private fun coverageText(id: SourceId): String = when (id) {
    SourceId.GOV_CN -> stringResource(R.string.source_coverage_gov)
    SourceId.COURT -> stringResource(R.string.source_coverage_court)
    SourceId.FLK_WEB -> stringResource(R.string.source_coverage_flk)
    SourceId.FLK -> stringResource(R.string.source_coverage_flk)
    SourceId.CASE_LIBRARY -> stringResource(R.string.source_coverage_case)
    SourceId.MPS_REG -> stringResource(R.string.source_coverage_mps)
}

@Composable
private fun statusLabel(health: com.lawquery.data.source.SourceHealth?): String = when (health?.status) {
    HealthStatus.OK -> stringResource(R.string.source_health_ok)
    HealthStatus.COOLDOWN -> stringResource(R.string.source_health_cooldown)
    HealthStatus.DEGRADED -> stringResource(R.string.source_health_degraded)
    else -> stringResource(R.string.source_health_unknown)
}

@Composable
private fun reasonText(reason: com.lawquery.data.source.FailureReason): String = when (reason) {
    com.lawquery.data.source.FailureReason.RATE_LIMITED -> stringResource(R.string.source_reason_rate_limited)
    com.lawquery.data.source.FailureReason.TIMEOUT -> stringResource(R.string.source_reason_timeout)
    com.lawquery.data.source.FailureReason.NETWORK -> stringResource(R.string.source_reason_network)
    com.lawquery.data.source.FailureReason.OFFLINE -> stringResource(R.string.source_reason_offline)
    com.lawquery.data.source.FailureReason.PARSE_FAILED -> stringResource(R.string.source_reason_parse_failed)
    com.lawquery.data.source.FailureReason.NOT_LINKED -> stringResource(R.string.source_reason_not_linked)
    com.lawquery.data.source.FailureReason.PREVIEW_ONLY -> stringResource(R.string.source_reason_preview_only)
}

private fun statusColor(health: com.lawquery.data.source.SourceHealth?): Color = when (health?.status) {
    HealthStatus.OK -> StatusCurrentColor
    HealthStatus.COOLDOWN, HealthStatus.DEGRADED -> StatusRevisedColor
    else -> StatusRepealedColor
}

@Composable
private fun SimpleDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { content() },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ConfirmClearDialog(target: String, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onConfirm,
        title = { Text(target) },
        text = { Text(stringResource(R.string.settings_clear_confirm, target)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.favorites_cancel)) }
        },
    )
}
