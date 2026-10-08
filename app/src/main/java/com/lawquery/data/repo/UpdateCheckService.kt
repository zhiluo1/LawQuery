package com.lawquery.data.repo

import com.lawquery.data.source.FailureReason
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.VersionFingerprint
import kotlinx.coroutines.delay

/**
 * 更新校验服务(需求 F4 / 6.2 / 验收 D4):
 * 冷启动时后台串行校验收藏列表元数据 —— 仅轻量元数据请求、间隔 ≥ 2 秒、失败静默跳过;
 * 结果驱动收藏列表「已更新」徽标。
 */
class UpdateCheckService(
    private val favorites: FavoriteRepository,
    private val minIntervalMs: Long = 2_000,
) {

    sealed interface ItemResult {
        data object Unchanged : ItemResult
        data class Updated(val latest: VersionFingerprint) : ItemResult
        data class Failed(val reason: FailureReason) : ItemResult
    }

    suspend fun checkSingle(ref: LawRef): ItemResult =
        when (val outcome = favorites.checkForUpdate(ref)) {
            FavoriteRepository.CheckOutcome.NotFavorited -> ItemResult.Failed(FailureReason.PARSE_FAILED)
            FavoriteRepository.CheckOutcome.Unchanged -> ItemResult.Unchanged
            is FavoriteRepository.CheckOutcome.Updated -> ItemResult.Updated(outcome.latest)
            is FavoriteRepository.CheckOutcome.CheckFailed -> ItemResult.Failed(outcome.reason)
        }

    /**
     * 冷启动串行校验:逐条请求,请求之间间隔 ≥ minIntervalMs;失败静默(不中断后续)。
     * @return 本次校验发现更新的 refKey 列表
     */
    suspend fun runColdStartCheck(): List<String> {
        val updatedKeys = mutableListOf<String>()
        val all = favorites.getAll()
        var first = true
        for (entity in all) {
            if (!first) delay(minIntervalMs)
            first = false
            when (val r = checkSingle(entity.toLawRef())) {
                is ItemResult.Updated -> {
                    favorites.registerUpdatedSilently(entity.refKey, r.latest)
                    updatedKeys += entity.refKey
                }
                else -> Unit // 失败/未变化:静默跳过
            }
        }
        return updatedKeys
    }
}
