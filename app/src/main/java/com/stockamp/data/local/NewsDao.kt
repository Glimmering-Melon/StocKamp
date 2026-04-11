package com.stockamp.data.local

import androidx.paging.PagingSource
import androidx.room.*
import com.stockamp.data.model.NewsArticleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {

    @Query("SELECT * FROM news_articles ORDER BY published_at DESC")
    fun getAllNews(): PagingSource<Int, NewsArticleEntity>

    @Query("""
        SELECT * FROM news_articles 
        WHERE stock_symbols LIKE '%' || :symbol || '%'
        ORDER BY published_at DESC LIMIT :limit
    """)
    fun getNewsBySymbol(symbol: String, limit: Int): Flow<List<NewsArticleEntity>>

    @Query("SELECT * FROM news_articles ORDER BY published_at DESC LIMIT :limit")
    fun getLatestNews(limit: Int): Flow<List<NewsArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<NewsArticleEntity>)

    @Query("DELETE FROM news_articles WHERE id NOT IN (SELECT id FROM news_articles ORDER BY published_at DESC LIMIT 200)")
    suspend fun deleteOldNews()

    @Query("SELECT COUNT(*) FROM news_articles")
    suspend fun getCount(): Int

    @Query("SELECT MAX(cached_at) FROM news_articles")
    suspend fun getMostRecentCachedAt(): Long?
}
