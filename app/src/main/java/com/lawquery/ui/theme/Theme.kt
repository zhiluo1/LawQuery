package com.lawquery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 主题(需求 7.5:深色模式跟随系统)。
 */
private val LightScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary,
    tertiaryContainer = BrandTertiaryContainer,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF6B6890),
)

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
)

@Composable
fun LawQueryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = LawTypography,
        content = content,
    )
}

val LawTypography = androidx.compose.material3.Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/** 正文字号 5 档(需求 F3 字号调节、F6 默认字号),全局记忆 */
object ReadingStyle {
    val levelsSp = listOf(15f, 17f, 19f, 21f, 23f)
    val levelLabels = listOf("小", "较小", "标准", "较大", "大")
    const val DEFAULT_INDEX = 2

    fun sp(index: Int) = levelsSp[index.coerceIn(0, levelsSp.lastIndex)]

    /** 相对标准档的缩放系数:章节/节标题等正文结构元素等比跟随字号档位(F3) */
    fun scale(index: Int): Float =
        levelsSp[index.coerceIn(0, levelsSp.lastIndex)] / levelsSp[DEFAULT_INDEX]

    /** 行间距倍数(行间距档位:紧凑/标准/宽松),详情正文与设置页预览共用 */
    val lineSpacingLevels = listOf(1.5f, 1.7f, 1.9f)

    fun lineSpacing(index: Int) =
        lineSpacingLevels[index.coerceIn(0, lineSpacingLevels.lastIndex)]
}
