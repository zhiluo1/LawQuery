package com.lawquery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lawquery.R
import com.lawquery.data.source.SourceId
import com.lawquery.domain.model.LawStatus
import com.lawquery.ui.theme.SourceCaseColor
import com.lawquery.ui.theme.SourceCaseContainer
import com.lawquery.ui.theme.SourceCourtColor
import com.lawquery.ui.theme.SourceCourtContainer
import com.lawquery.ui.theme.SourceFlkColor
import com.lawquery.ui.theme.SourceFlkContainer
import com.lawquery.ui.theme.SourceGovColor
import com.lawquery.ui.theme.SourceGovContainer
import com.lawquery.ui.theme.SourceMpsColor
import com.lawquery.ui.theme.SourceMpsContainer
import com.lawquery.ui.theme.StatusCurrentColor
import com.lawquery.ui.theme.StatusCurrentContainer
import com.lawquery.ui.theme.StatusPendingColor
import com.lawquery.ui.theme.StatusPendingContainer
import com.lawquery.ui.theme.StatusRepealedColor
import com.lawquery.ui.theme.StatusRepealedContainer
import com.lawquery.ui.theme.StatusRevisedColor
import com.lawquery.ui.theme.StatusRevisedContainer

/** 时效性徽标(需求 F1 原生结果项 / S2 确认时效性) */
@Composable
fun LawStatusBadge(status: LawStatus, modifier: Modifier = Modifier) {
    val (fg: Color, bg: Color, label: String) = when (status) {
        LawStatus.CURRENT -> Triple(
            StatusCurrentColor, StatusCurrentContainer,
            stringResource(R.string.status_current),
        )
        LawStatus.REVISED -> Triple(
            StatusRevisedColor, StatusRevisedContainer,
            stringResource(R.string.status_revised),
        )
        LawStatus.REPEALED -> Triple(
            StatusRepealedColor, StatusRepealedContainer,
            stringResource(R.string.status_repealed),
        )
        LawStatus.PENDING -> Triple(
            StatusPendingColor, StatusPendingContainer,
            stringResource(R.string.status_pending),
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 来源标识(需求 7.2.5:显著标注来源) */
@Composable
fun SourceBadge(source: SourceId, modifier: Modifier = Modifier) {
    val (fg: Color, bg: Color, label: String) = when (source) {
        SourceId.GOV_CN -> Triple(
            SourceGovColor, SourceGovContainer,
            stringResource(R.string.source_gov_cn),
        )
        SourceId.COURT -> Triple(
            SourceCourtColor, SourceCourtContainer,
            stringResource(R.string.source_court),
        )
        SourceId.FLK_WEB -> Triple(
            SourceFlkColor, SourceFlkContainer,
            stringResource(R.string.source_flk),
        )
        // 原生检索通道与官方直通同属 flk,展示名一致
        SourceId.FLK -> Triple(
            SourceFlkColor, SourceFlkContainer,
            stringResource(R.string.source_flk),
        )
        SourceId.CASE_LIBRARY -> Triple(
            SourceCaseColor, SourceCaseContainer,
            stringResource(R.string.source_alk),
        )
        SourceId.MPS_REG -> Triple(
            SourceMpsColor, SourceMpsContainer,
            stringResource(R.string.source_mps_reg),
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 「获取于」时间戳条(需求 F4:详情页常显) */
@Composable
fun FetchedAtBar(sourceName: String, fetchedAtText: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.detail_fetched_at, sourceName, fetchedAtText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
