package com.lawquery.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 元数据库(项目文档 4.5 / 需求 6.1):
 * 仅含收藏、最近浏览、源健康、搜索历史四张元数据表,**无任何正文表**。
 * 版本升级只允许加列/加表,迁移脚本随代码提交。
 */
@Database(
    entities = [
        FavoriteEntity::class,
        RecentEntity::class,
        SourceHealthEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentDao(): RecentDao
    abstract fun sourceHealthDao(): SourceHealthDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lawquery.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
