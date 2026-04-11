package com.stockamp.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.stockamp.data.local.NewsDao
import com.stockamp.data.model.NewsArticleEntity
import com.stockamp.data.model.toDomain
import com.stockamp.data.model.toEntity
import com.stockamp.data.supabase.SupabaseClient

@OptIn(ExperimentalPagingApi::class)
class NewsRemoteMediator(
    private val newsDao: NewsDao,
    private val supabaseClient: SupabaseClient,
    private val pageSize: Int = 20
) : RemoteMediator<Int, NewsArticleEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, NewsArticleEntity>
    ): MediatorResult {
        val offset = when (loadType) {
            LoadType.REFRESH -> 0
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> {
                val count = newsDao.getCount()
                count
            }
        }

        return try {
            val result = supabaseClient.fetchNewsArticles(offset = offset, limit = pageSize)
            result.fold(
                onSuccess = { dtos ->
                    val entities = dtos.mapNotNull { it.toDomain()?.toEntity() }
                    if (loadType == LoadType.REFRESH) {
                        newsDao.insertAll(entities)
                        newsDao.deleteOldNews()
                    } else {
                        newsDao.insertAll(entities)
                    }
                    MediatorResult.Success(endOfPaginationReached = entities.size < pageSize)
                },
                onFailure = { e ->
                    MediatorResult.Error(e)
                }
            )
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }
}
