package com.lawquery.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lawquery.R
import com.lawquery.data.repo.SourceStatusRepository
import com.lawquery.data.source.HealthStatus
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceHealth
import com.lawquery.ui.theme.StatusCurrentColor
import com.lawquery.ui.theme.StatusRepealedColor
import com.lawquery.ui.theme.StatusRevisedColor
import com.lawquery.util.TimeText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 数据源管理页 ViewModel(检测结果与健康快照合并展示) */
class SourceManagementViewModel(
    private val sourceStatusRepository: SourceStatusRepository,
) : ViewModel() {

    val sourceStatuses: StateFlow<Map<SourceId, SourceHealth>> =
        sourceStatusRepository.statuses
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _checkingSource = MutableStateFlow<SourceId?>(null)
    val checkingSource: StateFlow<SourceId?> = _checkingSource.asStateFlow()

    fun checkSource(sourceId: SourceId) {
        viewModelScope.launch {
            _checkingSource.value = sourceId
            runCatching { sourceStatusRepository.check(sourceId) }
            _checkingSource.value = null
        }
    }

    class Factory(
        private val sourceStatusRepository: SourceStatusRepository,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SourceManagementViewModel(sourceStatusRepository) as T
    }
}

/**
 * 五个数据源的接入方式不同,状态要分别写清 ——不能用同一个「正常/异常」概括:
 *
 * | 源 | 接入方式 | 状态语义 |
 * |---|---|---|
 * | 国家法律法规数据库 | 官方检索接口,免登录 | 已接入;健康检测反映连通性 |
 * | 中国政府网 | 官方接口,列表抓取 | 已接入 |
 * | 公安部规章库 | 官方 JSONP 接口,免登录 | 已接入;正文随详情下发 |
 * | 最高人民法院 | 官网列表抓取 | 已接入;正文多为附件,部分走官方页 |
 * | 人民法院案例库 | **需用户本人官方登录** | 未登录=未对接,登录后=已对接 |
 *
 * 政策上不可用的源(如[SourceId.FLK_WEB] 官方直通通道)不在此列 ——
 * 它只是列表页的跳转入口,不是独立数据源,单列一条说明。
 */
private enum class SourceEntry(val id: SourceId) {
    FLK(SourceId.FLK),
    GOV_CN(SourceId.GOV_CN),
    COURT(SourceId.COURT),
    MPS_REG(SourceId.MPS_REG),
    CASE_LIBRARY(SourceId.CASE_LIBRARY),
}

