package com.lawquery

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 核心路径冒烟测试(项目文档 7:搜索→详情→复制 全路径的 Compose UI 测试;
 * 此处为启动与首页元素的最小冒烟,完整路径测试依赖可用的官方源环境)。
 */
@RunWith(AndroidJUnit4::class)
class HomeSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsSearchEntryAndCategories() {
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.home_search_hint)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.category_judicial)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.nav_favorites)).assertExists()
    }
}
