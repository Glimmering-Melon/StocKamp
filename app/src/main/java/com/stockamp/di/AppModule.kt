package com.stockamp.di

import android.content.Context
import androidx.room.Room
import com.stockamp.data.local.JournalDao
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.local.StockDao
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.local.SyncQueueDao
import com.stockamp.data.local.SyncMetadataDao
import com.stockamp.data.local.ChartDataDao
import com.stockamp.data.local.NewsDao
import com.stockamp.data.local.StockSymbolCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StocKampDatabase {
        return Room.databaseBuilder(
            context,
            StocKampDatabase::class.java,
            "stockamp_database"
        )
            .addMigrations(StocKampDatabase.MIGRATION_1_2, StocKampDatabase.MIGRATION_2_3, StocKampDatabase.MIGRATION_3_4, StocKampDatabase.MIGRATION_4_5, StocKampDatabase.MIGRATION_5_6)
            .build()
    }

    @Provides
    fun provideStockDao(database: StocKampDatabase): StockDao = database.stockDao()

    @Provides
    fun provideWatchlistDao(database: StocKampDatabase): WatchlistDao = database.watchlistDao()

    @Provides
    fun provideJournalDao(database: StocKampDatabase): JournalDao = database.journalDao()
    
    @Provides
    fun provideSyncQueueDao(database: StocKampDatabase): SyncQueueDao = database.syncQueueDao()
    
    @Provides
    fun provideSyncMetadataDao(database: StocKampDatabase): SyncMetadataDao = database.syncMetadataDao()

    @Provides
    fun provideChartDataDao(database: StocKampDatabase): ChartDataDao = database.chartDataDao()

    @Provides
    fun provideNewsDao(database: StocKampDatabase): NewsDao = database.newsDao()

    @Provides
    fun provideStockSymbolCacheDao(database: StocKampDatabase): StockSymbolCacheDao = database.stockSymbolCacheDao()

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        defaultRequest {
            // Base URL can be configured here or via environment
        }
    }
}
