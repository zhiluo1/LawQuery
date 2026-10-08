package com.lawquery.data.repo

import com.lawquery.data.local.FavoriteDao
import com.lawquery.data.local.FavoriteEntity
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.LawSource
import com.lawquery.data.source.SearchPage
import com.lawquery.data.source.SearchQuery
import com.lawquery.data.source.SourceCapability
import com.lawquery.data.source.SourceId
import com.lawquery.data.source.SourceRegistry
import com.lawquery.data.source.SourceResult
import com.lawquery.data.source.toFingerprint
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.VersionFingerprint
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏与更新校验单测(需求 6.2 / 验收 D3:mock 元数据变化模拟官方更新)。
 */
class UpdateCheckServiceTest {

    // ---- 内存版 FavoriteDao(Room DAO 的 JVM 桩) ----
    private class FakeFavoriteDao : FavoriteDao {
        val store = MutableStateFlow<Map<String, FavoriteEntity>>(emptyMap())

        override fun observeAll(): Flow<List<FavoriteEntity>> =
            store.map { it.values.sortedByDescending { e -> e.favoritedAt } }

        override suspend fun getAll(): List<FavoriteEntity> =
            store.value.values.sortedByDescending { it.favoritedAt }

        override suspend fun get(refKey: String): FavoriteEntity? = store.value[refKey]

        override fun observeExists(refKey: String): Flow<Boolean> = store.map { it.containsKey(refKey) }

        override suspend fun upsert(entity: FavoriteEntity) {
            store.value = store.value + (entity.refKey to entity)
        }

        override suspend fun delete(entity: FavoriteEntity) {
            store.value = store.value - entity.refKey
        }

        override suspend fun deleteAll() {
            store.value = emptyMap()
        }
    }

    // ---- 可编程元数据的假源(模拟官方元数据变化) ----
    private class FakeSource(var latest: VersionFingerprint) : LawSource {
        var metaRequests = 0
        var failWith: FailureReason? = null
        override val id = SourceId.GOV_CN
        override val capability = SourceCapability.NATIVE
        override suspend fun search(query: SearchQuery) = SourceResult.Success(SearchPage(emptyList(), null))
        override suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument> =
            SourceResult.SourceUnavailable(id, FailureReason.NETWORK)

        override suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint> {
            metaRequests++
            return failWith?.let { SourceResult.SourceUnavailable(id, it) }
                ?: SourceResult.Success(latest)
        }

        override fun healthSnapshot(): Nothing = throw UnsupportedOperationException()
    }

    private fun ref() = LawRef(
        id = "22802973",
        title = "网络数据安全管理条例",
        issuingAuthority = "国务院",
        docNumber = "国令第790号",
        publishDate = LocalDate.of(2024, 9, 30),
        effectiveDate = LocalDate.of(2025, 1, 1),
        status = LawStatus.CURRENT,
        source = SourceId.GOV_CN,
        url = "https://www.gov.cn/x",
    )

    @Test
    fun `官方元数据变化时报告已更新(验收D3)`() = runBlocking {
        val dao = FakeFavoriteDao()
        val original = ref().toFingerprint()
        val source = FakeSource(original)
        val favorites = FavoriteRepository(dao, SourceRegistry(mapOf(SourceId.GOV_CN to source)))

        favorites.toggle(ref())

        // 官方元数据"变化":公布日期更新
        source.latest = original.copy(publishDate = LocalDate.of(2026, 10, 1))
        val outcome = favorites.checkForUpdate(ref())
        assertTrue(outcome is FavoriteRepository.CheckOutcome.Updated)
        assertEquals(1, source.metaRequests)

        // 用户确认后本地指纹同步,徽标消除
        favorites.applyUpdate(
            ref().key,
            (outcome as FavoriteRepository.CheckOutcome.Updated).latest,
        )
        assertTrue(favorites.updatedFingerprints.value[ref().key] == null)
    }

    @Test
    fun `元数据一致时报告未变化`() = runBlocking {
        val dao = FakeFavoriteDao()
        val source = FakeSource(ref().toFingerprint())
        val favorites = FavoriteRepository(dao, SourceRegistry(mapOf(SourceId.GOV_CN to source)))
        favorites.toggle(ref())

        val outcome = favorites.checkForUpdate(ref())
        assertTrue(outcome is FavoriteRepository.CheckOutcome.Unchanged)
        assertTrue(favorites.updatedFingerprints.value.isEmpty())
    }

    @Test
    fun `校验网络失败不冒充更新也不中断(验收D5)`() = runBlocking {
        val dao = FakeFavoriteDao()
        val source = FakeSource(ref().toFingerprint()).apply { failWith = FailureReason.TIMEOUT }
        val favorites = FavoriteRepository(dao, SourceRegistry(mapOf(SourceId.GOV_CN to source)))
        favorites.toggle(ref())

        val outcome = favorites.checkForUpdate(ref())
        assertTrue(outcome is FavoriteRepository.CheckOutcome.CheckFailed)
        assertTrue(favorites.updatedFingerprints.value.isEmpty())
    }

    @Test
    fun `冷启动串行校验返回更新键列表(验收D4)`() = runBlocking {
        val dao = FakeFavoriteDao()
        val original = ref().toFingerprint()
        val source = FakeSource(original.copy(status = LawStatus.REPEALED))
        val favorites = FavoriteRepository(dao, SourceRegistry(mapOf(SourceId.GOV_CN to source)))
        favorites.toggle(ref())
        favorites.toggle(ref().copy(id = "another", title = "另一件"))

        val service = UpdateCheckService(favorites, minIntervalMs = 1)
        val updated = service.runColdStartCheck()

        // 两条收藏全部命中"已废止"变化
        assertEquals(2, updated.size)
        assertEquals(2, source.metaRequests)
        assertTrue(favorites.updatedFingerprints.value.isNotEmpty())
    }

    @Test
    fun `收藏仅存元数据与指纹不存正文(验收D6)`() = runBlocking {
        val dao = FakeFavoriteDao()
        val favorites = FavoriteRepository(dao, SourceRegistry(emptyMap()))
        favorites.toggle(ref())

        val entity = dao.get(ref().key)!!
        assertTrue(entity.toString().contains("国令第790号"))
        assertTrue(
            "收藏实体不得出现任何正文字段",
            FavoriteEntity::class.java.declaredFields.none {
                it.name.contains("content", true) || it.name.contains("body", true) || it.name.contains("chapter", true)
            },
        )
    }
}