/**
 * 数据源管理页:逐个写明每个源的**接入方式与当前状态**。
 *
 * - 免登录的源(国家法律法规数据库 / 中国政府网 / 最高人民法院):显示「已接入」,
 *   并提供「立即检测」看当前连通性;
 * - 需登录的源(人民法院案例库):状态随登录标记变化,提供「去登录 / 重新登录」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagementScreen(
    sourceStatusRepository: SourceStatusRepository,
    settingsStore: com.lawquery.data.local.SettingsStore,
    onBack: () -> Unit,
    /** 人民法院案例库:打开官方登录页(未对接/需重新登录时) */
    onOpenCaseLogin: () -> Unit = {},
) {
    val viewModel: SourceManagementViewModel = viewModel(
        factory = SourceManagementViewModel.Factory(sourceStatusRepository),
    )
    val statuses by viewModel.sourceStatuses.collectAsStateWithLifecycle()
    val checkingSource by viewModel.checkingSource.collectAsStateWithLifecycle()
    // 人民法院案例库「已对接」标记(会话由 Keystore 加密持有,票据不落盘)
    val alkLinked by settingsStore.alkLinked.collectAsStateWithLifecycle(false)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                title = { Text(stringResource(R.string.settings_sources_page_title)) },
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_sources_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            SourceEntry.entries.forEach { entry ->
                SourceCard(
                    entry = entry,
                    health = statuses[entry.id],
                    checking = checkingSource == entry.id,
                    onCheck = { viewModel.checkSource(entry.id) },
                    alkLinked = alkLinked,
                    onOpenCaseLogin = onOpenCaseLogin,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_sources_page_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SourceCard(
    entry: SourceEntry,
    health: SourceHealth?,
    checking: Boolean,
    onCheck: () -> Unit,
    alkLinked: Boolean,
    onOpenCaseLogin: () -> Unit,
) {
    val isCaseLibrary = entry == SourceEntry.CASE_LIBRARY

    // 状态语义:需登录的源看登录标记,其余看「已接入 + 健康检测结果」
    val statusText = if (isCaseLibrary) {
        stringResource(
            if (alkLinked) R.string.alk_linked_label else R.string.alk_unlinked_label
        )
    } else {
        stringResource(R.string.source_status_ready)
    }
    // 颜色:未登录=待处理(琥珀),已接入/已登录=正常(绿),检测失败=降级(红)
    val statusColor = when {
        isCaseLibrary && !alkLinked -> StatusRevisedColor
        health?.status == HealthStatus.COOLDOWN || health?.status == HealthStatus.DEGRADED ->
            StatusRepealedColor
        else -> StatusCurrentColor
    }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sourceName(entry.id),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.source_official_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // 状态行:接入状态 + (检测异常时补充检测结论)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.source_state_prefix),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
                // 已接入的源:健康异常时把检测结论并列显示,避免「已接入」掩盖不可用
                if (!isCaseLibrary && health != null &&
                    (health.status == HealthStatus.COOLDOWN || health.status == HealthStatus.DEGRADED)
                ) {
                    Text(
                        "· " + healthLabel(health),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusRepealedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 收录范围 / 接入方式说明
            Spacer(Modifier.height(6.dp))
            Text(
                sourceNotice(entry, alkLinked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )

            // 国家法律法规数据库:标明实际访问域名(正文阅读器在独立子域)
            if (entry == SourceEntry.FLK) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.source_flk_domain),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 最近检测时间(仅已接入的源有)
            val lastCheck = health?.lastSuccessAt ?: health?.lastFailureAt
            if (lastCheck != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.source_last_check, TimeText.dateTime(lastCheck)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (isCaseLibrary) {
                    Button(onClick = onOpenCaseLogin) {
                        Text(
                            stringResource(
                                if (alkLinked) R.string.alk_relogin else R.string.alk_go_login
                            )
                        )
                    }
                } else {
                    Button(onClick = onCheck, enabled = !checking) {
                        Text(
                            if (checking) {
                                stringResource(R.string.settings_source_checking)
                            } else {
                                stringResource(R.string.source_check_now)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sourceNotice(entry: SourceEntry, alkLinked: Boolean): String = when (entry) {
    SourceEntry.FLK -> stringResource(R.string.source_flk_ready_notice)
    SourceEntry.GOV_CN -> stringResource(R.string.source_gov_ready_notice)
    SourceEntry.COURT -> stringResource(R.string.source_court_ready_notice)
    SourceEntry.MPS_REG -> stringResource(R.string.source_mps_ready_notice)
    SourceEntry.CASE_LIBRARY -> stringResource(
        if (alkLinked) R.string.alk_linked_notice else R.string.alk_unlinked_notice
    )
}

@Composable
private fun sourceName(id: SourceId): String = when (id) {
    SourceId.GOV_CN -> stringResource(R.string.source_gov_cn)
    SourceId.COURT -> stringResource(R.string.source_court)
    SourceId.FLK, SourceId.FLK_WEB -> stringResource(R.string.source_flk)
    SourceId.CASE_LIBRARY -> stringResource(R.string.source_alk)
    SourceId.MPS_REG -> stringResource(R.string.source_mps_reg)
}

@Composable
private fun healthLabel(health: SourceHealth): String = when (health.status) {
    HealthStatus.OK -> stringResource(R.string.source_health_ok)
    HealthStatus.COOLDOWN -> stringResource(R.string.source_health_cooldown)
    HealthStatus.DEGRADED -> stringResource(R.string.source_health_degraded)
    else -> stringResource(R.string.source_health_unknown)
}