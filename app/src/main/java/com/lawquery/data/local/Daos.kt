package com.lawquery.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY favoritedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY favoritedAt DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE refKey = :refKey")
    suspend fun get(refKey: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE refKey = :refKey)")
    fun observeExists(refKey: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: FavoriteEntity)

    @Delete
    suspend fun delete(entity: FavoriteEntity)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY browsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<RecentEntity>>

    @Upsert
    suspend fun upsert(entity: RecentEntity)

    @Query("DELETE FROM recents WHERE refKey IN (SELECT refKey FROM recents ORDER BY browsedAt DESC LIMIT -1 OFFSET 50)")
    suspend fun evictBeyond50()

    @Query("DELETE FROM recents")
    suspend fun deleteAll()
}

@Dao
interface SourceHealthDao {
    @Query("SELECT * FROM source_health")
    fun observeAll(): Flow<List<SourceHealthEntity>>

    @Upsert
    suspend fun upsert(entity: SourceHealthEntity)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE query LIKE '%' || :keyword || '%' ORDER BY searchedAt DESC LIMIT 10")
    suspend fun suggest(keyword: String): List<SearchHistoryEntity>

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history WHERE query IN (SELECT query FROM search_history ORDER BY searchedAt DESC LIMIT -1 OFFSET 50)")
    suspend fun evictBeyond50()

    @Query("DELETE FROM search_history")
    suspend fun deleteAll()
}
