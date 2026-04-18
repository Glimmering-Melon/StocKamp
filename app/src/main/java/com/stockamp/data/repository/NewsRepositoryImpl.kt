package com.stockamp.data.repository

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.stockamp.data.local.NewsDao
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.model.toDomain
import com.stockamp.data.model.toEntity
import com.stockamp.data.supabase.SupabaseClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NewsRepository"
private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val newsDao: NewsDao,
    private val supabaseClient: SupabaseClient
) : NewsRepository {

    @OptIn(ExperimentalPagingApi::class)
    override fun getNewsStream(pageSize: Int): Flow<PagingData<NewsArticle>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false
            ),
            remoteMediator = NewsRemoteMediator(
                newsDao = newsDao,
                supabaseClient = supabaseClient,
                pageSize = pageSize
            ),
            pagingSourceFactory = { newsDao.getAllNews() }
        ).flow.map { pagingData ->
            pagingData.map { entity -> entity.toDomain() }
        }
    }

    override fun getNewsBySymbol(symbol: String, limit: Int): Flow<List<NewsArticle>> {
        return newsDao.getNewsBySymbol(symbol, limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getLatestNews(limit: Int): Flow<List<NewsArticle>> {
        return newsDao.getLatestNews(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun refresh(): Result<Unit> {
        return try {
            // Cache-first: skip fetch if data is < 10 minutes old
            val mostRecentCachedAt = newsDao.getMostRecentCachedAt() ?: 0L
            val age = System.currentTimeMillis() - mostRecentCachedAt
            if (age < CACHE_TTL_MS && newsDao.getCount() > 0) {
                Log.d(TAG, "Cache is fresh (${age}ms old), skipping network fetch")
                return Result.success(Unit)
            }

            Log.d(TAG, "Fetching news from Supabase...")
            val result = supabaseClient.fetchNewsArticles(offset = 0, limit = 50)
            result.fold(
                onSuccess = { dtos ->
                    val entities = dtos.mapNotNull { it.toDomain()?.toEntity() }
                    newsDao.insertAll(entities)
                    newsDao.deleteOldNews()
                    Log.d(TAG, "Refreshed ${entities.size} articles from Supabase")
                    Result.success(Unit)
                },
                onFailure = { e ->
                    Log.e(TAG, "Refresh failed", e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            Result.failure(e)
        }
    }

    override suspend fun subscribeRealtime() {
        supabaseClient.subscribeToNewsInserts { dto ->
            // This callback runs on IO dispatcher inside SupabaseClientImpl
            try {
                dto.toDomain()?.toEntity()?.let { entity ->
                    // We need a coroutine scope — use runBlocking since we're already on IO
                    kotlinx.coroutines.runBlocking {
                        newsDao.insertAll(listOf(entity))
                        newsDao.deleteOldNews()
                    }
                    Log.d(TAG, "Realtime: cached article ${entity.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error caching realtime article", e)
            }
        }
    }

    override suspend fun unsubscribeRealtime() {
        supabaseClient.unsubscribeFromNews()
    }

    override fun searchNewsByTitle(query: String, limit: Int): Flow<List<NewsArticle>> {
        return newsDao.searchNewsByTitle(query, limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }
}

