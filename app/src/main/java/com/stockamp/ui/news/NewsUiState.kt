package com.stockamp.ui.news

import com.stockamp.data.model.NewsArticle

sealed class NewsUiState {
    object Loading : NewsUiState()
    data class Success(
        val latestNews: List<NewsArticle>,
        val activeFilters: List<String>,
        val isRefreshing: Boolean
    ) : NewsUiState()
    data class Error(val message: String, val hasCachedData: Boolean) : NewsUiState()
}
