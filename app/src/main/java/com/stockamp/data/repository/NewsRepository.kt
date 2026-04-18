package com.stockamp.data.repository

import androidx.paging.PagingData
import com.stockamp.data.model.NewsArticle
import kotlinx.coroutines.flow.Flow

interface NewsRepository {
    fun getNewsStream(pageSize: Int = 20): Flow<PagingData<NewsArticle>>
    fun getNewsBySymbol(symbol: String, limit: Int = 10): Flow<List<NewsArticle>>
    fun getLatestNews(limit: Int = 5): Flow<List<NewsArticle>>
    suspend fun refresh(): Result<Unit>
    suspend fun subscribeRealtime()
    suspend fun unsubscribeRealtime()
    fun searchNewsByTitle(query: String, limit: Int = 100): Flow<List<NewsArticle>>
}
