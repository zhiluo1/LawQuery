package com.lawquery

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lawquery.core.sessioncache.SessionCache
import com.lawquery.data.local.FavoriteDao
import com.lawquery.data.local.FavoriteEntity
import com.lawquery.data.local.RecentDao
import com.lawquery.data.local.RecentEntity
import com.lawquery.data.local.SearchHistoryDao
import com.lawquery.data.local.SearchHistoryEntity
import com.lawquery.data.local.SettingsStore
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.HistoryRepository
import com.lawquery.data.repo.LawDetailRepository
import com.lawquery.data.source.LawSource
import com.lawquery.data.source.SearchPage
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SourceCapability
import com.lawquery.data.source.SourceHealth
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceRegistry
import com.lawquery.data.source.SourceResult
import com.lawquery.domain.model.Chapter
import com.lawquery.domain.model.LawArticle
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.Section
import com.lawquery.domain.model.SourceId as DomainSourceId
import com.lawquery.domain.model.VersionFingerprint
import com.lawquery.ui.detail.DetailScreen
import com.lawquery.ui.detail.DetailViewModel
import com.lawquery.util.NetworkMonitor
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 目录 Tab「全部展开/全部折叠」回归测试(修复:目录 Tab 原先不消费 expandedChapters
 * 状态,按钮点击无任何可见效果)。覆盖多章 / 单章 / 大条目量 / 单章点按四种场景。
 */
@RunWith(AndroidJUnit4::class)
class DetailDirectoryTest {

    @get:Rule
    val rule = createComposeRule()

    // ---------- 内存版 DAO fake ----------

    private class FakeFavoriteDao : FavoriteDao {
        private val flow = MutableStateFlow<List<FavoriteEntity>>(emptyList())
        override fun observeAll(): Flow<List<FavoriteEntity>> = flow
        override suspend fun getAll(): List<FavoriteEntity> = flow.value
        override suspend fun get(refKey: String): FavoriteEntity? = flow.value.firstOrNull { it.refKey == refKey }
        override fun observeExists(refKey: String): Flow<Boolean> =
            flow.map { list -> list.any { it.refKey == refKey } }
        override suspend fun upsert(entity: FavoriteEntity) {
            flow.value = flow.value.filterNot { it.refKey == entity.refKey } + entity
        }
        override suspend fun delete(entity: FavoriteEntity) {
            flow.value = flow.value.filterNot { it.refKey == entity.refKey }
        }
        override suspend fun deleteAll() { flow.value = emptyList() }
    }

    private class FakeRecentDao : RecentDao {
        private val flow = MutableStateFlow<List<RecentEntity>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentEntity>> = flow
        override suspend fun upsert(entity: RecentEntity) {
            flow.value = flow.value.filterNot { it.refKey == entity.refKey } + entity
        }
        override suspend fun evictBeyond50() {}
        override suspend fun deleteAll() { flow.value = emptyList() }
    }

    private class FakeSearchHistoryDao : SearchHistoryDao {
        private val flow = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
        override fun observeRecent(): Flow<List<SearchHistoryEntity>> = flow
        override suspend fun suggest(keyword: String): List<SearchHistoryEntity> =
            flow.value.filter { it.query.contains(keyword) }
        override suspend fun upsert(entity: SearchHistoryEntity) {
            flow.value = flow.value.filterNot { it.query == entity.query } + entity
        }
        override suspend fun delete(query: String) {
            flow.value = flow.value.filterNot { it.query == query }
        }
        override suspend fun evictBeyond50() {}
        override suspend fun deleteAll() { flow.value = emptyList() }
    }

    // ---------- 构造文档与 ViewModel ----------

    private fun makeDoc(chapters: Int, sectionsPerChapter: Int, articlesPerSection: Int): LawDocument {
        val ref = LawRef(
            id = "test-1",
            title = "测试法规",
            issuingAuthority = "测试机关",
            docNumber = null,
            publishDate = LocalDate.of(2026, 1, 1),
            effectiveDate = LocalDate.of(2026, 2, 1),
            status = LawStatus.CURRENT,
            source = DomainSourceId.GOV_CN,
            url = "https://www.gov.cn/test",
        )
        var articleNo = 0
        val chs = (1..chapters).map { ci ->
            val secs = (1..sectionsPerChapter).map { si ->
                val arts = (1..articlesPerSection).map {
                    articleNo++
                    LawArticle("第${articleNo}条", articleNo, listOf("条文内容 $articleNo"))
                }
                Section("第${ci}-${si}节", arts)
            }
            Chapter("第${ci}章", ci, secs)
        }
        return LawDocument(ref, OffsetDateTime.now(), emptyList(), chs)
    }

