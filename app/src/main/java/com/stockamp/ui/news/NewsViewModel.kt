package com.stockamp.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.repository.NewsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val repository: NewsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewsUiState>(NewsUiState.Loading)
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val newsPagingData: Flow<PagingData<NewsArticle>> = _searchQuery
        .flatMapLatest {
            repository.getNewsStream().cachedIn(viewModelScope)
        }.cachedIn(viewModelScope)

    init {
        observeLatestNews()
    }

    private fun observeLatestNews() {
        repository.getLatestNews(limit = 5)
            .onEach { articles ->
                val currentFilters = if (_searchQuery.value.isNotBlank()) listOf(_searchQuery.value) else emptyList()
                _uiState.update {
                    NewsUiState.Success(
                        latestNews = articles,
                        activeFilters = currentFilters,
                        isRefreshing = false
                    )
                }
            }
            .catch { e ->
                val hasCached = (_uiState.value as? NewsUiState.Success)?.latestNews?.isNotEmpty() == true
                _uiState.update { NewsUiState.Error(e.message ?: "Unknown error", hasCached) }
            }
            .launchIn(viewModelScope)
    }

    fun applyFilter(query: String) {
        _searchQuery.value = query
        _uiState.update { current ->
            when (current) {
                is NewsUiState.Success -> current.copy(activeFilters = if (query.isNotBlank()) listOf(query) else emptyList())
                else -> current
            }
        }
    }

    fun clearFilter() {
        _searchQuery.value = ""
        _uiState.update { current ->
            when (current) {
                is NewsUiState.Success -> current.copy(activeFilters = emptyList())
                else -> current
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { current ->
                when (current) {
                    is NewsUiState.Success -> current.copy(isRefreshing = true)
                    else -> NewsUiState.Loading
                }
            }
            val result = repository.refresh()
            result.onFailure { e ->
                val hasCached = (_uiState.value as? NewsUiState.Success)?.latestNews?.isNotEmpty() == true
                _uiState.update { NewsUiState.Error(e.message ?: "Refresh failed", hasCached) }
            }
        }
    }

    fun loadForSymbol(symbol: String) {
        applyFilter(symbol)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.unsubscribeRealtime()
        }
    }
}