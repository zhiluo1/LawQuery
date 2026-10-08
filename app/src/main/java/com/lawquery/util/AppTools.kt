package com.lawquery.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 时间格式化(详情页「获取于 YYYY-MM-DD HH:mm」等) */
object TimeText {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun date(d: LocalDate?): String = d?.format(dateFmt) ?: "—"

    fun dateTime(t: OffsetDateTime?): String = t?.format(dateTimeFmt) ?: "—"

    /** 相对日期(收藏/浏览卡片用):今天 / 昨天 / yyyy-MM-dd */
    fun relativeDate(epochMilli: Long, today: LocalDate = LocalDate.now()): String {
        val d = java.time.Instant.ofEpochMilli(epochMilli).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return when (d) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> d.format(dateFmt)
        }
    }

    /** 时段问候(首页顶部,按当前小时):5-8 早上 / 9-11 上午 / 12-13 中午 / 14-17 下午 / 18-22 晚上 / 其余 夜深 */
    fun greetingForHour(hour: Int): String = when (hour) {
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
}

/** 复制/分享走系统能力,不单独申请权限(需求 7.2.8) */
object SystemShare {

    fun copy(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lawquery", text))
    }.isSuccess

    fun share(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