    private fun launch(doc: LawDocument): DetailViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = object : LawSource {
            override val id = SourceId.GOV_CN
            override val capability = SourceCapability.NATIVE
            override suspend fun search(query: SearchQuery) =
                SourceResult.Success(SearchPage(emptyList(), null))
            override suspend fun fetchDocument(ref: LawRef) = SourceResult.Success(doc)
            override suspend fun fetchMeta(ref: LawRef) =
                SourceResult.Success(VersionFingerprint(null, null, null, LawStatus.CURRENT))
            override fun healthSnapshot() = SourceHealth(SourceId.GOV_CN)
        }
        val registry = SourceRegistry(mapOf(SourceId.GOV_CN to source))
        val detailRepo = LawDetailRepository(
            registry, SessionCache(),
            HistoryRepository(FakeSearchHistoryDao(), FakeRecentDao()),
        )
        val vm = DetailViewModel(
            detailRepo,
            FavoriteRepository(FakeFavoriteDao(), registry),
            SettingsStore(context),
            NetworkMonitor(context),
            doc.ref,
        )
        rule.setContent { DetailScreen(vm, onBack = {}, onOpenOriginal = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("目录").performClick()
        rule.waitForIdle()
        return vm
    }

    private fun articleNodes(number: Int) = rule.onAllNodesWithText("第${number}条")

    // ---------- 用例 ----------

    @Test
    fun 多章文档_全部折叠后条目消失_全部展开后条目恢复() {
        launch(makeDoc(chapters = 2, sectionsPerChapter = 1, articlesPerSection = 3))

        // 目录页初始(≤40 条默认全展开):章标题与条文同时可见
        rule.onNodeWithText("第1章").assertExists()
        rule.onNodeWithText("第2章").assertExists()
        articleNodes(1).assertCountEquals(1)
        articleNodes(6).assertCountEquals(1)

        // 全部折叠:仅剩章标题,条文行全部消失
        rule.onNodeWithText("全部折叠").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("第1章").assertExists()
        rule.onNodeWithText("第2章").assertExists()
        (1..6).forEach { n -> articleNodes(n).assertCountEquals(0) }

        // 全部展开:所有条文行恢复
        rule.onNodeWithText("全部展开").performClick()
        rule.waitForIdle()
        (1..6).forEach { n -> articleNodes(n).assertCountEquals(1) }
    }

    @Test
    fun 单章文档_展开折叠按钮同样生效() {
        launch(makeDoc(chapters = 1, sectionsPerChapter = 1, articlesPerSection = 3))

        rule.onNodeWithText("全部折叠").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("第1章").assertExists()
        (1..3).forEach { n -> articleNodes(n).assertCountEquals(0) }

        rule.onNodeWithText("全部展开").performClick()
        rule.waitForIdle()
        (1..3).forEach { n -> articleNodes(n).assertCountEquals(1) }
    }

    @Test
    fun 大条目量_全部展开后可滚动到末尾条目() {
        launch(makeDoc(chapters = 2, sectionsPerChapter = 1, articlesPerSection = 40))

        rule.onNodeWithText("全部折叠").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("全部展开").performClick()
        rule.waitForIdle()

        // 80 条 + 章节行,懒加载列表应能滚到最后一条
        rule.onNodeWithTag("directory_list")
            .performScrollToNode(hasText("第80条"))
        rule.waitForIdle()
        articleNodes(80).assertCountEquals(1)
    }

    @Test
    fun 目录中点按章标题可单独展开折叠() {
        launch(makeDoc(chapters = 2, sectionsPerChapter = 1, articlesPerSection = 2))

        rule.onNodeWithText("全部折叠").performClick()
        rule.waitForIdle()
        (1..4).forEach { n -> articleNodes(n).assertCountEquals(0) }

        // 单章展开:仅第一章条文出现
        rule.onNodeWithText("第1章").performClick()
        rule.waitForIdle()
        (1..2).forEach { n -> articleNodes(n).assertCountEquals(1) }
        (3..4).forEach { n -> articleNodes(n).assertCountEquals(0) }

        // 再点折叠回目录态
        rule.onNodeWithText("第1章").performClick()
        rule.waitForIdle()
        (1..4).forEach { n -> articleNodes(n).assertCountEquals(0) }
    }
}
