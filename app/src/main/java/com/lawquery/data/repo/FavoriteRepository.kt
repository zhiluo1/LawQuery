package com.lawquery.data.repo

import com.lawquery.data.local.FavoriteDao
import com.lawquery.data.local.FavoriteEntity
import com.lawquery.data.source.FailureReason
import com.lawquery.data.source.SourceRegistry
import com.lawquery.data.source.SourceResult
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.LawStatus
import com.lawquery.domain.model.VersionFingerprint
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * 收藏(需求 F5):仅元数据 + 收藏时版本指纹,不存正文;
 * 打开收藏前实时拉取最新元数据比对指纹(需求 F4/6.2)。
 */
class FavoriteRepository(
    private val favoriteDao: FavoriteDao,
    private val registry: SourceRegistry,
) {

    /** refKey → 最新指纹;非空即表示官方元数据与收藏时不一致(S5「已更新」徽标) */
    private val _updatedFingerprints = MutableStateFlow<Map<String, VersionFingerprint>>(emptyMap())
    val updatedFingerprints: StateFlow<Map<String, VersionFingerprint>> = _updatedFingerprints.asStateFlow()

    fun observeAll(): Flow<List<FavoriteEntity>> = favoriteDao.observeAll()

    /**
     * 收藏列表(含「已更新」徽标)。
     *
     * ⚠️ 必须**同时订阅** [_updatedFingerprints](2026-10-08 修):此前只 map 数据库流、
     * 在 map 内部读 `_updatedFingerprints.value` —— 指纹变化**不会**触发重新发射,
     * 于是冷启动批量校验([UpdateCheckService])辛苦算出的「已更新」永远刷不到界面上
     * (要等用户增删收藏、数据库动了才偶然显示)。现在两个源 combine,任一变化即刷新。
     *
     * 顺带:`toLawRef()` 里的 `SourceId.valueOf` 遇到无法识别的来源(旧版本写入过、
     * 现在已下线的源)会抛异常 —— 这里 runCatching 跳过该条,而不是让整个收藏页崩掉。
     */
    fun observeItems(): Flow<List<FavoriteItem>> =
        combine(observeAll(), _updatedFingerprints) { list, updated ->
            list.mapNotNull { entity ->
                runCatching {
                    FavoriteItem(
                        ref = entity.toLawRef(),
                        favoritedAt = entity.favoritedAt,
                        updated = updated.containsKey(entity.refKey),
                    )
                }.getOrNull()
            }
        }

    fun observeExists(refKey: String): Flow<Boolean> = favoriteDao.observeExists(refKey)

    suspend fun get(refKey: String): FavoriteEntity? = favoriteDao.get(refKey)

    suspend fun getAll(): List<FavoriteEntity> = favoriteDao.getAll()

    suspend fun toggle(ref: LawRef) {
        val existing = favoriteDao.get(ref.key)
        if (existing != null) {
            favoriteDao.delete(existing)
        } else {
            favoriteDao.upsert(ref.toFavoriteEntity(System.currentTimeMillis()))
        }
    }

    suspend fun remove(refKey: String) {
        favoriteDao.get(refKey)?.let { favoriteDao.delete(it) }
    }

    suspend fun clearAll() = favoriteDao.deleteAll()

    /**
     * 打开收藏时校验(需求 6.2):实时拉取最新元数据 → 与本地指纹比对。
     * 一致:直接进入详情;不一致:由 UI 弹提示,确认后更新本地指纹。
     */
    suspend fun checkForUpdate(ref: LawRef): CheckOutcome {
        val entity = favoriteDao.get(ref.key) ?: return CheckOutcome.NotFavorited
        val stored = VersionFingerprint(
            docNumber = entity.fingerprintDocNumber,
            publishDate = entity.fingerprintPublishEpochDay?.let(LocalDate::ofEpochDay),
            effectiveDate = entity.fingerprintEffectiveEpochDay?.let(LocalDate::ofEpochDay),
            status = runCatching { LawStatus.valueOf(entity.fingerprintStatus) }.getOrDefault(LawStatus.CURRENT),
        )
        val source = registry.source(ref.source)
            ?: return CheckOutcome.CheckFailed(FailureReason.PARSE_FAILED)
        return when (val result = source.fetchMeta(ref)) {
            is SourceResult.Success -> {
                val latest = result.data.normalized()
                if (latest != stored.normalized()) {
                    _updatedFingerprints.value = _updatedFingerprints.value + (ref.key to latest)
                    CheckOutcome.Updated(latest)
                } else {
                    _updatedFingerprints.value = _updatedFingerprints.value - ref.key
                    CheckOutcome.Unchanged
                }
            }
            is SourceResult.SourceUnavailable -> CheckOutcome.CheckFailed(result.reason)
        }
    }

    /** 用户确认后更新本地指纹与元数据(需求 D3) */
    suspend fun applyUpdate(refKey: String, latest: VersionFingerprint) {
        val entity = favoriteDao.get(refKey) ?: return
        val updated = entity.copy(
            docNumber = latest.docNumber,
            publishDateEpochDay = latest.publishDate?.toEpochDay(),
            effectiveDateEpochDay = latest.effectiveDate?.toEpochDay(),
            status = latest.status.name,
            fingerprintDocNumber = latest.docNumber,
            fingerprintPublishEpochDay = latest.publishDate?.toEpochDay(),
            fingerprintEffectiveEpochDay = latest.effectiveDate?.toEpochDay(),
            fingerprintStatus = latest.status.name,
        )
        favoriteDao.upsert(updated)
        _updatedFingerprints.value = _updatedFingerprints.value - refKey
    }

    /** 冷启动批量校验时静默登记徽标(确认动作在打开详情时进行) */
    fun registerUpdatedSilently(refKey: String, latest: VersionFingerprint) {
        _updatedFingerprints.value = _updatedFingerprints.value + (refKey to latest)
    }

    sealed interface CheckOutcome {
        data object NotFavorited : CheckOutcome
        data object Unchanged : CheckOutcome
        data class Updated(val latest: VersionFingerprint) : CheckOutcome
        data class CheckFailed(val reason: FailureReason) : CheckOutcome
    }
}

data class FavoriteItem(
    val ref: LawRef,
    val favoritedAt: Long,
    val updated: Boolean,
)

fun LawRef.toFavoriteEntity(favoritedAt: Long) = FavoriteEntity(
    refKey = key,
    sourceId = source.name,
    docId = id,
    title = title,
    url = url,
    issuingAuthority = issuingAuthority,
    docNumber = docNumber,
    publishDateEpochDay = publishDate?.toEpochDay(),
    effectiveDateEpochDay = effectiveDate?.toEpochDay(),
    status = status.name,
    fingerprintDocNumber = docNumber,
    fingerprintPublishEpochDay = publishDate?.toEpochDay(),
    fingerprintEffectiveEpochDay = effectiveDate?.toEpochDay(),
    fingerprintStatus = status.name,
    favoritedAt = favoritedAt,
)

fun FavoriteEntity.toLawRef() = LawRef(
    id = docId,
    title = title,
    issuingAuthority = issuingAuthority,
    docNumber = docNumber,
    publishDate = publishDateEpochDay?.let(LocalDate::ofEpochDay),
    effectiveDate = effectiveDateEpochDay?.let(LocalDate::ofEpochDay),
    status = runCatching { LawStatus.valueOf(status) }.getOrDefault(LawStatus.CURRENT),
    source = com.lawquery.data.source.SourceId.valueOf(sourceId),
    url = url,
)
